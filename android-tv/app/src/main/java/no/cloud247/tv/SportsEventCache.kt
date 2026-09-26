package no.cloud247.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

object SportsEventCache {
    private const val PREFS = "cloud247_sports_event_cache"
    private const val KEY_EVENTS = "events"
    private const val KEY_UPDATED_AT = "updated_at"

    fun save(context: Context, matches: List<SportsEpgMatch>) {
        val array = JSONArray()
        matches.take(250).forEach { match ->
            array.put(
                JSONObject()
                    .put("title", match.title)
                    .put("channelId", match.channelId)
                    .put("channelName", match.channelName)
                    .put("start", match.start.time)
                    .put("stop", match.stop?.time ?: 0L)
                    .put("reason", match.reason)
                    .put("tennis", match.tennis)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EVENTS, array.toString())
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): List<SportsEpgMatch> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, "[]")
            .orEmpty()

        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val start = item.optLong("start", 0L)
                    if (start <= 0L) continue
                    val stop = item.optLong("stop", 0L)
                    add(
                        SportsEpgMatch(
                            title = item.optString("title"),
                            channelId = item.optString("channelId"),
                            channelName = item.optString("channelName"),
                            start = Date(start),
                            stop = stop.takeIf { it > 0L }?.let(::Date),
                            reason = item.optString("reason"),
                            tennis = item.optBoolean("tennis", false)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun updatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UPDATED_AT, 0L)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
