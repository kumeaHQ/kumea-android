package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * ⚠️ THIS CONTRACT IS UNVERIFIED. THERE IS NO `/plantings` RESOURCE YET.
 *
 * Checked 13 Aug against `kumea-api` at `c83917f` (main, deployed): the only
 * planting-shaped thing on the server is `fields.plantedAt`, added by
 * `20260712080852_add_planted_at_and_harvests`. No Prisma model, no controller,
 * no DTO.
 *
 * So the field names below are a PROPOSAL, not a contract, and
 * `PlantingRepository` is deliberately NOT bound into `Set<SyncableRepository>`
 * — see the commented-out binding in `di/RepositoryModule.kt`. Pushing at a
 * route that does not exist is a 404, and 404 is not terminal in these
 * repositories, so binding this early would park every planting at the head of
 * the offline queue and re-send it for ever. That is the `kumea_n_received`
 * precedent from KWAP-03, applied for the same reason.
 *
 * 🔴 BEFORE BINDING: diff every key below against the server's `CreatePlantingDto`
 * BY HAND, and check the numeric wire types. This project has shipped the same
 * bug three times — `cropType`/`acres`/`useGps` on the Farm, `kept`/`sold` on the
 * Harvest, and `GET /farms` returning Decimal where the client expected a
 * string. Each was a 400 or a parse failure that retried for ever. The API runs
 * `ValidationPipe({ forbidNonWhitelisted: true })`, so one wrong key is not
 * ignored, it is a rejection.
 *
 * Quantities follow the established rule: centi-Longs cross the wire as decimal
 * STRINGS ("1.6"), never JSON numbers — the same contract as harvest quantity,
 * acres and money cents. `seedCostCents` is an integer string of cents, matching
 * `AMOUNT_CENTS_PATTERN` on the notes DTO.
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
