package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Harvest wire contract (Build-2). Quantities are JSON STRINGS of the human
 * decimal ("1.2"), never JSON numbers — same rule as money cents and acres.
 * unit / replantIntent are the server's lowercase enum strings verbatim.
 */
@Serializable
data class HarvestCreateRequest(
    val id: String,
    val fieldId: String,
    val harvestDate: String,
    val quantity: String,
    val unit: String,
    val kept: String? = null,
    val sold: String? = null,
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
    val kept: String? = null,
    val sold: String? = null,
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
    val kept: String? = null,
    val sold: String? = null,
    val replantIntent: String? = null,
    val replantMonth: String? = null,
    val updatedAt: String,
)
