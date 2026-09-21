package no.cloud247.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(markerClass = [UnstableApi::class])
class MainActivity : Activity() {
    private val logTag = "Cloud247TV"
    companion object {
        private const val REQUEST_M3U = 1001
        private const val REQUEST_EPG = 1002
        private const val MAX_M3U_BYTES = 64 * 1024 * 1024
        private const val MAX_EPG_BYTES = 32 * 1024 * 1024
        private const val PREFS = "cloud247_tv"
        private const val PREF_FAVORITES = "favorites"

        private val NORWAY_TOKEN_REGEX =
            Regex("(^|[\\s|:_\\-\\[\\]])NO($|[\\s|:_\\-\\[\\]])")
        private val TENNIS_TOKEN_REGEX =
            Regex("(^|[\\s|:_\\-\\[\\]])(ATP|WTA)($|[\\s|:_\\-\\[\\]])")
        private val GOLF_TOKEN_REGEX =
            Regex("(^|[\\s|:_\\-\\[\\]])PGA($|[\\s|:_\\-\\[\\]])")
        private val NORWEGIAN_NAME_REGEX =
            Regex("^(NRK(?:\\s|$)|TV\\s?2(?:\\s|$)|TVNORGE(?:\\s|$)|FEM(?:\\s|$)|MAX(?:\\s|$)|VOX(?:\\s|$)|EUROSPORT\\s+NORGE(?:\\s|$)|VISJON\\s+NORGE(?:\\s|$)|FRIKANALEN(?:\\s|$)|MATKANALEN(?:\\s|$)|HEIM(?:\\s|$)|KANAL\\s+10\\s+NORGE(?:\\s|$))")
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val favorites = linkedSetOf<String>()

    private lateinit var sourcePanel: LinearLayout
    private lateinit var tvPanel: LinearLayout
    private lateinit var playlistUrl: EditText
    private lateinit var pairingQr: ImageView
    private lateinit var pairingCode: TextView
    private lateinit var pairingStatus: TextView
    private lateinit var newPairingButton: Button
    private lateinit var loadPlaylistButton: Button
    private lateinit var pickPlaylistButton: Button
    private lateinit var sourceProgress: ProgressBar
    private lateinit var sourceStatus: TextView
    private lateinit var playlistTitle: TextView
    private lateinit var playlistStats: TextView
    private lateinit var epgButton: Button
    private lateinit var changePlaylistButton: Button
    private lateinit var groupList: ListView
    private lateinit var channelList: ListView
    private lateinit var channelSearch: EditText
    private lateinit var channelHeading: TextView

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var playlist = Playlist(emptyList())
    private var channelsByGroup: Map<String, List<Channel>> = emptyMap()
    private var norwegianChannels: List<Channel> = emptyList()
    private var premierLeagueChannels: List<Channel> = emptyList()
    private var tennisChannels: List<Channel> = emptyList()
    private var golfChannels: List<Channel> = emptyList()
    private var epgData = EpgData.EMPTY
    private var activeGroup = "__all__"
    private var selectedChannel: Channel? = null
    private val pairingHandler = Handler(Looper.getMainLooper())
    private val securePlaylistStore by lazy { SecurePlaylistStore(this) }
    private var pairingSession: PairingSession? = null
    private var pairingGeneration = 0
    private val favoriteHoldHandler = Handler(Looper.getMainLooper())
    private var favoriteHoldTriggered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        loadFavorites()
        configureLists()
        configureActions()

        val savedUrl = securePlaylistStore.load()
        if (!savedUrl.isNullOrBlank()) {
            loadPlaylistUrl(savedUrl, persistOnSuccess = false, savedSource = true)
        } else {
            startPairing()
            newPairingButton.requestFocus()
        }
    }

