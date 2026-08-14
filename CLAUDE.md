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

133 Kotlin files under `app/src/main/java/co/ke/kumea/` (verified 14 Aug 2026).

```
app/src/main/java/co/ke/kumea/
  MainActivity.kt              # Entry point, splash → nav graph
  KumeaApplication.kt          # Hilt app + WorkManager config; arms SyncScheduler
  data/
    auth/TokenStore.kt         # DataStore: access_token, refresh_token
    local/                     # Room entities + DAOs: Agent, Farm, FarmCrop, Field,
                               #   Harvest, KumeaNReceived, Note, Order, Planting,
                               #   SyncConflict
                               #   + KumeaDatabase (v14)
    location/LocationCapturer.kt # Platform LocationManager (NOT play-services)
    remote/                    # KumeaApi, AuthRefreshApi, ApiErrors
      dto/                     # 21 request/response DTOs
      interceptor/             # AuthInterceptor, TokenAuthenticator
    repository/                # Agent, Auth, Commission, Farm, Field, Harvest,
                               #   Health, Ledger, Note, Order, Persona, Planting
    sync/                      # SyncableRepository, SyncWorker, SyncScheduler,
                               #   SyncNotifier, PushReport  (see "Sync architecture")
  di/                          # Hilt modules: Network, Database, DataStore, Repository
  domain/model/                # Persona.kt (FARMER / VILLAGE_AGENT / EXTENSION_OFFICER)
                               #   Crop.kt (the crop catalogue, grouped)
  util/                        # AgentCode, Area, Money, Phone, Quantity, YieldConversion
  ui/
    navigation/                # KumeaNavHost (contains `object Routes`), StartupViewModel
    common/                    # KumeaLockup, PaperCard, PullToRefresh, SyncBadge
    theme/                     # Color, Theme, Type
    screen/agent/              # VillageAgentHome
    screen/auth/               # PhoneEntry, OtpEntry, PinSetup, PinEntry
    screen/farm/               # FarmList, FarmCreate, FarmHome, FarmDetailViewModel
    screen/field/              # Planting (flow + VM), HarvestWizard
    screen/home/               # Landing (persona dispatcher)
    screen/ledger/             # Ledger
    screen/note/               # NoteCreate, NoteDetailViewModel
    screen/officer/            # OfficerHome, RegisterFarmer, FarmerDirectory
    screen/order/              # OrderCreate
```

Tests live in `app/src/test/java/co/ke/kumea/` (24 files, 268 tests: sync,
repository, persona, money, phone, token store, schema/enum/wire contracts,
location capture, farm profile). There is
no `androidTest` source set — which is why `SchemaMigrationTest` guards the
migration from the JVM instead of with Room's `MigrationTestHelper`.

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

**The DB is at version 14. Destructive fallback has been permanently removed
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
  (`1.json` … `13.json`). The new `N.json` is part of the change, and
  `SchemaMigrationTest` diffs consecutive versions so a forgotten column fails
  at JVM speed rather than on a farmer's phone.
- Worked examples, both in `di/DatabaseModule.kt`: `MIGRATION_9_10` (Build-2) —
  `ALTER TABLE fields ADD COLUMN plantedAt TEXT` + `CREATE TABLE harvests` + its
  index; `MIGRATION_10_11` (KWAP-01 step 1) — two nullable `TEXT` columns added
  to `farms`, written against `10.json` and validated against Room's `11.json`;
  `MIGRATION_11_12` (KWAP-01 step 4) — `farmerName` + `farmerPhone` on `farms`,
  **plus a data fix**: `UPDATE notes SET costCategory='OTHER' WHERE
  costCategory='BIOFIX'`. That second statement is not optional — Room stores an
  enum as its name, so dropping `BIOFIX` from the enum without rewriting the rows
  would throw on read and take the notes query down. `MIGRATION_12_13` (KWAP-03)
  is the largest so far — 9 `farms` columns, `fields.trialRole`, 3 `harvests`
  columns, two new tables — **plus a row rewrite**: existing harvests recorded in
  `kg` and `gorogoro` get their canonical kilograms, while ones recorded in
  `bags` are deliberately left at 0 with `conversionSource = 'unknown'`, because
  a bag is 50 or 90 kg and a guess there would be indistinguishable from data.
  `MIGRATION_13_14` (KWAP-03-V2) — the `plantings` table + its index, two
  nullable `TEXT` columns on `notes`, **plus a backfill**: every
  `fields.plantedAt` becomes a planting row, with a deterministic
  `'planting-' || fields.id` id so a re-run cannot double-insert. It moves zero
  rows on the current handsets and is written as though it matters anyway,
  because ~395 KWAP farms do not exist on any device yet. Seed weight and
  planted area backfill as **0, not from `farms.acres`** — nobody was asked, and
  a fabricated denominator in yield-per-acre is the same class of error as
  guessing a bag size.

