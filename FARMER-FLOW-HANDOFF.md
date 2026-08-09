# Farmer Flow — Redesign & Fix Handoff

> Context for an implementing agent. Project: **kumea-android** (Kotlin, Jetpack
> Compose, Hilt, Room, WorkManager, Retrofit). Offline-first farm app for Kenyan
> farmers. Personas: farmer / village_agent / extension_officer. This document
> covers only the **farmer surface** work from a design + debugging session.

---

## 0. IMPORTANT: two divergent code states

There are two versions of the code in play. Do not conflate them.

- **RB's working tree** — where the 13-file farmer-flow changeset was applied
  (new `FarmHomeScreen`, Swahili strings, `FarmEntity` fields, DB v9, etc.). This
  is the tree with the runtime bug that was debugged. The implementing agent is
  presumably working here.
- **The snapshot Claude had mounted** — an **earlier** state that did NOT contain
  the changeset: no `FarmHomeScreen.kt`, no `values-sw/`, `FarmEntity` had no
  `cropType`/`acres`/`useGps`, DB still `version = 8`, `KumeaNavHost` still routed
  to `NoteListScreen`. Not a git checkout (no history).

The one concrete code edit below was made in the mounted snapshot as a **reference
end-state**. It must be ported/reconciled into RB's tree.

---

## 1. Code fix applied (the field→note runtime break)

### Root cause

A `Note` has a **non-null `fieldId` foreign key to `Field`** — notes attach to a
*field*, never directly to a farm (`NoteEntity`: `val fieldId: String`, FK to
`FieldEntity`, indexed). In RB's tree, `FarmHomeScreen`'s FAB passes **`farmId`**
to note creation, but the broken `NoteDetailViewModel` still read **`fieldId`**
from `SavedStateHandle` → it got null → no field → note could not be saved.

### Fix

`NoteDetailViewModel` takes `farmId` and **resolves-or-lazily-creates** the field:
use the selected/first field if one exists, otherwise create the farm's
"Main field" on the spot so a note can always be saved. This also covers
server-pulled farms, old farms from before farm-create auto-created a field, and a
failed auto-create — none of which the farm-create path protects.

### Exact changes — `app/src/main/java/co/ke/kumea/ui/screen/note/NoteDetailViewModel.kt`

1. Added import: `import kotlinx.coroutines.CancellationException`
2. Store the repo: constructor param `fieldRepository: FieldRepository` →
   `private val fieldRepository: FieldRepository`
3. Deleted the early guard in `saveNote()`:
   ```kotlin
   val fieldId = state.selectedFieldId
   if (fieldId == null) {
       _uiState.update { it.copy(error = "No field available — pull to refresh on the farm first") }
       return
   }
   ```
4. In `saveNote()`'s `viewModelScope.launch { try { … } }`, resolve-or-create the
   field and re-throw cancellation:
   ```kotlin
   val fieldId = state.selectedFieldId ?: fieldRepository.createLocal(
       farmId = farmId,
       name = "Main field",
       acres = "1.0",
       cropType = null,
   )
   noteRepository.createLocal(
       fieldId = fieldId,
       type = state.type,
       body = state.body.trim(),
       amountCents = amountCents,
       occurredAt = occurredAt,
       costCategory = state.costCategory,
   )
   onSuccess()
   // …
   } catch (e: CancellationException) {
       throw e
   } catch (e: Exception) {
       _uiState.update { it.copy(isSaving = false, error = e.message ?: "Failed to save note") }
   }
   ```

`farmId` is already read as `checkNotNull(savedStateHandle["farmId"])`; the init
block already collects `fieldRepository.getActiveByFarm(farmId)` and defaults
`selectedFieldId` to the first field.

### To land in RB's tree

- Switch RB's `NoteDetailViewModel` nav arg from `fieldId` to `farmId` (the file
  above is the reference end-state).
