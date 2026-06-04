# Ticket 2.3 — Background Sync (WorkManager on the Abstraction)

**Sprint:** 2
**Depends on:** 2.2 (SyncableRepository + consolidated SyncWorker — closed-closed, device-verified on a32e1d6)
**Estimated effort:** 1 day Claude Code, 0.5 day device verification
**Why now:** The deferral from Sprint 0/1. Sync has always been manual pull-to-refresh. Now that one `SyncWorker` cleanly syncs all entities via the multibound set (proven on device), making it run unattended on connectivity is a small, well-founded step.

---

## What This Ticket Is

Today, sync only happens when the user pulls to refresh (pull) or creates/edits something (ad-hoc push). If a farmer records ten notes offline on the shamba and never manually refreshes once back in signal, those notes sit unsynced until they happen to pull. Background sync fixes that: when connectivity returns, the device syncs on its own.

**This is infrastructure, not a new entity.** No new data, no new money, no schema change. It takes the *existing* consolidated `SyncWorker` (from 2.2) and gives it a periodic + connectivity-triggered schedule, so sync happens without the user thinking about it.

Pull-to-refresh **stays** — it becomes the user-visible "sync now" affordance on top of the automatic background sync. Both paths run the same `SyncWorker`; they differ only in what triggers them.

---

## The Core Principle: One Worker, Many Triggers

`SyncWorker` already exists and is device-proven to sync all entities (farm→field→note) in one pass via the multibound `Set<SyncableRepository>`. This ticket does NOT change what the worker does. It adds *when* it runs:

| Trigger | Exists today | This ticket |
|---|---|---|
| User pull-to-refresh | ✅ | unchanged — stays as "sync now" |
| Ad-hoc push on create/edit | ✅ | unchanged |
| **Connectivity returns** | ❌ | **new — one-time work, constrained on CONNECTED** |
| **Periodic safety net** | ❌ | **new — every ~6h when connected, catches anything missed** |

All four triggers enqueue the *same* `SyncWorker`. The worker is idempotent (pushes only pending rows, pulls only deltas since last sync) so overlapping triggers are safe — but use WorkManager's unique-work policies to avoid piling up redundant runs (see below).

---

## Implementation

### 1. Connectivity-triggered sync (the main feature)

A one-time `SyncWorker` request constrained on `NetworkType.CONNECTED`, enqueued so it fires when the device regains connectivity.

```kotlin
val syncOnConnect = OneTimeWorkRequestBuilder<SyncWorker>()
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    )
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    .build()

workManager.enqueueUniqueWork(
    "sync-on-connect",
    ExistingWorkPolicy.KEEP,   // if one's already waiting for connectivity, don't stack another
    syncOnConnect,
)
```

**When is this enqueued?** Two reasonable options — pick based on what's simplest given the current code:
- **A:** Enqueue it once on app start. WorkManager holds it until the CONNECTED constraint is met, runs it, done. Re-enqueue (KEEP policy) on each app start so there's always one armed.
- **B:** Enqueue it whenever a row goes pending AND on app start. Belt-and-braces.

**Recommend A** for simplicity — one armed connectivity-triggered job, re-armed on app start. The periodic safety net (below) covers the gap if the app isn't opened for a while. Don't over-engineer the enqueue logic.

### 2. Periodic safety-net sync

A `PeriodicWorkRequest` every 6 hours, constrained on CONNECTED. This catches anything the connectivity trigger missed (e.g. app killed by the OS before the connectivity job ran, or the device was online continuously so "connectivity returned" never fired).

```kotlin
val periodicSync = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    )
    .build()

workManager.enqueueUniquePeriodicWork(
    "sync-periodic",
    ExistingPeriodicWorkPolicy.KEEP,   // don't reset the schedule on every app start
    periodicSync,
)
```

6 hours, not 15 minutes. This is a *safety net*, not the primary mechanism — the connectivity trigger is what makes sync feel immediate. A short periodic interval burns battery for little benefit since farmers' data changes in bursts (a morning of field work), not continuously. `ExistingPeriodicWorkPolicy.KEEP` so re-opening the app doesn't reset the 6h clock.

### 3. Worker setup — confirm it's enqueued at all

**Important context from the codebase:** in Sprint 0/1, `FarmSyncWorker`/`FieldSyncWorker` existed as classes but were **never enqueued** — sync was manual only. 2.2 consolidated them into `SyncWorker` but (per the 2.2 report) didn't necessarily wire up scheduling either. So this ticket may be the *first time* a sync worker is actually enqueued via WorkManager on a schedule.