### Retired in place, not dropped

A column this project stops using is left in the table and in the entity:
`farms.useGps`, and now `fields.plantedAt` + `fields.trialRole` (both superseded
by `plantings`). Dropping a column in SQLite means recreating the table, which on
a populated `fields` with two FK children is the riskiest thing a migration could
do for no user-visible gain. Stop writing it, stop reading it, say so in the
entity — and leave the column alone.

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
| Area | `Decimal(10, 4)` server-side | Acres to 4 dp. **On device: see below** |
| Percentage | `Decimal(5, 4)` | 0.0000–1.0000, multiply by 100 for display |
| Timestamps | UTC in DB, EAT (UTC+3) display | Use `kotlinx-datetime` |
| IDs | UUID v4 | Client-generated for mutable entities |
| Phone numbers | E.164 `String` | Normalized to `+254…` |
| Soft deletes | `deletedAt` column | Queries filter `WHERE deletedAt IS NULL` |

### Area on the device — three representations, one crossing

`Decimal(10, 4)` above describes the **server's** column. The device has never
stored a Decimal, and now holds three different shapes:

| Where | Type | Why |
|---|---|---|
| `farms.acres` | `Double?` (v9) | the farm's size as it was typed |
| `fields.acres` | `String` | preserves the farmer's exact decimal verbatim |
| `plantings.plantedAreaCenti` | `Long`, centi (v14) | it gets divided into a centi-Long |

**`util/Area.kt` is the only sanctioned `Double → centi-Long` crossing. Never
write `(acres * 100).toLong()`.** It rounds explicitly rather than truncating,
and it is the single place that decision is recorded.

**Planted area is deliberately 2 dp, not 4 (decided 14 Aug, before any row
existed).** 0.01 acre ≈ 40 m², already finer than a recalled answer to "how much
of your shamba did you plant?"; and yield per acre is
`harvests.qtyKgCenti ÷ plantedAreaCenti`, which stays integer end-to-end only if
both sides share a scale. Sub-centi area would need a measured input first, not a
wider column. Full reasoning in `Area.kt`.


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

**KWAP-03-V2 is built (14 Aug) and is the most recent work — see its section
below.** KWAP-01 step 5 (agent farmer-create + own roster) is still the next
unstarted item, and the sequence below is unchanged by it.

**TICKET-KWAP-01 — Farmer registration by officers and agents**
Full spec: `/Users/kumea/Desktop/Kumea-Claude/TICKET-KWAP-01-farmer-registration.md`
**Read `DECISIONS-11Aug-post-walkthrough.md` and `KWAP-STEP2-DECISIONS.md` too —
they supersede parts of the ticket.** That workspace holds a read-only copy of
this repo.

Blocks the KWAP research track — 20 sub-counties, 39 WAOs, ~395 farmers.

Sequence:

1. ✅ **Done** (`ad9177c`) — `farmerUserId` + `registeredByAgentId`, `MIGRATION_10_11`
2. ✅ **Done** (`kumea-api` `238032e`, deployed) — on-behalf creation, role + ward guarded
3. ✅ **Done** (`kumea-api` `3f48a7f`, deployed) — `GET /farms?registeredBy=me`
4. ✅ **Done** (12 Aug) — officer register + directory, DB v12, `farms.farmer_name` / `farmer_phone`
5. **Next** — agent farmer-create + own roster
6. Bulk paste-a-list intake — `kwap/KWAP-FARMER-REGISTER.xlsx` is the system of
   record until then

`kumea-api` lives at `~/Desktop/_old-repos/kumea-api` — the folder name reads
like an archive but it is a live git repo with its own `CLAUDE.md` and a Railway
deploy. Its e2e specs run against a hand-written `PrismaStub` and **cannot see
the database**, so they prove nothing about CHECK constraints, triggers or FKs.
Read the migrations, not the tests.

### What step 4 settled, and what it deliberately did not

**The person lives on the farm.** `farms.farmer_name` + `farms.farmer_phone`
(server migration `20260812120000_kwap01_step4_farmer_identity`, client v12).
`users.name` needs a User and KWAP farmers have none — creating them was deferred
in `KWAP-STEP2-DECISIONS.md` §2 — and `farms.name` is the *shamba's* name, which
Build-1 locked as distinct. With `farmerUserId` left null this season, the farm
row IS the farmer record, which is the model the ticket §2 already describes.

