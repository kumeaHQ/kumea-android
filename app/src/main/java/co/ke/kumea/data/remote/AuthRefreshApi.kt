package co.ke.kumea.data.remote

import co.ke.kumea.data.remote.dto.AuthResponse
import co.ke.kumea.data.remote.dto.RefreshRequest
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Minimal POST /auth/refresh client used ONLY by [co.ke.kumea.data.remote.interceptor.TokenAuthenticator].
 *
 * Built from a BARE OkHttpClient (no AuthInterceptor, no Authenticator) for two
 * reasons:
 *  - it breaks the DI cycle — the main OkHttpClient depends on the authenticator,
 *    which would otherwise depend back on the main client to refresh;
 *  - the refresh call authenticates with the refresh token in the BODY, so it
 *    must not carry the (expired) access token in an Authorization header.
 */
interface AuthRefreshApi {
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse
}