    private fun bindViews() {
        sourcePanel = findViewById(R.id.sourcePanel)
        tvPanel = findViewById(R.id.tvPanel)
        playlistUrl = findViewById(R.id.playlistUrl)
        pairingQr = findViewById(R.id.pairingQr)
        pairingCode = findViewById(R.id.pairingCode)
        pairingStatus = findViewById(R.id.pairingStatus)
        newPairingButton = findViewById(R.id.newPairingButton)
        loadPlaylistButton = findViewById(R.id.loadPlaylistButton)
        pickPlaylistButton = findViewById(R.id.pickPlaylistButton)
        sourceProgress = findViewById(R.id.sourceProgress)
        sourceStatus = findViewById(R.id.sourceStatus)
        playlistTitle = findViewById(R.id.playlistTitle)
        playlistStats = findViewById(R.id.playlistStats)
        epgButton = findViewById(R.id.epgButton)
        changePlaylistButton = findViewById(R.id.changePlaylistButton)
        groupList = findViewById(R.id.groupList)
        channelList = findViewById(R.id.channelList)
        channelSearch = findViewById(R.id.channelSearch)
        channelHeading = findViewById(R.id.channelHeading)
    }

    private fun configureLists() {
        groupAdapter = GroupAdapter(this)
        channelAdapter = ChannelAdapter(this, favorites) { channel ->
            programmeWindow(channel).now?.title ?: channel.group
        }
        groupList.adapter = groupAdapter
        channelList.adapter = channelAdapter
        groupList.choiceMode = ListView.CHOICE_MODE_SINGLE
        channelList.choiceMode = ListView.CHOICE_MODE_SINGLE

        groupList.setOnItemClickListener { _, _, position, _ ->
            val item = groupAdapter.getItem(position)
            activeGroup = item.key
            groupAdapter.activeKey = activeGroup
            channelSearch.setText("")
            renderChannels()
            if (channelAdapter.count > 0) {
                channelList.setSelection(0)
                channelList.requestFocus()
            }
        }

        channelList.setOnItemClickListener { _, _, position, _ ->
            val channel = channelAdapter.getItem(position)
            selectChannel(channel)
            openFullscreen(channel)
        }

        channelList.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
                return@setOnKeyListener false
            }

