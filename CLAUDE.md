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

```
app/src/main/java/co/ke/kumea/
  MainActivity.kt              # Entry point, splash → nav graph
  KumeaApplication.kt          # Hilt app + WorkManager config
  data/
    auth/TokenStore.kt         # DataStore: access_token, refresh_token
    local/                     # Room: FarmEntity, FarmDao, SyncConflictEntity, KumeaDatabase
    remote/                    # Retrofit: KumeaApi, DTOs, AuthInterceptor
    repository/                # AuthRepository, FarmRepository, HealthRepository
    sync/FarmSyncWorker.kt     # WorkManager: pushPending → pullSince
  di/                          # Hilt modules: Network, Database, DataStore
  ui/
    navigation/                # KumeaNavHost, StartupViewModel, Routes
    screen/auth/               # PhoneEntry, OtpEntry, PinSetup, PinEntry
    screen/farm/               # FarmList, FarmCreate
    theme/                     # Color, Theme, Type
    common/PullToRefresh.kt
```

## Architecture Decisions

### Offline-first sync (the critical pattern)

1. User creates entity offline → Room insert with `pendingSync=true`, client-generated UUID
2. Entity appears in UI immediately (no spinner)
3. WorkManager detects connectivity → pushPending() then pullSince()
4. Server: idempotent on UUID, conflict detection via `updatedAt` comparison
5. 409 → server wins, local discarded, conflict logged to `audit_sync_conflicts`

### Auth flow

- **Registration:** phone → OTP → verify → PIN setup → tokens saved → FarmList
- **Login (existing):** phone → PIN → tokens saved → FarmList
- **Returning (token saved):** GET /auth/me → 200 → FarmList (skip auth screens)
- **Returning (token expired):** GET /auth/me → 401 → clear session → PhoneEntry

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

### Sync conflict resolution

- Last-write-wins, server-authoritative
- Client sends `updatedAt` in write requests
- Server compares: stale `updatedAt` → 409
- Rejected payload saved to `audit_sync_conflicts`

### No generics in sync workers

FarmSyncWorker is concrete, not generic. Field sync in Sprint 1 gets its own
FieldSyncWorker. Do not introduce `SyncableEntity<T>` abstractions.

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

**Ticket 2.3 — Background Sync** (full spec at `.claude/ticket-2.3.md`)

Build a WorkManager chain on top of the SyncWorker/SyncableRepository from Ticket 2.2.
Connectivity-triggered primary sync + 6-hour periodic safety net. Notification only when
data actually moved, error only on sustained failure. This is the first real WorkManager
enqueue in the app — the workers existed before but nothing was ever scheduled.


**Ticket 2.2 — Extract Sync Abstraction** (full spec at `.claude/ticket-2.2.md`)

FarmSyncWorker, FieldSyncWorker, and NoteSyncWorker are identical copies (28 lines each).
FarmRepository, FieldRepository, and NoteRepository all expose the same `pushPending()` +
`pullSince()` contract. Extract a `SyncableRepository` interface + single `SyncWorker`
with Hilt multibindings (`Set<SyncableRepository>`). Delete the three concrete workers.
Repository bodies, entity classes, DTOs — NO changes.


## See Also

- `.claude/second-brain.md` — Claude Code ↔ RB agent coordination contract (Obsidian vault)
