package no.cloud247.tv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SportsEpgMatch(
    val title: String,
    val channelId: String,
    val channelName: String,
    val start: Date,
    val stop: Date?,
    val reason: String,
    val tennis: Boolean
) {
    fun notificationKey(): String =
        "${start.time}|$channelId|${title.lowercase(Locale.ROOT)}"
}

object SportsEpgScanner {
    private val majorAliases = listOf(
        "australian open",
        "roland garros",
        "french open",
        "wimbledon",
        "us open"
    )
    private val premierAliases = listOf(
        "premier league",
        "english premier league",
        "epl"
    )

    fun scan(
        input: InputStream,
        config: SportsFavoriteConfig,
        now: Long = System.currentTimeMillis(),
        windowMinutes: Int = config.leadMinutes
    ): List<SportsEpgMatch> {
        if (!config.hasFavorites) return emptyList()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")

        val channelNames = linkedMapOf<String, String>()
        val matches = mutableListOf<SportsEpgMatch>()
        val horizon = now + windowMinutes.coerceAtLeast(config.leadMinutes) * 60_000L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser)?.let { (id, name) ->
                        channelNames[id] = name
                    }
                    "programme" -> {
                        val channelId = parser.getAttributeValue(null, "channel").orEmpty()
                        val start = parseDate(parser.getAttributeValue(null, "start"))
                        val stop = parseDate(parser.getAttributeValue(null, "stop"))
                        val content = readProgramme(parser)

                        if (start != null && start.time in now..horizon) {
                            val match = matchContent(
                                title = content.title,
                                description = content.description,
                                category = content.category,
                                config = config
                            )
                            if (match != null) {
                                matches += SportsEpgMatch(
                                    title = content.title.ifBlank { match.first },
                                    channelId = channelId,
                                    channelName = channelNames[channelId].orEmpty()
                                        .ifBlank { channelId.ifBlank { "Ukjent kanal" } },
                                    start = start,
                                    stop = stop,
                                    reason = match.first,
                                    tennis = match.second
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        return matches
            .distinctBy(SportsEpgMatch::notificationKey)
            .sortedBy(SportsEpgMatch::start)
            .take(250)
    }

    private fun readChannel(parser: XmlPullParser): Pair<String, String>? {
        val id = parser.getAttributeValue(null, "id").orEmpty()
        var name = ""
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "display-name" && name.isBlank()) {
                        name = parser.nextText().trim()
                    } else {
                        depth += 1
                    }
                }
                XmlPullParser.END_TAG -> depth -= 1
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return id.takeIf { it.isNotBlank() }?.let { it to name }
    }

    private data class ProgrammeContent(
        val title: String,
        val description: String,
        val category: String
    )

    private fun readProgramme(parser: XmlPullParser): ProgrammeContent {
        var title = ""
        val descriptions = mutableListOf<String>()
        val categories = mutableListOf<String>()
        var depth = 1

        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "title" -> if (title.isBlank()) title = parser.nextText().trim()
                        "desc", "sub-title" -> {
                            val text = parser.nextText().trim()
                            if (text.isNotBlank()) descriptions += text
                        }
                        "category" -> {
                            val text = parser.nextText().trim()
                            if (text.isNotBlank()) categories += text
                        }
                        else -> depth += 1
                    }
                }
                XmlPullParser.END_TAG -> depth -= 1
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        return ProgrammeContent(
            title = title,
            description = descriptions.joinToString(" "),
            category = categories.joinToString(" ")
        )
    }

    private fun matchContent(
        title: String,
        description: String,
        category: String,
        config: SportsFavoriteConfig
    ): Pair<String, Boolean>? {
        val combined = normalize("$title $description $category")

        for (player in config.tennisPlayers) {
            if (containsTerm(combined, player)) return player to true
        }

        if (config.tennisMajors) {
            val major = majorAliases.firstOrNull { combined.contains(it) }
            if (major != null) return majorDisplayName(major) to true
        }

        for (team in config.teams) {
            if (containsTerm(combined, team)) return team to false
        }

        for (league in config.leagues) {
            if (containsTerm(combined, league)) return league to false
        }

        if (config.premierLeague && premierAliases.any(combined::contains)) {
            return "Premier League" to false
        }

        return null
    }

    private fun containsTerm(haystack: String, term: String): Boolean {
        val needle = normalize(term)
        return needle.length >= 2 && haystack.contains(needle)
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("[^a-z0-9æøåáéíóúüöäçñ -]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun majorDisplayName(alias: String): String = when (alias) {
        "french open", "roland garros" -> "Roland-Garros"
        "us open" -> "US Open"
        "australian open" -> "Australian Open"
        "wimbledon" -> "Wimbledon"
        else -> alias
    }

    private fun parseDate(value: String?): Date? {
        val input = value?.trim().orEmpty()
        if (input.isBlank()) return null

        val formats = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmm Z",
            "yyyyMMddHHmmss",
            "yyyyMMddHHmm"
        )

        for (pattern in formats) {
            try {
                return SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    if (!pattern.contains('Z')) timeZone = TimeZone.getDefault()
                }.parse(input)
            } catch (_: Exception) {
            }
        }
        return null
    }
}
