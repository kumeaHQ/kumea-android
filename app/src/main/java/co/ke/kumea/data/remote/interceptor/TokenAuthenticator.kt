package co.ke.kumea.data.remote.interceptor

import co.ke.kumea.data.auth.TokenStore
import co.ke.kumea.data.remote.AuthRefreshApi
import co.ke.kumea.data.remote.dto.RefreshRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Refreshes the access token on a 401 and retries the failed request once
 * (P1-T5a Part 2). This is exactly what the sync path was missing: with no
 * refresh, an expired access token 401'd every push, and the push path swallowed
 * the 401 — "0 pushed, no error".
 *
 * The worker does NOT hold its own token. [AuthInterceptor] reads the latest
 * token from the single shared [TokenStore] on every request; this authenticator
 * refreshes that shared token when the server rejects it. Refresh goes through
 * [AuthRefreshApi] (a bare client) so it neither cycles on the main OkHttpClient
 * nor re-sends the expired bearer.
 *
 * Behaviour:
 *  - On 401, refresh via /auth/refresh (rotates both tokens), persist them, and
 *    retry the original request once with the new access token.
 *  - If another in-flight request already refreshed (the stored token changed
 *    since the one that 401'd), skip the network call and just retry with it.
 *  - Give up after one attempt (priorResponse set) to avoid an infinite 401 loop.
 *    Returning null lets the original 401 stand, which the repository then
 *    surfaces in its PushReport as failed (401) — never silent.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
    private val refreshApi: AuthRefreshApi,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // This 401 already followed a refreshed retry → stop, don't loop.
        if (response.priorResponse != null) return null

        val bearerThatFailed = response.request.header("Authorization")?.removePrefix("Bearer ")

        return synchronized(this) {
            val current = runBlocking { tokenStore.tokenFlow.firstOrNull() }
            // Another request refreshed already → retry with the fresh token.
            if (!current.isNullOrBlank() && current != bearerThatFailed) {
                return@synchronized retryWith(response, current)
            }

            val refreshToken = runBlocking { tokenStore.refreshTokenFlow.firstOrNull() }
            if (refreshToken.isNullOrBlank()) return@synchronized null

            val refreshed = try {
                runBlocking { refreshApi.refresh(RefreshRequest(refreshToken)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Refresh failed (network down, or the refresh token itself was
                // rejected). Let the original 401 stand — surfaced by the caller.
                return@synchronized null
            }

            runBlocking { tokenStore.saveTokens(refreshed.accessToken, refreshed.refreshToken) }
            retryWith(response, refreshed.accessToken)
        }
    }

    /** header() REPLACES the stale Authorization, so the retry carries one bearer. */
    private fun retryWith(response: Response, token: String): Request =
        response.request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
}
