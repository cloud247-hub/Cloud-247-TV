package no.cloud247.tv

import androidx.annotation.OptIn
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import android.app.Activity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(markerClass = [UnstableApi::class])
class FullscreenPlayerActivity : Activity() {
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
    private lateinit var channelsTouch: TextView
    private lateinit var nextTouch: TextView
    private lateinit var channelPanel: LinearLayout
    private lateinit var channelPanelTitle: TextView
    private lateinit var channelListView: ListView
    private lateinit var channelPanelAdapter: MiniChannelAdapter

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()
    private var epgData: EpgData = EpgData.EMPTY
    private var channelIndex: Int = 0
    private var lastChannelSwitchAt: Long = 0L
    private var lastAutoFrameRate: Float = -1f

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
        channelsTouch = findViewById(R.id.fullscreenChannels)
        nextTouch = findViewById(R.id.fullscreenNext)
        channelPanel = findViewById(R.id.fullscreenChannelPanel)
        channelPanelTitle = findViewById(R.id.fullscreenChannelPanelTitle)
        channelListView = findViewById(R.id.fullscreenChannelList)

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

        configureTouchControls()
        clockView.text = timeFormat.format(Date())
        playCurrentChannel()
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    private fun configureTouchControls() {
        previousTouch.setOnClickListener { switchChannel(-1) }
        nextTouch.setOnClickListener { switchChannel(1) }
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
        playerView.player = null
        player?.release()
        player = null

        nameView.text = channel.name.ifBlank { "Cloud247 TV" }
        updateProgramInfo(channel)
        hintView.text = "Laster kanal …"
        showOverlay()

        try {
            val session = PlayerFactory.create(this, channel.url)
            player = session.player
            playerView.player = session.player
            session.player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        applyDetectedFrameRate(session.player)
                        refreshHint()
                        scheduleOverlayHide()
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    playerView.postDelayed({
                        if (player === session.player) applyDetectedFrameRate(session.player)
                    }, 150L)
                }

                override fun onPlayerError(error: PlaybackException) {
                    hintView.text = "Avspillingsfeil: ${error.errorCodeName}"
                    showOverlay()
                }
            })
            session.player.setMediaItem(session.mediaItem)
            session.player.prepare()
            session.player.playWhenReady = true
            playerView.requestFocus()
        } catch (error: Exception) {
            hintView.text =
                "Kunne ikke starte avspillingen: ${error.message?.take(100) ?: "ukjent feil"}"
            showOverlay()
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
        if (channelPanel.visibility == View.VISIBLE) return

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
        hintView.text = REMOTE_HINT + afr
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
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
