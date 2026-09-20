package no.cloud247.tv

import java.util.Date

data class Channel(
    val name: String,
    val tvgId: String = "",
    val tvgName: String = "",
    val logo: String = "",
    val group: String = "Andre",
    val url: String
) {
    fun favoriteKey(): String = if (tvgId.isNotBlank()) {
        "id:$tvgId"
    } else {
        "name:${tvgName.ifBlank { name }}|group:$group"
    }
}

data class Playlist(
    val channels: List<Channel>,
    val epgUrl: String = "",
    val name: String = "Spilleliste"
)

data class Program(
    val start: Date,
    val stop: Date?,
    val title: String
)

data class ProgrammeWindow(
    val now: Program?,
    val next: Program?
)

data class EpgData(
    val programsById: Map<String, List<Program>>,
    val aliases: Map<String, String>
) {
    companion object {
        val EMPTY = EpgData(emptyMap(), emptyMap())
    }
}
