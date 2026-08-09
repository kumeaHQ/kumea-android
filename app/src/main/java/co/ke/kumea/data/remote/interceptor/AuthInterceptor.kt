package co.ke.kumea.data.remote.interceptor

import co.ke.kumea.data.auth.TokenStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Adds Authorization: Bearer <token> when a token exists. No-op while logged out.
 *
 * The runBlocking is intentional and safe here:
 *  - OkHttp interceptors are synchronous by contract
 *  - This runs on OkHttp's dispatcher thread, never the main thread
 *  - DataStore's read is fast (just a memory hit once warmed up)
 *
 * 2.2 will add X-Client-Id (device UUID) here too.
 */
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenStore.tokenFlow.firstOrNull() }
        val request = chain.request().newBuilder().apply {
            token?.let { addHeader("Authorization", "Bearer $it") }
        }.build()
        return chain.proceed(request)
    }
}
