package co.ke.kumea.di

import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.NoteRepository
import co.ke.kumea.data.sync.SyncableRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt multibindings for the sync abstraction (Ticket 2.2).
 *
 * Each repository that implements SyncableRepository is bound into a
 * Set<SyncableRepository> that SyncWorker injects. Declaration order is
 * farm → field → note so the Set iteration order matches the FK dependency
 * order when iterating (LinkedHashSet preserves declaration order).
 * Add new repos here for each new syncable entity (e.g. Weather in 2.4).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @IntoSet
    abstract fun bindFarmSyncable(repo: FarmRepository): SyncableRepository

    @Binds
    @IntoSet
    abstract fun bindFieldSyncable(repo: FieldRepository): SyncableRepository

    @Binds
    @IntoSet
    abstract fun bindNoteSyncable(repo: NoteRepository): SyncableRepository
}
