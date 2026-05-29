package co.ke.kumea.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * DataStore-related Hilt bindings.
 *
 * TokenStore is currently self-provisioning via @Inject + @Singleton, so this
 * module is empty. Kept as an anchor for future additions (e.g. SyncSettingsStore,
 * UserPreferencesStore in 2.2+).
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule
