package no.cloud247.tv

import android.app.Activity
import android.content.Context

object NightModePreferences {
    private const val PREFS = "cloud247_night_mode"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FILTER_INDEX = "filter_index"
    private const val KEY_VOLUME_INDEX = "volume_index"
    private const val KEY_BRIGHTNESS_INDEX = "brightness_index"
    private const val KEY_SLEEP_END_AT = "sleep_end_at"

    private val filterLevels = floatArrayOf(0.10f, 0.18f, 0.28f)
    private val volumeLevels = floatArrayOf(0.55f, 0.45f, 0.35f)
    private val brightnessLevels = floatArrayOf(0.40f, 0.30f, 0.20f)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun filterIndex(context: Context): Int =
        prefs(context).getInt(KEY_FILTER_INDEX, 1).coerceIn(filterLevels.indices)

    fun volumeIndex(context: Context): Int =
        prefs(context).getInt(KEY_VOLUME_INDEX, 1).coerceIn(volumeLevels.indices)

    fun brightnessIndex(context: Context): Int =
        prefs(context).getInt(KEY_BRIGHTNESS_INDEX, 1).coerceIn(brightnessLevels.indices)

    fun filterAlpha(context: Context): Float = filterLevels[filterIndex(context)]
    fun playerVolume(context: Context): Float = volumeLevels[volumeIndex(context)]
    fun screenBrightness(context: Context): Float = brightnessLevels[brightnessIndex(context)]

    fun cycleFilter(context: Context): Int {
        val next = (filterIndex(context) + 1) % filterLevels.size
        prefs(context).edit().putInt(KEY_FILTER_INDEX, next).apply()
        return next
    }

    fun cycleVolume(context: Context): Int {
        val next = (volumeIndex(context) + 1) % volumeLevels.size
        prefs(context).edit().putInt(KEY_VOLUME_INDEX, next).apply()
        return next
    }

    fun cycleBrightness(context: Context): Int {
        val next = (brightnessIndex(context) + 1) % brightnessLevels.size
        prefs(context).edit().putInt(KEY_BRIGHTNESS_INDEX, next).apply()
        return next
    }

    fun filterLabel(context: Context): String =
        listOf("Mild", "Normal", "Mørk")[filterIndex(context)]

    fun volumeLabel(context: Context): String =
        listOf("55 %", "45 %", "35 %")[volumeIndex(context)]

    fun brightnessLabel(context: Context): String =
        listOf("40 %", "30 %", "20 %")[brightnessIndex(context)]

    fun sleepEndAt(context: Context): Long =
        prefs(context).getLong(KEY_SLEEP_END_AT, 0L)

    fun setSleepMinutes(context: Context, minutes: Int) {
        val endAt = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        prefs(context).edit().putLong(KEY_SLEEP_END_AT, endAt).apply()
    }

    fun clearSleepTimer(context: Context) {
        prefs(context).edit().putLong(KEY_SLEEP_END_AT, 0L).apply()
    }

    fun applyWindowBrightness(activity: Activity) {
        val attributes = activity.window.attributes
        attributes.screenBrightness =
            if (isEnabled(activity)) screenBrightness(activity) else -1f
        activity.window.attributes = attributes
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
