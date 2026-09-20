package no.cloud247.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.net.URI
import java.net.URL

@OptIn(markerClass = [UnstableApi::class])
object PlayerFactory {
    private const val USER_AGENT = "Cloud247-TV/1.0.0 (Android TV)"

    data class Session(val player: ExoPlayer, val mediaItem: MediaItem)

    fun create(context: Context, originalUrl: String): Session {
        val prepared = prepareUrl(originalUrl)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)

        if (prepared.authorization != null) {
            httpFactory.setDefaultRequestProperties(mapOf("Authorization" to prepared.authorization))
        }

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        val builder = MediaItem.Builder().setUri(prepared.url)
        if (looksLikeHls(originalUrl)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)

        return Session(player, builder.build())
    }

    private fun looksLikeHls(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true) ||
            url.contains("output=m3u8", ignoreCase = true) ||
            url.contains("type=m3u8", ignoreCase = true)
    }

    private fun prepareUrl(value: String): PreparedUrl {
        return try {
            val url = URL(value)
            val userInfo = url.userInfo
            if (userInfo.isNullOrBlank()) return PreparedUrl(Uri.parse(value), null)

            val auth = "Basic " + Base64.encodeToString(
                userInfo.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val clean = URI(url.protocol, null, url.host, url.port, url.path, url.query, null).toString()
            PreparedUrl(Uri.parse(clean), auth)
        } catch (_: Exception) {
            PreparedUrl(Uri.parse(value), null)
        }
    }

    private data class PreparedUrl(val url: Uri, val authorization: String?)
}
