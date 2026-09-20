package no.cloud247.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.ByteArrayOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(markerClass = [UnstableApi::class])
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_M3U = 1001
        private const val REQUEST_EPG = 1002
        private const val MAX_M3U_BYTES = 16 * 1024 * 1024
        private const val MAX_EPG_BYTES = 32 * 1024 * 1024
        private const val PREFS = "cloud247_tv"
        private const val PREF_FAVORITES = "favorites"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val favorites = linkedSetOf<String>()

    private lateinit var sourcePanel: LinearLayout
    private lateinit var tvPanel: LinearLayout
    private lateinit var playlistUrl: EditText
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
    private lateinit var playerView: PlayerView
    private lateinit var currentChannel: TextView
    private lateinit var currentGroup: TextView
    private lateinit var favoriteButton: Button
    private lateinit var fullscreenButton: Button
    private lateinit var nowTitle: TextView
    private lateinit var nowTime: TextView
    private lateinit var nextTitle: TextView
    private lateinit var nextTime: TextView
    private lateinit var playerStatus: TextView

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var playlist = Playlist(emptyList())
    private var epgData = EpgData.EMPTY
    private var activeGroup = "__all__"
    private var selectedChannel: Channel? = null
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        loadFavorites()
        configureLists()
        configureActions()
        playlistUrl.requestFocus()
    }

    private fun bindViews() {
        sourcePanel = findViewById(R.id.sourcePanel)
        tvPanel = findViewById(R.id.tvPanel)
        playlistUrl = findViewById(R.id.playlistUrl)
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
        playerView = findViewById(R.id.playerView)
        currentChannel = findViewById(R.id.currentChannel)
        currentGroup = findViewById(R.id.currentGroup)
        favoriteButton = findViewById(R.id.favoriteButton)
        fullscreenButton = findViewById(R.id.fullscreenButton)
        nowTitle = findViewById(R.id.nowTitle)
        nowTime = findViewById(R.id.nowTime)
        nextTitle = findViewById(R.id.nextTitle)
        nextTime = findViewById(R.id.nextTime)
        playerStatus = findViewById(R.id.playerStatus)
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
            channelList.requestFocus()
        }

        channelList.setOnItemClickListener { _, _, position, _ ->
            selectChannel(channelAdapter.getItem(position))
        }
    }

    private fun configureActions() {
        loadPlaylistButton.setOnClickListener { loadPlaylistFromUrl() }
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
        favoriteButton.setOnClickListener { selectedChannel?.let(::toggleFavorite) }
        fullscreenButton.setOnClickListener { openFullscreen() }

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

        setSourceLoading(true, "Henter spilleliste direkte fra IPTV-leverandøren …")
        executor.execute {
            try {
                val text = NetworkClient.fetchText(url, MAX_M3U_BYTES)
                val name = try { URL(url).host.removePrefix("www.") } catch (_: Exception) { "Spilleliste" }
                val parsed = M3uParser.parse(text, name)
                runOnUiThread {
                    playlistUrl.setText("")
                    applyPlaylist(parsed)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setSourceLoading(false, "Kunne ikke hente spillelisten: ${safeMessage(error)}")
                }
            }
        }
    }

    private fun applyPlaylist(parsed: Playlist) {
        if (parsed.channels.isEmpty()) {
            setSourceLoading(false, "Fant ingen kanaler i M3U-filen.")
            return
        }

        releasePlayer()
        playlist = parsed
        epgData = EpgData.EMPTY
        activeGroup = "__all__"
        selectedChannel = null
        channelSearch.setText("")
        playlistTitle.text = parsed.name
        playlistStats.text = "${parsed.channels.size} kanaler"
        currentChannel.text = "Velg en kanal"
        currentGroup.text = "—"
        nowTitle.text = "Ingen EPG lastet"
        nowTime.text = "—"
        nextTitle.text = "—"
        nextTime.text = "—"
        favoriteButton.text = "☆"
        playerStatus.text = "Android TV spiller streamen direkte uten nettleser-CORS."

        renderGroups()
        renderChannels()
        setSourceLoading(false, "Spilleliste lastet")
        sourcePanel.visibility = View.GONE
        tvPanel.visibility = View.VISIBLE
        groupList.requestFocus()
    }

    private fun renderGroups() {
        val byGroup = playlist.channels.groupingBy { it.group }.eachCount()
        val favoriteCount = playlist.channels.count { it.favoriteKey() in favorites }
        val items = mutableListOf(
            GroupItem("__all__", "Alle kanaler", playlist.channels.size),
            GroupItem("__favorites__", "★ Favoritter", favoriteCount)
        )
        byGroup.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (group, count) ->
            items += GroupItem(group, group, count)
        }
        groupAdapter.activeKey = activeGroup
        groupAdapter.setItems(items)
    }

    private fun renderChannels() {
        if (!::channelAdapter.isInitialized) return
        val query = channelSearch.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val filtered = playlist.channels.filter { channel ->
            val groupMatch = when (activeGroup) {
                "__all__" -> true
                "__favorites__" -> channel.favoriteKey() in favorites
                else -> channel.group == activeGroup
            }
            val searchMatch = query.isBlank() || listOf(channel.name, channel.tvgName, channel.group)
                .any { it.lowercase(Locale.getDefault()).contains(query) }
            groupMatch && searchMatch
        }

        channelHeading.text = when (activeGroup) {
            "__all__" -> "ALLE KANALER"
            "__favorites__" -> "FAVORITTER"
            else -> activeGroup.uppercase(Locale.getDefault())
        }
        channelAdapter.activeChannel = selectedChannel
        channelAdapter.setItems(filtered)
    }

    private fun selectChannel(channel: Channel) {
        selectedChannel = channel
        currentChannel.text = channel.name
        currentGroup.text = channel.group
        updateFavoriteButton()
        updateProgramme()
        channelAdapter.activeChannel = channel
        startPlayback(channel)
    }

    private fun startPlayback(channel: Channel) {
        releasePlayer()
        playerStatus.text = "Kobler til IPTV-leverandøren …"
        try {
            val session = PlayerFactory.create(this, channel.url)
            player = session.player
            playerView.player = session.player
            session.player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> playerStatus.text = "Laster stream …"
                        Player.STATE_READY -> playerStatus.text = "Direkte stream • ingen nettleser-CORS"
                        Player.STATE_ENDED -> playerStatus.text = "Streamen er avsluttet."
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    playerStatus.text = "Kanalen kunne ikke spilles: ${error.errorCodeName}"
                }
            })
            session.player.setMediaItem(session.mediaItem)
            session.player.prepare()
            session.player.playWhenReady = true
            playerView.requestFocus()
        } catch (error: Exception) {
            playerStatus.text = "Kunne ikke starte avspillingen: ${safeMessage(error)}"
        }
    }

    private fun toggleFavorite(channel: Channel) {
        val key = channel.favoriteKey()
        if (key in favorites) favorites.remove(key) else favorites.add(key)
        saveFavorites()
        updateFavoriteButton()
        renderGroups()
        renderChannels()
    }

    private fun updateFavoriteButton() {
        val selected = selectedChannel
        favoriteButton.text = if (selected != null && selected.favoriteKey() in favorites) "★" else "☆"
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
        playerStatus.text = "Laster XMLTV / EPG …"
        executor.execute {
            try {
                val bytes = NetworkClient.fetchBytes(url, MAX_EPG_BYTES)
                val parsed = XmlTvParser.parse(bytes, playlist.channels)
                runOnUiThread { applyEpg(parsed) }
            } catch (error: Exception) {
                runOnUiThread { playerStatus.text = "Kunne ikke laste EPG: ${safeMessage(error)}" }
            }
        }
    }

    private fun applyEpg(data: EpgData) {
        epgData = data
        val count = data.programsById.size
        playerStatus.text = if (count > 0) "EPG lastet for $count kanaler." else "EPG lastet, men fant ingen matchende kanal-ID-er."
        renderChannels()
        updateProgramme()
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

    private fun updateProgramme() {
        val channel = selectedChannel ?: return
        val window = programmeWindow(channel)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())

        nowTitle.text = window.now?.title ?: "Ingen EPG-data"
        nowTime.text = window.now?.let { program ->
            val stop = program.stop?.let(time::format) ?: ""
            if (stop.isBlank()) time.format(program.start) else "${time.format(program.start)} – $stop"
        } ?: "—"
        nextTitle.text = window.next?.title ?: "—"
        nextTime.text = window.next?.let { time.format(it.start) } ?: "—"
    }

    private fun openFullscreen() {
        val channel = selectedChannel ?: run {
            playerStatus.text = "Velg en kanal først."
            return
        }
        player?.pause()
        startActivity(Intent(this, FullscreenPlayerActivity::class.java).apply {
            putExtra(FullscreenPlayerActivity.EXTRA_URL, channel.url)
            putExtra(FullscreenPlayerActivity.EXTRA_NAME, channel.name)
        })
    }

    private fun showSourcePanel() {
        releasePlayer()
        tvPanel.visibility = View.GONE
        sourcePanel.visibility = View.VISIBLE
        sourceStatus.text = "Spillelisten lagres ikke. URL og innloggingsdetaljer beholdes kun i minnet mens appen er åpen."
        playlistUrl.requestFocus()
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
                runOnUiThread { applyPlaylist(parsed) }
            } catch (error: Exception) {
                runOnUiThread { setSourceLoading(false, "Kunne ikke lese M3U-filen: ${safeMessage(error)}") }
            }
        }
    }

    private fun loadEpgFile(uri: Uri) {
        playerStatus.text = "Leser XMLTV-fil …"
        executor.execute {
            try {
                val bytes = readUriLimited(uri, MAX_EPG_BYTES)
                val parsed = XmlTvParser.parse(bytes, playlist.channels)
                runOnUiThread { applyEpg(parsed) }
            } catch (error: Exception) {
                runOnUiThread { playerStatus.text = "Kunne ikke lese XMLTV-filen: ${safeMessage(error)}" }
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

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        releasePlayer()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (tvPanel.visibility == View.VISIBLE && selectedChannel != null) {
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                stepChannel(if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) -1 else 1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun stepChannel(direction: Int) {
        if (channelAdapter.count == 0) return
        val selected = selectedChannel
        val currentIndex = (0 until channelAdapter.count).firstOrNull { channelAdapter.getItem(it) == selected } ?: 0
        val nextIndex = (currentIndex + direction).coerceIn(0, channelAdapter.count - 1)
        val channel = channelAdapter.getItem(nextIndex)
        channelList.setSelection(nextIndex)
        selectChannel(channel)
    }
}
