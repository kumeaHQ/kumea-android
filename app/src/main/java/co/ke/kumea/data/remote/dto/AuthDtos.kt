package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

// ---- Request bodies ----

@Serializable
data class SendOtpRequest(val phone: String)

@Serializable
data class VerifyOtpRequest(val phone: String, val code: String)

@Serializable
data class RegisterRequest(
    val registrationToken: String,
    val pin: String,
    // T4: the Agent who registered this farmer (optional, non-commercial;
    // officers allowed). referrer = who registered; agent_code = who sold.
    val referrerAgentId: String? = null,
)

@Serializable
data class LoginRequest(val phone: String, val pin: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

// ---- Responses ----

@Serializable
data class SendOtpResponse(val maskedPhone: String)

@Serializable
data class VerifyOtpResponse(
    val registrationToken: String,
    val isNewUser: Boolean,
)

@Serializable
data class AuthUser(
    val id: String,
    val phone: String,
    val role: String,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: AuthUser,
)

@Serializable
data class UserProfile(
    val id: String,
    val phone: String,
    val role: String,
    // Server sends an ISO timestamp; optional so a future shape change won't crash startup.
    val createdAt: String? = null,
)

@Serializable
data class MessageResponse(val message: String)
