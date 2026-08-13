package co.ke.kumea.di

import co.ke.kumea.data.repository.AgentRepository
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.HarvestRepository
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

    // Harvest follows field: Harvest.fieldId → Field, so the parent field must
    // reach the server first. FK guard in HarvestRepository.pullSince is the
    // load-bearing correctness; order is belt-and-braces (Build-2).
    @Binds
    @IntoSet
    abstract fun bindHarvestSyncable(repo: HarvestRepository): SyncableRepository

    @Binds
    @IntoSet
    abstract fun bindNoteSyncable(repo: NoteRepository): SyncableRepository

    // Order trails note: Order.farmerId → Farm and Order.agentCode → Agent, so
    // both must sync first. OrderRepository.pushPending() defers an order whose
    // farmer or selling agent isn't on the server yet (P1-T5).
    @Binds
    @IntoSet
    abstract fun bindOrderSyncable(repo: OrderRepository): SyncableRepository

    // ── DELIBERATELY NOT BOUND YET: KumeaNReceivedRepository ────────────────
    //
    // It implements SyncableRepository and its push/pull are written, but the
    // `kumea-n-received` routes ship in the KWAP-03 kumea-api patch and are not
    // deployed. Binding it now would push at a route that answers 404 — and 404
    // is not terminal in any repository here, so the row would sit at the head
    // of the offline queue and be re-sent on every sync cycle for ever.
    //
    // That failure has already happened three times on this codebase from three
    // different directions: an unwhitelisted DTO key, a client-invented enum
    // value, and a field named differently from the server's. All three were a
    // client that knew something the server did not. This would be the fourth.
    //
    // WHEN THE SERVER PATCH IS DEPLOYED, uncomment. It belongs after farm —
    // KumeaNReceived.farmId → Farm — and the FK guard, not this order, is what
    // makes that correct.
    //
    // @Binds
    // @IntoSet
    // abstract fun bindKumeaNReceivedSyncable(repo: KumeaNReceivedRepository): SyncableRepository
}
