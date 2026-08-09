package co.ke.kumea

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import android.os.PowerManager
import android.provider.Settings
import co.ke.kumea.data.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KumeaApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            @Suppress("DEPRECATION")
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

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
        // Samsung / aggressive OEMs kill WorkManager jobs unless the app
        // is exempted from battery optimisation. Ask once on first launch.
        requestBatteryExemption()

        SyncScheduler.schedule(this)
    }
}
