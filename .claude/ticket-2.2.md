# Ticket 2.2 — Extract Sync Abstraction

**Repo:** `kumea-android` (at `/Users/kumea/Desktop/kumea-android`)
**Base:** `main` branch (3eb8b89 — Ticket 2.1 shipped)
**Depends on:** 2.1 ✅ (costCategory stable on Note), 2.0 ✅ (silent-catch sweep)

---

## Goal

FarmSyncWorker, FieldSyncWorker, and NoteSyncWorker are mechanically identical — the only difference is which repository they call. The pushPending()/pullSince() contract is replicated across all three repositories with zero divergence. With three entities proven on real device against production, we have the evidence to extract a generic sync worker without guessing.

**After this ticket:** one `SyncWorker` takes a Hilt-injected `Set<SyncableRepository>` and runs all three repos in dependency order. Adding a fourth entity (Weather in 2.4) is a one-line interface declaration — no new worker class.

---

## The Pattern (confirmed across Farm/Field/Note)

Every sync worker is:

```kotlin
@HiltWorker
class XxxSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val xxxRepository: XxxRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        xxxRepository.pushPending()
        xxxRepository.pullSince()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
```

And every repository exposes:
```kotlin
suspend fun pushPending()   // push local → server
suspend fun pullSince()     // pull server → local
```

---

## What to Build

### 1. `SyncableRepository` interface

```kotlin
// data/sync/SyncableRepository.kt
package co.ke.kumea.data.sync

interface SyncableRepository {
    /** Push local changes to server. Throws on network failure — caller retries. */
    suspend fun pushPending()

    /** Pull server changes since last sync. Throws on network failure. */
    suspend fun pullSince()
}
```

### 2. Make each repository implement it

Just add `: SyncableRepository` to the class declaration — the methods already exist and match:

```kotlin
@Singleton
class FarmRepository @Inject constructor(...) : SyncableRepository { ... }
class FieldRepository @Inject constructor(...) : SyncableRepository { ... }
class NoteRepository @Inject constructor(...) : SyncableRepository { ... }
```

No body changes. The `pushPending()` and `pullSince()` signatures already match.

### 3. Hilt multibindings

Each DI module that provides a repository also binds it into a `Set<SyncableRepository>`:

```kotlin
// In the @Module that provides FarmRepository:
@Binds @IntoSet
abstract fun bindFarmSyncable(repo: FarmRepository): SyncableRepository

// In the @Module that provides FieldRepository:
@Binds @IntoSet
abstract fun bindFieldSyncable(repo: FieldRepository): SyncableRepository

// In the @Module that provides NoteRepository:
@Binds @IntoSet
abstract fun bindNoteSyncable(repo: NoteRepository): SyncableRepository
```

Which DI modules provide these repositories? Check `DatabaseModule.kt`, `NetworkModule.kt` — the repos have `@Singleton @Inject constructor(...)` so Hilt may already create them without explicit module bindings. If repos are provided by constructor injection only (no `@Provides` or `@Binds` in a module), add a new `RepositoryModule.kt` with the three `@Binds @IntoSet` declarations.

### 4. Single `SyncWorker`

```kotlin
// data/sync/SyncWorker.kt
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repositories: Set<@JvmSuppressWildcards SyncableRepository>,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        // Dependency order: farms → fields → notes. The Set iteration order is
        // the Hilt binding order, which is the @IntoSet declaration order.
        // Add explicit ordering if needed (or just run all — the repos handle
        // their own FK constraints via the pullSince guards already in place).
        for (repo in repositories) {
            repo.pushPending()
            repo.pullSince()
        }
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
```

**Critical note on ordering:** Notes pullSince() already guards against orphaned FKs (the FieldRepository guard added in 3.2). So running repos in any order is safe for pull. For push: pushPending pushes local writes — there's no FK risk there because all entities are created with valid parent FKs already. So Set iteration order is fine. If you want guaranteed ordering anyway, inject a `List` instead of `Set` and use `@IntoSet` → manual sort by priority, or add a `val priority: Int` to the interface.

### 5. Remove concrete workers

Delete:
```
data/sync/FarmSyncWorker.kt
data/sync/FieldSyncWorker.kt
data/sync/NoteSyncWorker.kt
```

Grepped for any remaining references to these class names (tests, ViewModels, etc.) and update them to reference `SyncWorker` instead.

### 6. Ordering safety (belt-and-braces)

The `pullSince()` guard in FieldRepository and NoteRepository prevents orphan FK violations regardless of sync order. But set iteration order in Kotlin (LinkedHashSet from Hilt) preserves declaration order, and the multibindings are declared in the module in farm → field → note order. This is deterministic, but document it explicitly in a `syncOrder` comment rather than relying on luck.

---

## Files to Create

```
app/src/main/java/co/ke/kumea/data/sync/SyncableRepository.kt
app/src/main/java/co/ke/kumea/data/sync/SyncWorker.kt
app/src/main/java/co/ke/kumea/di/RepositoryModule.kt          # if needed
```

## Files to Modify

```
app/src/main/java/co/ke/kumea/data/repository/FarmRepository.kt   # add : SyncableRepository
app/src/main/java/co/ke/kumea/data/repository/FieldRepository.kt  # add : SyncableRepository
app/src/main/java/co/ke/kumea/data/repository/NoteRepository.kt   # add : SyncableRepository
app/src/main/java/co/ke/kumea/di/DatabaseModule.kt               # add @Binds @IntoSet (if repos bound here)
```

## Files to Delete

```
app/src/main/java/co/ke/kumea/data/sync/FarmSyncWorker.kt
app/src/main/java/co/ke/kumea/data/sync/FieldSyncWorker.kt
app/src/main/java/co/ke/kumea/data/sync/NoteSyncWorker.kt
```

## Do Not Touch

- Repository bodies (pushPending/pullSince implementations — they already work)
- Entity classes, DAOs, DTOs
- ViewModels, Screens, navigation
- API (kumea-api) — no changes needed
- `build.gradle.kts` — no dependency changes
- `KumeaApplication.kt` (unless it references concrete workers — check and update)

---

## Acceptance Criteria

1. **`./gradlew assembleDebug` compiles with zero errors**
2. **All existing tests pass** — unit tests for FarmSyncTest, NoteSyncTest, LedgerRepositoryTest (21 tests)
3. **Hilt graph compiles** — `Set<SyncableRepository>` resolves with all three repos
4. **No new lint warnings** introduced
5. **Concrete worker references swept** — grep for `FarmSyncWorker`, `FieldSyncWorker`, `NoteSyncWorker` returns zero results
6. **No generics on entities** — `SyncableRepository` is an interface on repositories, NOT a `SyncableEntity<T>` base class on entities. Do not touch entity classes.

---

## Verification (AC #7)

7. **Real-device test on Solana against Railway:**
   - Open Kumea → auth survives → Farm List loads
   - Create a note offline → pull to refresh → note syncs to server
   - Note appears in list, P&L updates correctly
   - No crashes, no regressions in existing flows

---

## Architecture Rule (carried forward)

- No generics in entity classes. The abstraction is at the **repository/worker** level only.
- `SyncableRepository` is a behavioural interface (pushPending + pullSince), not a data contract.
- Repository bodies do not change — this is an interface extraction, not a refactor of sync logic.
