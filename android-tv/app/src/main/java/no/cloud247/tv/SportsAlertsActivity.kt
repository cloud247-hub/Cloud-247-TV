package no.cloud247.tv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class SportsAlertsActivity : Activity() {
    private lateinit var enabled: CheckBox
    private lateinit var premierLeague: CheckBox
    private lateinit var tennisMajors: CheckBox
    private lateinit var teams: EditText
    private lateinit var players: EditText
    private lateinit var leagues: EditText
    private lateinit var leadTime: Button
    private lateinit var status: TextView

    private var leadMinutes = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!DeviceProfile.isTablet(this)) {
            finish()
            return
        }

        setContentView(R.layout.activity_sports_alerts)

        enabled = findViewById(R.id.sportsEnabled)
        premierLeague = findViewById(R.id.sportsPremierLeague)
        tennisMajors = findViewById(R.id.sportsTennisMajors)
        teams = findViewById(R.id.sportsTeams)
        players = findViewById(R.id.sportsPlayers)
        leagues = findViewById(R.id.sportsLeagues)
        leadTime = findViewById(R.id.sportsLeadTime)
        status = findViewById(R.id.sportsStatus)

        val config = SportsPreferences.loadConfig(this)
        enabled.isChecked = SportsPreferences.isEnabled(this)
        premierLeague.isChecked = config.premierLeague
        tennisMajors.isChecked = config.tennisMajors
        teams.setText(SportsPreferences.entriesText(config.teams))
        players.setText(SportsPreferences.entriesText(config.tennisPlayers))
        leagues.setText(SportsPreferences.entriesText(config.leagues))
        leadMinutes = config.leadMinutes
        updateLeadButton()
        updateStatus()

        leadTime.setOnClickListener {
            leadMinutes = when (leadMinutes) {
                15 -> 30
                30 -> 60
                else -> 15
            }
            updateLeadButton()
        }

        findViewById<Button>(R.id.sportsSave).setOnClickListener {
            save()
        }

        findViewById<Button>(R.id.sportsCheckNow).setOnClickListener {
            save()
            SportsAlertScheduler.refreshNow(this)
            Toast.makeText(
                this,
                "Oppdaterer TV-guiden og sjekker kommende favoritter.",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.sportsBack).setOnClickListener {
            finish()
        }
    }

    private fun save() {
        val teamSet = SportsPreferences.parseEntries(teams.text.toString())
        val playerSet = SportsPreferences.parseEntries(players.text.toString())
        val leagueSet = SportsPreferences.parseEntries(leagues.text.toString())

        SportsPreferences.saveConfig(
            context = this,
            teams = teamSet,
            players = playerSet,
            leagues = leagueSet,
            premierLeague = premierLeague.isChecked,
            tennisMajors = tennisMajors.isChecked,
            leadMinutes = leadMinutes
        )

        val hasFavorites =
            teamSet.isNotEmpty() ||
                playerSet.isNotEmpty() ||
                leagueSet.isNotEmpty() ||
                premierLeague.isChecked ||
                tennisMajors.isChecked

        SportsPreferences.setEnabled(this, enabled.isChecked && hasFavorites)

        if (enabled.isChecked) requestNotificationPermissionIfNeeded()
        SportsAlertScheduler.sync(this)
        updateStatus()

        Toast.makeText(this, "Sportsvarsler lagret.", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1401
            )
        }
    }

    private fun updateLeadButton() {
        leadTime.text = "Varsle omtrent $leadMinutes min før"
    }

    private fun updateStatus() {
        val epgUrl = SportsPreferences.epgUrl(this)
        status.text = when {
            epgUrl.isBlank() ->
                "Ingen XMLTV-URL er lagret. Åpne TV-guide og last EPG fra URL først."
            SportsPreferences.isEnabled(this) ->
                "Aktiv · lokal sports-cache sjekkes ca. hvert 15. minutt. XMLTV oppdateres ca. hver 12. time."
            else ->
                "Av · favorittene beholdes lokalt på nettbrettet."
        }
        status.setTextColor(
            getColor(
                if (epgUrl.isBlank()) R.color.danger
                else if (SportsPreferences.isEnabled(this)) R.color.success
                else R.color.muted
            )
        )
    }
}
