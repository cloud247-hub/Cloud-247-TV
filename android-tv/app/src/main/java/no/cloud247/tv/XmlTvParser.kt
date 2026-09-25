package no.cloud247.tv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object XmlTvParser {
    private const val PAST_WINDOW_MS = 6L * 60 * 60 * 1000
    private const val FUTURE_WINDOW_MS = 72L * 60 * 60 * 1000

    fun parse(bytes: ByteArray, channels: List<Channel>): EpgData {
        return parse(ByteArrayInputStream(bytes), channels)
    }

    fun parse(input: InputStream, channels: List<Channel>): EpgData {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")

        val wantedIds = channels.mapNotNull { it.tvgId.takeIf(String::isNotBlank) }.toMutableSet()
        val wantedNames = channels
            .flatMap { listOf(it.tvgName, it.name) }
            .filter { it.isNotBlank() }
            .map { it.trim().lowercase(Locale.ROOT) }
            .toSet()

        val aliases = linkedMapOf<String, String>()
        val programs = linkedMapOf<String, MutableList<Program>>()
        var currentChannelId: String? = null
        var event = parser.eventType
        val now = System.currentTimeMillis()

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> currentChannelId = parser.getAttributeValue(null, "id")
                    "display-name" -> {
                        val channelId = currentChannelId
                        if (!channelId.isNullOrBlank()) {
                            val displayName = parser.nextText().trim()
                            if (displayName.isNotBlank()) {
                                aliases[displayName] = channelId
                                aliases[displayName.lowercase(Locale.ROOT)] = channelId
                                if (displayName.lowercase(Locale.ROOT) in wantedNames) wantedIds += channelId
                            }
                        }
                    }
                    "programme" -> {
                        val channelId = parser.getAttributeValue(null, "channel").orEmpty()
                        val start = parseDate(parser.getAttributeValue(null, "start"))
                        val stop = parseDate(parser.getAttributeValue(null, "stop"))
                        val shouldKeep = channelId.isNotBlank() &&
                            (wantedIds.isEmpty() || channelId in wantedIds) &&
                            start != null &&
                            start.time <= now + FUTURE_WINDOW_MS &&
                            (stop?.time ?: start.time) >= now - PAST_WINDOW_MS

                        val title = readProgrammeTitle(parser)
                        if (shouldKeep && start != null) {
                            programs.getOrPut(channelId) { mutableListOf() }
                                .add(Program(start, stop, title.ifBlank { "Uten tittel" }))
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "channel") {
                currentChannelId = null
            }
            event = parser.next()
        }

        programs.values.forEach { it.sortBy(Program::start) }
        return EpgData(programs.mapValues { it.value.toList() }, aliases)
    }

    private fun readProgrammeTitle(parser: XmlPullParser): String {
        var depth = 1
        var title = ""
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth += 1
                    if (parser.name == "title" && title.isBlank()) {
                        title = parser.nextText().trim()
                        depth -= 1
                    }
                }
                XmlPullParser.END_TAG -> depth -= 1
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return title
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
