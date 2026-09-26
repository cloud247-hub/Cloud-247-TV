package no.cloud247.tv

import android.content.Context

data class SportsFavoriteConfig(
    val teams: Set<String>,
    val tennisPlayers: Set<String>,
    val leagues: Set<String>,
    val premierLeague: Boolean,
    val tennisMajors: Boolean,
    val leadMinutes: Int
) {
    val hasFavorites: Boolean
        get() = teams.isNotEmpty() ||
            tennisPlayers.isNotEmpty() ||
            leagues.isNotEmpty() ||
            premierLeague ||
            tennisMajors
}

object SportsPreferences {
    private const val PREFS = "cloud247_sports_alerts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TEAMS = "teams"
    private const val KEY_PLAYERS = "players"
    private const val KEY_LEAGUES = "leagues"
    private const val KEY_PREMIER_LEAGUE = "premier_league"
    private const val KEY_TENNIS_MAJORS = "tennis_majors"
    private const val KEY_LEAD_MINUTES = "lead_minutes"
    private const val KEY_EPG_URL = "epg_url"
    private const val KEY_NOTIFIED = "notified_events"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun loadConfig(context: Context): SportsFavoriteConfig {
        val p = prefs(context)
        return SportsFavoriteConfig(
            teams = p.getStringSet(KEY_TEAMS, emptySet()).orEmpty(),
            tennisPlayers = p.getStringSet(KEY_PLAYERS, emptySet()).orEmpty(),
            leagues = p.getStringSet(KEY_LEAGUES, emptySet()).orEmpty(),
            premierLeague = p.getBoolean(KEY_PREMIER_LEAGUE, true),
            tennisMajors = p.getBoolean(KEY_TENNIS_MAJORS, true),
            leadMinutes = p.getInt(KEY_LEAD_MINUTES, 30).coerceIn(15, 60)
        )
    }

    fun saveConfig(
        context: Context,
        teams: Set<String>,
        players: Set<String>,
        leagues: Set<String>,
        premierLeague: Boolean,
        tennisMajors: Boolean,
        leadMinutes: Int
    ) {
        prefs(context).edit()
            .putStringSet(KEY_TEAMS, teams)
            .putStringSet(KEY_PLAYERS, players)
            .putStringSet(KEY_LEAGUES, leagues)
            .putBoolean(KEY_PREMIER_LEAGUE, premierLeague)
            .putBoolean(KEY_TENNIS_MAJORS, tennisMajors)
            .putInt(KEY_LEAD_MINUTES, leadMinutes.coerceIn(15, 60))
            .apply()
    }

    fun epgUrl(context: Context): String =
        prefs(context).getString(KEY_EPG_URL, "").orEmpty()

    fun setEpgUrl(context: Context, url: String) {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            prefs(context).edit().putString(KEY_EPG_URL, url).apply()
        }
    }

    fun wasNotified(context: Context, key: String): Boolean =
        key in prefs(context).getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()

    fun markNotified(context: Context, key: String) {
        val current = prefs(context).getStringSet(KEY_NOTIFIED, emptySet())
            .orEmpty()
            .toMutableList()
        current.remove(key)
        current.add(key)
        val trimmed = current.takeLast(200).toSet()
        prefs(context).edit().putStringSet(KEY_NOTIFIED, trimmed).apply()
    }

    fun parseEntries(value: String): Set<String> =
        value.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()

    fun entriesText(values: Set<String>): String =
        values.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(", ")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
