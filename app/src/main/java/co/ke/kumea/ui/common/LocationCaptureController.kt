package co.ke.kumea.ui.common

import co.ke.kumea.data.location.CapturedLocation
import co.ke.kumea.data.location.LocationFix
import co.ke.kumea.data.location.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

enum class LocationStatus {
    /** Nothing asked for yet. The button reads "Use my location". */
    IDLE,

    /** Permission granted, satellites being found. Accuracy is live on screen. */
    SEARCHING,

    /** We have a fix and stopped looking. The farmer decides whether to keep it. */
    FOUND,

    /** The farmer kept it. This is the only state the save writes coordinates from. */
    ACCEPTED,

    /** Denied this time. The farm still saves; the button says so honestly. */
    DENIED,

    /** "Don't ask again". Only Settings can undo it, so offer that and move on. */
    BLOCKED,

    /** Permission is fine but location is switched off device-wide. */
    UNAVAILABLE,
}

data class LocationCaptureState(
    val status: LocationStatus = LocationStatus.IDLE,
    /** Best fix so far — updated live while [LocationStatus.SEARCHING]. */
    val fix: LocationFix? = null,
    val elapsedSeconds: Int = 0,
    /**
     * Whether the farmer explicitly said they are standing on this shamba.
     * Drives `farms.locationConfirmedAt` and NOTHING else. Defaults to false
     * because the common case is an officer registering ten farmers in a day
     * from wherever they happen to be (KWAP-03 §5.1⑤).
     */
    val standingHere: Boolean = false,
) {
    /** Accurate enough to stop looking, and to present without a warning. */
    val isPrecise: Boolean get() = fix != null && fix.accuracyM <= GOOD_ACCURACY_M

    /** A kept fix that is vaguer than [GOOD_ACCURACY_M] — allowed, but say so. */
    val isVague: Boolean get() = fix != null && !isPrecise

    companion object {
        const val GOOD_ACCURACY_M = 20f
    }
}

/**
 * The location capture state machine (KWAP-03 §5.1), owned by a ViewModel.
 *
 * A controller rather than a base class or a shared singleton: both registration
 * flows need identical behaviour (`FarmDetailViewModel` for self-registration,
 * `RegisterFarmerViewModel` for the officer path), a singleton would leak one
 * farm's fix into the next farm's form, and copying the state machine twice is
 * how the two flows would quietly drift apart.
 *
 * ── THE INVARIANT THIS TYPE EXISTS TO ENFORCE ────────────────────────────────
 *
 * [captured] returns coordinates ONLY in [LocationStatus.ACCEPTED], and always
 * returns all four metadata fields with them. There is no path that yields a
 * claim without a fact. `useGps = 1, locationLat = null` — a boolean asserting a
 * location that was never captured — is the exact bug being fixed here, and it
 * was possible because the claim and the fact lived in different places. Now
 * there is one place.
 */
class LocationCaptureController(
    private val capturer: LocationProvider,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LocationCaptureState())
    val state: StateFlow<LocationCaptureState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** True when the screen must launch the system permission dialog first. */
    fun needsPermission(): Boolean = !capturer.hasPermission()

    /**
     * Called after the system dialog. `canAskAgain` comes from
     * `shouldShowRequestPermissionRationale` — false after a permanent denial,
     * which is a different situation and a different message.
     */
    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        when {
            granted -> start()
            canAskAgain -> _state.update { it.copy(status = LocationStatus.DENIED) }
            else -> _state.update { it.copy(status = LocationStatus.BLOCKED) }
        }
    }

    /**
     * Stream fixes until one is precise enough or [TIMEOUT_SECONDS] passes.
     *
     * A cold GPS start under tree cover genuinely takes 30–60 seconds, so the
     * timeout is generous and the accuracy is on screen throughout — a farmer
     * watching ±48 m fall to ±8 m is watching it work, where a spinner for the
     * same minute reads as broken. Whatever the best fix is when we stop is
     * kept: sixty seconds of waiting must never end with nothing.
     */
    fun start() {
        if (!capturer.hasPermission()) {
            _state.update { it.copy(status = LocationStatus.DENIED) }
            return
        }
        if (!capturer.isLocationEnabled()) {
            _state.update { it.copy(status = LocationStatus.UNAVAILABLE) }
            return
        }

        searchJob?.cancel()
        _state.value = LocationCaptureState(status = LocationStatus.SEARCHING)

        searchJob = scope.launch {
            val started = Clock.System.now()
            val ticker = launch {
                while (isActive) {
                    delay(1_000)
                    _state.update {
                        it.copy(elapsedSeconds = (Clock.System.now() - started).inWholeSeconds.toInt())
                    }
                }
            }
            try {
                capturer.stream().collect { fix ->
                    // Keep the BEST fix, not the latest. Providers interleave,
                    // so a fresh 800 m network fix can arrive right after a 6 m
                    // GPS one and would otherwise overwrite it.
                    val best = _state.value.fix
                    if (best == null || fix.accuracyM < best.accuracyM) {
                        _state.update { it.copy(fix = fix) }
                    }
                    if (_state.value.isPrecise) stop()
                }
            } finally {
                ticker.cancel()
            }
        }

        scope.launch {
            delay(TIMEOUT_SECONDS * 1_000L)
            if (_state.value.status == LocationStatus.SEARCHING) stop()
        }
    }

    /** Stop looking and present whatever we have. */
    private fun stop() {
        searchJob?.cancel()
        searchJob = null
        _state.update {
            it.copy(status = if (it.fix != null) LocationStatus.FOUND else LocationStatus.DENIED)
        }
    }

    fun cancel() {
        searchJob?.cancel()
        searchJob = null
        _state.value = LocationCaptureState()
    }

    /** "Try again" — throw the fix away and re-run rather than keep a bad one. */
    fun retry() = start()

    fun setStandingHere(standing: Boolean) {
        _state.update { it.copy(standingHere = standing) }
    }

    /** "Use this location". The only transition that makes coordinates savable. */
    fun accept() {
        if (_state.value.fix == null) return
        _state.update { it.copy(status = LocationStatus.ACCEPTED) }
    }

    /**
     * What the save writes — or null, which is a complete and honest answer.
     *
     * Null in every state except ACCEPTED, including FOUND: a fix the farmer
     * was shown but never kept is not a location they agreed to.
     */
    fun captured(): CapturedLocation? {
        val current = _state.value
        val fix = current.fix ?: return null
        if (current.status != LocationStatus.ACCEPTED) return null
        return CapturedLocation(
            lat = fix.lat,
            lng = fix.lng,
            accuracyM = fix.accuracyM,
            source = fix.source,
            capturedAt = fix.capturedAt,
            // Set ONLY on the explicit "I am standing at this shamba now" tick.
            // This is what later separates a coordinate someone typed a farm
            // near from one a human stood on, and what a "farms needing
            // location confirmed" worklist would read.
            confirmedAt = if (current.standingHere) Clock.System.now().toString() else null,
        )
    }

    companion object {
        const val TIMEOUT_SECONDS = 60
    }
}
