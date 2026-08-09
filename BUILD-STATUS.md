# BUILD-STATUS.md
> Updated: 26 June 2026 12:10 EAT
> Tracks what is built, verified, and pending across all three repos.

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

### 🔜 Pending (Sprint 1: Notes → Field → Ledger)

| Component | Status | Notes |
|---|---|---|
| **Farmer surface** | Partial | Farm + field exists. Missing: order history, product info, crop guidance |
| **Village Agent surface** | Not built | Missing: order recording, farmer registration flow, earnings dashboard |
| **Officer surface** | Not built | Missing: farmer directory, agent directory, referral tracking. MUST have zero commercial surface. |
| Notes capture | Not built | Observation logging per farm/field |
| Field detail (planting dates, harvest) | Not built | Extended field model |
| Ledger / earnings view | Not built | Read-only commission surface from API |
| Agent registration flow | Not built | Phone → OTP → role selection → agent code |
| Offline order recording | Not built | Agent records sale in field → syncs when online |

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
- [ ] Offline test: create farm + order in airplane mode → go online → sync → verify on server
- [ ] Money display: KES amounts formatted with comma separators, never raw cents
- [ ] Error handling: network failure shows message, not white screen
