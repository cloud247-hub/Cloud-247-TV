package no.cloud247.tv

object M3uParser {
    private val attrRegex = Regex("""([\\w-]+)=\"([^\"]*)\"""")

    fun parse(text: String, name: String = "Spilleliste"): Playlist {
        val channels = mutableListOf<Channel>()
        var epgUrl = ""
        var pending: Pending? = null
        var firstLine = true

        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            if (firstLine) {
                firstLine = false
                val header = raw.trim()
                if (header.startsWith("#EXTM3U", ignoreCase = true)) {
                    val attrs = parseAttrs(header)
                    epgUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: ""
                }
            }
            val line = raw.trim()
            if (line.isBlank()) continue

            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val comma = line.indexOf(',')
                    val metadata = if (comma >= 0) line.substring(0, comma) else line
                    val displayName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    val attrs = parseAttrs(metadata)
                    pending = Pending(
                        name = displayName.ifBlank { attrs["tvg-name"].orEmpty().ifBlank { "Uten navn" } },
                        tvgId = attrs["tvg-id"].orEmpty(),
                        tvgName = attrs["tvg-name"].orEmpty(),
                        logo = attrs["tvg-logo"].orEmpty(),
                        group = attrs["group-title"].orEmpty().ifBlank { "Andre" }
                    )
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) && pending != null -> {
                    pending = pending.copy(group = line.substringAfter(':').trim().ifBlank { "Andre" })
                }

                !line.startsWith("#") && pending != null -> {
                    val item = pending
                    channels += Channel(
                        name = item.name,
                        tvgId = item.tvgId,
                        tvgName = item.tvgName,
                        logo = item.logo,
                        group = item.group,
                        url = line
                    )
                    pending = null
                }
            }
        }

        return Playlist(channels = channels, epgUrl = epgUrl, name = name)
    }

    private fun parseAttrs(input: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        attrRegex.findAll(input).forEach { match ->
            result[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        return result
    }

    private data class Pending(
        val name: String,
        val tvgId: String,
        val tvgName: String,
        val logo: String,
        val group: String
    )
}
