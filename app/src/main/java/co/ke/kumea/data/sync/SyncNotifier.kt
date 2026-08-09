package co.ke.kumea.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import co.ke.kumea.MainActivity
import co.ke.kumea.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background-sync notifications (Ticket 2.3).
 *
 * Two notifications, both one-line and dismissible:
 *   - success: shown only when a background sync actually moved data (≥1 row
 *     pushed or pulled). Silent when nothing changed — this is reassurance, not
 *     an engagement stream.
 *   - error: shown only when a background sync has failed across all WorkManager
 *     retries (sustained failure), not on a single transient 2G hiccup. Tapping
 *     it opens the app so the user can pull-to-refresh.
 *
 * Notifications are never shown for user-initiated pull-to-refresh — that path
 * doesn't run SyncWorker at all (the ViewModels call the repositories directly),
 * so the refreshed list on screen is the only feedback the user needs.
 *
 * POST_NOTIFICATIONS (API 33+) is requested gracefully by the UI. If it was
 * denied, every post here is a silent no-op — background sync still runs, just
 * without the reassurance notification. Sync is never blocked on the permission.
 */
@Singleton
class SyncNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sync_channel_name),
                // LOW: no sound/vibration — a "your data's safe" status line, not an alert.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.sync_channel_description)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /** Show a one-line "synced" notification. Caller guarantees data actually moved. */
    fun showSyncSuccess(pushed: Int, pulled: Int) {
        // Compose from the two plural-correct fragments so the "both" case never
        // needs a separate string with two %d-followed-by-words (PluralsCandidate).
        val parts = buildList {
            if (pushed > 0) add(
                context.resources.getQuantityString(R.plurals.sync_success_uploaded, pushed, pushed)
            )
            if (pulled > 0) add(
                context.resources.getQuantityString(R.plurals.sync_success_downloaded, pulled, pulled)
            )
        }
        val text = parts.joinToString(", ")
        val notification = baseBuilder()
            .setContentTitle(context.getString(R.string.sync_success_title))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        post(SUCCESS_NOTIFICATION_ID, notification)
    }

    /** Show a "couldn't sync — tap to retry" notification that opens the app. */
    fun showSyncError() {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = baseBuilder()
            .setContentTitle(context.getString(R.string.sync_error_title))
            .setContentText(context.getString(R.string.sync_error_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        post(ERROR_NOTIFICATION_ID, notification)
    }

    private fun baseBuilder() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun post(id: Int, notification: android.app.Notification) {
        // API 33+: posting without POST_NOTIFICATIONS is a silent no-op, by design.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted — sync notification suppressed (sync still ran)")
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        private const val TAG = "SyncNotifier"
        const val CHANNEL_ID = "kumea-sync"
        const val SUCCESS_NOTIFICATION_ID = 2301
        const val ERROR_NOTIFICATION_ID = 2302
    }
}
