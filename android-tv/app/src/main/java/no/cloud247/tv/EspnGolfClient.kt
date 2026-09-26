package no.cloud247.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object EspnGolfClient {
    private val BASES = listOf(
        "https://site.web.api.espn.com/apis/site/v2/sports/golf",
        "https://site.api.espn.com/apis/site/v2/sports/golf"
    )
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val majorAliases = listOf(
        "masters tournament" to "Masters",
        "the masters" to "Masters",
        "pga championship" to "PGA Championship",
        "u.s. open" to "U.S. Open",
        "us open" to "U.S. Open",
        "the open championship" to "The Open",
        "british open" to "The Open"
    )

    fun fetch(config: SportsHubConfig): SportsHubResponse {
        val tours = if (config.golfTours.isNotEmpty()) {
            config.golfTours
        } else {
            setOf("pga", "eur", "lpga", "liv")
        }

        val formatter = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val from = formatter.format(Date())
        val to = formatter.format(
            Date(System.currentTimeMillis() + 14L * 24L * 60L * 60L * 1000L)
        )

        val events = tours
            .flatMap { tour -> fetchTour(tour, from, to, config) }
            .distinctBy { it.id }
            .sortedBy { it.start }

        return SportsHubResponse(
            events = events,
            sources = mapOf(
                "football" to SportsSourceStatus(false, "Sports-backend ikke tilgjengelig"),
                "tennis" to SportsSourceStatus(false, "Sports-backend ikke tilgjengelig"),
                "golf" to SportsSourceStatus(true, "ESPN Golf · direkte")
            )
        )
    }

    private fun fetchTour(
        tour: String,
        from: String,
        to: String,
        config: SportsHubConfig
    ): List<SportsHubEvent> {
        val root = fetchGolfJson(tour, from, to)
        val events = root.optJSONArray("events") ?: JSONArray()
        val out = mutableListOf<SportsHubEvent>()

        for (index in 0 until events.length()) {
            val raw = events.optJSONObject(index) ?: continue
            val title = raw.optString("name")
                .ifBlank { raw.optString("shortName") }
                .trim()

            val start = parseDate(
                raw.optString("date").ifBlank {
                    raw.optJSONArray("competitions")
                        ?.optJSONObject(0)
                        ?.optString("date")
                        .orEmpty()
                }
            ) ?: continue

            if (start.time < System.currentTimeMillis() - 12L * 60L * 60L * 1000L) continue
            if (title.isBlank()) continue

            val participants = participants(raw)
            val combined = normalize(
                buildString {
                    append(title)
                    append(' ')
                    append(participants.joinToString(" "))
                }
            )

            var matchType = ""
            var matchName = ""

            for (player in config.golfPlayers) {
                if (combined.contains(normalize(player))) {
                    matchType = "golf_player"
                    matchName = player
                    break
                }
            }

            if (matchType.isBlank() && config.golfMajors) {
                val major = majorAliases.firstOrNull { combined.contains(it.first) }
                if (major != null) {
                    matchType = "golf_major"
                    matchName = major.second
                }
            }

            if (matchType.isBlank() && tour in config.golfTours) {
                matchType = "golf_tour"
                matchName = tourLabel(tour)
            }

            if (matchType.isBlank()) continue

            out += SportsHubEvent(
                id = "golf:$tour:${raw.optString("id").ifBlank { title + ":" + start.time }}",
                sport = "golf",
                title = title,
                competition = tourLabel(tour),
                start = start,
                participants = participants,
                matchType = matchType,
                matchName = matchName,
                source = "ESPN Golf"
            )
        }

        return out
    }

    private fun participants(event: JSONObject): List<String> {
        val competitions = event.optJSONArray("competitions") ?: return emptyList()
        val competition = competitions.optJSONObject(0) ?: return emptyList()
        val competitors = competition.optJSONArray("competitors") ?: return emptyList()

        return buildList {
            for (index in 0 until competitors.length()) {
                val competitor = competitors.optJSONObject(index) ?: continue
                val athlete = competitor.optJSONObject("athlete")
                val name = athlete?.optString("displayName")
                    ?.ifBlank { athlete.optString("fullName") }
                    ?.trim()
                    .orEmpty()
                    .ifBlank { competitor.optString("displayName").trim() }
                if (name.isNotBlank()) add(name)
            }
        }
    }

    private fun fetchGolfJson(tour: String, from: String, to: String): JSONObject {
        var lastError: Exception? = null

        for (base in BASES) {
            val target = "$base/$tour/scoreboard?dates=$from-$to&limit=100"
            try {
                return fetchJson(target)
            } catch (error: Exception) {
                lastError = error
            }
        }

        throw lastError ?: NetworkException("ESPN Golf er utilgjengelig")
    }

    private fun fetchJson(target: String): JSONObject {
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            useCaches = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("User-Agent", "Cloud247-TV-Golf/1.5.2")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw NetworkException("ESPN Golf: HTTP $status")
            }

            val text = readLimited(connection.inputStream)
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
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
                if (total > MAX_BYTES) {
                    throw NetworkException("ESPN Golf-responsen er for stor")
                }
                out.write(buffer, 0, read)
            }

            return out.toString(Charsets.UTF_8.name())
        }
    }

    private fun parseDate(value: String): Date? {
        if (value.isBlank()) return null
        return try {
            Date.from(java.time.Instant.parse(value))
        } catch (_: Exception) {
            null
        }
    }

    private fun tourLabel(tour: String): String = when (tour) {
        "pga" -> "PGA Tour"
        "eur" -> "DP World Tour"
        "lpga" -> "LPGA"
        "liv" -> "LIV Golf"
        else -> tour.uppercase(Locale.ROOT)
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("[^a-z0-9æøåáéíóúüöäçñ .-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
