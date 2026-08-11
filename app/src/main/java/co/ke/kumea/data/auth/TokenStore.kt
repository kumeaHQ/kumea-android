package co.ke.kumea.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.ke.kumea.di.AUTH_DATASTORE
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The backing DataStore is injected (see [co.ke.kumea.di.DataStoreModule]) rather
 * than built from a Context delegate, so this class is constructible on the JVM.
 * The TokenAuthenticator tests depend on that — they exercise the real store.
 */
@Singleton
class TokenStore @Inject constructor(
    @Named(AUTH_DATASTORE) private val dataStore: DataStore<Preferences>,
) {
    val tokenFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[TOKEN_KEY]
    }

    val refreshTokenFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[REFRESH_TOKEN_KEY]
    }

    /**
     * The signed-in user's id (UUID). Persisted at login/register/startup so the
     * persona resolver (P1-T7) can match this user against the channel-wide agent
     * roster (Agent.linkedUserId) — including offline, from the Room cache.
     */
    val userIdFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[USER_ID_KEY]
    }

    suspend fun saveToken(token: String) {
        dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
        }
    }

    /** Persist the signed-in user's id (idempotent; overwrites). */
    suspend fun saveUserId(userId: String) {
        dataStore.edit { prefs ->
            prefs[USER_ID_KEY] = userId
        }
    }

    /** Persist both tokens together after a successful register/login. */
    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = accessToken
            prefs[REFRESH_TOKEN_KEY] = refreshToken
        }
    }

    suspend fun clearToken() {
        dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
        }
    }

    /** Clear access + refresh tokens AND the cached user id (logout / invalid session). */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
            prefs.remove(REFRESH_TOKEN_KEY)
            prefs.remove(USER_ID_KEY)
        }
    }

    companion object {
        private val TOKEN_KEY = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
    }
}
