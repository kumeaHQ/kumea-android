package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
    val uptime: Double,
    val timestamp: String,
)
