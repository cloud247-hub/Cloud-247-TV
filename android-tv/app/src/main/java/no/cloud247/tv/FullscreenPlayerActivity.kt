package no.cloud247.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(markerClass = [UnstableApi::class])
class FullscreenPlayerActivity : Activity() {
    companion object {
        const val EXTRA_URL = "stream_url"
        const val EXTRA_NAME = "channel_name"

        private const val REMOTE_HINT =
            "↑/↓ eller CH+/− bytter kanal · OK viser info · Tilbake går til kanaloversikten"

        private var preparedChannels: List<Channel> = emptyList()
        private var preparedIndex: Int = 0

        fun prepareSession(channels: List<Channel>, selected: Channel) {
            preparedChannels = channels.toList()
            val selectedIndex = preparedChannels.indexOfFirst {
                it.url == selected.url && it.name == selected.name
            }
            preparedIndex = if (selectedIndex >= 0) selectedIndex else 0
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var nameView: TextView
    private lateinit var hintView: TextView
    private lateinit var clockView: TextView
    private lateinit var overlay: LinearLayout
    private val overlayHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()
    private var channelIndex: Int = 0
    private var lastChannelSwitchAt: Long = 0L

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
        hintView = findViewById(R.id.fullscreenHint)
        clockView = findViewById(R.id.fullscreenClock)
        overlay = findViewById(R.id.fullscreenOverlay)

        channels = preparedChannels
        channelIndex = preparedIndex.coerceIn(0, channels.lastIndex.coerceAtLeast(0))

        if (channels.isEmpty()) {
            val fallbackUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
            val fallbackName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Cloud247 TV" }
            if (fallbackUrl.isNotBlank()) {
                channels = listOf(Channel(name = fallbackName, url = fallbackUrl))
                channelIndex = 0
            }
        }

        clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        playCurrentChannel()
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
        playerView.player = null
        player?.release()
        player = null

        nameView.text = channel.name.ifBlank { "Cloud247 TV" }
        hintView.text = "Laster kanal …"
        showOverlay()

        try {
            val session = PlayerFactory.create(this, channel.url)
            player = session.player
            playerView.player = session.player
            session.player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        hintView.text = REMOTE_HINT
                        scheduleOverlayHide()
                    }
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

    private fun switchChannel(delta: Int) {
        if (channels.size <= 1) {
            showOverlay()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastChannelSwitchAt < 250L) return
        lastChannelSwitchAt = now

        channelIndex = (channelIndex + delta + channels.size) % channels.size
        playCurrentChannel()
    }

    private fun showOverlay() {
        overlay.animate().cancel()
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        scheduleOverlayHide()
    }

    private fun scheduleOverlayHide() {
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.postDelayed({
            overlay.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction { overlay.visibility = View.GONE }
                .start()
        }, 2500L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU -> {
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
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
