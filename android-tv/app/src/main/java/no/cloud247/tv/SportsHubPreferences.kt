package no.cloud247.tv

import android.content.Context

object SportsHubPreferences {
    private const val PREFS = "cloud247_sports_hub"
    private const val KEY_MIGRATED = "legacy_migrated"
    private const val KEY_ENABLED = "enabled"
    private const val FOOTBALL_TEAMS = "football_teams"
    private const val FOOTBALL_LEAGUES = "football_leagues"
    private const val TENNIS_PLAYERS = "tennis_players"
    private const val GOLF_PLAYERS = "golf_players"
    private const val PREMIER_LEAGUE = "premier_league"
    private const val TENNIS_MAJORS = "tennis_majors"
    private const val GOLF_MAJORS = "golf_majors"
    private const val GOLF_TOURS = "golf_tours"
    private const val ALERT_SELECTIONS = "alert_selections"
    private const val LEAD_MINUTES = "lead_minutes"
    private const val NOTIFIED = "notified"

    fun migrateLegacyIfNeeded(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        val old = SportsPreferences.loadConfig(context)
        val alerts = linkedSetOf<String>()
        old.teams.forEach { alerts += alertKey("football_team", it) }
        old.tennisPlayers.forEach { alerts += alertKey("tennis_player", it) }
        old.leagues.forEach { alerts += alertKey("football_league", it) }
        if (old.premierLeague) alerts += alertKey("premier_league", "Premier League")
        if (old.tennisMajors) alerts += alertKey("tennis_major", "Tennis Majors")

        prefs.edit()
            .putBoolean(KEY_MIGRATED, true)
            .putBoolean(KEY_ENABLED, SportsPreferences.isEnabled(context))
            .putStringSet(FOOTBALL_TEAMS, old.teams)
            .putStringSet(FOOTBALL_LEAGUES, old.leagues)
            .putStringSet(TENNIS_PLAYERS, old.tennisPlayers)
            .putBoolean(PREMIER_LEAGUE, old.premierLeague)
            .putBoolean(TENNIS_MAJORS, old.tennisMajors)
            .putBoolean(GOLF_MAJORS, true)
            .putStringSet(GOLF_TOURS, setOf("pga"))
            .putStringSet(ALERT_SELECTIONS, alerts)
            .putInt(LEAD_MINUTES, old.leadMinutes)
            .apply()
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun load(context: Context): SportsHubConfig {
        migrateLegacyIfNeeded(context)
        val p = prefs(context)
        return SportsHubConfig(
            footballTeams = p.getStringSet(FOOTBALL_TEAMS, emptySet()).orEmpty(),
            footballLeagues = p.getStringSet(FOOTBALL_LEAGUES, emptySet()).orEmpty(),
            tennisPlayers = p.getStringSet(TENNIS_PLAYERS, emptySet()).orEmpty(),
            golfPlayers = p.getStringSet(GOLF_PLAYERS, emptySet()).orEmpty(),
            premierLeague = p.getBoolean(PREMIER_LEAGUE, true),
            tennisMajors = p.getBoolean(TENNIS_MAJORS, true),
            golfMajors = p.getBoolean(GOLF_MAJORS, true),
            golfTours = p.getStringSet(GOLF_TOURS, setOf("pga")).orEmpty(),
            alertSelections = p.getStringSet(ALERT_SELECTIONS, emptySet()).orEmpty(),
            leadMinutes = p.getInt(LEAD_MINUTES, 30).coerceIn(15, 120)
        )
    }

    fun save(context: Context, config: SportsHubConfig) {
        prefs(context).edit()
            .putStringSet(FOOTBALL_TEAMS, config.footballTeams)
            .putStringSet(FOOTBALL_LEAGUES, config.footballLeagues)
            .putStringSet(TENNIS_PLAYERS, config.tennisPlayers)
            .putStringSet(GOLF_PLAYERS, config.golfPlayers)
            .putBoolean(PREMIER_LEAGUE, config.premierLeague)
            .putBoolean(TENNIS_MAJORS, config.tennisMajors)
            .putBoolean(GOLF_MAJORS, config.golfMajors)
            .putStringSet(GOLF_TOURS, config.golfTours)
            .putStringSet(ALERT_SELECTIONS, config.alertSelections)
            .putInt(LEAD_MINUTES, config.leadMinutes.coerceIn(15, 120))
            .apply()
    }

    fun setAlertSelection(context: Context, key: String, enabled: Boolean) {
        val current = load(context).alertSelections.toMutableSet()
        if (enabled) current += key else current -= key
        prefs(context).edit().putStringSet(ALERT_SELECTIONS, current).apply()
    }

    fun parseEntries(value: String): Set<String> =
        value.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()

    fun entries(values: Set<String>): String =
        values.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(", ")

    fun alertKey(type: String, name: String): String =
        "${type.lowercase()}|${name.trim().lowercase()}"

    fun shouldAlert(config: SportsHubConfig, event: SportsHubEvent): Boolean {
        val exact = alertKey(event.matchType, event.matchName)
        if (exact in config.alertSelections) return true

        if (event.matchType == "tennis_major") {
            return alertKey("tennis_major", "Tennis Majors") in config.alertSelections
        }
        if (event.matchType == "golf_major") {
            return alertKey("golf_major", "Golf Majors") in config.alertSelections
        }
        return false
    }

    fun wasNotified(context: Context, key: String): Boolean =
        key in prefs(context).getStringSet(NOTIFIED, emptySet()).orEmpty()

    fun markNotified(context: Context, key: String) {
        val p = prefs(context)
        val current = p.getStringSet(NOTIFIED, emptySet()).orEmpty().toMutableList()
        current.remove(key)
        current.add(key)
        p.edit().putStringSet(NOTIFIED, current.takeLast(250).toSet()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
