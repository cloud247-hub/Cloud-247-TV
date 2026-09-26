package no.cloud247.tv

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class XtreamLoginResult(
    val playlistUrl: String,
    val epgUrl: String,
    val username: String,
    val status: String
)

object XtreamClient {
    private const val MAX_API_BYTES = 2 * 1024 * 1024

    fun login(server: String, username: String, password: String): XtreamLoginResult {
        val base = normalizeServer(server)
        val user = username.trim()
        val pass = password.trim()

        if (user.isBlank()) throw NetworkException("Brukernavn mangler")
        if (pass.isBlank()) throw NetworkException("Passord mangler")

        val playerApi = buildUrl(base, "player_api.php", user, pass)
        val root = try {
            JSONObject(NetworkClient.fetchText(playerApi, MAX_API_BYTES))
        } catch (error: Exception) {
            throw NetworkException(
                "Kunne ikke logge inn på XC-serveren: ${error.message ?: "ukjent feil"}"
            )
        }

        val userInfo = root.optJSONObject("user_info")
            ?: throw NetworkException("Serveren svarte ikke som en Xtream Codes-server")

        val authPresent = userInfo.has("auth")
        val authOk = when (val auth = userInfo.opt("auth")) {
            is Number -> auth.toInt() == 1
            is String -> auth == "1" || auth.equals("true", ignoreCase = true)
            is Boolean -> auth
            else -> false
        }

        val status = userInfo.optString("status").trim()
        val negativeStatus = status.equals("Expired", true) ||
            status.equals("Banned", true) ||
            status.equals("Disabled", true)

        if ((authPresent && !authOk) || negativeStatus) {
            throw NetworkException(
                if (status.isNotBlank()) "XC-kontoen er ikke aktiv: $status"
                else "Feil brukernavn eller passord"
            )
        }

        return XtreamLoginResult(
            playlistUrl = buildUrl(
                base = base,
                endpoint = "get.php",
                username = user,
                password = pass,
                extra = listOf("type" to "m3u_plus", "output" to "ts")
            ),
            epgUrl = buildUrl(base, "xmltv.php", user, pass),
            username = userInfo.optString("username").ifBlank { user },
            status = status.ifBlank { "Active" }
        )
    }

    fun epgUrlFromPlaylistUrl(url: String): String? {
        return try {
            val uri = URI(url)
            val path = uri.path.orEmpty()
            if (!path.endsWith("/get.php", true) && !path.equals("/get.php", true)) return null

            val query = parseQuery(uri.rawQuery.orEmpty())
            val username = query["username"].orEmpty()
            val password = query["password"].orEmpty()
            if (username.isBlank() || password.isBlank()) return null

            val basePath = path.substringBeforeLast("/get.php", "")
            val authority = uri.rawAuthority ?: return null
            val base = buildString {
                append(uri.scheme)
                append("://")
                append(authority)
                if (basePath.isNotBlank()) append(basePath)
            }

            buildUrl(base, "xmltv.php", username, password)
        } catch (_: Exception) {
            null
        }
    }

    fun isXtreamPlaylistUrl(url: String): Boolean =
        try {
            URI(url).path.orEmpty().endsWith("/get.php", ignoreCase = true) &&
                epgUrlFromPlaylistUrl(url) != null
        } catch (_: Exception) {
            false
        }

    private fun normalizeServer(input: String): String {
        var value = input.trim()
        if (value.isBlank()) throw NetworkException("Serveradresse mangler")
        if (!value.contains("://")) value = "http://$value"

        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw NetworkException("Ugyldig serveradresse")
        }

        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            throw NetworkException("Server må være en http:// eller https:// adresse")
        }

        var path = uri.path.orEmpty().trimEnd('/')
        val lower = path.lowercase()
        if (
            lower.endsWith("/player_api.php") ||
            lower.endsWith("/get.php") ||
            lower.endsWith("/xmltv.php")
        ) {
            path = path.substringBeforeLast('/')
        }

        return buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(uri.host)
            if (uri.port != -1) append(":").append(uri.port)
            if (path.isNotBlank() && path != "/") {
                if (!path.startsWith("/")) append("/")
                append(path.trimEnd('/'))
            }
        }
    }

    private fun buildUrl(
        base: String,
        endpoint: String,
        username: String,
        password: String,
        extra: List<Pair<String, String>> = emptyList()
    ): String {
        val params = buildList {
            add("username" to username)
            add("password" to password)
            addAll(extra)
        }.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

        return "${base.trimEnd('/')}/$endpoint?$params"
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = decode(part.substring(0, separator))
                val value = decode(part.substring(separator + 1))
                key.lowercase() to value
            }
            .toMap()

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decode(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
