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
        SportsHubPreferences.migrateLegacyIfNeeded(applicationContext)
        if (!SportsHubPreferences.isEnabled(applicationContext)) return Result.success()

        val config = SportsHubPreferences.load(applicationContext)
        val now = System.currentTimeMillis()
        val horizon = now + config.leadMinutes * 60_000L

        SportsHubCache.load(applicationContext)
            .events
            .asSequence()
            .filter { it.start.time in now..horizon }
            .filter { SportsHubPreferences.shouldAlert(config, it) }
            .take(10)
            .forEach { event ->
                val key = event.notificationKey()
                if (!SportsHubPreferences.wasNotified(applicationContext, key)) {
                    notifyEvent(event)
                    SportsHubPreferences.markNotified(applicationContext, key)
                }
            }

        return Result.success()
    }

    private fun notifyEvent(event: SportsHubEvent) {
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
                    description = "Varsler om valgte lag, spillere, ligaer og turneringer."
                }
            )
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_SPORTS_HUB, true)
            putExtra(MainActivity.EXTRA_SPORTS_EVENT_TITLE, event.title)
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            event.notificationKey().hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val time = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(event.start)
        val icon = when (event.sport) {
            "tennis" -> "🎾"
            "golf" -> "⛳"
            else -> "⚽"
        }
        val body = buildString {
            append(time)
            if (event.competition.isNotBlank()) append(" · ").append(event.competition)
            append(" · Trykk for å åpne Sport")
        }

        manager.notify(
            event.notificationKey().hashCode(),
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$icon ${event.title}")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }
}

class SportsApiRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        if (!DeviceProfile.isTablet(applicationContext)) return Result.success()
        val config = SportsHubPreferences.load(applicationContext)
        if (!config.hasFavorites) return Result.success()

        return try {
            SportsHubCache.save(applicationContext, SportsApiClient.fetchUpcoming(config))
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object SportsAlertScheduler {
    private const val ALERT_WORK = "cloud247_sports_alerts_v15"
    private const val REFRESH_WORK = "cloud247_sports_refresh_v15"

    fun sync(context: Context) {
        SportsHubPreferences.migrateLegacyIfNeeded(context)
        val manager = WorkManager.getInstance(context)
        val config = SportsHubPreferences.load(context)
        val hasSports = DeviceProfile.isTablet(context) && config.hasFavorites

        if (!hasSports) {
            manager.cancelUniqueWork(ALERT_WORK)
            manager.cancelUniqueWork(REFRESH_WORK)
            return
        }

        val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        manager.enqueueUniquePeriodicWork(
            REFRESH_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SportsApiRefreshWorker>(4, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
        )

        if (SportsHubPreferences.isEnabled(context) && config.alertSelections.isNotEmpty()) {
            manager.enqueueUniquePeriodicWork(
                ALERT_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SportsAlertWorker>(15, TimeUnit.MINUTES).build()
            )
        } else {
            manager.cancelUniqueWork(ALERT_WORK)
        }

        if (
            SportsHubCache.updatedAt(context) <
            System.currentTimeMillis() - 2L * 60L * 60L * 1000L
        ) {
            refreshNow(context)
        }
    }

    fun refreshNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val refresh = OneTimeWorkRequestBuilder<SportsApiRefreshWorker>()
            .setConstraints(constraints)
            .build()
        val alert = OneTimeWorkRequestBuilder<SportsAlertWorker>().build()

        WorkManager.getInstance(context)
            .beginWith(refresh)
            .then(alert)
            .enqueue()
    }

    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SportsAlertWorker>().build()
        )
    }
}
