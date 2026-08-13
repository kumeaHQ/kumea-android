package co.ke.kumea.di

import co.ke.kumea.data.repository.AgentRepository
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.HarvestRepository
import co.ke.kumea.data.repository.KumeaNReceivedRepository
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
 * agent → farm → field → harvest → note → order → kumeaNReceived, so the Set's
 * iteration order matches the FK dependency order (LinkedHashSet preserves
 * declaration order).
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

    // KWAP-03 §7 — the Kumea N handover shim. Bound once the `kumea-n-received`
    // routes were live; before that it would have pushed at a 404, which is not
    // terminal here and would have parked a row at the head of the offline
    // queue for ever.
    //
    // Declared last, after order, and that placement is only cosmetic: it needs
    // Farm to exist server-side (KumeaNReceived.farmId → Farm), and what
    // actually guarantees that is the 404-on-missing-farm the service returns
    // plus the row staying pending, not this line's position.
    //
    // ITS pullSince() RUNS ON EVERY DEVICE, whatever persona is signed in —
    // SyncWorker iterates this whole set in one try block, so a repository that
    // throws takes the entire cycle down with it. That is exactly why the
    // server's GET is scoped by farm visibility rather than gated on role: a
    // farmer-persona handset must get 200 and an empty list here, not a 403.
    @Binds
    @IntoSet
    abstract fun bindKumeaNReceivedSyncable(repo: KumeaNReceivedRepository): SyncableRepository
}
