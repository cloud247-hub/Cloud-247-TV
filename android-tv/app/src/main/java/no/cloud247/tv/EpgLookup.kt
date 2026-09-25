package no.cloud247.tv

import java.util.Date
import java.util.Locale

object EpgLookup {
    fun programsForChannel(channel: Channel, data: EpgData): List<Program> {
        val candidates = linkedSetOf<String>()
        if (channel.tvgId.isNotBlank()) candidates += channel.tvgId

        for (name in listOf(channel.tvgName, channel.name)) {
            if (name.isBlank()) continue
            data.aliases[name]?.let(candidates::add)
            data.aliases[name.lowercase(Locale.ROOT)]?.let(candidates::add)
        }

        return candidates.firstNotNullOfOrNull { data.programsById[it] }.orEmpty()
    }

    fun window(channel: Channel, data: EpgData, now: Date = Date()): ProgrammeWindow {
        val programs = programsForChannel(channel, data)
        if (programs.isEmpty()) return ProgrammeWindow(null, null)

        var current: Program? = null
        var next: Program? = null

        for (index in programs.indices) {
            val program = programs[index]
            val stop = effectiveStop(programs, index)
            if (!program.start.after(now) && now.before(stop)) {
                current = program
                next = programs.getOrNull(index + 1)
                break
            }
            if (program.start.after(now)) {
                next = program
                break
            }
        }

        return ProgrammeWindow(current, next)
    }

    fun effectiveStop(programs: List<Program>, index: Int): Date {
        val program = programs[index]
        return program.stop
            ?: programs.getOrNull(index + 1)?.start
            ?: Date(program.start.time + 30L * 60L * 1000L)
    }
}
