package no.cloud247.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@OptIn(markerClass = [UnstableApi::class])
class FullscreenPlayerActivity : Activity() {
    companion object {
        const val EXTRA_URL = "stream_url"
        const val EXTRA_NAME = "channel_name"
    }

    private lateinit var playerView: PlayerView
    private lateinit var nameView: TextView
    private lateinit var hintView: TextView
    private var player: ExoPlayer? = null

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

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val channelName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Cloud247 TV" }
        nameView.text = channelName

        if (url.isBlank()) {
            hintView.text = "Mangler stream-adresse."
            return
        }

        try {
            val session = PlayerFactory.create(this, url)
            player = session.player
            playerView.player = session.player
            session.player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        hintView.text = "Tilbake for å gå tilbake til kanaloversikten"
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    hintView.text = "Avspillingsfeil: ${error.errorCodeName}"
                }
            })
            session.player.setMediaItem(session.mediaItem)
            session.player.prepare()
            session.player.playWhenReady = true
            playerView.requestFocus()
        } catch (error: Exception) {
            hintView.text = "Kunne ikke starte avspillingen: ${error.message?.take(100) ?: "ukjent feil"}"
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
