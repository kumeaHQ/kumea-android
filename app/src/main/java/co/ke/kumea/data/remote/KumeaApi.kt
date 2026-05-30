package co.ke.kumea.data.remote

import co.ke.kumea.data.remote.dto.AuthResponse
import co.ke.kumea.data.remote.dto.FarmCreateRequest
import co.ke.kumea.data.remote.dto.FarmResponse
import co.ke.kumea.data.remote.dto.FarmUpdateRequest
import co.ke.kumea.data.remote.dto.FieldCreateRequest
import co.ke.kumea.data.remote.dto.FieldResponse
import co.ke.kumea.data.remote.dto.FieldUpdateRequest
import co.ke.kumea.data.remote.dto.HealthResponse
import co.ke.kumea.data.remote.dto.LoginRequest
import co.ke.kumea.data.remote.dto.LogoutRequest
import co.ke.kumea.data.remote.dto.MessageResponse
import co.ke.kumea.data.remote.dto.RefreshRequest
import co.ke.kumea.data.remote.dto.RegisterRequest
import co.ke.kumea.data.remote.dto.SendOtpRequest
import co.ke.kumea.data.remote.dto.SendOtpResponse
import co.ke.kumea.data.remote.dto.UserProfile
import co.ke.kumea.data.remote.dto.VerifyOtpRequest
import co.ke.kumea.data.remote.dto.VerifyOtpResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Response

interface KumeaApi {
    @GET("health")
    suspend fun health(): HealthResponse

    // ---- Auth ----
    // These return the body directly (not Response<>) so non-2xx responses surface
    // as HttpException and can be mapped to inline errors in the auth ViewModels.

    @POST("auth/send-otp")
    suspend fun sendOtp(@Body body: SendOtpRequest): SendOtpResponse

    @POST("auth/verify-otp")
    suspend fun verifyOtp(@Body body: VerifyOtpRequest): VerifyOtpResponse

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest): MessageResponse

    @GET("auth/me")
    suspend fun me(): UserProfile

    @GET("farms")
    suspend fun getFarms(
        @Query("since") since: String? = null,
        @Query("includeDeleted") includeDeleted: Boolean = false,
    ): List<FarmResponse>

    @POST("farms")
    suspend fun createFarm(@Body farm: FarmCreateRequest): Response<FarmResponse>

    @PATCH("farms/{id}")
    suspend fun updateFarm(
        @Path("id") id: String,
        @Body farm: FarmUpdateRequest,
    ): Response<FarmResponse>

    @DELETE("farms/{id}")
    suspend fun deleteFarm(@Path("id") id: String): Response<Unit>

    // ---- Fields ----
    // Flat /fields resource, identical shape to /farms. farmId travels in the
    // create body, not the path. acres is a String end-to-end.

    @GET("fields")
    suspend fun getFields(
        @Query("since") since: String? = null,
        @Query("includeDeleted") includeDeleted: Boolean = false,
    ): List<FieldResponse>

    @POST("fields")
    suspend fun createField(@Body field: FieldCreateRequest): Response<FieldResponse>

    @PATCH("fields/{id}")
    suspend fun updateField(
        @Path("id") id: String,
        @Body field: FieldUpdateRequest,
    ): Response<FieldResponse>

    @DELETE("fields/{id}")
    suspend fun deleteField(@Path("id") id: String): Response<Unit>
}
