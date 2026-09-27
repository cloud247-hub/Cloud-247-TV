package no.cloud247.tv

import java.util.Locale
import kotlin.math.abs

data class SportsChannelMatch(
    val channel: Channel,
    val programme: String,
    val score: Int
)

object SportsChannelMatcher {
    private const val HOUR = 60L * 60L * 1000L
    private const val DAY = 24L * HOUR

    fun find(
        event: SportsHubEvent,
        channels: List<Channel>,
        epgData: EpgData
    ): SportsChannelMatch? {
        if (channels.isEmpty() || epgData.programsById.isEmpty()) return null

        val now = System.currentTimeMillis()
        var best: SportsChannelMatch? = null

        for (channel in channels) {
            val programs = EpgLookup.programsForChannel(channel, epgData)

            for (index in programs.indices) {
                val program = programs[index]
                if (!isTimeCandidate(event, programs, index)) continue

                val score = score(event, channel, program, programs, index, now)
                val threshold = if (event.sport == "golf") 60 else 55

                if (score >= threshold && (best == null || score > best.score)) {
                    best = SportsChannelMatch(
                        channel = channel,
                        programme = program.title,
                        score = score
                    )
                }
            }
        }

        return best
    }

    private fun isTimeCandidate(
        event: SportsHubEvent,
        programs: List<Program>,
        index: Int
    ): Boolean {
        val program = programs[index]

        if (event.sport == "golf") {
            // ESPN gives the tournament start. XMLTV often contains separate
            // broadcasts such as Day 1, Day 2, Day 3 and Day 4.
            val tournamentStart = event.start.time - 12L * HOUR
            val tournamentEnd = event.start.time + 5L * DAY + 12L * HOUR
            val programmeStart = program.start.time
            val programmeStop = EpgLookup.effectiveStop(programs, index).time

            return programmeStop >= tournamentStart &&
                programmeStart <= tournamentEnd
        }

        // Keep football and tennis strict to avoid false positives.
        return abs(program.start.time - event.start.time) <= 4L * HOUR
    }

    private fun score(
        event: SportsHubEvent,
        channel: Channel,
        program: Program,
        programs: List<Program>,
        index: Int,
        now: Long
    ): Int {
        val programme = normalize(program.title)
        val title = normalize(event.title)
        val competition = normalize(event.competition)
        val matchName = normalize(event.matchName)
        val channelText = normalize(
            listOf(channel.name, channel.tvgName, channel.group)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )

        var score = 0

        // Exact tournament/title matching is the strongest signal.
        if (title.length >= 5 && programme.contains(title)) {
            score += 120
        } else if (
            programme.length >= 8 &&
            title.length >= programme.length &&
            title.contains(programme)
        ) {
            score += 80
        }

        if (competition.length >= 4 && programme.contains(competition)) {
            score += 35
        }

        if (matchName.length >= 3 && programme.contains(matchName)) {
            score += 45
        }

        val participantHits = event.participants
            .map(::normalize)
            .filter { it.length >= 3 }
            .count { programme.contains(it) }

        score += participantHits * 45

        val titleTokens = meaningfulTokens(title)
        val programmeTokens = meaningfulTokens(programme)
        if (titleTokens.isNotEmpty()) {
            val overlap = titleTokens.intersect(programmeTokens).size
            score += overlap * 18

            val coverage = overlap.toDouble() / titleTokens.size.toDouble()
            if (overlap >= 2 && coverage >= 0.50) {
                score += 35
            }
        }

        if (event.sport == "golf") {
            // Golf-channel metadata helps when XMLTV only contains a generic
            // tour title, for example "DP World Tour".
            if (channelText.contains("golf")) {
                score += 15
            }

            // Prefer Norwegian golf channels when the EPG evidence is otherwise
            // about equally strong.
            if (isNorwegianChannel(channelText)) {
                score += 18
            }

            // Prefer a programme that is on air now.
            val stop = EpgLookup.effectiveStop(programs, index).time
            if (program.start.time <= now && now < stop) {
                score += 18
            }

            // Generic tour labels such as "DP World Tour" are valid fallback
            // signals for multi-day golf tournaments.
            val tourTokens = meaningfulTokens(
                listOf(event.competition, event.matchName).joinToString(" ")
            )
            val tourOverlap = tourTokens.intersect(programmeTokens).size
            score += tourOverlap * 10
        }

        return score
    }

    private fun meaningfulTokens(value: String): Set<String> {
        val ignored = setOf(
            "live",
            "golf",
            "day",
            "round",
            "presented",
            "pres",
            "tournament",
            "championship"
        )

        return normalize(value)
            .split(' ')
            .filter { it.length >= 4 && it !in ignored }
            .toSet()
    }

    private fun isNorwegianChannel(value: String): Boolean {
        val tokens = value.split(' ').toSet()
        return "no" in tokens ||
            "norge" in tokens ||
            "norway" in tokens ||
            "norwegian" in tokens
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("[^a-z0-9æøåáéíóúüöäçñ -]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
