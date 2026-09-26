package no.cloud247.tv

import java.util.Locale
import kotlin.math.abs

data class SportsChannelMatch(
    val channel: Channel,
    val programme: String,
    val score: Int
)

object SportsChannelMatcher {
    fun find(
        event: SportsHubEvent,
        channels: List<Channel>,
        epgData: EpgData
    ): SportsChannelMatch? {
        if (channels.isEmpty() || epgData.programsById.isEmpty()) return null

        var best: SportsChannelMatch? = null
        for (channel in channels) {
            for (program in EpgLookup.programsForChannel(channel, epgData)) {
                if (abs(program.start.time - event.start.time) > 4L * 60L * 60L * 1000L) continue
                val score = score(event, program.title)
                if (score >= 55 && (best == null || score > best.score)) {
                    best = SportsChannelMatch(channel, program.title, score)
                }
            }
        }
        return best
    }

    private fun score(event: SportsHubEvent, programme: String): Int {
        val p = normalize(programme)
        val title = normalize(event.title)
        val competition = normalize(event.competition)
        val matchName = normalize(event.matchName)

        var score = 0
        if (title.length >= 5 && p.contains(title)) score += 100
        if (competition.length >= 4 && p.contains(competition)) score += 25
        if (matchName.length >= 3 && p.contains(matchName)) score += 40

        val participantHits = event.participants
            .map(::normalize)
            .filter { it.length >= 3 }
            .count { p.contains(it) }

        score += participantHits * 38

        val titleTokens = title.split(' ').filter { it.length >= 4 }.toSet()
        val programTokens = p.split(' ').filter { it.length >= 4 }.toSet()
        if (titleTokens.isNotEmpty()) {
            score += titleTokens.intersect(programTokens).size * 12
        }
        return score
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("[^a-z0-9æøåáéíóúüöäçñ -]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
