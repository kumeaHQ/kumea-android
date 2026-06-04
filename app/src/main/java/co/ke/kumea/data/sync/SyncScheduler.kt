package co.ke.kumea.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules background sync (Ticket 2.3). This is the first real WorkManager
 * enqueue in the app — the SyncWorker existed (2.2) but nothing ever scheduled
 * it; sync was manual pull-to-refresh only.
 *
 * Two triggers, both running the same idempotent SyncWorker:
 *
 *   1. Connectivity-triggered (the main feature): a one-time request constrained
 *      on CONNECTED. WorkManager holds it until the device has a network, then
 *      runs it. Re-armed on every app start with KEEP, so there is always exactly
 *      one armed (toggling airplane mode repeatedly doesn't stack N jobs).
 *
 *   2. Periodic safety net: every 6 hours, constrained on CONNECTED. Catches
 *      anything the connectivity trigger missed (app killed before its job ran,
 *      or the device was online continuously so "connectivity returned" never
 *      fired). 6h, not 15m — farmers' data changes in bursts; a short interval
 *      burns battery for no benefit. KEEP so re-opening the app doesn't reset the
 *      6h clock.
 */
object SyncScheduler {

    const val CONNECTIVITY_WORK_NAME = "sync-on-connect"
    const val PERIODIC_WORK_NAME = "sync-periodic"
    private const val PERIODIC_INTERVAL_HOURS = 6L

    private val connectedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Arm both triggers. Safe to call on every app start (both use KEEP). */
    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        enqueueConnectivitySync(workManager)
        enqueuePeriodicSync(workManager)
    }

    private fun enqueueConnectivitySync(workManager: WorkManager) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            CONNECTIVITY_WORK_NAME,
            // KEEP: if one is already waiting for connectivity, don't stack another.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun enqueuePeriodicSync(workManager: WorkManager) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(connectedConstraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            // KEEP: don't reset the 6h schedule every time the app is opened.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
