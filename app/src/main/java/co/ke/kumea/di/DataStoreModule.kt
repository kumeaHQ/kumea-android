package co.ke.kumea.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Qualifier for the auth-scoped DataStore (access token, refresh token, user id). */
const val AUTH_DATASTORE = "authDataStore"

/**
 * DataStore-related Hilt bindings.
 *
 * The auth DataStore delegate lives here rather than inside TokenStore so that
 * TokenStore takes a plain DataStore<Preferences> and can be constructed on the
 * JVM — the TokenAuthenticator tests drive the real TokenStore, not a mirror of
 * it. The delegate name is unchanged ("auth"), so this reads the same
 * `auth.preferences_pb` file as before: no migration, no logged-out users.
 */
private const val AUTH_DATASTORE_NAME = "auth"
private val Context.authDataStore by preferencesDataStore(name = AUTH_DATASTORE_NAME)

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    @Named(AUTH_DATASTORE)
    fun provideAuthDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.authDataStore
}
