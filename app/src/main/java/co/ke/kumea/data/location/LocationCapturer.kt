package co.ke.kumea.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import co.ke.kumea.data.local.LocationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One shamba's coordinates, with everything needed to judge them.
 *
 * A bare lat/lng cannot be judged at all — this is the whole reason KWAP-03
 * §4.1 added four columns instead of trusting two.
 */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    /** Metres, as the provider reported. */
    val accuracyM: Float,
    /** [LocationSource] — which provider produced it. */
    val source: String,
    /** UTC ISO-8601, taken when the fix arrived. */
    val capturedAt: String,
)

/**
 * A fix a human decided to keep — the shape the save writes.
 *
 * ALL FIVE FIELDS OR NONE. The bug this replaces was a boolean (`useGps`) that
 * could assert a location while the coordinates stayed null, because the claim
 * and the fact lived in different places. They cannot here: there is no way to
 * construct a confirmation without the coordinates it is about.
 */
data class CapturedLocation(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val source: String,
    val capturedAt: String,
    /** Non-null only on an explicit "I am standing at this shamba now". */
    val confirmedAt: String?,
)

/**
 * What the capture state machine actually needs from the platform.
 *
 * An interface purely so `LocationCaptureController` is testable off-device:
 * the invariant it enforces — never a location claim without coordinates — is
 * the exact bug KWAP-03 exists to fix, and an invariant that cannot be tested
 * is a comment.
 */
interface LocationProvider {
    fun hasPermission(): Boolean
    fun isLocationEnabled(): Boolean
    fun stream(): Flow<LocationFix>
}

/**
 * GPS, via the platform's own `LocationManager`.
 *
 * NOT `FusedLocationProviderClient`, which KWAP-03 §5.1 named. Fused lives in
 * `com.google.android.gms:play-services-location` — a new dependency, and
 * CLAUDE.md bars adding one without a ticket that authorises it, for reasons
 * this project has already paid for once. The platform API needs no dependency,
 * no Play Services on the handset, and provides everything §5.1 actually
 * specifies: streaming updates, an accuracy in metres, and a provider name.
 * Fused's advantages are battery and sensor fusion, and neither is worth a GMS
 * dependency for a screen used a few times a day for at most sixty seconds.
 *
 * FULLY OFFLINE, which is the point. GPS needs no network — it needs sky. That
 * is exactly why the old no-op was so expensive: the one capability that works
 * perfectly in a Nandi maize field with no signal was the one being faked.
 *
 * Both providers are requested. GPS is the accurate one and the slow one; the
 * network provider (cell/wifi) usually answers in a second at 20–2000 m and
 * gives the farmer something moving on screen while the satellites are found.
 * Which one produced a given fix is recorded, never averaged away.
 */
@Singleton
class LocationCapturer @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationProvider {

    override fun hasPermission(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** True when both providers are switched off — location is off device-wide. */
    override fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return PROVIDERS.any { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /**
     * Every fix both providers produce, as they arrive. Never completes on its
     * own — the caller decides when it has waited long enough, because "good
     * enough" is a product decision (≤20 m or 60 s) and not this class's.
     *
     * DELIBERATELY NO `getLastKnownLocation()` SEED. RB's 13 Aug sweep found a
     * four-day-old network fix being presented as current, which is the failure
     * this whole change exists to remove. A cached fix has an age this API
     * cannot honestly show at the moment of asking, so nothing here emits one.
     */
    override fun stream(): Flow<LocationFix> {
        if (!hasPermission()) return emptyFlow()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return emptyFlow()

        return callbackFlow {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    trySend(location.toFix())
                }

                // Required on API < 30 — an abstract method there, defaulted
                // later. Deprecated and never used; omitting it crashes on the
                // minSdk-24 handsets this app is actually for.
                @Deprecated("Required by the pre-30 interface", ReplaceWith(""))
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }

            val registered = PROVIDERS.filter { provider ->
                runCatching {
                    if (!manager.isProviderEnabled(provider)) return@runCatching false
                    manager.requestLocationUpdates(
                        provider,
                        MIN_INTERVAL_MS,
                        MIN_DISTANCE_M,
                        listener,
                        Looper.getMainLooper(),
                    )
                    true
                }.getOrDefault(false)
            }
            if (registered.isEmpty()) close()

            awaitClose {
                // Unconditional: leaving GPS streaming after the screen is gone
                // is the classic way to flatten a battery in a place where
                // charging is not a given.
                runCatching { manager.removeUpdates(listener) }
            }
        }
    }

    private fun Location.toFix() = LocationFix(
        lat = latitude,
        lng = longitude,
        // hasAccuracy() is false on some cheap chipsets. Float.MAX_VALUE rather
        // than 0f, so an unknown accuracy sorts as the worst possible fix
        // instead of the best one — a 0 here would look like perfect precision.
        accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        source = when (provider) {
            LocationManager.GPS_PROVIDER -> LocationSource.GPS
            else -> LocationSource.NETWORK
        },
        capturedAt = Clock.System.now().toString(),
    )

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        /** Stream as fast as the provider will give; the UI throttles, not this. */
        private const val MIN_INTERVAL_MS = 0L
        private const val MIN_DISTANCE_M = 0f
    }
}
