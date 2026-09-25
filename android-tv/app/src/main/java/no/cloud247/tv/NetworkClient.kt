package no.cloud247.tv

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

object NetworkClient {
    private const val USER_AGENT = "Cloud247-TV/1.2.1 (Android)"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5

    fun fetchText(url: String, maxBytes: Int = 16 * 1024 * 1024): String {
        return String(fetchBytes(url, maxBytes), Charsets.UTF_8)
    }

    fun fetchBytes(url: String, maxBytes: Int): ByteArray {
        return withInputStream(url, maxBytes) { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    fun <T> withInputStream(url: String, maxBytes: Int, block: (InputStream) -> T): T {
        var current = URL(url)
        var inheritedAuthorization: String? = basicAuthorization(current)

        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val requestUrl = withoutUserInfo(current)
            val connection = (requestUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "gzip")
                inheritedAuthorization?.let { setRequestProperty("Authorization", it) }
            }

            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (redirectIndex >= MAX_REDIRECTS) {
                        throw NetworkException("For mange videresendinger")
                    }

                    val location = connection.getHeaderField("Location")
                        ?: throw NetworkException("Ugyldig videresending fra leverandøren")
                    val next = URL(current, location)

                    if (next.protocol !in listOf("http", "https")) {
                        throw NetworkException("Ustøttet protokoll i videresending")
                    }

                    val sameHost = current.host.equals(next.host, ignoreCase = true)
                    inheritedAuthorization =
                        basicAuthorization(next) ?: if (sameHost) inheritedAuthorization else null
                    current = next
                    return@repeat
                }

                if (status !in 200..299) {
                    throw NetworkException("Leverandøren svarte HTTP $status")
                }

                val contentLength = connection.contentLengthLong
                val compressed = connection.contentEncoding.equals("gzip", ignoreCase = true) ||
                    current.path.endsWith(".gz", ignoreCase = true)

                // For compressed XMLTV, Content-Length is the compressed size. The actual
                // decompressed size is enforced by LimitedInputStream below.
                if (!compressed && contentLength > maxBytes) {
                    throw NetworkException("Responsen er for stor")
                }

                val raw = connection.inputStream
                val decoded = if (compressed) GZIPInputStream(raw) else raw
                LimitedInputStream(decoded, maxBytes).use { limited ->
                    return block(limited)
                }
            } finally {
                connection.disconnect()
            }
        }

        throw NetworkException("Kunne ikke hente adressen")
    }

    private class LimitedInputStream(
        input: InputStream,
        private val maxBytes: Int
    ) : FilterInputStream(input) {
        private var total: Long = 0

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) addBytes(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) addBytes(read)
            return read
        }

        private fun addBytes(count: Int) {
            total += count.toLong()
            if (total > maxBytes.toLong()) {
                throw NetworkException("Responsen er for stor")
            }
        }
    }

    private fun basicAuthorization(url: URL): String? {
        val info = url.userInfo ?: return null
        val encoded = Base64.encodeToString(info.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    private fun withoutUserInfo(url: URL): URL {
        if (url.userInfo.isNullOrEmpty()) return url
        return URI(url.protocol, null, url.host, url.port, url.path, url.query, null).toURL()
    }
}

class NetworkException(message: String) : Exception(message)
