package co.ke.kumea.di

import co.ke.kumea.BuildConfig
import co.ke.kumea.data.remote.AuthRefreshApi
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.interceptor.AuthInterceptor
import co.ke.kumea.data.remote.interceptor.TokenAuthenticator
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // Tolerate fields the server adds in future without crashing old clients.
        ignoreUnknownKeys = true
        // Tolerate missing optional fields by using @Serializable defaults.
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            // P1-T5a: refresh the access token on a 401 and retry once. Without
            // this, an expired token 401'd every push and the worker reported
            // "0 pushed, no error". The token lives in the shared TokenStore; the
            // authenticator refreshes that, never a worker-private copy.
            .authenticator(tokenAuthenticator)
            // Kenyan 2G is slow — generous timeouts. Tune in 2.2 if we add retry logic.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // Logging interceptor only in debug. Release builds get nothing logged.
        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(logging)
        }

        return builder.build()
    }

    /**
     * Bare client for the token-refresh call only — NO AuthInterceptor and NO
     * Authenticator. This breaks the DI cycle (the main client depends on the
     * authenticator) and keeps the expired bearer off the refresh request.
     */
    @Provides
    @Singleton
    @Named("authRefresh")
    fun provideRefreshOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideAuthRefreshApi(
        @Named("authRefresh") client: OkHttpClient,
        json: Json,
    ): AuthRefreshApi {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(AuthRefreshApi::class.java)
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json,
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideKumeaApi(retrofit: Retrofit): KumeaApi =
        retrofit.create(KumeaApi::class.java)
}
