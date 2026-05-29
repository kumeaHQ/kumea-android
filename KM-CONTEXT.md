# Kumea Project Context

## Project Overview
Offline-first Android app for agricultural biotech. "Not perfect, just better."

## Stack
- Kotlin, Jetpack Compose, Hilt (DI), Room (Local DB), WorkManager (Sync).
- Retrofit (API calls).

## Architectural Invariants (DO NOT VIOLATE)
1. **Offline-First:** All UI interactions write to Room first. Remote sync is backgrounded via WorkManager.
2. **WorkManager Setup:** Auto-init is DISABLED in AndroidManifest (via tools:node="remove"). We use KumeaApplication (Configuration.Provider) for initialization. DO NOT re-add default WorkManager startup.
3. **SyncPattern:** `FarmRepository` uses a push-before-pull strategy in `FarmSyncWorker`. Never allow server pull to overwrite rows with `pendingSync = true`.
4. **Data Integrity:** `FarmDao` uses `pendingSync` flags and `SyncAction` enums. Conflict handling is via `audit_sync_conflicts` table.

## Current State
- Ticket 2.1 (Scaffold) & Ticket 2.2 (Offline-First Skeleton) are COMPLETED and verified.
- Unit testing for `FarmSyncWorker` and `FarmRepository` is passing.

## Next Task (Ticket 2.3)
Building the UI screens (FarmList, Create Farm, Edit Farm) using the existing engine. 

## Rules for AI Agent
- Never add external libraries without consulting current build config.
- Always prefer Compose idiomatic state management (`collectAsStateWithLifecycle`).
- Keep code concise; prioritize readability in mobile screens.
- If you see `IllegalStateException: WorkManager is already initialized`, check the Manifest immediately.
