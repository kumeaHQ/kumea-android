# BUILD-STATUS.md
> Updated: 11 August 2026 — kumea-android section reconciled against the code.
> Tracks what is built, verified, and pending across all three repos.
>
> **The kumea-android tables below were six weeks stale (26 Jun) and said the
> officer surface did not exist. It does.** That error caused a wrong answer in
> the week of 4 Aug. Statuses in the kumea-android section are now checked
> against files on disk; where a claim could not be checked from this repo it
> says so. The kumea-api section is **not** re-verified — it is still as of
> 26 Jun and must be checked against `kumea-api` before it is trusted.

---

## kumea-api (NestJS + Prisma + PostgreSQL on Railway)

### ✅ Built & Verified

| Component | Status | Notes |
|---|---|---|
| Auth (phone → OTP → PIN → JWT) | ✅ Live | Africa's Talking SMS provider |
| User / Farm / Field CRUD | ✅ Live | Offline-first sync pattern |
| Order entity + channel attribution | ✅ Live | agent_id (UUID) attribution, never agent_code |
| Agent entity + role system | ✅ Live | farmer, village_agent, agro_dealer, extension_officer |
| Agent allowlist (officer exclusion) | ✅ Live | isCommissionEligible() gate |
| CommissionRule + CommissionRuleTier | ✅ Live | Tiered + per_sachet + percent types |
| Commission accrued compute (accrual.ts) | ✅ Live | Pure function, BigInt cents, tiered resolver |
| Commission service + ledger | ✅ Live | Upsert pipeline, earnings surface |
| Commission engine activation (C15) | ✅ **LIVE 26 Jun** | 6 village_agents linked, 3 bands seeded |
| Referrer attribution (T4) | ✅ Live | Non-commercial — officers CAN refer |
| Audit tables (append-only) | ✅ Live | DB trigger enforced |
| Sync conflict resolution | ✅ Live | Last-write-wins, server-authoritative |
| Health endpoint | ✅ Live | GET /health (DB ping) |

### 🔜 Pending

| Component | Status | Notes |
|---|---|---|
| Settlement pipeline (Phase 4) | Not started | Monthly payout to agents |
| Platform fee field (C17) | Not started | Structural hook for future third-party products |
| Dealer bulk tier rules | Not started | 1,100/1,000 cohorts as separate CommissionRule records |
| Agent close-gate order flow | Not started | Village agent places order → accrual verified in real time |

---

## kumea-android (Kotlin + Jetpack Compose)

### ✅ Built & Verified

| Component | Status | Notes |
|---|---|---|
| Auth flow (phone → OTP → PIN) | ✅ Device-verified | AC22 fix applied |
| Token management (DataStore) | ✅ Device-verified | 401-only session clear |
| Farm CRUD (list, create, edit) | ✅ Device-verified | Offline-first with sync |
| Field CRUD | ✅ Device-verified | Per-farm, crop type, acres |
| Offline sync worker | ✅ Device-verified | pushPending → pullSince via WorkManager |
| Pull-to-refresh | ✅ Device-verified | |
| Theme (Material 3) | ✅ Device-verified | Brand colours applied |
| Navigation graph | ✅ Device-verified | Splash → auth → farm list |
| Agent model (data layer) | ✅ Built | Agent entity in Room + remote DTO |
| Order model (data layer) | ✅ Built | Order entity in Room + remote DTO |
| Money.kt | ✅ Built | Long cents, display formatter |

### 🎨 Build-3 — Design System Pass (13 Jul 2026, per BUILD-3-V2-RECONCILED)

| Item | Status | Notes |
|---|---|---|
| Soil Paper palette (theme-wide) | ✅ Built | Zero raw hex outside `ui/theme/Color.kt`; `#2E7D32` gone; one gold `#C79A2A` |
| Logo: splash stacked lockup, launcher mark, Welcome horizontal lockup | ✅ Built | Mark generated from kumea-mark.svg; Poppins SemiBold bundled (lockups only) |
| FarmHome: 3-verb bar, Biofix link-row+sheet, money line-card, Shughuli feed | ✅ Built | FAB + SELL/MONEY toolbar deleted |
| Season Record card (gold rule, icon rows, teal stamp) | ✅ Built | "Imesawazishwa · HH:MM" / "Kwenye simu"; APK grep confirms zero "Imethibitishwa" |
| Purchase picklist (7 icon cards → costCategory) | ✅ Built | **BIOFIX enum is client-first — RB must confirm/add server-side BEFORE ship** (Herbicide→SPRAY) |
| Harvest wizard restyle (5 gold dots, unit icons, mini-record review) | ✅ Built | Flow untouched: UNIT→QUANTITY→SPLIT→REPLANT→REVIEW, atomic save |
| Sync felt-states (save-beat chip, on-phone/synced row badges) | ✅ Built | "Saving…" removed from APK strings |
| Swahili strings | ✅ Built | All new keys in values + values-sw |
| assembleDebug + unit tests | ✅ Green | Test fakes updated for Build-2 harvest endpoints (were broken pre-Build-3) |
| Device contrast spot-check (daylight, Redmi) | ⏳ Pending | No device on adb during the pass |

