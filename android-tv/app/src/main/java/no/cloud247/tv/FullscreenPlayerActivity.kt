package no.cloud247.tv

import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(markerClass = [UnstableApi::class])
class FullscreenPlayerActivity : FragmentActivity() {
    companion object {
        const val EXTRA_URL = "stream_url"
        const val EXTRA_NAME = "channel_name"

        private const val REMOTE_HINT =
            "↑/↓ bytter kanal · → kanalvelger · Swipe og touch støttes"

        private var preparedChannels: List<Channel> = emptyList()
        private var preparedIndex: Int = 0
        private var preparedEpgData: EpgData = EpgData.EMPTY

        fun prepareSession(
            channels: List<Channel>,
            selected: Channel,
            epgData: EpgData = EpgData.EMPTY
        ) {
            preparedChannels = channels.toList()
            preparedEpgData = epgData
            val selectedIndex = preparedChannels.indexOfFirst {
                it.url == selected.url && it.name == selected.name
            }
            preparedIndex = if (selectedIndex >= 0) selectedIndex else 0
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var nameView: TextView
    private lateinit var programView: TextView
    private lateinit var nextProgramView: TextView
    private lateinit var hintView: TextView
    private lateinit var clockView: TextView
    private lateinit var overlay: LinearLayout
    private lateinit var touchControls: LinearLayout
    private lateinit var previousTouch: TextView
    private lateinit var nightTouch: TextView
    private lateinit var channelsTouch: TextView
    private lateinit var nextTouch: TextView
    private lateinit var nightFilterView: View
    private lateinit var nightPanel: LinearLayout
    private lateinit var nightStatus: TextView
    private lateinit var nightToggle: TextView
    private lateinit var sleepTimer: TextView
    private lateinit var nightFilterButton: TextView
    private lateinit var nightVolume: TextView
    private lateinit var nightBrightness: TextView
    private lateinit var channelPanel: LinearLayout
    private lateinit var channelPanelTitle: TextView
    private lateinit var channelListView: ListView
    private lateinit var channelPanelAdapter: MiniChannelAdapter

    private lateinit var castButton: MediaRouteButton
    private lateinit var castRemoteRouteButton: MediaRouteButton
    private lateinit var castRemoteRoot: LinearLayout
    private lateinit var castRemoteDevice: TextView
    private lateinit var castRemoteName: TextView
    private lateinit var castRemoteProgram: TextView
    private lateinit var castRemoteNextProgram: TextView
    private lateinit var castRemotePrevious: TextView
    private lateinit var castRemotePlayPause: TextView
    private lateinit var castRemoteNext: TextView
    private lateinit var castRemoteSleep: TextView
    private lateinit var castRemoteStop: TextView
    private lateinit var castRemoteContentTitle: TextView
    private lateinit var castRemoteContentStatus: TextView
    private lateinit var castRemoteContentList: ListView
    private lateinit var castTabRemote: TextView
    private lateinit var castTabChannels: TextView
    private lateinit var castTabGuide: TextView
    private lateinit var castTabSport: TextView
    private lateinit var castRemoteAdapter: CastRemoteAdapter

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val sleepHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val castEventFormat = SimpleDateFormat("EEE d. MMM · HH:mm", Locale.getDefault())

    private val sleepRunnable = Runnable {
        NightModePreferences.clearSleepTimer(this)
        if (isCasting) {
            player?.stop()
            endCastSession()
            android.widget.Toast.makeText(
                this,
                "Sleep timer ferdig. Chromecast-avspillingen er stoppet.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            player?.pause()
            android.widget.Toast.makeText(
                this,
                "Sleep timer ferdig. Avspillingen er stoppet.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }

    private val sleepFadeRunnable = object : Runnable {
        override fun run() {
            val endAt = NightModePreferences.sleepEndAt(this@FullscreenPlayerActivity)
            val remaining = endAt - System.currentTimeMillis()
            if (endAt <= 0L || remaining <= 0L) return

            if (!isCasting) {
                val baseVolume = if (NightModePreferences.isEnabled(this@FullscreenPlayerActivity)) {
                    NightModePreferences.playerVolume(this@FullscreenPlayerActivity)
                } else {
                    1f
                }
                val factor = (remaining.coerceAtMost(60_000L) / 60_000f).coerceIn(0.08f, 1f)
                player?.volume = baseVolume * factor
            }
            sleepHandler.postDelayed(this, 1_000L)
        }
    }

    private var player: Player? = null
    private var localPlayer: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private var castSupported: Boolean = false
    private var isCasting: Boolean = false
    private var castTab: CastTab = CastTab.REMOTE
    private var channels: List<Channel> = emptyList()
    private var epgData: EpgData = EpgData.EMPTY
    private var channelIndex: Int = 0
    private var lastChannelSwitchAt: Long = 0L
    private var lastAutoFrameRate: Float = -1f

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                if (!isCasting) {
                    localPlayer?.let(::applyDetectedFrameRate)
                }
                refreshHint()
                refreshCastRemotePanel()
                scheduleOverlayHide()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (!isCasting) {
                playerView.postDelayed({
                    localPlayer?.let(::applyDetectedFrameRate)
                }, 150L)
            }
        }

        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            updateCastMode(deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshCastRemoteControls()
        }

        override fun onPlayerError(error: PlaybackException) {
            hintView.text = "Avspillingsfeil: ${error.errorCodeName}"
            if (isCasting) {
                castRemoteContentStatus.text =
                    "Cast-feil: ${error.errorCodeName}. Streamen kan være inkompatibel med Chromecast."
            } else {
                showOverlay()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContentView(R.layout.activity_fullscreen_player)

        playerView = findViewById(R.id.fullscreenPlayer)
        nameView = findViewById(R.id.fullscreenName)
        programView = findViewById(R.id.fullscreenProgram)
        nextProgramView = findViewById(R.id.fullscreenNextProgram)
        hintView = findViewById(R.id.fullscreenHint)
        clockView = findViewById(R.id.fullscreenClock)
        overlay = findViewById(R.id.fullscreenOverlay)
        touchControls = findViewById(R.id.fullscreenTouchControls)
        previousTouch = findViewById(R.id.fullscreenPrevious)
        nightTouch = findViewById(R.id.fullscreenNight)
        channelsTouch = findViewById(R.id.fullscreenChannels)
        nextTouch = findViewById(R.id.fullscreenNext)
        nightFilterView = findViewById(R.id.fullscreenNightFilter)
        nightPanel = findViewById(R.id.fullscreenNightPanel)
        nightStatus = findViewById(R.id.fullscreenNightStatus)
        nightToggle = findViewById(R.id.fullscreenNightToggle)
        sleepTimer = findViewById(R.id.fullscreenSleepTimer)
        nightFilterButton = findViewById(R.id.fullscreenNightFilterButton)
        nightVolume = findViewById(R.id.fullscreenNightVolume)
        nightBrightness = findViewById(R.id.fullscreenNightBrightness)
        channelPanel = findViewById(R.id.fullscreenChannelPanel)
        channelPanelTitle = findViewById(R.id.fullscreenChannelPanelTitle)
        channelListView = findViewById(R.id.fullscreenChannelList)

        castButton = findViewById(R.id.fullscreenCastButton)
        castRemoteRouteButton = findViewById(R.id.castRemoteRouteButton)
        castRemoteRoot = findViewById(R.id.castRemoteRoot)
        castRemoteDevice = findViewById(R.id.castRemoteDevice)
        castRemoteName = findViewById(R.id.castRemoteName)
        castRemoteProgram = findViewById(R.id.castRemoteProgram)
        castRemoteNextProgram = findViewById(R.id.castRemoteNextProgram)
        castRemotePrevious = findViewById(R.id.castRemotePrevious)
        castRemotePlayPause = findViewById(R.id.castRemotePlayPause)
        castRemoteNext = findViewById(R.id.castRemoteNext)
        castRemoteSleep = findViewById(R.id.castRemoteSleep)
        castRemoteStop = findViewById(R.id.castRemoteStop)
        castRemoteContentTitle = findViewById(R.id.castRemoteContentTitle)
        castRemoteContentStatus = findViewById(R.id.castRemoteContentStatus)
        castRemoteContentList = findViewById(R.id.castRemoteContentList)
        castTabRemote = findViewById(R.id.castTabRemote)
        castTabChannels = findViewById(R.id.castTabChannels)
        castTabGuide = findViewById(R.id.castTabGuide)
        castTabSport = findViewById(R.id.castTabSport)

        channels = preparedChannels
        epgData = preparedEpgData
        channelIndex = preparedIndex.coerceIn(0, channels.lastIndex.coerceAtLeast(0))

        if (channels.isEmpty()) {
            val fallbackUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
            val fallbackName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Cloud247 TV" }
            if (fallbackUrl.isNotBlank()) {
                channels = listOf(Channel(name = fallbackName, url = fallbackUrl))
                channelIndex = 0
            }
        }

        channelPanelAdapter = MiniChannelAdapter()
        channelListView.adapter = channelPanelAdapter
        channelListView.choiceMode = ListView.CHOICE_MODE_SINGLE
        channelListView.setOnItemClickListener { _, _, position, _ ->
            selectChannelFromPanel(position)
        }

        castRemoteAdapter = CastRemoteAdapter()
        castRemoteContentList.adapter = castRemoteAdapter
        castRemoteContentList.setOnItemClickListener { _, _, position, _ ->
            val row = castRemoteAdapter.getItem(position)
            val target = row.channelIndex
            if (target != null && target in channels.indices) {
                channelIndex = target
                playCurrentChannel()
            } else if (castTab == CastTab.SPORT) {
                android.widget.Toast.makeText(
                    this,
                    "Ingen sikker kanal-match for denne sportshendelsen ennå.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        configureCasting()
        configureTouchControls()
        configureNightMode()
        applyNightMode()
        restoreSleepTimer()
        clockView.text = timeFormat.format(Date())
        playCurrentChannel()
    }

    override fun onStart() {
        super.onStart()
        restoreSleepTimer()
        applyNightMode()
        if (!isCasting) player?.play()
    }

    private fun configureCasting() {
        castSupported = !DeviceProfile.isTelevision(this)
        if (!castSupported) {
            castButton.visibility = View.GONE
            castRemoteRouteButton.visibility = View.GONE
            return
        }

        try {
            MediaRouteButtonFactory.setUpMediaRouteButton(this, castButton)
            MediaRouteButtonFactory.setUpMediaRouteButton(this, castRemoteRouteButton)
            castButton.visibility = View.VISIBLE
        } catch (_: Exception) {
            castSupported = false
            castButton.visibility = View.GONE
            castRemoteRouteButton.visibility = View.GONE
        }

        castRemotePrevious.setOnClickListener { switchChannel(-1) }
        castRemoteNext.setOnClickListener { switchChannel(1) }
        castRemotePlayPause.setOnClickListener {
            if (player?.isPlaying == true) player?.pause() else player?.play()
            refreshCastRemoteControls()
        }
        castRemoteSleep.setOnClickListener {
            cycleSleepTimer()
            refreshNightPanel()
            refreshCastRemotePanel()
        }
        castRemoteStop.setOnClickListener {
            player?.stop()
            endCastSession()
        }

        castTabRemote.setOnClickListener { setCastTab(CastTab.REMOTE) }
        castTabChannels.setOnClickListener { setCastTab(CastTab.CHANNELS) }
        castTabGuide.setOnClickListener { setCastTab(CastTab.GUIDE) }
        castTabSport.setOnClickListener { setCastTab(CastTab.SPORT) }
    }

    private fun configureTouchControls() {
        previousTouch.setOnClickListener { switchChannel(-1) }
        nextTouch.setOnClickListener { switchChannel(1) }
        nightTouch.setOnClickListener { showNightPanel() }
        channelsTouch.setOnClickListener { showChannelPanel() }

        val swipeThreshold = 100f * resources.displayMetrics.density
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val width = playerView.width.toFloat()
                    if (width <= 0f) {
                        toggleOverlay()
                        return true
                    }

                    when {
                        e.x < width / 3f -> switchChannel(-1)
                        e.x > width * 2f / 3f -> switchChannel(1)
                        else -> toggleOverlay()
                    }
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = e1 ?: return false
                    val deltaX = e2.x - start.x
                    val deltaY = e2.y - start.y

                    if (abs(deltaY) >= swipeThreshold && abs(deltaY) > abs(deltaX)) {
                        if (deltaY < 0f) showChannelPanel() else hideChannelPanel()
                        return true
                    }

                    if (abs(deltaX) < swipeThreshold || abs(deltaX) <= abs(deltaY)) {
                        return false
                    }

                    if (deltaX < 0f) switchChannel(1) else switchChannel(-1)
                    return true
                }
            }
        )

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun configureNightMode() {
        nightToggle.setOnClickListener {
            NightModePreferences.setEnabled(
                this,
                !NightModePreferences.isEnabled(this)
            )
            applyNightMode()
            refreshNightPanel()
        }

        sleepTimer.setOnClickListener {
            cycleSleepTimer()
            refreshNightPanel()
        }

        nightFilterButton.setOnClickListener {
            NightModePreferences.cycleFilter(this)
            applyNightMode()
            refreshNightPanel()
        }

        nightVolume.setOnClickListener {
            NightModePreferences.cycleVolume(this)
            applyNightMode()
            refreshNightPanel()
        }

        nightBrightness.setOnClickListener {
            NightModePreferences.cycleBrightness(this)
            applyNightMode()
            refreshNightPanel()
        }
    }

    private fun applyNightMode() {
        val enabled = NightModePreferences.isEnabled(this)
        nightFilterView.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            nightFilterView.alpha = NightModePreferences.filterAlpha(this)
            if (!isCasting) player?.volume = NightModePreferences.playerVolume(this)
        } else {
            nightFilterView.alpha = 0f
            if (!isCasting) player?.volume = 1f
        }
        NightModePreferences.applyWindowBrightness(this)
        nightTouch.text = if (enabled) "Natt ✓" else "Natt"
        refreshHint()
    }

    private fun showNightPanel() {
        hideChannelPanelOnly()
        overlayHandler.removeCallbacksAndMessages(null)
        refreshNightPanel()
        nightPanel.visibility = View.VISIBLE
        nightToggle.requestFocus()
    }

    private fun hideNightPanel() {
        if (nightPanel.visibility == View.GONE) return
        nightPanel.visibility = View.GONE
        playerView.requestFocus()
        showOverlay()
    }

    private fun hideChannelPanelOnly() {
        if (channelPanel.visibility == View.VISIBLE) {
            channelPanel.visibility = View.GONE
        }
    }

    private fun refreshNightPanel() {
        val enabled = NightModePreferences.isEnabled(this)
        nightToggle.text = "Nattmodus: " + if (enabled) "På" else "Av"
        nightFilterButton.text = "Nattfilter: ${NightModePreferences.filterLabel(this)}"
        nightVolume.text = "Nattlyd: ${NightModePreferences.volumeLabel(this)}"
        nightBrightness.text = "Lysstyrke: ${NightModePreferences.brightnessLabel(this)}"

        val endAt = NightModePreferences.sleepEndAt(this)
        val remaining = endAt - System.currentTimeMillis()
        sleepTimer.text = if (remaining > 0L) {
            val minutes = ((remaining + 59_999L) / 60_000L).toInt()
            "Sleep timer: ${minutes} min igjen"
        } else {
            "Sleep timer: Av"
        }

        nightStatus.text = if (isCasting) {
            "Nattfilter og lysstyrke gjelder nettbrettet. Cast-volumet endres ikke. Sleep timer stopper Chromecast."
        } else if (enabled) {
            "Nattfilter, lavere lysstyrke og redusert app-lyd er aktivt."
        } else {
            "Slå på for roligere bilde og lyd uten å endre systemvolumet."
        }
    }

    private fun cycleSleepTimer() {
        val endAt = NightModePreferences.sleepEndAt(this)
        val remainingMinutes = if (endAt > System.currentTimeMillis()) {
            ((endAt - System.currentTimeMillis()) / 60_000L).toInt()
        } else {
            0
        }

        val next = when {
            remainingMinutes <= 0 -> 30
            remainingMinutes <= 30 -> 60
            remainingMinutes <= 60 -> 90
            remainingMinutes <= 90 -> 120
            else -> 0
        }

        if (next == 0) {
            NightModePreferences.clearSleepTimer(this)
        } else {
            NightModePreferences.setSleepMinutes(this, next)
        }
        restoreSleepTimer()
        applyNightMode()
    }

    private fun restoreSleepTimer() {
        sleepHandler.removeCallbacksAndMessages(null)
        val endAt = NightModePreferences.sleepEndAt(this)
        val delay = endAt - System.currentTimeMillis()

        if (endAt <= 0L) return
        if (delay <= 0L) {
            NightModePreferences.clearSleepTimer(this)
            return
        }

        sleepHandler.postDelayed(sleepRunnable, delay)
        val fadeDelay = (delay - 60_000L).coerceAtLeast(0L)
        sleepHandler.postDelayed(sleepFadeRunnable, fadeDelay)
    }

    private fun playCurrentChannel() {
        val channel = channels.getOrNull(channelIndex)
        if (channel == null || channel.url.isBlank()) {
            nameView.text = "Cloud247 TV"
            hintView.text = "Mangler stream-adresse."
            showOverlay()
            return
        }
        playChannel(channel)
    }

    private fun playChannel(channel: Channel) {
        clearAutoFrameRate()

        nameView.text = channel.name.ifBlank { "Cloud247 TV" }
        updateProgramInfo(channel)
        hintView.text = if (isCasting) "Sender kanal til Chromecast …" else "Laster kanal …"
        if (!isCasting) showOverlay()

        try {
            if (player == null) {
                val session = PlayerFactory.create(this, channel.url)
                localPlayer = session.player

                player = if (castSupported) {
                    CastPlayer.Builder(this)
                        .setLocalPlayer(session.player)
                        .build()
                        .also { castPlayer = it }
                } else {
                    session.player
                }

                player?.addListener(playerListener)
                playerView.player = player
                player?.setMediaItem(session.mediaItem)
            } else {
                player?.setMediaItem(PlayerFactory.mediaItemFor(channel.url))
            }

            if (!isCasting) {
                player?.volume = if (NightModePreferences.isEnabled(this)) {
                    NightModePreferences.playerVolume(this)
                } else {
                    1f
                }
            }

            player?.prepare()
            player?.playWhenReady = true

            val remote =
                player?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
            updateCastMode(remote)
            refreshCastRemotePanel()

            if (!remote) playerView.requestFocus()
        } catch (error: Exception) {
            val message = error.message?.take(120) ?: "ukjent feil"
            hintView.text = "Kunne ikke starte avspillingen: $message"
            if (isCasting) {
                castRemoteContentStatus.text = "Kunne ikke caste kanalen: $message"
            } else {
                showOverlay()
            }
        }
    }

    private fun updateProgramInfo(channel: Channel) {
        val window = EpgLookup.window(channel, epgData)
        val current = window.now
        val next = window.next

        if (current != null) {
            val programs = EpgLookup.programsForChannel(channel, epgData)
            val currentIndex = programs.indexOf(current)
            val stop = if (currentIndex >= 0) EpgLookup.effectiveStop(programs, currentIndex) else current.stop
            val stopText = stop?.let(timeFormat::format).orEmpty()
            programView.visibility = View.VISIBLE
            programView.text = "${timeFormat.format(current.start)}–$stopText  ${current.title}"
        } else {
            programView.visibility = View.GONE
            programView.text = ""
        }

        if (next != null) {
            nextProgramView.visibility = View.VISIBLE
            nextProgramView.text = "Neste ${timeFormat.format(next.start)} · ${next.title}"
        } else {
            nextProgramView.visibility = View.GONE
            nextProgramView.text = ""
        }
    }

    private fun switchChannel(delta: Int) {
        if (channels.size <= 1) {
            showOverlay()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastChannelSwitchAt < 250L) return
        lastChannelSwitchAt = now

        channelIndex = (channelIndex + delta + channels.size) % channels.size
        channelPanelAdapter.notifyDataSetChanged()
        playCurrentChannel()
    }

    private fun showChannelPanel() {
        if (channels.isEmpty()) return
        if (nightPanel.visibility == View.VISIBLE) nightPanel.visibility = View.GONE
        overlayHandler.removeCallbacksAndMessages(null)
        channelPanelTitle.text = "KANALER · ${channels.size}"
        channelPanelAdapter.notifyDataSetChanged()
        channelPanel.visibility = View.VISIBLE
        channelListView.setSelection(channelIndex)
        channelListView.post {
            channelListView.requestFocus()
            channelListView.setSelection(channelIndex)
        }
    }

    private fun hideChannelPanel() {
        if (channelPanel.visibility == View.GONE) return
        channelPanel.visibility = View.GONE
        playerView.requestFocus()
        showOverlay()
    }

    private fun selectChannelFromPanel(position: Int) {
        if (position !in channels.indices) return
        channelIndex = position
        hideChannelPanel()
        playCurrentChannel()
    }

    private fun updateCastMode(remote: Boolean) {
        if (!castSupported && remote) return
        val changed = isCasting != remote
        isCasting = remote

        if (remote) {
            clearAutoFrameRate()
            overlayHandler.removeCallbacksAndMessages(null)
            overlay.visibility = View.GONE
            touchControls.visibility = View.GONE
            channelPanel.visibility = View.GONE
            nightPanel.visibility = View.GONE
            playerView.visibility = View.GONE
            castRemoteRoot.visibility = View.VISIBLE
            refreshCastRemotePanel()
        } else {
            castRemoteRoot.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            playerView.player = player
            applyNightMode()
            if (changed) showOverlay()
        }
    }

    private fun currentCastDeviceName(): String {
        return try {
            CastContext.getSharedInstance(this)
                .sessionManager
                .currentCastSession
                ?.castDevice
                ?.friendlyName
                ?.takeIf { it.isNotBlank() }
                ?: "Chromecast"
        } catch (_: Exception) {
            "Chromecast"
        }
    }

    private fun endCastSession() {
        try {
            CastContext.getSharedInstance(this)
                .sessionManager
                .endCurrentSession(true)
        } catch (_: Exception) {
        }
    }

    private fun setCastTab(tab: CastTab) {
        castTab = tab
        refreshCastRemotePanel()
    }

    private fun refreshCastRemoteControls() {
        if (!::castRemotePlayPause.isInitialized) return
        castRemotePlayPause.text = if (player?.isPlaying == true) "Pause" else "Spill"
    }

    private fun refreshCastRemotePanel() {
        if (!::castRemoteRoot.isInitialized || !isCasting) return

        val channel = channels.getOrNull(channelIndex)
        castRemoteDevice.text = "📺 Spiller på ${currentCastDeviceName()}"
        castRemoteName.text = channel?.name?.ifBlank { "Cloud247 TV" } ?: "Cloud247 TV"

        val window = channel?.let { EpgLookup.window(it, epgData) }
        castRemoteProgram.text = window?.now?.let {
            "Nå ${timeFormat.format(it.start)} · ${it.title}"
        } ?: "Direktesending"
        castRemoteNextProgram.text = window?.next?.let {
            "Neste ${timeFormat.format(it.start)} · ${it.title}"
        } ?: ""

        val endAt = NightModePreferences.sleepEndAt(this)
        val remaining = endAt - System.currentTimeMillis()
        castRemoteSleep.text = if (remaining > 0L) {
            "Sleep · ${((remaining + 59_999L) / 60_000L)} min"
        } else {
            "Sleep · Av"
        }
        refreshCastRemoteControls()

        castTabRemote.setTextColor(
            getColor(if (castTab == CastTab.REMOTE) R.color.yellow else R.color.white)
        )
        castTabChannels.setTextColor(
            getColor(if (castTab == CastTab.CHANNELS) R.color.yellow else R.color.white)
        )
        castTabGuide.setTextColor(
            getColor(if (castTab == CastTab.GUIDE) R.color.yellow else R.color.white)
        )
        castTabSport.setTextColor(
            getColor(if (castTab == CastTab.SPORT) R.color.yellow else R.color.white)
        )

        val rows = when (castTab) {
            CastTab.REMOTE -> buildRemoteRows(channel)
            CastTab.CHANNELS -> buildChannelRows()
            CastTab.GUIDE -> buildGuideRows()
            CastTab.SPORT -> buildSportsRows()
        }

        castRemoteContentTitle.text = when (castTab) {
            CastTab.REMOTE -> "REMOTE"
            CastTab.CHANNELS -> "KANALER · ${channels.size}"
            CastTab.GUIDE -> "TV-GUIDE"
            CastTab.SPORT -> "SPORT · KOMMENDE FOR DEG"
        }
        castRemoteContentStatus.text = when (castTab) {
            CastTab.REMOTE -> "Nettbrettet fungerer nå som kontrollpanel."
            CastTab.CHANNELS -> "Trykk på en kanal for å sende den direkte til TV-en."
            CastTab.GUIDE -> "Nå og neste. Trykk på en kanal for å bytte på TV-en."
            CastTab.SPORT -> "Trykk på en sportshendelse med kanal-match for å sende den til TV-en."
        }

        castRemoteAdapter.setRows(rows)
    }

    private fun buildRemoteRows(channel: Channel?): List<CastPanelRow> {
        if (channel == null) return emptyList()
        val window = EpgLookup.window(channel, epgData)
        val endAt = NightModePreferences.sleepEndAt(this)
        val remaining = endAt - System.currentTimeMillis()
        val sleepText = if (remaining > 0L) {
            "${((remaining + 59_999L) / 60_000L)} min igjen"
        } else {
            "Av"
        }

        return listOf(
            CastPanelRow(
                title = "Nå · ${channel.name}",
                subtitle = window.now?.title ?: "Direktesending"
            ),
            CastPanelRow(
                title = "Neste",
                subtitle = window.next?.let {
                    "${timeFormat.format(it.start)} · ${it.title}"
                } ?: "Ingen EPG-data"
            ),
            CastPanelRow(
                title = "Cast-enhet",
                subtitle = currentCastDeviceName()
            ),
            CastPanelRow(
                title = "Sleep timer",
                subtitle = "$sleepText · stopper Chromecast når tiden går ut"
            )
        )
    }

    private fun buildChannelRows(): List<CastPanelRow> =
        channels.mapIndexed { index, channel ->
            val now = EpgLookup.window(channel, epgData).now
            CastPanelRow(
                title = if (index == channelIndex) "▶ ${channel.name}" else channel.name,
                subtitle = now?.title ?: channel.group,
                channelIndex = index
            )
        }

    private fun buildGuideRows(): List<CastPanelRow> =
        channels.mapIndexed { index, channel ->
            val window = EpgLookup.window(channel, epgData)
            val subtitle = buildString {
                if (window.now != null) append("Nå · ${window.now.title}")
                else append("Ingen programinfo")
                if (window.next != null) {
                    append(
                        "   |   Neste ${timeFormat.format(window.next.start)} · ${window.next.title}"
                    )
                }
            }
            CastPanelRow(
                title = channel.name,
                subtitle = subtitle,
                channelIndex = index
            )
        }

    private fun buildSportsRows(): List<CastPanelRow> {
        val events = SportsHubCache.load(this).events.take(30)
        if (events.isEmpty()) {
            return listOf(
                CastPanelRow(
                    title = "Ingen kommende sport lagret",
                    subtitle = "Åpne Sport fra hovedskjermen for å velge favoritter og hente sportsdata."
                )
            )
        }

        return events.map { event ->
            val match = SportsChannelMatcher.find(event, channels, epgData)
            val targetIndex = match?.channel?.let { matched ->
                channels.indexOfFirst {
                    it.url == matched.url && it.name == matched.name
                }.takeIf { it >= 0 }
            }
            val icon = when (event.sport) {
                "tennis" -> "🎾"
                "golf" -> "⛳"
                else -> "⚽"
            }
            val channelText = match?.channel?.name ?: "Kanal ikke matchet ennå"
            CastPanelRow(
                title = "$icon ${event.title}",
                subtitle =
                    "${castEventFormat.format(event.start)} · ${event.competition} · $channelText",
                channelIndex = targetIndex
            )
        }
    }

    private fun showOverlay() {
        overlay.animate().cancel()
        touchControls.animate().cancel()

        overlay.alpha = 1f
        touchControls.alpha = 1f
        overlay.visibility = View.VISIBLE
        touchControls.visibility = View.VISIBLE

        clockView.text = timeFormat.format(Date())
        channels.getOrNull(channelIndex)?.let(::updateProgramInfo)
        scheduleOverlayHide()
    }

    private fun hideOverlay() {
        if (channelPanel.visibility == View.VISIBLE || nightPanel.visibility == View.VISIBLE) return

        overlay.animate().cancel()
        touchControls.animate().cancel()

        overlay.animate()
            .alpha(0f)
            .setDuration(220L)
            .withEndAction { overlay.visibility = View.GONE }
            .start()

        touchControls.animate()
            .alpha(0f)
            .setDuration(220L)
            .withEndAction { touchControls.visibility = View.GONE }
            .start()
    }

    private fun toggleOverlay() {
        if (channelPanel.visibility == View.VISIBLE) {
            hideChannelPanel()
            return
        }
        if (nightPanel.visibility == View.VISIBLE) {
            hideNightPanel()
            return
        }

        if (overlay.visibility == View.VISIBLE && overlay.alpha > 0.2f) {
            overlayHandler.removeCallbacksAndMessages(null)
            hideOverlay()
        } else {
            showOverlay()
        }
    }

    private fun scheduleOverlayHide() {
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.postDelayed({ hideOverlay() }, 3500L)
    }

    private fun applyDetectedFrameRate(targetPlayer: ExoPlayer) {
        val frameRate = targetPlayer.videoFormat?.frameRate ?: -1f
        if (!frameRate.isFinite() || frameRate < 10f || frameRate > 240f) return
        if (abs(frameRate - lastAutoFrameRate) < 0.01f) return

        val surfaceView = playerView.videoSurfaceView as? SurfaceView
        val surface = surfaceView?.holder?.surface

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && surface?.isValid == true -> {
                    surface.setFrameRate(
                        frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS
                    )
                    lastAutoFrameRate = frameRate
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surface?.isValid == true -> {
                    surface.setFrameRate(
                        frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                    )
                    lastAutoFrameRate = frameRate
                }
                else -> {
                    applyLegacyDisplayMode(frameRate)
                }
            }
        } catch (_: Exception) {
            applyLegacyDisplayMode(frameRate)
        }

        refreshHint()
    }

    private fun applyLegacyDisplayMode(frameRate: Float) {
        val display = window.decorView.display ?: return
        val current = display.mode
        val sameResolution = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val candidates = if (sameResolution.isNotEmpty()) sameResolution else display.supportedModes.toList()
        val best = candidates.minByOrNull { mode ->
            (1..5).minOf { multiplier ->
                abs(mode.refreshRate - frameRate * multiplier)
            }
        } ?: return

        val score = (1..5).minOf { multiplier ->
            abs(best.refreshRate - frameRate * multiplier)
        }
        if (score > 0.75f) return

        val attributes = window.attributes
        attributes.preferredDisplayModeId = best.modeId
        window.attributes = attributes
        lastAutoFrameRate = frameRate
    }

    private fun clearAutoFrameRate() {
        val surface = (playerView.videoSurfaceView as? SurfaceView)?.holder?.surface
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && surface?.isValid == true -> {
                    surface.setFrameRate(
                        0f,
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ALWAYS
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surface?.isValid == true -> {
                    surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                }
            }
        } catch (_: Exception) {
        }

        val attributes = window.attributes
        attributes.preferredDisplayModeId = 0
        window.attributes = attributes
        lastAutoFrameRate = -1f
    }

    private fun refreshHint() {
        val afr = if (lastAutoFrameRate > 0f) {
            " · AFR ${String.format(Locale.US, "%.2f", lastAutoFrameRate).trimEnd('0').trimEnd('.')} fps"
        } else {
            ""
        }
        val night = if (NightModePreferences.isEnabled(this)) " · Natt" else ""
        hintView.text = REMOTE_HINT + afr + night
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (nightPanel.visibility == View.VISIBLE) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MENU -> {
                    hideNightPanel()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        if (channelPanel.visibility == View.VISIBLE) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MENU -> {
                    hideChannelPanel()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if ((event?.repeatCount ?: 0) == 0) switchChannel(-1)
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if ((event?.repeatCount ?: 0) == 0) switchChannel(1)
                true
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if ((event?.repeatCount ?: 0) == 0) switchChannel(1)
                true
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if ((event?.repeatCount ?: 0) == 0) switchChannel(-1)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MENU -> {
                showChannelPanel()
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showNightPanel()
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_INFO -> {
                showOverlay()
                true
            }

            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        overlayHandler.removeCallbacksAndMessages(null)
        sleepHandler.removeCallbacksAndMessages(null)
        clearAutoFrameRate()
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private inner class MiniChannelAdapter : BaseAdapter() {
        override fun getCount(): Int = channels.size
        override fun getItem(position: Int): Channel = channels[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@FullscreenPlayerActivity)
                .inflate(R.layout.item_fullscreen_channel, parent, false)

            val channel = getItem(position)
            val name = view.findViewById<TextView>(R.id.fullscreenChannelName)
            val program = view.findViewById<TextView>(R.id.fullscreenChannelProgram)
            val nowProgram = EpgLookup.window(channel, epgData).now

            name.text = if (position == channelIndex) "▶ ${channel.name}" else channel.name
            name.setTextColor(
                getColor(if (position == channelIndex) R.color.yellow else R.color.white)
            )
            program.text = nowProgram?.title ?: channel.group

            return view
        }
    }
}
