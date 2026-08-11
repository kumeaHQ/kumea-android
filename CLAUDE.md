# CLAUDE.md — kumea-android

Offline-first farm management app for Kenyan farmers. Android-only (Kotlin +
Jetpack Compose).

## Stack

| Layer | Choice | Version |
|-------|--------|---------|
| Language | Kotlin | 2.0.0 |
| Build | AGP (Android Gradle Plugin) | 8.13.2 |
| UI | Jetpack Compose + Material 3 | BOM 2024.09.00 |
| DI | Hilt | 2.51.1 |
| Local DB | Room | 2.6.1 |
| Preferences | DataStore Preferences | 1.1.1 |
| HTTP | Retrofit + OkHttp | 2.11.0 / 4.12.0 |
| Serialization | kotlinx.serialization | 1.7.3 |
| Date/Time | kotlinx-datetime | 0.6.1 |
| Background | WorkManager | 2.9.0 |
| Navigation | Navigation Compose | 2.8.1 |
| Target/Compile SDK | 35 | — |
| Min SDK | 24 | — |
| JVM Target | 17 | — |

**Critical:** Retrofit (NOT Ktor). kotlinx-serialization (NOT Gson, NOT Moshi).
kotlinx-datetime (NOT java.util.Date). The Sprint 0 retro explicitly records
these choices.

## Build & Run

```bash
./gradlew assembleDebug        # build debug APK
./gradlew installDebug          # install on connected device
./gradlew test                  # unit tests
./gradlew lint                  # lint (requires network — JVM 17 must be configured)

# APK output: app/build/outputs/apk/debug/app-debug.apk
# NOT the intermediate APK at intermediates/apk/debug/ (unsigned)
```

## Project Structure

108 Kotlin files under `app/src/main/java/co/ke/kumea/` (verified 9 Aug 2026).

```
app/src/main/java/co/ke/kumea/
  MainActivity.kt              # Entry point, splash → nav graph
  KumeaApplication.kt          # Hilt app + WorkManager config; arms SyncScheduler
  data/
    auth/TokenStore.kt         # DataStore: access_token, refresh_token
    local/                     # Room entities + DAOs: Agent, Farm, Field, Harvest,
                               #   Note, Order, SyncConflict + KumeaDatabase (v11)
    remote/                    # KumeaApi, AuthRefreshApi, ApiErrors
      dto/                     # 21 request/response DTOs
      interceptor/             # AuthInterceptor, TokenAuthenticator
    repository/                # Agent, Auth, Commission, Farm, Field, Harvest,
                               #   Health, Ledger, Note, Order, Persona
    sync/                      # SyncableRepository, SyncWorker, SyncScheduler,
                               #   SyncNotifier, PushReport  (see "Sync architecture")
  di/                          # Hilt modules: Network, Database, DataStore, Repository
  domain/model/Persona.kt      # FARMER / VILLAGE_AGENT / EXTENSION_OFFICER
  util/                        # AgentCode, Money, Quantity
  ui/
    navigation/                # KumeaNavHost (contains `object Routes`), StartupViewModel
    common/                    # KumeaLockup, PaperCard, PullToRefresh, SyncBadge
    theme/                     # Color, Theme, Type
    screen/agent/              # VillageAgentHome
    screen/auth/               # PhoneEntry, OtpEntry, PinSetup, PinEntry
    screen/distribution/       # DistributionDemo
    screen/farm/               # FarmList, FarmCreate, FarmHome, FarmDetailViewModel
    screen/field/              # PlantingDate, HarvestWizard
    screen/home/               # Landing (persona dispatcher)
    screen/ledger/             # Ledger
    screen/note/               # NoteCreate, NoteDetailViewModel
    screen/officer/            # OfficerHome
    screen/order/              # OrderCreate
```

Tests live in `app/src/test/java/co/ke/kumea/` (11 files: sync, repository,
persona, money, token store). There is no `androidTest` source set.

## Architecture Decisions

### Offline-first sync (the critical pattern)

1. User creates entity offline → Room insert with `pendingSync=true`, client-generated UUID
2. Entity appears in UI immediately (no spinner)
3. WorkManager detects connectivity → pushPending() then pullSince()
4. Server: idempotent on UUID, conflict detection via `updatedAt` comparison
5. 409 → server wins, local discarded, conflict logged to `audit_sync_conflicts`

### Auth flow

- **Registration:** phone → OTP → verify → PIN setup → tokens saved → Landing
- **Login (existing):** phone → PIN → tokens saved → Landing
- **Returning (token saved):** GET /auth/me → 200 → Landing (skip auth screens)
- **Returning (token expired):** GET /auth/me → 401 → clear session → PhoneEntry