### Sprint 1 (Notes → Field → Ledger) — verified against code, 11 Aug 2026

All three persona surfaces exist and are routed from `LandingScreen` via
`PersonaRepository`. There are **10 screen packages** under `ui/screen/`
(agent, auth, distribution, farm, field, home, ledger, note, officer, order)
across 108 Kotlin files.

| Component | Status | Notes |
|---|---|---|
| **Farmer surface** | ✅ Built | `FarmList` → `FarmHome` → fields, notes, planting date, harvest wizard, per-farm ledger. Missing: order-history list, crop guidance |
| **Village Agent surface** | ✅ Built | `VillageAgentHomeScreen` + ViewModel. Record sale → `OrderCreate`; earnings → `Ledger`. ⚠️ farmer registration is wired but **wrong** — see KWAP-01 row |
| **Officer surface** | ✅ Built | `OfficerHomeScreen` (201 lines) + ViewModel (150). Ward outcomes card, agent endorsement list, honest gap notice at `OfficerHomeScreen.kt:136`. Zero commercial content — enforced in the type system (`Persona.allowsEarnings`, no `commissionRuleId` on `AgentEntity`) |
| Notes capture | ✅ Built | `NoteCreateScreen` + `NoteRepository`, per farm/field |
| Field detail (planting dates, harvest) | ✅ Built | `PlantingDateScreen`, `HarvestWizardScreen` (Build-2), `harvests` table via `MIGRATION_9_10` |
| Ledger / earnings view | ✅ Built | `LedgerScreen` + `LedgerRepository` / `CommissionRepository` |
| Offline order recording | ✅ Built | `OrderCreateScreen` + `OrderRepository : SyncableRepository` |
| Agent registration flow | Not built | Agents are still provisioned server-side. The distribution demo used to mint agent rows on the device; that was removed (commit `55a0906`) |
| Officer farmer directory | Not built | **Blocked on the server ward report** — KWAP-01 step 3. This is what the gap notice on the officer home is waiting for |

### ⚠️ KWAP-01 — farmer registration by officers and agents

Spec: `~/Desktop/Kumea-Claude/TICKET-KWAP-01-farmer-registration.md`.
Blocks the KWAP research track (20 sub-counties, 39 WAOs, ~395 farmers).

`FarmEntity` had no owner field, so a farm belonged implicitly to whoever held
the JWT — *the farm is the farmer*. Registering a farmer on someone's behalf
attached that farm to the registrar's own account.

| Step | Status |
|---|---|
| 1. `farmerUserId` + `registeredByAgentId` + `MIGRATION_10_11` (client) | ✅ Done — commit `ad9177c`, DB at v11, `11.json` exported |
| 2. API on-behalf creation, role + ward guarded | Not started — `kumea-api` |
| 3. Ward-scoped read endpoint | Not started — `kumea-api` |
| 4. Officer farmer-create + ward directory | Not started |
| 5. Agent farmer-create + own roster | Not started |
| 6. Bulk paste-a-list intake | Not started — `kwap/KWAP-FARMER-REGISTER.xlsx` is the system of record until then |

**Live mis-attribution, unfixed:** `KumeaNavHost.kt:181` routes the agent home's
"New farmer" button straight to `Routes.FARM_CREATE`, commented `INTERIM`. Every
farmer an agent registers today becomes a farm owned by *the agent*. Step 1 added
the fields but nothing sets them yet. Closed by steps 2 + 5.

**Do not set `referrerAgentId` on officer-registered farmers.** It records who
gets commission, not who typed it in — that is `registeredByAgentId`. The
commission engine has been live since 26 Jun and runs effective from 1 Jun.

### ❓ Unknown (needs UI assessment)

| Question | Concern |
|---|---|
| Is the farmer surface usable by a real farmer? | Language, icon clarity, offline behaviour |
| Does the village agent surface feel like it earns them money? | Earnings visibility, order speed, trust |
| Can an officer navigate without seeing anything commercial? | Structural zero, not hidden-by-flag |
| Is the sync feedback clear? | Pending sync indicator, conflict resolution messaging |
| Is the onboarding flow simple enough? | 3-screen max, Swahili-first |

---

## kumea-web (Next.js)

| Component | Status |
|---|---|
| Landing page | ✅ In GitHub |
| Dashboard | Not started |
| Any functional surface | Not started |

---

## Close Gate Checklist (Before Real Users)

- [ ] Real device against Railway: farmer registration → order → sync → verify on server
- [ ] Real device against Railway: agent places order → earnings surface shows accrued cents
- [ ] Officer logs in → verifies ZERO commercial surface (no earnings tab, no commission mention)
      — **cheap to close now.** No new code needed: create an `AgentEntity` row
      server-side with `role = "extension_officer"`, a `ward` matching the agent
      records, and `linkedUserId` set. Phone → OTP → PIN routes to `OFFICER_HOME`
      via `PersonaRepository`. Open since 26 Jun.
- [ ] Offline test: create farm + order in airplane mode → go online → sync → verify on server
- [ ] Money display: KES amounts formatted with comma separators, never raw cents
- [ ] Error handling: network failure shows message, not white screen
