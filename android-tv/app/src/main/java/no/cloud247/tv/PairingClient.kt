package no.cloud247.tv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PairingSession(
    val code: String,
    val token: String,
    val link: String,
    val expiresIn: Int
)

object PairingClient {
    private const val BASE = "https://tv-api.cloud247.no"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000

    fun createSession(): PairingSession {
        val response = post("/v1/pair/create", JSONObject())
        if (response.status !in 200..299) throw PairingException("Kunne ikke lage TV-kode")
        val body = JSONObject(response.body)
        return PairingSession(
            code = body.getString("code"),
            token = body.getString("token"),
            link = body.getString("link"),
            expiresIn = body.optInt("expires_in", 600)
        )
    }

    fun poll(session: PairingSession): String? {
        val payload = JSONObject()
            .put("code", session.code)
            .put("token", session.token)
        val response = post("/v1/pair/poll", payload)
        if (response.status == 204) return null
        if (response.status == 410) throw PairingException("TV-koden har utløpt")
        if (response.status !in 200..299) throw PairingException("Pairing feilet")
        return JSONObject(response.body).optString("url").takeIf { it.isNotBlank() }
    }

    private fun post(path: String, payload: JSONObject): HttpResult {
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Cloud247-TV/1.1.4 (Android TV)")
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResult(val status: Int, val body: String)
}

class PairingException(message: String) : Exception(message)