            val position = channelList.selectedItemPosition
            if (position < 0 || position >= channelAdapter.count) return@setOnKeyListener true
            val channel = channelAdapter.getItem(position)

            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        favoriteHoldTriggered = false
                        selectChannel(channel)
                        favoriteHoldHandler.removeCallbacksAndMessages(null)
                        favoriteHoldHandler.postDelayed({
                            if (channelList.hasFocus()) {
                                favoriteHoldTriggered = true
                                toggleFavorite(channel)
                                val isFavorite = channel.favoriteKey() in favorites
                                Toast.makeText(
                                    this,
                                    if (isFavorite) "★ Lagt til i Favoritter" else "Fjernet fra Favoritter",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }, 650L)
                    }
                    true
                }
                KeyEvent.ACTION_UP -> {
                    favoriteHoldHandler.removeCallbacksAndMessages(null)
                    if (!favoriteHoldTriggered) {
                        selectChannel(channel)
                        openFullscreen(channel)
                    }
                    favoriteHoldTriggered = false
                    true
                }
                else -> true
            }
        }
    }

    private fun configureActions() {
        loadPlaylistButton.setOnClickListener { loadPlaylistFromUrl() }
        newPairingButton.setOnClickListener { startPairing() }
        playlistUrl.setOnEditorActionListener { _, actionId, event ->
            val submit = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (submit) loadPlaylistFromUrl()
            submit
        }

        pickPlaylistButton.setOnClickListener { pickFile(REQUEST_M3U, arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*")) }
        epgButton.setOnClickListener { showEpgDialog() }
        changePlaylistButton.setOnClickListener { showSourcePanel() }

        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderChannels()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun loadPlaylistFromUrl() {
        val url = playlistUrl.text.toString().trim()
        if (url.isBlank()) {
            sourceStatus.text = "Skriv inn en M3U-adresse først."
            return
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            sourceStatus.text = "Kun http:// og https:// støttes."
            return
        }
        loadPlaylistUrl(url, persistOnSuccess = true, savedSource = false)
    }

    private fun loadPlaylistUrl(url: String, persistOnSuccess: Boolean, savedSource: Boolean) {
        Log.i(logTag, "playlist_load_start savedSource=$savedSource persist=$persistOnSuccess")
        setSourceLoading(true, if (savedSource) "Henter lagret spilleliste …" else "Henter spilleliste direkte fra IPTV-leverandøren …")
        executor.execute {
            try {
                val text = NetworkClient.fetchText(url, MAX_M3U_BYTES)
                val name = try { URL(url).host.removePrefix("www.") } catch (_: Exception) { "Spilleliste" }
                val parsed = M3uParser.parse(text, name)
                val index = buildPlaylistIndex(parsed.channels)
                runOnUiThread {
                    if (persistOnSuccess) securePlaylistStore.save(url)
                    Log.i(
                        logTag,
                        "playlist_load_success channels=${parsed.channels.size} norwegian=${index.norwegian.size} epl=${index.premierLeague.size} tennis=${index.tennis.size} golf=${index.golf.size}"
                    )
                    playlistUrl.setText("")
                    applyPlaylist(parsed, index)
                }
            } catch (error: Exception) {
                Log.e(logTag, "playlist_load_failed: ${safeMessage(error)}")
                runOnUiThread {
                    setSourceLoading(false, "Kunne ikke hente spillelisten: ${safeMessage(error)}")
                    if (savedSource) startPairing()
                }
            }
        }
    }

    private fun startPairing() {
        val generation = ++pairingGeneration
        pairingSession = null
        pairingHandler.removeCallbacksAndMessages(null)
        pairingQr.setImageDrawable(null)
        pairingCode.text = "------"
        pairingStatus.text = "Lager sikker TV-kode …"

        executor.execute {
            try {
                val session = PairingClient.createSession()
                Log.i(logTag, "pair_create_success code=${session.code}")
                runOnUiThread {
                    if (generation != pairingGeneration || isFinishing) return@runOnUiThread
                    pairingSession = session
                    pairingCode.text = session.code
                    pairingQr.setImageBitmap(QrCodeRenderer.render(session.link, 320))
                    pairingStatus.text = "Skann QR-koden eller gå til tv.cloud247.no/link"
                    pairingHandler.postDelayed({ pollPairing(generation) }, 1500)
                }
            } catch (error: Exception) {
                Log.e(logTag, "pair_create_failed: ${safeMessage(error)}")
                runOnUiThread {
                    if (generation != pairingGeneration) return@runOnUiThread
                    pairingStatus.text = "Kunne ikke lage TV-kode: ${safeMessage(error)}"
                }
            }
        }
    }

    private fun pollPairing(generation: Int) {
        if (generation != pairingGeneration) return
        val session = pairingSession ?: return
        executor.execute {
            try {
                val pairedUrl = PairingClient.poll(session)
                Log.d(logTag, if (pairedUrl.isNullOrBlank()) "pair_poll_pending code=${session.code}" else "pair_poll_received code=${session.code}")
                runOnUiThread {
                    if (generation != pairingGeneration || isFinishing) return@runOnUiThread
                    if (!pairedUrl.isNullOrBlank()) {
                        pairingStatus.text = "Spilleliste mottatt. Kobler til …"
                        stopPairing()
                        loadPlaylistUrl(pairedUrl, persistOnSuccess = true, savedSource = false)
                    } else {
                        pairingHandler.postDelayed({ pollPairing(generation) }, 2500)
                    }
                }
            } catch (error: Exception) {
                Log.e(logTag, "pair_poll_failed: ${safeMessage(error)}")
                runOnUiThread {
                    if (generation != pairingGeneration) return@runOnUiThread
                    pairingStatus.text = safeMessage(error)
                    if (error !is PairingException || !safeMessage(error).contains("utløpt", true)) {
                        pairingHandler.postDelayed({ pollPairing(generation) }, 4000)
                    }
                }
            }
        }
    }

    private fun stopPairing() {
        pairingGeneration += 1
        pairingSession = null
        pairingHandler.removeCallbacksAndMessages(null)
    }

    private fun applyPlaylist(parsed: Playlist, index: PlaylistIndex) {
        if (parsed.channels.isEmpty()) {
            setSourceLoading(false, "Fant ingen kanaler i M3U-filen.")
            return
        }

        stopPairing()
        playlist = parsed
        channelsByGroup = index.byGroup
        norwegianChannels = index.norwegian
        premierLeagueChannels = index.premierLeague
        tennisChannels = index.tennis
        golfChannels = index.golf
        epgData = EpgData.EMPTY
        activeGroup = "__all__"
        selectedChannel = null
        channelSearch.setText("")
        playlistTitle.text = parsed.name
        playlistStats.text = "${parsed.channels.size} kanaler"

        renderGroups()
        renderChannels()
        setSourceLoading(false, "Spilleliste lastet")
        sourcePanel.visibility = View.GONE
        tvPanel.visibility = View.VISIBLE
        groupList.requestFocus()
    }

    private fun renderGroups() {
        val favoriteCount = playlist.channels.count { it.favoriteKey() in favorites }
        val norwegianCount = norwegianChannels.size
        val premierLeagueCount = premierLeagueChannels.size
        val tennisCount = tennisChannels.size
        val golfCount = golfChannels.size

        val items = mutableListOf(
            GroupItem("__all__", "Alle kanaler", playlist.channels.size),
            GroupItem("__favorites__", "★ Favoritter", favoriteCount)
        )
        if (norwegianCount > 0) {
            items += GroupItem("__norwegian__", "Norske kanaler", norwegianCount)
        }
        if (premierLeagueCount > 0) {
            items += GroupItem("__premier_league__", "Fotball", premierLeagueCount)
        }
        if (tennisCount > 0) {
            items += GroupItem("__tennis__", "Tennis", tennisCount)
        }
        if (golfCount > 0) {
            items += GroupItem("__golf__", "Golf", golfCount)
        }

        channelsByGroup.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (group, channels) ->
            items += GroupItem(group, group, channels.size)
        }
        groupAdapter.activeKey = activeGroup
        groupAdapter.setItems(items)
    }

    private fun isPremierLeagueChannel(channel: Channel): Boolean {
        return channel.name.trim().startsWith("EPL", ignoreCase = true)
    }

    private fun isTennisChannel(channel: Channel): Boolean {
        return listOf(channel.name, channel.tvgName, channel.group)
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .any { value ->
                value.contains("TENNIS") ||
                    TENNIS_TOKEN_REGEX.containsMatchIn(value)
            }
    }

    private fun isGolfChannel(channel: Channel): Boolean {
        return listOf(channel.name, channel.tvgName, channel.group)
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .any { value ->
                value.contains("GOLF") ||
                    GOLF_TOKEN_REGEX.containsMatchIn(value)
            }
    }

    private fun isNorwegianChannel(channel: Channel): Boolean {
        val values = listOf(channel.name, channel.tvgName, channel.group)
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }

        if (values.any { value ->
                value.contains("NORWAY") ||
                    value.contains("NORWEGIAN") ||
                    value.contains("NORGE") ||
                    NORWAY_TOKEN_REGEX.containsMatchIn(value)
            }) {
            return true
        }

        val name = channel.name.trim().uppercase(Locale.ROOT)
        return NORWEGIAN_NAME_REGEX.containsMatchIn(name)
    }

    private fun renderChannels() {
        if (!::channelAdapter.isInitialized) return
        val query = channelSearch.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val source = when (activeGroup) {
            "__all__" -> playlist.channels
            "__favorites__" -> playlist.channels.filter { it.favoriteKey() in favorites }
            "__norwegian__" -> norwegianChannels
            "__premier_league__" -> premierLeagueChannels
            "__tennis__" -> tennisChannels
            "__golf__" -> golfChannels
            else -> channelsByGroup[activeGroup].orEmpty()
        }
        val filtered = if (query.isBlank()) {
            source
        } else {
            source.filter { channel ->
                listOf(channel.name, channel.tvgName, channel.group)
                    .any { it.lowercase(Locale.getDefault()).contains(query) }
            }
        }

        channelHeading.text = when (activeGroup) {
            "__all__" -> "ALLE KANALER"
            "__favorites__" -> "FAVORITTER"
            "__norwegian__" -> "NORSKE KANALER"
            "__premier_league__" -> "FOTBALL"
            "__tennis__" -> "TENNIS"
            "__golf__" -> "GOLF"
            else -> activeGroup.uppercase(Locale.getDefault())
        }
        channelAdapter.activeChannel = selectedChannel
        channelAdapter.setItems(filtered)
    }

    private data class PlaylistIndex(
        val byGroup: Map<String, List<Channel>>,
        val norwegian: List<Channel>,
        val premierLeague: List<Channel>,
        val tennis: List<Channel>,
        val golf: List<Channel>
    )

    private fun buildPlaylistIndex(channels: List<Channel>): PlaylistIndex {
        val byGroup = linkedMapOf<String, MutableList<Channel>>()
        val norwegian = ArrayList<Channel>()
        val premierLeague = ArrayList<Channel>()
        val tennis = ArrayList<Channel>()
        val golf = ArrayList<Channel>()

        for (channel in channels) {
            byGroup.getOrPut(channel.group) { ArrayList() }.add(channel)
            if (isNorwegianChannel(channel)) norwegian.add(channel)
            if (isPremierLeagueChannel(channel)) premierLeague.add(channel)
            if (isTennisChannel(channel)) tennis.add(channel)
            if (isGolfChannel(channel)) golf.add(channel)
        }

        return PlaylistIndex(
            byGroup = byGroup.mapValues { it.value.toList() },
            norwegian = norwegian,
            premierLeague = premierLeague,
            tennis = tennis,
            golf = golf
        )
    }

    private fun selectChannel(channel: Channel) {
        selectedChannel = channel
        channelAdapter.activeChannel = channel
    }

    private fun toggleFavorite(channel: Channel) {
        val key = channel.favoriteKey()
        if (key in favorites) favorites.remove(key) else favorites.add(key)
        saveFavorites()
        renderGroups()
        renderChannels()
    }

    private fun showEpgDialog() {
        if (playlist.channels.isEmpty()) return
        val input = EditText(this).apply {
            hint = "XMLTV-adresse"
            setText(playlist.epgUrl)
            setSingleLine(true)
            selectAll()
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(this)
            .setTitle("Programguide / XMLTV")
            .setMessage("Last XMLTV direkte fra leverandøren, eller velg en lokal XMLTV-fil.")
            .setView(wrapper)
            .setPositiveButton("Last EPG") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) loadEpgFromUrl(url)
            }
            .setNeutralButton("Velg XMLTV-fil") { _, _ ->
                pickFile(REQUEST_EPG, arrayOf("application/xml", "text/xml", "text/plain", "*/*"))
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    private fun loadEpgFromUrl(url: String) {
        Toast.makeText(this, "Laster XMLTV / EPG …", Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val bytes = NetworkClient.fetchBytes(url, MAX_EPG_BYTES)
                val parsed = XmlTvParser.parse(bytes, playlist.channels)
                runOnUiThread { applyEpg(parsed) }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, "Kunne ikke laste EPG: ${safeMessage(error)}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun applyEpg(data: EpgData) {
        epgData = data
        val count = data.programsById.size
        Toast.makeText(
            this,
            if (count > 0) "EPG lastet for $count kanaler." else "EPG lastet, men fant ingen matchende kanal-ID-er.",
            Toast.LENGTH_SHORT
        ).show()
        renderChannels()
    }

    private fun programmeWindow(channel: Channel): ProgrammeWindow {
        val candidates = linkedSetOf<String>()
        if (channel.tvgId.isNotBlank()) candidates += channel.tvgId
        for (name in listOf(channel.tvgName, channel.name)) {
            if (name.isBlank()) continue
            epgData.aliases[name]?.let(candidates::add)
            epgData.aliases[name.lowercase(Locale.ROOT)]?.let(candidates::add)
        }

        val programs = candidates.firstNotNullOfOrNull { epgData.programsById[it] }
            ?: return ProgrammeWindow(null, null)

        val now = Date()
        var current: Program? = null
        var next: Program? = null
        for (index in programs.indices) {
            val program = programs[index]
            val effectiveStop = program.stop ?: programs.getOrNull(index + 1)?.start
            if (!program.start.after(now) && (effectiveStop == null || now.before(effectiveStop))) {
                current = program
                next = programs.getOrNull(index + 1)
                break
            }
            if (program.start.after(now)) {
                next = program
                break
            }
        }
        return ProgrammeWindow(current, next)
    }

    private fun openFullscreen(channel: Channel) {
        startActivity(Intent(this, FullscreenPlayerActivity::class.java).apply {
            putExtra(FullscreenPlayerActivity.EXTRA_URL, channel.url)
            putExtra(FullscreenPlayerActivity.EXTRA_NAME, channel.name)
        })
    }

    private fun showSourcePanel() {
        tvPanel.visibility = View.GONE
        sourcePanel.visibility = View.VISIBLE
        sourceStatus.text = "Skann QR-koden med mobilen, eller skriv inn M3U-adressen manuelt."
        startPairing()
        newPairingButton.requestFocus()
    }

    private fun setSourceLoading(loading: Boolean, message: String) {
        sourceProgress.visibility = if (loading) View.VISIBLE else View.GONE
        loadPlaylistButton.isEnabled = !loading
        pickPlaylistButton.isEnabled = !loading
        sourceStatus.text = message
    }

    private fun pickFile(requestCode: Int, mimeTypes: Array<String>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: Exception) {
            Toast.makeText(this, "Ingen filvelger er tilgjengelig på denne Android TV-en.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Legacy result API keeps v1 dependency-light and works on Android TV API 23+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQUEST_M3U -> loadPlaylistFile(uri)
            REQUEST_EPG -> loadEpgFile(uri)
        }
    }

    private fun loadPlaylistFile(uri: Uri) {
        setSourceLoading(true, "Leser M3U-fil …")
        executor.execute {
            try {
                val bytes = readUriLimited(uri, MAX_M3U_BYTES)
                val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "M3U-fil" } ?: "M3U-fil"
                val parsed = M3uParser.parse(String(bytes, Charsets.UTF_8), name)
                val index = buildPlaylistIndex(parsed.channels)
                runOnUiThread { applyPlaylist(parsed, index) }
            } catch (error: Exception) {
                runOnUiThread { setSourceLoading(false, "Kunne ikke lese M3U-filen: ${safeMessage(error)}") }
            }
        }
    }

    private fun loadEpgFile(uri: Uri) {
        Toast.makeText(this, "Leser XMLTV-fil …", Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val bytes = readUriLimited(uri, MAX_EPG_BYTES)
                val parsed = XmlTvParser.parse(bytes, playlist.channels)
                runOnUiThread { applyEpg(parsed) }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, "Kunne ikke lese XMLTV-filen: ${safeMessage(error)}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun readUriLimited(uri: Uri, maxBytes: Int): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw NetworkException("Kunne ikke åpne filen")
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw NetworkException("Filen er for stor")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun loadFavorites() {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(PREF_FAVORITES, emptySet()).orEmpty()
        favorites.clear()
        favorites.addAll(saved)
    }

    private fun saveFavorites() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_FAVORITES, favorites.toSet())
            .apply()
    }

    private fun safeMessage(error: Exception): String {
        return error.message?.take(140)?.replace(Regex("https?://\\S+"), "[adresse]") ?: "Ukjent feil"
    }

    override fun onDestroy() {
        stopPairing()
        favoriteHoldHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (tvPanel.visibility == View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK) {
            return when {
                channelSearch.hasFocus() || channelList.hasFocus() -> {
                    groupList.requestFocus()
                    true
                }
                groupList.hasFocus() -> {
                    showSourcePanel()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

}