- Confirm `FarmHomeScreen`'s FAB passes `farmId` to the note-create route.
- **Follow-up:** the lazily-created field hardcodes `acres = "1.0"`. Once
  `FarmEntity` carries `acres`/`cropType`, have the "Main field" inherit the
  farm's values (Sigona should read 0.5 acre, not 1.0).

### Relevant signatures (mounted snapshot)

- `FieldRepository.createLocal(farmId: String, name: String, acres: String, cropType: String?): String`
- `FarmRepository.createLocal(name, locationLat: Double?, locationLng: Double?, waterSource: String?, referrerAgentId: String? = null): String`
- `NoteEntity`: `fieldId: String` (FK → `FieldEntity`), `type: NoteType`,
  `amountCents: Long?`, `costCategory: CostCategory?`, `occurredAt`, sync fields.

### Verification status

Verified statically only (control flow, no dangling `fieldId`, catch hygiene). An
Android build could not be run in the environment. **Build + real-device-against-
Railway run is the close gate.**

---

## 2. Farmer flow redesign (agreed spec)

Three screens. RB reports a 13-file changeset implementing most of this; treat the
below as the authoritative design intent. Reference farm used throughout:
**Sigona — 0.5 acre, beans (Maharagwe), water = Dam, 1 Biofix sachet.**

### Cross-cutting

- **Localization:** move every hardcoded English UI string into
  `res/values/strings.xml`; add `res/values-sw/strings.xml` (Swahili). Baseline
  had **0** `stringResource` usages — all text was hardcoded. Design in English;
  Swahili is a translation twin + optional in-app toggle. Keep layouts flexible
  (Swahili runs longer). Match the SMS voice ("Panda maharagwe msimu huu?").
- **Remove the debug surface from farmer/agent:** the "Agents"/"Demo" button opens
  `DistributionDemoScreen` (a developer harness). Gate it behind
  `BuildConfig.DEBUG`; it must not be reachable by real users. Baseline: not gated.
- **Sync wording:** replace the "PENDING" badge with "Saving… / Inahifadhi…".

### New data on the farm

- `FarmEntity` gains `cropType: String?`, `acres: Double?`, `useGps: Boolean`;
  mirror in `FarmCreateRequest` and `FarmResponse` DTOs and `FarmRepository.createLocal`.
- DB `version` bump 8 → 9 (destructive migration). Destructive wipes local farms +
  fields on upgrade — acceptable pre-launch; the §1 lazy-create-field-on-note is
  what protects against resulting fieldless farms.

### Screen 1 — Farm List (My Farms / Mashamba yangu) — `FarmListScreen`

- Farm cards show **name · crop · size · water**, e.g. "Sigona · Beans · 0.5 acre · Dam".
- Welcoming empty state: "Add your first shamba" (not "No farms found. Tap + to add one").
- Move "Log out" into an overflow menu (MoreVert icon).
- Remove the "Agents" debug button.

### Screen 2 — Add Farm (Ongeza shamba) — `FarmCreateScreen`

- Crop picker: FilterChips (beans / maize / soya).
- Acres field (decimal input).
- Water as FilterChips (Dam / Rain / Borehole), replacing the free-text field.
- "Use my location" GPS button, replacing typed Latitude/Longitude.

### Screen 3 — Farm Home (NEW — replaces NoteListScreen as farm detail)

- New files `FarmHomeScreen.kt` + `FarmHomeViewModel.kt`. Route `farms/{farmId}` →
  `FarmHomeScreen` (was `NoteListScreen`); `FarmListScreen.onOpenFarm` → farm home.
- **Title:** the farm name with crop · size beneath (e.g. "Sigona" / "Beans · 0.5 acre"),
  NOT "Notes".