**The ward is derived, never stored and never typed.** It comes from
`registeredByAgentId` → `AgentEntity.ward`. There is still no `farms.ward`
(proposed in `KWAP-STEP2-DECISIONS` §1, deferred in §4) and adding one would only
create a second copy that can disagree.

**The directory is "farms I registered", not ward-scoped.** A ward-wide view
needs that column and its own authorisation question. The officer home says so
on the card rather than implying a bigger number.

**`referrerAgentId` stays null on every registration.** `createLocalForFarmer`
does not take it as a parameter, so it cannot be passed by mistake. Commission
accrual reads it and the engine is backdated to 1 June; a value there on ~395
free-product research farmers is money owed to agents who did nothing.

### Two live bugs step 4 fixed — both the same shape

The client retries every non-2xx except 403 and 409, so **any 400 is an infinite
retry loop with a poisoned row at the head of the offline queue**. Two shipped
that way:

- `FarmCreateRequest` sent `cropType`, `acres` and `useGps`, which the server's
  `CreateFarmDto` does not whitelist (`forbidNonWhitelisted: true`).
  kotlinx.serialization omits a property still holding its default, so a farm
  saved with only a name synced fine — and one with a crop chip or an acreage
  never synced again. Crop and acreage reach the server on the **Field**, which
  `FarmDetailViewModel` already creates. `FarmerRegistrationTest` pins the
  request's key set.
- `CostCategory.BIOFIX` was never in the server's enum. Fixed to `OTHER` with the
  display reading "Kumea N sachet"; `CostCategoryContractTest` pins the enum.

**The rule this leaves behind: never add a client enum value or DTO field the
server does not already accept.** Being early costs a poisoned queue, not a
missing label.

`FarmRepository` also gained the 403-terminal branch `AgentRepository` already
had — the on-behalf role and ward rejections are 403 precisely so it exists.

## TICKET-KWAP-03 — Farmer page (13 Aug, built)

Spec: `~/Desktop/Kumea-Claude/TICKET-KWAP-03-farmer-page.md`, with all eight
⚠️VERIFY items resolved in `VERIFY-RESULTS-KWAP-03.md`. **Client shipped
(`d42a785`); the server half is written and NOT deployed** — branch
`kwap-03/farm-profile` in `kumea-api`, commit `a457197`.

**Deploy the server before building a release APK.** Nine `farms` columns, three
`harvests` columns and `farm_crops` are device-only until it lands;
`FarmRepository.applyServerFarms` and `HarvestRepository.pullSince` carry them
forward from the local row so a pull cannot erase a baseline nobody can re-ask
for, and both spots say where the `local?.x` becomes `server.x`.

Four decisions taken during the build, each recorded where it applies:

1. **No `county`.** §4.1 wanted one beside `ward`, but nothing on either side
   holds a county — `AgentEntity` has `region` + `ward`, and `region` is free
   text that is county-shaped in the agent code (`EO-NANDI-041`) while
   `KUMEA-REGIONS-CANONICAL.md` defines it as one of seven regions. Needs a real
   county field on `Agent` first.
2. **`ward` IS now stored, reversing KWAP-01.** That deferral was about a *typed*
   copy; this one is stamped from the registering agent and there is no ward
   input anywhere. Grouping ~395 farms through the join would make every analysis
   depend on a roster that may have been edited since.
3. **Platform `LocationManager`, not `FusedLocationProviderClient`.** Fused means
   a new `play-services-location` dependency, which this file bars without a
   ticket. The platform API needs none and does everything §5.1 specifies.
4. **Yields are centi-`Long`, not §4.4's `Double`.** They are summed across ~395
   farms into one headline figure, and the neighbouring quantity columns in the
   same table are already centi.

`kumea_n_received` is fully written including push/pull and **is now bound**
into `Set<SyncableRepository>` (`958552e`), because its server patch deployed.
The pattern it established is still live, though: **`plantings` is written and
deliberately NOT bound** — see KWAP-03-V2 below.

**The ledger now has no entry point.** Removing the farmer page's money card
(§5.4) removed the only navigation into `Routes.LEDGER`. The route and
`LedgerScreen` are intact and deliberately left registered; giving the agent
surface its own link is agent-home work.

## TICKET-KWAP-03-V2 — Farmer page refinements (14 Aug, built)

Spec: `~/Desktop/Kumea-Claude/TICKET-KWAP-03-V2-farmer-page.md`. **Room v14.**

