package co.ke.kumea.ui.common

import co.ke.kumea.data.local.LocationSource
import co.ke.kumea.data.location.LocationFix
import co.ke.kumea.data.location.LocationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE INVARIANT (KWAP-03 §12, first bullet):
 *
 *   A farm can never be saved with a location CLAIM and null COORDINATES.
 *
 * That is not a hypothetical. `useGps = 1, locationLat = null` is what RB's
 * 13 Aug sweep actually found in the database, because the button wrote a
 * boolean and the coordinates came from a `TODO` that was never done. The whole
 * of §5.1 is the fix, and these tests are what stop it coming back — a comment
 * saying "never store a claim without the fact" is not an invariant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationCaptureControllerTest {

    private class FakeProvider(
        private val granted: Boolean = true,
        private val enabled: Boolean = true,
        private val fixes: List<LocationFix> = emptyList(),
    ) : LocationProvider {
        override fun hasPermission() = granted
        override fun isLocationEnabled() = enabled
        override fun stream(): Flow<LocationFix> =
            if (fixes.isEmpty()) emptyFlow() else flow { fixes.forEach { emit(it) } }
    }

    private fun fix(accuracy: Float, lat: Double = 0.1874, lng: Double = 35.1021) = LocationFix(
        lat = lat,
        lng = lng,
        accuracyM = accuracy,
        source = LocationSource.GPS,
        capturedAt = "2026-08-13T10:43:00Z",
    )

    // ── the invariant ───────────────────────────────────────────────────────

    @Test
    fun `nothing is captured before a fix arrives`() = runTest {
        val controller = LocationCaptureController(FakeProvider(fixes = emptyList()), TestScope(testScheduler))

        controller.start()
        advanceUntilIdle()

        assertNull("no fix means no location, not a flag", controller.captured())
    }

    @Test
    fun `a fix that was shown but never accepted is not a location`() = runTest {
        val controller = LocationCaptureController(FakeProvider(fixes = listOf(fix(8f))), TestScope(testScheduler))

        controller.start()
        advanceUntilIdle()

        // The farmer saw it and did not tap "Use this location". Showing someone
        // a coordinate is not the same as them agreeing it is their shamba.
        assertEquals(LocationStatus.FOUND, controller.state.value.status)
        assertNull(controller.captured())
    }

    @Test
    fun `permission denied captures nothing and does not throw`() = runTest {
        val controller = LocationCaptureController(FakeProvider(granted = false), TestScope(testScheduler))

        controller.start()
        advanceUntilIdle()

        assertEquals(LocationStatus.DENIED, controller.state.value.status)
        // The farm still saves. A refused permission is a farm without
        // coordinates, never a failed registration.
        assertNull(controller.captured())
    }

    @Test
    fun `location switched off device-wide is its own state, not a denial`() = runTest {
        val controller = LocationCaptureController(FakeProvider(enabled = false), TestScope(testScheduler))

        controller.start()
        advanceUntilIdle()

        // Different cause, different fix: this one opens location settings
        // rather than re-asking for a permission the app already has.
        assertEquals(LocationStatus.UNAVAILABLE, controller.state.value.status)
        assertNull(controller.captured())
    }

    // ── accuracy is recorded, never rounded away ────────────────────────────

    @Test
    fun `accepting a 45 metre fix stores 45 and flags it as vague`() = runTest {
        val controller = LocationCaptureController(FakeProvider(fixes = listOf(fix(45f))), TestScope(testScheduler))

        controller.start()
        advanceUntilIdle()
        controller.accept()

        val captured = controller.captured()!!
        // Stored as measured. Accepting a vague fix is allowed — never block the
        // save — but the vagueness travels with it rather than being rounded to
        // something that looks authoritative.
        assertEquals(45f, captured.accuracyM)
        assertTrue("45 m must present as vague", controller.state.value.isVague)
    }

    @Test
    fun `the best fix wins, not the most recent one`() = runTest {
        // Providers interleave: a fresh 800 m network fix routinely lands right
        // after a 6 m GPS one. Keeping the latest would throw away the good one.
        val controller = LocationCaptureController(
            FakeProvider(fixes = listOf(fix(50f), fix(6f), fix(800f))),
            TestScope(testScheduler),
        )

        controller.start()
        advanceUntilIdle()

        assertEquals(6f, controller.state.value.fix?.accuracyM)
    }

    @Test
    fun `a precise fix stops the search instead of burning GPS for a minute`() = runTest {
        val controller = LocationCaptureController(FakeProvider(fixes = listOf(fix(8f))), TestScope(testScheduler))

        controller.start()
        advanceUntilIdle()

        assertEquals(LocationStatus.FOUND, controller.state.value.status)
        assertTrue(controller.state.value.isPrecise)
    }

    // ── captured vs confirmed ───────────────────────────────────────────────

    @Test
    fun `accepting without ticking standing-here leaves confirmedAt null`() = runTest {
        val controller = LocationCaptureController(FakeProvider(fixes = listOf(fix(8f))), TestScope(testScheduler))

        controller.start()
        advanceUntilIdle()
        controller.accept()

        val captured = controller.captured()!!
        // THE OFFICER CASE, and it is the normal one: someone registering ten
        // farmers in a day is not standing on ten shambas. A captured point and
        // a confirmed one are different facts.
        assertNull(captured.confirmedAt)
        assertEquals(0.1874, captured.lat, 0.0)
    }

    @Test
    fun `ticking standing-here is the only thing that sets confirmedAt`() = runTest {
        val controller = LocationCaptureController(FakeProvider(fixes = listOf(fix(8f))), TestScope(testScheduler))

        controller.start()
        advanceUntilIdle()
        controller.setStandingHere(true)
        controller.accept()

        assertNotNull(controller.captured()!!.confirmedAt)
    }

    @Test
    fun `retry throws the old fix away rather than keeping a stale one`() = runTest {
        val controller = LocationCaptureController(FakeProvider(fixes = listOf(fix(8f))), TestScope(testScheduler))

        controller.start()
        advanceUntilIdle()
        controller.accept()
        assertNotNull(controller.captured())

        controller.retry()
        // Mid-retry there is no accepted location. A "Try again" that silently
        // kept the previous answer would be the same class of lie as useGps.
        assertNull(controller.captured())
    }
}
