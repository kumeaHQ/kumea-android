package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * ✅ VERIFIED AGAINST THE DEPLOYED SERVER, 18 AUG 2026.
 *
 * This file used to say "a PROPOSAL, not a contract". It is the contract now:
 * every key below was diffed by hand, key by key and type by type, against
 * `CreatePlantingDto` / `UpdatePlantingDto` in `kumea-api` `7cb03d2` before the
 * first row existed, and the five `/plantings` routes are live on Railway.
 * `PlantingRepository` is bound into `Set<SyncableRepository>`.
 *
 * ── THE DIFF MOVED THE SERVER, NOT THIS FILE ────────────────────────────────
 *
 * `TICKET-KWAP-03-V2-SERVER.md` recommended `seedKgCenti` / `plantedAreaCenti`
 * as INTEGER columns carrying JSON numbers. This client sends `seedKg` and
 * `plantedArea` as decimal STRINGS, and the client was already on a handset, so
 * the server took these names and these types.
 *
 * ── THE TWO-DECIMAL RULE IS LOAD-BEARING IN BOTH DIRECTIONS ─────────────────
 *
 * Quantities cross as decimal strings ("1.6"), never JSON numbers — the same
 * contract as harvest quantity, acres and money cents. `seedCostCents` is an
 * integer string of cents matching `AMOUNT_CENTS_PATTERN` on the notes DTO.
 *
 * The SCALE is part of it. `Quantity.parseToCenti` accepts at most TWO decimal
 * places and `PlantingRepository.pullSince` drops a row it cannot parse, so
 * `plantings.seed_kg` / `planted_area` are `Decimal(10,2)` serialised
 * `.toFixed(2)` — deliberately unlike `fields.acres` and `harvests.quantity`,
 * which are 4 dp. A 4-dp planting would round-trip as "1.6000" and vanish on
 * every device that pulled it, with no error anywhere.
 *
 * 🔴 That is not hypothetical: `harvests.quantity` IS serialised at 4 dp today,
 * and every harvest the server returns is silently discarded by
 * `HarvestRepository.pullSince` for exactly this reason. See CLAUDE.md.
 */
@Serializable
data class PlantingCreateRequest(
    val id: String,
    val farmId: String,
    val plantedOn: String,
    val crop: String,
    val seedVariety: String? = null,
    val seedKg: String,
    val plantedArea: String,
    val seedCostCents: String? = null,
    val trialRole: String,
)

@Serializable
data class PlantingResponse(
    val id: String,
    val farmId: String,
    val plantedOn: String,
    val crop: String,
    val seedVariety: String? = null,
    val seedKg: String,
    val plantedArea: String,
    val seedCostCents: String? = null,
    val trialRole: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class PlantingUpdateRequest(
    val plantedOn: String? = null,
    val crop: String? = null,
    val seedVariety: String? = null,
    val seedKg: String? = null,
    val plantedArea: String? = null,
    val seedCostCents: String? = null,
    val trialRole: String? = null,
    val updatedAt: String,
)