What the eight ⚠️VERIFY checks actually found, since two contradicted the ticket:

- **V1 — the harvest "tick with no record" was NOT a farmId/fieldId mismatch.**
  The tick and its date always came from one flow. `SeasonRecordCard` — the
  composable that renders the harvest — was **orphaned in the KWAP-03 rewrite and
  called from nowhere**, and the Shughuli feed lists `notes` only. So a farm with
  one harvest and no notes showed "Harvest ✓" above "no activity yet". Fixed by
  wiring the card back to the same `latestHarvest` flow the tick reads.
- **V8 — `trialRole` had already shipped** on `FieldEntity` in v13. It moves to
  `plantings`; the Field column is retired in place.
- V5 confirmed `farms.acres` is `Double?`. `fields.acres` is a `String`, so
  there are **three** area representations — `util/Area.kt` is the single
  sanctioned `Double → centi-Long` crossing. Do not write `(acres * 100).toLong()`.
- V2's backfill is zero-row on every device checked. V3 found no ACTIVITY row
  carrying money. V4 was pure removal (the picker already auto-selected).

**`plantings` is written, complete, and NOT bound into `Set<SyncableRepository>`.**
`kumea-api` has no Planting model, controller or DTO — a push would 404, and 404
is not terminal, so binding it early would poison the queue exactly as
`cropType`/`acres`/`useGps` and `kept`/`sold` did. The `@Binds @IntoSet` is
commented out in `RepositoryModule` with the conditions for arming it.
`PlantingDtos.kt` is a **proposal, not a contract** — diff it by hand first.

**`notes.sourceType` / `sourceId` are device-only for the same reason.** The
server's `CreateNoteDto` does not whitelist them and runs
`forbidNonWhitelisted: true`. `NoteRepository.pullSince()` carries them forward
from the local row; losing the link would un-hide the seed Purchase and re-open
the double-count.

Two repository bugs this ticket had to fix because §2.5 made them load-bearing:
`NoteRepository.updateLocal`/`deleteLocal` and `HarvestRepository.deleteLocal`
all read `getPendingSync().find { it.id == id }`, so they silently ignored any
row that had already synced. All three read by id now.

**§2.6 says red for a purchase; the code keeps Clay.** `LedgerScreen` got there
first — "buckets are inputs, not losses" — and the ticket's real requirement
(colour is not the only signal) is met by the `−`/`+` prefix. Clay-vs-LeafGreen
is also a brown/green contrast rather than the red/green pair ~8% of men cannot
separate, so this is strictly safer than what was specified.

### Still open

- **Agent home "New farmer" still mis-attributes.** `KumeaNavHost` routes it to
  `Routes.FARM_CREATE`; `Routes.FARMER_REGISTER` is correct and the server already
  permits a `village_agent`. One line, but it belongs with step 5's roster.
- **Batch B — the money rules.** Three pack sizes (50/100/150 g), price derived
  from pack size rather than typed, selling agent derived from the caller rather
  than picked. Numbers are in `PRICE-MATRIX-LOCKED.md` (revised 12 Aug).
  `SkuOptions` still holds the superseded 14 Jun codes and says so in a comment.
  Land before the first real sale.
- 🔴 **`plantings` has no server half.** The client is done and unbound; the
  server needs a Planting model, controller, DTO and migration before the
  `@Binds @IntoSet` in `RepositoryModule` can be uncommented. Until then a
  planting lives only on the device that recorded it.
- **v13 → v14 has not been run on a handset.** The SQL was verified against a
  pulled copy of the real v13 device database (111 agents + TestFarm survive,
  `foreign_key_check` clean, backfill correct) and the resulting schema was
  diffed column-for-column against Room's exported `14.json`, which is the check
  Room performs on open. The device was unplugged before `adb install`. Do that
  before shipping anything to a WAO.
- ~~**Crop capture is still a single string.**~~ **Done (KWAP-03).** `farm_crops`
  holds the set, `CropMultiSelect` cycles growing → interested, and
  `farms.cropType` stays as the list-card denorm sourced from the selection.
- **Profile screen** — display-only, per the 11 Aug decisions. `AgentEntity` has
  no name field, so it can still only show `EO-NANDI-041`, not Sila Serem.

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

The Kumea N rename is done in the app (both locales, `CostCategory`, the
FarmHome sheet). What is NOT done is the SKU catalogue: `SkuOptions` still reads
`BFX-150G` / `BFX-50G`. `orders.sku` is free TEXT server-side with no CHECK, so
renaming is safe whenever Batch B lands; historical rows keep `BFX-`.
