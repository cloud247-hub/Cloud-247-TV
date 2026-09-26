package no.cloud247.tv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class SportsHubActivity : Activity() {
    companion object {
        private var sessionChannels: List<Channel> = emptyList()
        private var sessionEpg: EpgData = EpgData.EMPTY

        fun prepareSession(channels: List<Channel>, epg: EpgData) {
            sessionChannels = channels.toList()
            sessionEpg = epg
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var teams: EditText
    private lateinit var leagues: EditText
    private lateinit var tennisPlayers: EditText
    private lateinit var golfPlayers: EditText
    private lateinit var premierLeague: CheckBox
    private lateinit var tennisMajors: CheckBox
    private lateinit var golfMajors: CheckBox
    private lateinit var pga: CheckBox
    private lateinit var dpWorld: CheckBox
    private lateinit var lpga: CheckBox
    private lateinit var liv: CheckBox
    private lateinit var masterAlerts: CheckBox
    private lateinit var leadTime: Button
    private lateinit var alertOptions: LinearLayout
    private lateinit var status: TextView
    private lateinit var sources: TextView
    private lateinit var list: ListView
    private lateinit var adapter: EventAdapter
    private var leadMinutes = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceProfile.isTablet(this)) {
            finish()
            return
        }

        setContentView(R.layout.activity_sports_hub)
        SportsHubPreferences.migrateLegacyIfNeeded(this)
        bindViews()
        loadConfig()

        adapter = EventAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.getItem(position)
            val match = row.channelMatch
            if (match == null) {
                Toast.makeText(
                    this,
                    "Ingen sikker kanal-match ennå. Hendelsen blir fortsatt fulgt.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                FullscreenPlayerActivity.prepareSession(sessionChannels, match.channel, sessionEpg)
                startActivity(Intent(this, FullscreenPlayerActivity::class.java).apply {
                    putExtra(FullscreenPlayerActivity.EXTRA_URL, match.channel.url)
                    putExtra(FullscreenPlayerActivity.EXTRA_NAME, match.channel.name)
                })
            }
        }

        findViewById<Button>(R.id.sportsHubSave).setOnClickListener {
            saveFavorites()
            refresh()
        }
        findViewById<Button>(R.id.sportsHubRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.sportsHubBack).setOnClickListener { finish() }

        masterAlerts.setOnCheckedChangeListener { _, checked ->
            SportsHubPreferences.setEnabled(this, checked)
            if (checked) requestNotificationPermissionIfNeeded()
            SportsAlertScheduler.sync(this)
        }

        leadTime.setOnClickListener {
            leadMinutes = when (leadMinutes) {
                15 -> 30
                30 -> 60
                60 -> 120
                else -> 15
            }
            updateLeadButton()
            saveFavorites(silent = true)
        }

        render(SportsHubCache.load(this))
        refresh()
    }

    private fun bindViews() {
        teams = findViewById(R.id.sportsHubTeams)
        leagues = findViewById(R.id.sportsHubLeagues)
        tennisPlayers = findViewById(R.id.sportsHubTennisPlayers)
        golfPlayers = findViewById(R.id.sportsHubGolfPlayers)
        premierLeague = findViewById(R.id.sportsHubPremierLeague)
        tennisMajors = findViewById(R.id.sportsHubTennisMajors)
        golfMajors = findViewById(R.id.sportsHubGolfMajors)
        pga = findViewById(R.id.sportsHubPga)
        dpWorld = findViewById(R.id.sportsHubDpWorld)
        lpga = findViewById(R.id.sportsHubLpga)
        liv = findViewById(R.id.sportsHubLiv)
        masterAlerts = findViewById(R.id.sportsHubAlertsEnabled)
        leadTime = findViewById(R.id.sportsHubLeadTime)
        alertOptions = findViewById(R.id.sportsHubAlertOptions)
        status = findViewById(R.id.sportsHubStatus)
        sources = findViewById(R.id.sportsHubSources)
        list = findViewById(R.id.sportsHubList)
    }

    private fun loadConfig() {
        val config = SportsHubPreferences.load(this)
        teams.setText(SportsHubPreferences.entries(config.footballTeams))
        leagues.setText(SportsHubPreferences.entries(config.footballLeagues))
        tennisPlayers.setText(SportsHubPreferences.entries(config.tennisPlayers))
        golfPlayers.setText(SportsHubPreferences.entries(config.golfPlayers))
        premierLeague.isChecked = config.premierLeague
        tennisMajors.isChecked = config.tennisMajors
        golfMajors.isChecked = config.golfMajors
        pga.isChecked = "pga" in config.golfTours
        dpWorld.isChecked = "eur" in config.golfTours
        lpga.isChecked = "lpga" in config.golfTours
        liv.isChecked = "liv" in config.golfTours
        masterAlerts.isChecked = SportsHubPreferences.isEnabled(this)
        leadMinutes = config.leadMinutes
        updateLeadButton()
        renderAlertOptions(config)
    }

    private fun saveFavorites(silent: Boolean = false) {
        val previous = SportsHubPreferences.load(this)
        val teamsSet = SportsHubPreferences.parseEntries(teams.text.toString())
        val leaguesSet = SportsHubPreferences.parseEntries(leagues.text.toString())
        val tennisSet = SportsHubPreferences.parseEntries(tennisPlayers.text.toString())
        val golfSet = SportsHubPreferences.parseEntries(golfPlayers.text.toString())
        val tours = buildSet {
            if (pga.isChecked) add("pga")
            if (dpWorld.isChecked) add("eur")
            if (lpga.isChecked) add("lpga")
            if (liv.isChecked) add("liv")
        }

        val validAlertKeys = linkedSetOf<String>()
        teamsSet.forEach { validAlertKeys += SportsHubPreferences.alertKey("football_team", it) }
        leaguesSet.forEach { validAlertKeys += SportsHubPreferences.alertKey("football_league", it) }
        tennisSet.forEach { validAlertKeys += SportsHubPreferences.alertKey("tennis_player", it) }
        golfSet.forEach { validAlertKeys += SportsHubPreferences.alertKey("golf_player", it) }
        if (premierLeague.isChecked) validAlertKeys += SportsHubPreferences.alertKey("premier_league", "Premier League")
        if (tennisMajors.isChecked) validAlertKeys += SportsHubPreferences.alertKey("tennis_major", "Tennis Majors")
        if (golfMajors.isChecked) validAlertKeys += SportsHubPreferences.alertKey("golf_major", "Golf Majors")
        tours.forEach { tour ->
            validAlertKeys += SportsHubPreferences.alertKey("golf_tour", tourLabel(tour))
        }

        val config = SportsHubConfig(
            footballTeams = teamsSet,
            footballLeagues = leaguesSet,
            tennisPlayers = tennisSet,
            golfPlayers = golfSet,
            premierLeague = premierLeague.isChecked,
            tennisMajors = tennisMajors.isChecked,
            golfMajors = golfMajors.isChecked,
            golfTours = tours,
            alertSelections = previous.alertSelections.intersect(validAlertKeys),
            leadMinutes = leadMinutes
        )

        SportsHubPreferences.save(this, config)
        renderAlertOptions(config)
        SportsAlertScheduler.sync(this)

        if (!silent) {
            Toast.makeText(this, "Sportsfavoritter lagret.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderAlertOptions(config: SportsHubConfig) {
        alertOptions.removeAllViews()
        val options = mutableListOf<Pair<String, String>>()

        config.footballTeams.forEach {
            options += SportsHubPreferences.alertKey("football_team", it) to "⚽ $it"
        }
        config.footballLeagues.forEach {
            options += SportsHubPreferences.alertKey("football_league", it) to "🏆 $it"
        }
        if (config.premierLeague) {
            options += SportsHubPreferences.alertKey("premier_league", "Premier League") to "⚽ Premier League"
        }

        config.tennisPlayers.forEach {
            options += SportsHubPreferences.alertKey("tennis_player", it) to "🎾 $it"
        }
        if (config.tennisMajors) {
            options += SportsHubPreferences.alertKey("tennis_major", "Tennis Majors") to "🎾 Tennis Majors"
        }

        config.golfPlayers.forEach {
            options += SportsHubPreferences.alertKey("golf_player", it) to "⛳ $it"
        }
        if (config.golfMajors) {
            options += SportsHubPreferences.alertKey("golf_major", "Golf Majors") to "⛳ Golf Majors"
        }
        config.golfTours.sorted().forEach { tour ->
            val label = tourLabel(tour)
            options += SportsHubPreferences.alertKey("golf_tour", label) to "⛳ $label"
        }

        if (options.isEmpty()) {
            alertOptions.addView(TextView(this).apply {
                text = "Lagre favoritter først for å velge hvilke som skal varsle."
                setTextColor(getColor(R.color.muted))
                textSize = 13f
            })
            return
        }

        val selections = SportsHubPreferences.load(this).alertSelections
        options.distinctBy { it.first }.forEach { (key, label) ->
            alertOptions.addView(CheckBox(this).apply {
                text = label
                setTextColor(getColor(R.color.white))
                buttonTintList = getColorStateList(R.color.yellow)
                textSize = 15f
                isChecked = key in selections
                setOnCheckedChangeListener { _, checked ->
                    SportsHubPreferences.setAlertSelection(this@SportsHubActivity, key, checked)
                    SportsAlertScheduler.sync(this@SportsHubActivity)
                }
            })
        }
    }

    private fun refresh() {
        saveFavorites(silent = true)
        val config = SportsHubPreferences.load(this)
        if (!config.hasFavorites) {
            status.text = "Legg til et lag, en spiller, liga eller tour først."
            adapter.setRows(emptyList())
            return
        }

        status.text = "Henter kommende fotball, tennis og golf …"
        executor.execute {
            try {
                val response = SportsApiClient.fetchUpcoming(config)
                SportsHubCache.save(this, response)
                runOnUiThread {
                    if (!isFinishing) render(response)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    val cached = SportsHubCache.load(this)
                    render(cached)
                    status.text = if (cached.events.isNotEmpty()) {
                        "Kunne ikke oppdatere nå. Viser sist lagrede sportsdata."
                    } else {
                        "Kunne ikke hente sportsdata: ${error.message ?: "ukjent feil"}"
                    }
                }
            }
        }
    }

    private fun render(response: SportsHubResponse) {
        val rows = response.events.map { event ->
            EventRow(event, SportsChannelMatcher.find(event, sessionChannels, sessionEpg))
        }
        adapter.setRows(rows)

        status.text = if (rows.isEmpty()) {
            "Ingen kommende treff for favorittene dine akkurat nå."
        } else {
            "${rows.size} kommende · grønn kanaltekst betyr sikker EPG-match."
        }

        sources.text = listOf(
            "football" to "⚽",
            "tennis" to "🎾",
            "golf" to "⛳"
        ).joinToString("   ") { (key, icon) ->
            val source = response.sources[key]
            when {
                source == null -> "$icon –"
                source.ok -> "$icon ✓ ${source.detail}"
                else -> "$icon ⚠ ${source.detail}"
            }
        }
    }

    private fun updateLeadButton() {
        leadTime.text = "Varsle ca. $leadMinutes min før"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1501)
        }
    }

    private fun tourLabel(tour: String): String = when (tour) {
        "pga" -> "PGA Tour"
        "eur" -> "DP World Tour"
        "lpga" -> "LPGA"
        "liv" -> "LIV Golf"
        else -> tour.uppercase(Locale.ROOT)
    }

    override fun onResume() {
        super.onResume()
        SportsAlertScheduler.sync(this)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class EventRow(
        val event: SportsHubEvent,
        val channelMatch: SportsChannelMatch?
    )

    private inner class EventAdapter : BaseAdapter() {
        private val rows = mutableListOf<EventRow>()
        private val dateFormat = SimpleDateFormat("EEE d. MMM · HH:mm", Locale.getDefault())

        fun setRows(items: List<EventRow>) {
            rows.clear()
            rows.addAll(items)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): EventRow = rows[position]
        override fun getItemId(position: Int): Long = rows[position].event.id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@SportsHubActivity)
                .inflate(R.layout.item_sports_hub_event, parent, false)
            val row = getItem(position)
            val event = row.event

            view.findViewById<TextView>(R.id.sportsEventIcon).text = when (event.sport) {
                "tennis" -> "🎾"
                "golf" -> "⛳"
                else -> "⚽"
            }
            view.findViewById<TextView>(R.id.sportsEventTitle).text = event.title
            view.findViewById<TextView>(R.id.sportsEventMeta).text = buildString {
                append(dateFormat.format(event.start))
                if (event.competition.isNotBlank()) append(" · ").append(event.competition)
            }
            view.findViewById<TextView>(R.id.sportsEventReason).text =
                "${event.matchName} · ${event.source}"

            val channel = view.findViewById<TextView>(R.id.sportsEventChannel)
            channel.text = row.channelMatch?.let {
                "▶ ${it.channel.name}\n${it.programme}"
            } ?: "Kanal ikke matchet ennå"
            channel.setTextColor(
                getColor(if (row.channelMatch != null) R.color.success else R.color.muted)
            )
            return view
        }
    }
}
