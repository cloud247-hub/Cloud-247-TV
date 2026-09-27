package no.cloud247.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

object SportsHubCache {
    private const val PREFS = "cloud247_sports_hub_cache"
    private const val EVENTS = "events"
    private const val SOURCES = "sources"
    private const val UPDATED = "updated"
    private const val MATCHES = "channel_matches"
    private const val MATCH_UPDATED = "channel_matches_updated"
    private const val MATCH_TTL_MS = 24L * 60L * 60L * 1000L

    fun save(context: Context, response: SportsHubResponse) {
        val events = JSONArray()
        response.events.take(100).forEach { event ->
            events.put(
                JSONObject()
                    .put("id", event.id)
                    .put("sport", event.sport)
                    .put("title", event.title)
                    .put("competition", event.competition)
                    .put("start", event.start.time)
                    .put("participants", JSONArray(event.participants))
                    .put("matchType", event.matchType)
                    .put("matchName", event.matchName)
                    .put("source", event.source)
            )
        }

        val sources = JSONObject()
        response.sources.forEach { (key, value) ->
            sources.put(key, JSONObject().put("ok", value.ok).put("detail", value.detail))
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(EVENTS, events.toString())
            .putString(SOURCES, sources.toString())
            .putLong(UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): SportsHubResponse {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = mutableListOf<SportsHubEvent>()

        try {
            val array = JSONArray(p.getString(EVENTS, "[]").orEmpty())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optLong("start", 0L)
                if (start <= 0L) continue

                val participantsJson = item.optJSONArray("participants") ?: JSONArray()
                val participants = buildList {
                    for (index in 0 until participantsJson.length()) {
                        val value = participantsJson.optString(index).trim()
                        if (value.isNotBlank()) add(value)
                    }
                }

                events += SportsHubEvent(
                    id = item.optString("id"),
                    sport = item.optString("sport"),
                    title = item.optString("title"),
                    competition = item.optString("competition"),
                    start = Date(start),
                    participants = participants,
                    matchType = item.optString("matchType"),
                    matchName = item.optString("matchName"),
                    source = item.optString("source")
                )
            }
        } catch (_: Exception) {
        }

        val sources = linkedMapOf<String, SportsSourceStatus>()
        try {
            val obj = JSONObject(p.getString(SOURCES, "{}").orEmpty())
            for (key in listOf("football", "tennis", "golf")) {
                val item = obj.optJSONObject(key) ?: continue
                sources[key] = SportsSourceStatus(
                    ok = item.optBoolean("ok", false),
                    detail = item.optString("detail")
                )
            }
        } catch (_: Exception) {
        }

        return SportsHubResponse(
            events = events
                .filter { it.start.time >= System.currentTimeMillis() - 60 * 60 * 1000L }
                .sortedBy { it.start },
            sources = sources
        )
    }

    fun updatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(UPDATED, 0L)

    fun eventMatchKey(event: SportsHubEvent): String =
        "${event.sport}|${event.id}|${event.start.time}"

    fun matchesFresh(context: Context): Boolean {
        val updated = matchUpdatedAt(context)
        val now = System.currentTimeMillis()
        return updated > 0L && now >= updated && now - updated < MATCH_TTL_MS
    }

    fun matchUpdatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(MATCH_UPDATED, 0L)

    fun clearMatches(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(MATCHES)
            .remove(MATCH_UPDATED)
            .apply()
    }

    fun saveMatches(
        context: Context,
        matches: Map<String, SportsChannelMatch>
    ) {
        val root = JSONObject()
        matches.forEach { (key, match) ->
            root.put(
                key,
                JSONObject()
                    .put("url", match.channel.url)
                    .put("favoriteKey", match.channel.favoriteKey())
                    .put("programme", match.programme)
                    .put("score", match.score)
            )
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MATCHES, root.toString())
            .putLong(MATCH_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun loadMatches(
        context: Context,
        channels: List<Channel>
    ): Map<String, SportsChannelMatch> {
        if (channels.isEmpty()) return emptyMap()

        val byUrl = channels.associateBy { it.url }
        val byFavoriteKey = channels.associateBy { it.favoriteKey() }
        val result = linkedMapOf<String, SportsChannelMatch>()

        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val root = JSONObject(prefs.getString(MATCHES, "{}").orEmpty())
            val keys = root.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                val item = root.optJSONObject(key) ?: continue
                val channel = byUrl[item.optString("url")] ?:
                    byFavoriteKey[item.optString("favoriteKey")] ?:
                    continue

                result[key] = SportsChannelMatch(
                    channel = channel,
                    programme = item.optString("programme"),
                    score = item.optInt("score", 0)
                )
            }
        } catch (_: Exception) {
        }

        return result
    }
}
