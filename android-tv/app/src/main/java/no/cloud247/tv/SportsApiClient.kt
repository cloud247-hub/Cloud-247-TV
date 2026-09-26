package no.cloud247.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

object SportsApiClient {
    private const val ENDPOINT = "https://tv-api.cloud247.no/v1/sports/upcoming"
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun fetchUpcoming(config: SportsHubConfig): SportsHubResponse {
        return try {
            fetchFromWorker(config)
        } catch (workerError: Exception) {
            if (hasGolfPreferences(config)) {
                EspnGolfClient.fetch(config)
            } else {
                throw workerError
            }
        }
    }

    private fun fetchFromWorker(config: SportsHubConfig): SportsHubResponse {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Cloud247-TV/1.5.1 (Android)")
        }

        try {
            val body = JSONObject()
                .put("football_teams", JSONArray(config.footballTeams.toList()))
                .put("football_leagues", JSONArray(config.footballLeagues.toList()))
                .put("tennis_players", JSONArray(config.tennisPlayers.toList()))
                .put("golf_players", JSONArray(config.golfPlayers.toList()))
                .put("premier_league", config.premierLeague)
                .put("tennis_majors", config.tennisMajors)
                .put("golf_majors", config.golfMajors)
                .put("golf_tours", JSONArray(config.golfTours.toList()))
                .toString()

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = readLimited(stream ?: throw NetworkException("Tomt svar fra sports-API"))

            if (status !in 200..299) {
                val error = try {
                    JSONObject(text).optString("error").ifBlank { "HTTP $status" }
                } catch (_: Exception) {
                    "HTTP $status"
                }
                throw NetworkException("Sports-API: $error")
            }
            return parse(JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }

    private fun hasGolfPreferences(config: SportsHubConfig): Boolean =
        config.golfPlayers.isNotEmpty() ||
            config.golfMajors ||
            config.golfTours.isNotEmpty()

    private fun parse(root: JSONObject): SportsHubResponse {
        val eventsJson = root.optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (index in 0 until eventsJson.length()) {
                val item = eventsJson.optJSONObject(index) ?: continue
                val start = item.optLong("start_ms", 0L)
                if (start <= 0L) continue

                val participantsJson = item.optJSONArray("participants") ?: JSONArray()
                val participants = buildList {
                    for (p in 0 until participantsJson.length()) {
                        val value = participantsJson.optString(p).trim()
                        if (value.isNotBlank()) add(value)
                    }
                }

                add(
                    SportsHubEvent(
                        id = item.optString("id"),
                        sport = item.optString("sport"),
                        title = item.optString("title"),
                        competition = item.optString("competition"),
                        start = Date(start),
                        participants = participants,
                        matchType = item.optString("match_type"),
                        matchName = item.optString("match_name"),
                        source = item.optString("source")
                    )
                )
            }
        }

        val sourceMap = linkedMapOf<String, SportsSourceStatus>()
        val sources = root.optJSONObject("sources") ?: JSONObject()
        for (key in listOf("football", "tennis", "golf")) {
            val source = sources.optJSONObject(key) ?: continue
            sourceMap[key] = SportsSourceStatus(
                ok = source.optBoolean("ok", false),
                detail = source.optString("detail")
            )
        }

        return SportsHubResponse(events.sortedBy { it.start }, sourceMap)
    }

    private fun readLimited(stream: java.io.InputStream): String {
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) throw NetworkException("Sportsresponsen er for stor")
                out.write(buffer, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