All authenticated entry points land on `Routes.LANDING`, never directly on a
home screen. `LandingScreen` resolves the persona via `PersonaRepository` and
dispatches: `FARMER → FARM_LIST`, `VILLAGE_AGENT → AGENT_HOME`,
`EXTENSION_OFFICER → OFFICER_HOME`.

### Personas (P1-T7)

`domain/model/Persona.kt` is derived **only** from the signed-in user's linked
`AgentEntity.role` — never chosen by hand. No linked agent → `FARMER`;
`extension_officer` → `EXTENSION_OFFICER`; every other role → `VILLAGE_AGENT`.

**The officer allow-list is expressed in the type system, and must stay there.**
`AgentEntity` deliberately has no `commissionRuleId` field — the device cannot
represent commission on an agent. `Persona.allowsEarnings` is the single
predicate gating whether the earnings surface is even instantiated; for an
officer it is structurally absent from the view hierarchy, not hidden behind a
flag. Do not "simplify" either of these into a boolean check at render time.

### Token strategy

- Access token: JWT, short-lived, stored in DataStore
- Refresh token: opaque, rotated on use
- `AuthInterceptor` attaches `Authorization: Bearer <token>` to all requests
- `runBlocking` in interceptor is intentional — OkHttp interceptors are synchronous

### Critical rule — AC22 fix (May 29, 2026)

**Only HTTP 401 clears the session.** Network errors, timeouts, 5xx must NEVER
clear tokens. `AuthRepository.isAuthenticated()` three-branch pattern:
```
200 → authenticated
401 → clear session → login
any other error → proceed with cached state (return true)
```

If you find `catch (Exception) { clearSession() }` anywhere, flag it immediately.

### Room migration discipline — hardest constraint in the project

**The DB is at version 11. Destructive fallback has been permanently removed
(`di/DatabaseModule.kt`). Every schema change from v10 onward MUST ship a
hand-written `Migration`.**

`fallbackToDestructiveMigration()` is not a debugging convenience here — it wipes
a farmer's records. A missing migration now crashes on open instead, which is the
*desired* failure mode: loud, immediate, at the gate, never silent data loss.

**Correction (11 Aug 2026):** earlier versions of this file said *"real user data
exists on Mulu's phone"*, and TICKET-KWAP-01 repeated it. **It is not true.** All
three devices in use are test handsets and BWAP S1 data was never entered into the
app. There is no irreplaceable data on any device *today* — a bad migration costs
a reinstall, not a season. The discipline below still stands unchanged: it is
protecting the data that arrives the moment a WAO registers a real farmer. Do not
use this correction to argue for destructive fallback.

Rules:

- Bumping `version` in `KumeaDatabase.kt` without adding a `Migration` to
  `DatabaseModule.addMigrations(...)` is a bug, not a shortcut. Never re-add
  destructive fallback to "get the build green".
- Migration DDL must match Room's expected schema **exactly** — column affinity,
  nullability, indices, FK clauses — or Room throws on open. Diff against the
  exported schema, don't hand-guess.
- `exportSchema = true`; JSON schemas are committed at `app/schemas/co.ke.kumea.data.local.KumeaDatabase/`
  (`1.json` … `11.json`). The new `N.json` is part of the change.
- Worked examples, both in `di/DatabaseModule.kt`: `MIGRATION_9_10` (Build-2) —
  `ALTER TABLE fields ADD COLUMN plantedAt TEXT` + `CREATE TABLE harvests` + its
  index; `MIGRATION_10_11` (KWAP-01 step 1) — two nullable `TEXT` columns added
  to `farms`, written against `10.json` and validated against Room's `11.json`.

### Sync conflict resolution

- Last-write-wins, server-authoritative
- Client sends `updatedAt` in write requests
- Server compares: stale `updatedAt` → 409
- Rejected payload saved to `audit_sync_conflicts`

### Sync architecture (data/sync/) — supersedes the old per-entity workers

The concrete `FarmSyncWorker` / `FieldSyncWorker` / `NoteSyncWorker` are **gone**
(Ticket 2.2, done). `data/sync/` now contains exactly five files:

| File | Role |
|------|------|
| `SyncableRepository.kt` | Interface: `pushPending(): PushReport` + `pullSince(): Int` |
| `SyncWorker.kt` | The single `@HiltWorker`. Injects `Set<SyncableRepository>`, runs push-then-pull over all of them |
| `SyncScheduler.kt` | Enqueues the work (Ticket 2.3, done) |
| `SyncNotifier.kt` | Success/error notifications |
| `PushReport.kt` | Per-repo found/attempted/succeeded/failed/deferred breakdown |

**Adding a syncable entity is one `@Binds @IntoSet` in `di/RepositoryModule.kt`
plus `: SyncableRepository` on the repository. Do not write a new Worker class.**

Binding order in `RepositoryModule` is agent → farm → field → harvest → note →
order, matching FK dependency (Hilt's `LinkedHashSet` preserves it). Order is
belt-and-braces only: each repository's `pushPending()` defers a row whose FK
parent isn't on the server yet and retries next cycle. Correctness lives in those
guards, not in the iteration order.

`SyncWorker` is the *background* path only — pull-to-refresh calls repositories
directly. So the worker owns background-only behaviour: notify on success only
when ≥1 row actually moved, log every failure at error level, and notify on
sustained failure only (after `MAX_RETRIES = 3`), never on one transient 2G
hiccup.

### Sync scheduling (Ticket 2.3, done)

`SyncScheduler.schedule()` is called from `KumeaApplication.onCreate()`. Two
triggers, same idempotent worker, both `KEEP` so cold starts don't stack jobs or
reset the clock:

1. **Connectivity-triggered** — one-time request constrained on `CONNECTED`
2. **Periodic safety net** — every 6 hours, constrained on `CONNECTED`

6 hours, not 15 minutes: farmer data changes in bursts, and a short interval
burns battery for nothing. The app also requests battery-optimisation exemption
on first launch — aggressive OEMs (Samsung) kill WorkManager jobs otherwise.

## Units & Conventions

| Concept | Type | Notes |
|---------|------|-------|
| Money | `BigInt` (cents) | KES, divide by 100 for display |
| Area | `Decimal(10, 4)` | Acres to 4 decimal places |
| Percentage | `Decimal(5, 4)` | 0.0000–1.0000, multiply by 100 for display |
| Timestamps | UTC in DB, EAT (UTC+3) display | Use `kotlinx-datetime` |
| IDs | UUID v4 | Client-generated for mutable entities |
| Phone numbers | E.164 `String` | Normalized to `+254…` |
| Soft deletes | `deletedAt` column | Queries filter `WHERE deletedAt IS NULL` |


## Build Tool Version Discipline

**Do NOT upgrade build-tool versions (Gradle, AGP, Kotlin, KSP) or add/bump
dependencies without an explicit ticket authorizing it.** The versions in
`gradle/libs.versions.toml` are load-bearing. In particular:

- **Hilt 2.51.1 requires AGP < 9.x** — bumping AGP past 8.x breaks the DI layer.
- Claude Code has been observed reaching for newer versions unprompted (Gradle
  8.13→9.4.1, AGP→9.2.1, Kotlin→2.2.10, KSP→2.3.2) — these would have broken
  Hilt and were reverted.
- If you believe an upgrade is needed, **stop and flag it** rather than doing it.
  File a task chip; the team decides.

This is a confirmed live risk (May 30, 2026 session). Version drift has broken
the build before.

## Naming Conventions

- Package: `co.ke.kumea`
- ViewModels: `*ViewModel` suffix, `@HiltViewModel`
- Repositories: `*Repository` suffix, `@Singleton`
- DTOs in `data/remote/dto/` package
- Screen composables in `ui/screen/<feature>/`
- One file per screen + one per ViewModel

## Git

- Remote: `origin https://github.com/kumeaHQ/kumea-android.git`
- `suppressUnsupportedCompileSdk=35` in `gradle.properties`
- Configuration cache: **disabled** (Hilt + AGP 8.5 rough edges). Re-enable once Sprint 1 is green.

## Active Ticket

**TICKET-KWAP-01 — Farmer registration by officers and agents**
Full spec: `/Users/kumea/Desktop/Kumea-Claude/TICKET-KWAP-01-farmer-registration.md`
(written 9 Aug 2026; that workspace holds a read-only copy of this repo)

Blocks the entire KWAP research track — 20 sub-counties, 39 WAOs, ~395 farmers.

**Read the spec before starting. This is a data-model change, not a screen.**
`FarmEntity` had no owner field — no `userId`, no `farmerId`, no `ownerId` — so a
farm belongs implicitly to whoever holds the JWT, and the server enforces that via
`assertFarmOwned`. An Order's `farmerId` *is* a Farm id. In this model the farm is
the farmer, so registering a farmer on someone's behalf attaches that farm to the
registrar's own account.

Sequence (1–3 are the real work):

1. ✅ **Done** (commit `ad9177c`) — `farmerUserId` + `registeredByAgentId` on
   `FarmEntity`, `MIGRATION_10_11` written and wired, DB at v11, `11.json` exported
2. API on-behalf creation, guarded on agent role + ward ← **next**
3. Ward-scoped read endpoint (the officer screen's honest gap notice is waiting on it)
4. Officer farmer-create + ward directory
5. Agent farmer-create + own roster
6. Bulk paste-a-list intake

Steps 2 and 3 are server work: `kumea-api` lives at `~/Desktop/_old-repos/kumea-api`
— the folder name reads like an archive but it is a live git repo with its own
`CLAUDE.md` and a Railway deploy.

**Three traps in step 2, all from `kumea-api/HANDOVER.md`:**

- **Do not relax `assertFarmOwned` in place — add a sibling.** That one function
  does two jobs: it stops you editing someone else's farm, *and* it stops an agent
  recording an order against a farm that isn't theirs. The second sits directly on
  the commission path (`Order.agentId` is what accrual reads). Widening it in place
  silently widens order attribution. Add `assertMayCreateFarmFor` (or similar) and
  check every call site first.
- **Reject with 403, not 400.** The client retries non-2xx forever unless the
  repository handles the code explicitly; 403 is terminal, 400 is not. A 400 on the
  ward/role rejection poisons the offline sync queue permanently.
- **The e2e suite cannot see the database.** The specs run against a hand-written
  `PrismaStub`, so they prove nothing about CHECK constraints, triggers, FKs or
  cascades — `orders_commercial_attribution_guard` is a live trigger and
  `Order.farmerId` *is* a Farm id. Read the migrations, not the tests.

**Client follow-up, timed to step 2:** `FarmRepository.pullSince()`
([FarmRepository.kt:177](app/src/main/java/co/ke/kumea/data/repository/FarmRepository.kt:177))
rebuilds `FarmEntity` field by field and does not yet map the two new columns —
correct for now, since the DTO doesn't carry them. Map them **in the same commit
the DTO gains them**. Later, and every pull silently nulls them locally, which
breaks the officer directory (a local Room read) while leaving the server correct.

**Live mis-attribution, unfixed:** `KumeaNavHost.kt:181` routes the agent home's
"New farmer" button to `Routes.FARM_CREATE`, commented `INTERIM`. Farmers an agent
registers today become farms owned by the agent. Step 1 added the fields; nothing
sets them yet. Closed by steps 2 + 5.

⚠️ **The trap: do NOT set `referrerAgentId` when an officer registers a farmer.**
`registeredByAgentId` records who typed it in; `referrerAgentId` records who gets
commission. The commission engine has been live and accruing since 1 June — a wrong
attribution makes ~395 research farmers accrue against agents who did nothing. That
is wrong in the ledger, not merely on screen. The field is nullable in both entity
and DTO. Leave it null.

Do not rebuild what exists: `OfficerHomeScreen` + ViewModel, `PersonaRepository`
resolution, the officer/agent split in `Persona`, `FarmCreateScreen`.

**Done, do not re-do:**

- **Ticket 2.2 — Extract Sync Abstraction.** `SyncableRepository` + single `SyncWorker`
  with Hilt multibindings shipped; the three concrete workers are deleted.
- **Ticket 2.3 — Background Sync.** `SyncScheduler` shipped and armed from
  `KumeaApplication.onCreate()`. See "Sync scheduling" above.

## Other docs in this repo

`BUILD-STATUS.md`: the **kumea-android** section was reconciled against the code on
11 Aug 2026 and can be trusted. Its **kumea-api** section is still as of 26 Jun and
has not been re-verified — check it against `kumea-api` before relying on it.

`FARMER-FLOW-HANDOFF.md`, `KM-CONTEXT.md`, `KUMEA-STRATEGY-CANONICAL.md` are
unverified. Treat them with suspicion and check against code before trusting them.

The 26 Jun version of `BUILD-STATUS.md` said the officer surface was "Not built"
when `OfficerHomeScreen` had existed for weeks, and that caused a wrong answer in
the week of 4 Aug. When you find a doc that disagrees with the code, fix the doc in
the same session — that is how this one got expensive.

Also outstanding from the KWAP spec: the cost-category enum is still `BIOFIX`,
client-first; the product is Kumea N. RB must confirm/add the server-side value
before ship. Rename before it hardens.