- **Biofix card:** description ("A natural soil treatment that helps your legumes
  fix more nitrogen. Bigger roots, better pods, higher yield."), sachet calculator
  ("This shamba needs 1 sachet (150 g each)"), price "KSh 1,500 per sachet",
  tagline "Not perfect, just better.", "Learn more" link, and a guidance line
  ("Mix with the seed before planting").
- **Money card — two states:**
  - *Pre-harvest* (money out, no income yet): label **"Invested so far · Umewekeza"**,
    number in **neutral grey**, sub "Bado mavuno · harvest still to come", plus
    In (Mapato) / Out (Matumizi) rows. Pre-harvest spend is framed as *investment,
    never a loss*.
  - *Post-harvest, resolved*: label **"Profit · Faida"** in **green** if positive;
    **"Hasara · loss"** in **red** only if a completed season's income < costs.
  - Flip trigger = the **first income (sale)** recorded for the farm. Needs a
    notion of **season** so it resets each planting.
  - The whole card taps through to the full P&L (existing `LedgerScreen`).
- **Activity feed** ("Notes" / "Shughuli"): typed entries (sale / purchase /
  activity), amounts **signed and colored** — sale green `+`, cost red `−` — each
  with `type · date`. Welcoming empty state.
- **FAB:** "+ Record / Andika".

---

## 3. NEW concept — two-capital view (NOT yet built; needs decisions)

Show a season in **two measures**, side by side on the farm home:

1. **Production capital — Harvest (Mavuno):** the quantity the land yielded
   (bags / kg), whether or not it was sold. Example card: "3 bags ≈ 270 kg ·
   2 kept for home and seed · 1 sold".
2. **Financial capital — Money (Pesa):** cash in / out (the existing money card).

**Why:** smallholders eat, share, or keep much of the harvest as seed. Money alone
undervalues that and can mislabel a good farming season a "loss." Production capital
also makes **Biofix's benefit visible** (Biofix raises yield, not cash) season over
season.

### Open questions (blocking implementation — need RB's decision)

1. **How is harvest recorded?** Proposed: a **new "Harvest" entry type** (4th
   alongside sale/purchase/activity) carrying a quantity, with no money unless some
   is sold. Alternative: capture as part of a sale plus a separate "kept" amount.
2. **Unit:** bags (90 kg) / kilos / a smaller everyday measure (debe, gorogoro
   tin)? Must match how Nandi bean farmers actually count.

---

## 4. Non-negotiables the implementing agent MUST respect

- **Money:** integer cents — `Long` on device, `String` on the wire, never
  `Float`/`Double`. Use `util/Money.kt` (`parseToCents`, `lineTotalCents`,
  `formatCents`) for all money parsing/display.
- **Officer exclusion:** the extension_officer persona shows **zero commercial
  surface** — enforced structurally (separate route; earnings composable never
  instantiated), not by hiding a flag. Do not route officers through farmer/agent
  commercial screens.
- **No silent catches:** re-throw `CancellationException`, then log and surface
  errors to the user. Never swallow.
- **Offline-first:** local writes go to Room first (client-generated UUID,
  `pendingSync = true`) and appear immediately — never a spinner for a local write.
  `SyncWorker` pushes later; parents push before children (FK order).
- **No build-tool version bumps** (Gradle / AGP / Kotlin / KSP / dependencies)
  without a ticket. Hilt 2.51.1 requires AGP < 9.x; `gradle/libs.versions.toml` is
  load-bearing.
- **Real device against Railway is the close gate**, not green CI.
- **Retail price locked at KES 1,500/sachet.**

---

## 5. Other known issues noted this session (not farmer-flow; not yet fixed)

- **Order SKU list is wrong** (`OrderCreateViewModel.SkuOptions =
  ["BFX-100G", "BFX-500G"]`). Canonical packs are **150 g (standard)** and
  **50 g (demo)**; 100 g is **retired** and 500 g does not exist. An agent cannot
  record a correct sale. (Village-agent surface, flagged for a separate fix.)
- `DistributionDemoViewModel` also hardcodes `sku = "BFX-100G"`.
