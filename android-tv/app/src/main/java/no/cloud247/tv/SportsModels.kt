package no.cloud247.tv

import java.util.Date

data class SportsSourceStatus(
    val ok: Boolean,
    val detail: String
)

data class SportsHubEvent(
    val id: String,
    val sport: String,
    val title: String,
    val competition: String,
    val start: Date,
    val participants: List<String>,
    val matchType: String,
    val matchName: String,
    val source: String
) {
    fun notificationKey(): String = "$sport|$id|${start.time}"
}

data class SportsHubResponse(
    val events: List<SportsHubEvent>,
    val sources: Map<String, SportsSourceStatus>
)

data class SportsHubConfig(
    val footballTeams: Set<String>,
    val footballLeagues: Set<String>,
    val tennisPlayers: Set<String>,
    val golfPlayers: Set<String>,
    val premierLeague: Boolean,
    val tennisMajors: Boolean,
    val golfMajors: Boolean,
    val golfTours: Set<String>,
    val alertSelections: Set<String>,
    val leadMinutes: Int
) {
    val hasFavorites: Boolean
        get() = footballTeams.isNotEmpty() ||
            footballLeagues.isNotEmpty() ||
            tennisPlayers.isNotEmpty() ||
            golfPlayers.isNotEmpty() ||
            premierLeague ||
            tennisMajors ||
            golfMajors ||
            golfTours.isNotEmpty()
}
