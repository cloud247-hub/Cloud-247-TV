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
}
