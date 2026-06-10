package co.ke.kumea.data.repository

import co.ke.kumea.data.auth.TokenStore
import co.ke.kumea.data.local.KumeaDatabase
import android.util.Log
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.AuthResponse
import co.ke.kumea.data.remote.dto.LoginRequest
import co.ke.kumea.data.remote.dto.LogoutRequest
import co.ke.kumea.data.remote.dto.RegisterRequest
import co.ke.kumea.data.remote.dto.SendOtpRequest
import co.ke.kumea.data.remote.dto.SendOtpResponse
import co.ke.kumea.data.remote.dto.UserProfile
import co.ke.kumea.data.remote.dto.VerifyOtpRequest
import co.ke.kumea.data.remote.dto.VerifyOtpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the auth endpoints on [KumeaApi] together with [TokenStore] persistence.
 *
 * On a successful register/login the access + refresh tokens are saved so
 * [co.ke.kumea.data.remote.interceptor.AuthInterceptor] attaches them to later
 * requests. Logout / an invalid session clears tokens AND the local Room cache so
 * the next signed-in user never sees the previous user's farms.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: KumeaApi,
    private val tokenStore: TokenStore,
    private val database: KumeaDatabase,
) {
    suspend fun sendOtp(phone: String): SendOtpResponse =
        api.sendOtp(SendOtpRequest(phone))

    /**
     * Current user profile (GET /auth/me). Throws on network/HTTP failure —
     * callers that treat it as best-effort (e.g. agent-code auto-populate)
     * handle and log the failure themselves.
     */
    suspend fun me(): UserProfile = api.me()

    suspend fun verifyOtp(phone: String, code: String): VerifyOtpResponse =
        api.verifyOtp(VerifyOtpRequest(phone, code))

    suspend fun register(registrationToken: String, pin: String): AuthResponse {
        val res = api.register(RegisterRequest(registrationToken, pin))
        tokenStore.saveTokens(res.accessToken, res.refreshToken)
        return res
    }

    suspend fun login(phone: String, pin: String): AuthResponse {
        val res = api.login(LoginRequest(phone, pin))
        tokenStore.saveTokens(res.accessToken, res.refreshToken)
        return res
    }

    /**
     * Startup check: true if a saved access token is still valid (GET /auth/me 200).
     *
     * - 200 → true, proceed to FarmList
     * - 401 → token expired/revoked → clear session → route to PhoneEntry
     * - Network error / 5xx → token might still be valid, proceed to FarmList
     *   so returning users aren't logged out by weak signal.
     */
    suspend fun isAuthenticated(): Boolean {
        val token = tokenStore.tokenFlow.firstOrNull()
        if (token.isNullOrBlank()) return false
        return try {
            api.me()
            true
        } catch (e: HttpException) {
            if (e.code() == 401) {
                clearSession()
                false
            } else {
                // Non-401 HTTP errors (5xx, 429, etc.) — keep session, try again later
                false
            }
        } catch (e: Exception) {
            // Network errors (DNS, timeout, no connectivity) — keep session alive.
            // Returning farmers opening the app with bad signal go to FarmList
            // with cached data, not booted to login.
            true
        }
    }

    /**
     * Best-effort server logout, then clear all local state (tokens + Room).
     * A failed network call must not block the local sign-out.
     */
    suspend fun logout() {
        val refreshToken = tokenStore.refreshTokenFlow.firstOrNull()
        if (!refreshToken.isNullOrBlank()) {
            try {
                api.logout(LogoutRequest(refreshToken))
            } catch (e: Exception) {
                Log.w("Auth", "Logout API call failed (best-effort): ${e.message}")
            }
        }
        clearSession()
    }

    private suspend fun clearSession() {
        tokenStore.clearAll()
        withContext(Dispatchers.IO) {
            database.clearAllTables()
        }
    }
}
