package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Harvest wire contract (Build-2). Quantities are JSON STRINGS of the human
 * decimal ("1.2"), never JSON numbers — same rule as money cents and acres.
 * unit / replantIntent are the server's lowercase enum strings verbatim.
 *
 * ── THE KEPT/SOLD NAMES ARE A BUG FIX, NOT A PREFERENCE (KWAP-03) ────────────
 *
 * These were `kept` and `sold`. The server's `CreateHarvestDto` whitelists
 * `keptQuantity` and `soldQuantity`, and the API runs
 * `ValidationPipe({ forbidNonWhitelisted: true })` — so `kept` was not ignored,
 * it was a 400. `HarvestRepository.pushPending()` treats 400 as retryable (only
 * 403 and 409 are terminal), which makes it a row stuck at the head of the
 * offline queue for ever, re-sent on every sync cycle.
 *
 * It stayed invisible for the same reason the `cropType`/`acres`/`useGps` bug
 * did: kotlinx.serialization omits a property still holding its default, so a
 * harvest recorded WITHOUT a kept/sold split serialised without those keys and
 * synced fine. The moment a farmer filled in the split — which the wizard's
 * SPLIT step invites — the request grew two keys the server rejected and that
 * harvest never synced again.
 *
 * The response half had the mirror bug: the server serialises `keptQuantity` /
 * `soldQuantity`, `ignoreUnknownKeys = true` swallowed them, and both fields
 * defaulted to null on every pull. The split was silently dropped coming back.
 *
 * THE RULE THIS LEAVES BEHIND, for the third time: a field name here is a hard
 * contract with the server's DTO, not a local choice. `HarvestWireContractTest`
 * pins the key set so the next one fails in CI instead of in a Nandi maize field.
 */
@Serializable
data class HarvestCreateRequest(
    val id: String,
    val fieldId: String,
    val harvestDate: String,
    val quantity: String,
    val unit: String,
    val keptQuantity: String? = null,
    val soldQuantity: String? = null,
    val replantIntent: String,
    val replantMonth: String? = null,
)

@Serializable
data class HarvestResponse(
    val id: String,
    val fieldId: String,
    val harvestDate: String,
    val quantity: String,
    val unit: String,
    val keptQuantity: String? = null,
    val soldQuantity: String? = null,
    val replantIntent: String,
    val replantMonth: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class HarvestUpdateRequest(
    val quantity: String? = null,
    val unit: String? = null,
    val keptQuantity: String? = null,
    val soldQuantity: String? = null,
    val replantIntent: String? = null,
    val replantMonth: String? = null,
    val updatedAt: String,
)
