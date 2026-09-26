package no.cloud247.tv

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class SportsAlertWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    companion object {
        private const val CHANNEL_ID = "cloud247_sports_alerts"
        private const val CHANNEL_NAME = "Sportsvarsler"
    }

    override fun doWork(): Result {
        if (!DeviceProfile.isTablet(applicationContext)) return Result.success()
        if (!SportsPreferences.isEnabled(applicationContext)) return Result.success()

        val config = SportsPreferences.loadConfig(applicationContext)
        if (!config.hasFavorites) return Result.success()

        val now = System.currentTimeMillis()
        val horizon = now + config.leadMinutes * 60_000L

        SportsEventCache.load(applicationContext)
            .asSequence()
            .filter { it.start.time in now..horizon }
            .take(8)
            .forEach { match ->
                val key = match.notificationKey()
                if (!SportsPreferences.wasNotified(applicationContext, key)) {
                    notifyMatch(match)
                    SportsPreferences.markNotified(applicationContext, key)
                }
            }

        return Result.success()
    }

    private fun notifyMatch(match: SportsEpgMatch) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description =
                        "Varsler når favorittlag, spillere eller turneringer vises i TV-guiden."
                }
            )
        }

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SPORTS_CHANNEL_ID, match.channelId)
            putExtra(MainActivity.EXTRA_SPORTS_CHANNEL_NAME, match.channelName)
            putExtra(MainActivity.EXTRA_SPORTS_EVENT_TITLE, match.title)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            match.notificationKey().hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(match.start)
        val icon = if (match.tennis) "🎾" else "⚽"
        val title = "$icon ${match.title.ifBlank { match.reason }}"
        val channelText = match.channelName.ifBlank { "kanal funnet i EPG" }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("$time · $channelText")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Starter $time på $channelText. Trykk for å åpne kanalen i Cloud247 TV."
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(match.notificationKey().hashCode(), notification)
    }
}

class SportsEpgRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    companion object {
        private const val MAX_EPG_BYTES = 128 * 1024 * 1024
        private const val CACHE_WINDOW_MINUTES = 72 * 60
    }

    override fun doWork(): Result {
        if (!DeviceProfile.isTablet(applicationContext)) return Result.success()
        if (!SportsPreferences.isEnabled(applicationContext)) return Result.success()

        val epgUrl = SportsPreferences.epgUrl(applicationContext)
        val config = SportsPreferences.loadConfig(applicationContext)
        if (epgUrl.isBlank() || !config.hasFavorites) return Result.success()

        return try {
            val matches = NetworkClient.withInputStream(epgUrl, MAX_EPG_BYTES) { stream ->
                SportsEpgScanner.scan(
                    input = stream,
                    config = config,
                    windowMinutes = CACHE_WINDOW_MINUTES
                )
            }
            SportsEventCache.save(applicationContext, matches)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object SportsAlertScheduler {
    private const val ALERT_WORK = "cloud247_sports_alerts"
    private const val REFRESH_WORK = "cloud247_sports_epg_refresh"

    fun sync(context: Context) {
        val manager = WorkManager.getInstance(context)
        val shouldRun =
            DeviceProfile.isTablet(context) &&
                SportsPreferences.isEnabled(context) &&
                SportsPreferences.epgUrl(context).isNotBlank() &&
                SportsPreferences.loadConfig(context).hasFavorites

        if (!shouldRun) {
            manager.cancelUniqueWork(ALERT_WORK)
            manager.cancelUniqueWork(REFRESH_WORK)
            return
        }

        val alertWork = PeriodicWorkRequestBuilder<SportsAlertWorker>(
            15,
            TimeUnit.MINUTES
        ).build()

        manager.enqueueUniquePeriodicWork(
            ALERT_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            alertWork
        )

        val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val refreshWork = PeriodicWorkRequestBuilder<SportsEpgRefreshWorker>(
            12,
            TimeUnit.HOURS
        )
            .setConstraints(network)
            .build()

        manager.enqueueUniquePeriodicWork(
            REFRESH_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            refreshWork
        )

        val stale =
            SportsEventCache.updatedAt(context) <
                System.currentTimeMillis() - 6L * 60L * 60L * 1000L

        if (stale) refreshNow(context)
    }

    fun checkNow(context: Context) {
        val alert = OneTimeWorkRequestBuilder<SportsAlertWorker>().build()
        WorkManager.getInstance(context).enqueue(alert)
    }

    fun refreshNow(context: Context) {
        val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val refresh = OneTimeWorkRequestBuilder<SportsEpgRefreshWorker>()
            .setConstraints(network)
            .build()
        val alert = OneTimeWorkRequestBuilder<SportsAlertWorker>().build()

        WorkManager.getInstance(context)
            .beginWith(refresh)
            .then(alert)
            .enqueue()
    }
}
