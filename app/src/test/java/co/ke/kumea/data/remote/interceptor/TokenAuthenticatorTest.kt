package co.ke.kumea.data.remote.interceptor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import co.ke.kumea.data.auth.TokenStore
import co.ke.kumea.data.remote.AuthRefreshApi
import co.ke.kumea.data.remote.dto.AuthResponse
import co.ke.kumea.data.remote.dto.AuthUser
import co.ke.kumea.data.remote.dto.RefreshRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the silent re-authentication path (P1-T5a Part 2).
 *
 * These drive the REAL [TokenAuthenticator] against the REAL [TokenStore] — only
 * the network edge ([AuthRefreshApi]) is faked, in the hand-written style the rest
 * of this suite uses. There is no mocking library on this project.
 *
 * The case that matters is `concurrent 401s refresh exactly once`. It is written
 * as a reproduction attempt, not as coverage: see its comment.
 */
class TokenAuthenticatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var tokenStore: TokenStore
    // A real scope on real threads — the authenticator's runBlocking must be
    // exercised off a test dispatcher for the concurrency case to mean anything.
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    @Before
    fun setUp() {
        val file: File = tempFolder.newFile("test_auth.preferences_pb")
        // DataStore requires the file not to exist at construction time.
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { file },
        )
        tokenStore = TokenStore(dataStore)
        runBlocking { tokenStore.saveTokens(ACCESS_1, REFRESH_1) }
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    // ---- 1. Happy path ----

    @Test
    fun `401 then successful refresh retries the original request and keeps the session`() {
        val api = RotatingRefreshApi()
        val authenticator = TokenAuthenticator(tokenStore, api)

        val retry = authenticator.authenticate(null, response401(bearer = ACCESS_1))

        assertNotNull("expected a retry request, got null (request would be abandoned)", retry)
        assertEquals("Bearer $ACCESS_2", retry!!.header("Authorization"))
        assertEquals(1, api.callCount.get())
        // Session survives: both tokens still present, and rotated forward.
        assertEquals(ACCESS_2, runBlocking { tokenStore.tokenFlow.first() })
        assertEquals(REFRESH_2, runBlocking { tokenStore.refreshTokenFlow.first() })
    }

    // ---- 2. Refresh token genuinely dead ----

    /**
     * NOTE ON AC22: the authenticator does NOT clear the session itself, even
     * here. It returns null, the original 401 stands, and the repository surfaces
     * it in its PushReport. The session is cleared one layer up, by
     * AuthRepository.isAuthenticated() — the documented three-branch pattern —
     * on the next 401 from GET /auth/me.
     *
     * That is a deliberate trade: logout is delayed by one call, in exchange for
     * making it structurally impossible for a failed refresh to wipe a WAO's
     * session from inside an OkHttp callback. This test pins that contract so a
     * later "improvement" that clears tokens here fails loudly.
     */
    @Test
    fun `401 then refresh rejected with 401 abandons the request without clearing tokens`() {
        val api = FailingRefreshApi(http401())
        val authenticator = TokenAuthenticator(tokenStore, api)

        val retry = authenticator.authenticate(null, response401(bearer = ACCESS_1))

        assertNull("a dead refresh token must not produce a retry", retry)
        assertEquals(1, api.callCount.get())
        assertEquals(ACCESS_1, runBlocking { tokenStore.tokenFlow.first() })
        assertEquals(REFRESH_1, runBlocking { tokenStore.refreshTokenFlow.first() })
    }

    // ---- 3. The AC22 case: offline ----

    /**
     * The one from the field. A ward agricultural officer on 3G in the sun: the
     * access token expires, the refresh call times out because there is no signal.
     * She must still be logged in. A timeout is not a rejection.
     */
    @Test
    fun `401 then refresh times out leaves the session intact`() {
        val api = FailingRefreshApi(SocketTimeoutException("timeout"))
        val authenticator = TokenAuthenticator(tokenStore, api)

        val retry = authenticator.authenticate(null, response401(bearer = ACCESS_1))

        assertNull(retry)
        assertEquals(
            "network failure must never clear the access token",
            ACCESS_1,
            runBlocking { tokenStore.tokenFlow.first() },
        )
        assertEquals(
            "network failure must never clear the refresh token",
            REFRESH_1,
            runBlocking { tokenStore.refreshTokenFlow.first() },
        )
    }

    // ---- 4. The rotation case ----

    /**
     * REPRODUCTION ATTEMPT, not coverage.
     *
     * SyncWorker runs pushPending() then pullSince() with several requests in
     * flight. When the access token expires they all 401 at once and all reach
     * this authenticator. The refresh token rotates on use, so a second refresh
     * presenting the now-stale token is rejected — and on this server that is not
     * a single failed request: TokenService.rotate() detects the replay and
     * revokes the ENTIRE token family, logging the user out of every session.
     *
     * [RotatingRefreshApi] is built to reproduce exactly that, not to count calls:
     *  - it rejects any refresh presenting a token it has already rotated away,
     *    with the same 401 the server sends;
     *  - it holds each caller inside the refresh for REFRESH_LATENCY_MS, so the
     *    window is genuinely open rather than closed by luck of scheduling;
     *  - the threads are real and released together by a latch.
     *
     * If the single-flight guard in TokenAuthenticator were removed, the losing
     * threads would present a dead token, get 401, and return null — and the
     * "every caller retried" assertion below fails. Verified by removing that
     * guard and watching this test fail; see the commit message.
     */
    @Test
    fun `concurrent 401s refresh exactly once and every caller retries with the new token`() {
        val api = RotatingRefreshApi()
        val authenticator = TokenAuthenticator(tokenStore, api)

        val startGate = CountDownLatch(1)
        val finished = CountDownLatch(CONCURRENT_CALLERS)
        val retries = Collections.synchronizedList(mutableListOf<Request?>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        repeat(CONCURRENT_CALLERS) {
            Thread {
                try {
                    startGate.await()
                    // Every caller 401s holding the SAME expired access token —
                    // which is what actually happens to a batch mid-sync.
                    retries += authenticator.authenticate(null, response401(bearer = ACCESS_1))
                } catch (t: Throwable) {
                    failures += t
                } finally {
                    finished.countDown()
                }
            }.start()
        }

        startGate.countDown()
        assertTrue(
            "callers deadlocked inside authenticate()",
            finished.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )

        assertTrue("callers threw: $failures", failures.isEmpty())
        assertTrue(
            "a replayed refresh token reached the server — every session for this " +
                "user would now be revoked",
            !api.familyRevoked,
        )
        assertEquals(
            "the refresh token rotates on use — a second refresh revokes the family",
            1,
            api.callCount.get(),
        )
        assertEquals(CONCURRENT_CALLERS, retries.size)
        retries.forEachIndexed { i, request ->
            assertNotNull("caller $i was abandoned (null retry) — it would 401 and stay pending", request)
            assertEquals(
                "caller $i retried with a stale bearer",
                "Bearer $ACCESS_2",
                request!!.header("Authorization"),
            )
        }
        assertEquals(REFRESH_2, runBlocking { tokenStore.refreshTokenFlow.first() })
    }

    // ---- 5. Rotation is persisted ----

    @Test
    fun `refresh persists the rotated refresh token so the next refresh can use it`() {
        val api = RotatingRefreshApi()
        val authenticator = TokenAuthenticator(tokenStore, api)

        authenticator.authenticate(null, response401(bearer = ACCESS_1))

        assertEquals(REFRESH_1, api.lastTokenPresented)
        assertEquals(REFRESH_2, runBlocking { tokenStore.refreshTokenFlow.first() })

        // A second, later 401 must refresh off the ROTATED token, not the original.
        // If saveTokens had missed the refresh token, this presents the dead one
        // and the fake throws the replay 401 the server would.
        val second = authenticator.authenticate(null, response401(bearer = ACCESS_2))
        assertNotNull("second refresh presented a stale token", second)
        assertEquals(
            "the rotated token must be the one presented next",
            REFRESH_2,
            api.lastTokenPresented,
        )
        assertEquals(2, api.callCount.get())
    }

    // ---- fakes ----

    /**
     * Models the server's rotate-on-use contract from
     * kumea-api/src/auth/token.service.ts: one live refresh token at a time, and
     * a 401 for anything else presented.
     */
    private class RotatingRefreshApi : AuthRefreshApi {
        val callCount = AtomicInteger(0)

        /** Set when a replayed token arrives — on the real server every session dies. */
        @Volatile
        var familyRevoked = false
            private set

        @Volatile
        var lastTokenPresented: String? = null
            private set

        private val lock = Any()
        private var liveRefreshToken: String = REFRESH_1
        private var issued = 1

        override suspend fun refresh(body: RefreshRequest): AuthResponse {
            lastTokenPresented = body.refreshToken
            // The server validates and rotates in ONE transaction, so a token is
            // dead the instant another caller has used it — before any response
            // gets back to this device.
            val response = synchronized(lock) {
                if (body.refreshToken != liveRefreshToken) {
                    familyRevoked = true
                    throw http401()
                }
                callCount.incrementAndGet()
                issued += 1
                liveRefreshToken = "refresh-$issued"
                AuthResponse(
                    accessToken = "access-$issued",
                    refreshToken = liveRefreshToken,
                    user = AuthUser(id = "user-1", phone = "+254712345678", role = "farmer"),
                )
            }
            // The response still has to travel back over 3G. This is the window a
            // broken single-flight races into, so hold it open deliberately
            // instead of leaving the bug to scheduling luck.
            Thread.sleep(REFRESH_LATENCY_MS)
            return response
        }
    }

    private class FailingRefreshApi(private val error: Throwable) : AuthRefreshApi {
        val callCount = AtomicInteger(0)

        override suspend fun refresh(body: RefreshRequest): AuthResponse {
            callCount.incrementAndGet()
            throw error
        }
    }

    private companion object {
        const val ACCESS_1 = "access-1"
        const val ACCESS_2 = "access-2"
        const val REFRESH_1 = "refresh-1"
        const val REFRESH_2 = "refresh-2"
        const val CONCURRENT_CALLERS = 8
        const val REFRESH_LATENCY_MS = 60L
        const val TEST_TIMEOUT_SECONDS = 10L

        fun response401(bearer: String?): Response {
            val request = Request.Builder()
                .url("https://kumea.test/farms")
                .apply { bearer?.let { header("Authorization", "Bearer $it") } }
                .build()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .build()
        }

        fun http401(): retrofit2.HttpException = retrofit2.HttpException(
            retrofit2.Response.error<Any>(
                401,
                """{"message":"Invalid or expired refresh token"}"""
                    .toResponseBody("application/json".toMediaType()),
            ),
        )
    }
}