That means the WorkManager + Hilt wiring needs to be confirmed live, not assumed:
- `KumeaApplication` implements `Configuration.Provider`, provides `HiltWorkerFactory` (set up in 2.2's `SyncWorker` as a `@HiltWorker`).
- The default `WorkManagerInitializer` is removed in the manifest (the `tools:node="remove"` provider block — this was flagged back in 2.2's predecessor). **Confirm it's present**, or you'll get "WorkManager already initialized" on first launch.
- If WorkManager was never actually initialized before (because nothing was enqueued), this ticket is where that wiring gets exercised for real. Test cold-start specifically.

### 4. Completion notification (light touch)

When a background sync completes AND actually synced new data (pushed or pulled at least one row), show a notification: "Kumea synced — 3 notes uploaded" or similar. If nothing changed, **stay silent** — no "sync complete, nothing to do" spam.

- Notification only for background runs, not for user-initiated pull-to-refresh (the user's already looking at the screen; the refreshed list is the feedback).
- Requires `POST_NOTIFICATIONS` permission on Android 13+ (API 33+). Request it once, gracefully; if denied, background sync still works, just silently. **Do not** block sync on the permission.
- Keep it minimal — one line, no actions, dismissible. This is a "your data's safe" reassurance, not an engagement hook.

### 5. Error surfacing for background runs

Background sync runs unattended, so errors can't surface as a snackbar (no screen). Per the 2.0 rule (no silent failures), background sync errors must still be *visible somewhere*:
- Log at error level (always).
- On repeated failure (e.g. WorkManager exhausts its backoff retries), show a notification: "Kumea couldn't sync — tap to retry" that opens the app. Don't notify on a single transient failure (that's normal on patchy 2G); notify when sync has genuinely been failing across retries.
- The next time the user opens the app and pulls to refresh, the existing snackbar path surfaces the error too.

This keeps the 2.0 "loud failure" discipline intact even when there's no UI present.

---

## Acceptance Criteria

### Wiring (confirm the foundation is actually live)
1. WorkManager + Hilt wiring confirmed working: `Configuration.Provider`, `HiltWorkerFactory`, default initializer removed in manifest. Cold-start (force-stop → launch) does NOT crash with "WorkManager already initialized."
2. `SyncWorker` is enqueued via WorkManager for the first time on a schedule (it wasn't, before this ticket). Verify via `adb shell dumpsys jobscheduler | grep kumea` — the connectivity and periodic jobs are registered.

### Connectivity-triggered sync (the main feature)
3. Create a note offline (airplane mode). Do NOT pull to refresh. Turn airplane mode off. Within ~30s of connectivity returning, the note syncs automatically — no user action. Verify on Railway.
4. The "sync-on-connect" unique work uses `KEEP` so it doesn't stack: toggle airplane mode several times rapidly → only one connectivity sync job armed at a time (not N stacked).

### Periodic safety net
5. The 6-hour periodic job is registered with `KEEP` policy: re-opening the app does not reset its schedule (verify the periodic job's next-run-time doesn't jump forward on each app launch).
6. (Hard to test in real time — verify via WorkManager test harness or by temporarily shortening the interval in a debug build to confirm the periodic worker fires and syncs, then revert to 6h.)

### Idempotency / no double-sync
7. Multiple triggers overlapping is safe: force a pull-to-refresh AND a connectivity sync near-simultaneously → no duplicate rows on server, no crash. (The worker only pushes pending rows and pulls deltas, so this should be inherently safe — confirm it.)
8. Pull-to-refresh still works as "sync now" and is unchanged from 2.2 behaviour.

### Notification
9. Background sync that uploads/downloads new data shows a one-line notification. Background sync with nothing to do is silent.
10. Notification permission (API 33+) requested gracefully; denial does not break sync (sync runs silently).
11. User-initiated pull-to-refresh does NOT show a notification (screen feedback is enough).

### Error surfacing (2.0 rule survives unattended runs)
12. A background sync that fails across all retries shows a "couldn't sync — tap to retry" notification. A single transient failure on flaky network does NOT notify (only sustained failure does).
13. Background sync errors are logged at error level regardless of notification.
14. No silent catches introduced — every catch in the new code surfaces, logs-and-rethrows, or (background context) logs + notifies-on-sustained-failure. CancellationException re-thrown.

### Device verification (the real gate)
15. **On the Solana against Railway:** record notes offline, lock the phone / leave the app, regain connectivity → confirm sync happened in the background without opening the app (check Railway, then open app and see SYNCED badges). This is the gate — background sync proven on a real device, not just the emulator.
16. Battery sanity: the periodic job at 6h + connectivity trigger does not cause obvious battery drain or wakelock warnings in `adb shell dumpsys batterystats` over a normal day. (Light check, not a formal audit.)

### Regression & build
17. All 24 tests from 2.2 still pass. Manual pull-to-refresh, offline create, multi-entity single-pass sync all unaffected.
18. Build green, lint clean, CI runs tests (CI-01 guard intact).

---

## Handoff Notes for Claude Code

- **Don't change what `SyncWorker` does.** This ticket changes only *when* it runs. The worker's sync logic, the multibound set, the FK-guarded pullSince — all untouched. You're adding schedule triggers around proven machinery.
- **This may be the first real WorkManager enqueue in the app.** The workers existed but were never scheduled. Confirm the Hilt/WorkManager wiring and the manifest initializer-removal are actually correct, because they may never have been exercised. Test cold-start.
- **6 hours for periodic, not 15 minutes.** Farmers' data changes in bursts. The connectivity trigger handles immediacy; periodic is just a safety net. Short intervals waste battery.
- **Notifications are reassurance, not engagement.** One line, only when data actually moved, only for background runs. Silent when nothing changed. Don't turn this into a notification stream.
- **Background errors must stay loud (2.0 rule)** but appropriately — log always, notify only on *sustained* failure (not every transient 2G hiccup). A farmer in a signal dead-zone shouldn't get an error notification every 30 seconds.
- **No new entity, no schema change, no money.** If you're touching the DB schema, you've misread the ticket.
- **Device gate is the close.** Emulator/CI green is necessary but not sufficient — same as every ticket. Background sync isn't done until the Solana syncs unattended against Railway.

---

## What This Unblocks

After 2.3, sync is automatic — the app keeps itself current without the farmer thinking about it. This is also the last piece of infrastructure before Weather (2.4): when Weather registers itself as a `SyncableRepository` with one `@Binds @IntoSet` line, it automatically inherits background sync too, with zero changes to the sync chain. That's the payoff of the 2.2 abstraction — Weather drops into a system that already syncs it, foregrounds it, and backgrounds it.

2.4 (Weather) then becomes the final validation: a brand-new entity that, because of 2.2 and 2.3, needs only its domain fields + UI + one binding line, and gets full offline sync (foreground and background) for free. If Weather drops in that cleanly, the abstraction is vindicated end to end.
