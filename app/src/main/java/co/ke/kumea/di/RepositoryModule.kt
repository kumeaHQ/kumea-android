package co.ke.kumea.di

import co.ke.kumea.data.repository.AgentRepository
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.NoteRepository
import co.ke.kumea.data.repository.OrderRepository
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
 * agent → farm → field → note → order so the Set iteration order matches the FK
 * dependency order when iterating (LinkedHashSet preserves declaration order).
 * Agent leads because Farm.referrerAgentId attributes to an Agent, so the agent
 * must reach the server before a farmer registered with it as referrer; Order
 * trails because Order.farmerId reads from Farm and Order.agentCode resolves to
 * an Agent, so both parents must reach the server first (P1-T5).
 *
 * Iteration order is belt-and-braces, not load-bearing: each repository's
 * pushPending() defers a row whose FK parent isn't on the server yet and retries
 * next cycle (see OrderRepository / FarmRepository). Add new repos here for each
 * new syncable entity.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @IntoSet
    abstract fun bindAgentSyncable(repo: AgentRepository): SyncableRepository

    @Binds
    @IntoSet
    abstract fun bindFarmSyncable(repo: FarmRepository): SyncableRepository

    @Binds
    @IntoSet
    abstract fun bindFieldSyncable(repo: FieldRepository): SyncableRepository

    @Binds
    @IntoSet
    abstract fun bindNoteSyncable(repo: NoteRepository): SyncableRepository

    // Order trails note: Order.farmerId → Farm and Order.agentCode → Agent, so
    // both must sync first. OrderRepository.pushPending() defers an order whose
    // farmer or selling agent isn't on the server yet (P1-T5).
    @Binds
    @IntoSet
    abstract fun bindOrderSyncable(repo: OrderRepository): SyncableRepository
}
