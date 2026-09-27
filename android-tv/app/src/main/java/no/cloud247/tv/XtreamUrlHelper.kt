package no.cloud247.tv

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object XtreamUrlHelper {
    fun epgUrlFromPlaylistUrl(value: String): String? {
        return try {
            val uri = URI(value)
            if (uri.scheme !in listOf("http", "https")) return null

            val rawPath = uri.rawPath.orEmpty()
            if (!rawPath.endsWith("/get.php", ignoreCase = true)) return null

            val query = parseQuery(uri.rawQuery.orEmpty())
            val username = query["username"].orEmpty()
            val password = query["password"].orEmpty()
            if (username.isBlank() || password.isBlank()) return null

            val authority = uri.rawAuthority ?: return null
            val basePath = rawPath.substringBeforeLast("/get.php", "")
            val base = buildString {
                append(uri.scheme)
                append("://")
                append(authority)
                if (basePath.isNotBlank()) append(basePath)
            }

            val params = "username=${encode(username)}&password=${encode(password)}"
            "${base.trimEnd('/')}/xmltv.php?$params"
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQuery(rawQuery: String): Map<String, String> =
        rawQuery.split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = decode(part.substring(0, separator)).lowercase()
                val value = decode(part.substring(separator + 1))
                key to value
            }
            .toMap()

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decode(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
