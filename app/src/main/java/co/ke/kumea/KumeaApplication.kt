package co.ke.kumea

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import co.ke.kumea.data.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KumeaApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Arm background sync (Ticket 2.3): connectivity-triggered + 6h periodic.
        // Both use KEEP, so re-arming on every cold start is safe and idempotent.
        // WorkManager.getInstance() here also forces on-demand initialisation via
        // our workManagerConfiguration above (the default initializer is removed
        // in the manifest), so this is where that wiring first gets exercised.
        SyncScheduler.schedule(this)
    }
}
