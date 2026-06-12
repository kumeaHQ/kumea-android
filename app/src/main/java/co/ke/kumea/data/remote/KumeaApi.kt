package co.ke.kumea.data.remote

import co.ke.kumea.data.remote.dto.AgentCreateRequest
import co.ke.kumea.data.remote.dto.AgentResponse
import co.ke.kumea.data.remote.dto.AgentUpdateRequest
import co.ke.kumea.data.remote.dto.AuthResponse
import co.ke.kumea.data.remote.dto.EarningsResponse
import co.ke.kumea.data.remote.dto.FarmCreateRequest
import co.ke.kumea.data.remote.dto.FarmResponse
import co.ke.kumea.data.remote.dto.FarmUpdateRequest
import co.ke.kumea.data.remote.dto.FarmLedgerResponse
import co.ke.kumea.data.remote.dto.FieldCreateRequest
import co.ke.kumea.data.remote.dto.FieldLedgerResponse
import co.ke.kumea.data.remote.dto.FieldResponse
import co.ke.kumea.data.remote.dto.FieldUpdateRequest
import co.ke.kumea.data.remote.dto.HealthResponse
import co.ke.kumea.data.remote.dto.NoteCreateRequest
import co.ke.kumea.data.remote.dto.NoteResponse
import co.ke.kumea.data.remote.dto.NoteUpdateRequest
import co.ke.kumea.data.remote.dto.OrderCreateRequest
import co.ke.kumea.data.remote.dto.OrderResponse
import co.ke.kumea.data.remote.dto.OrderUpdateRequest
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

    // ---- Agents (Phase 1a · T5-slice) ----
    // Flat /agents resource, identical offline-sync shape to /farms. The Agent
    // is a root in the distribution graph (synced before anything that
    // attributes to it). No commission field anywhere in the contract — an
    // officer can never be given commission from the app.

    @GET("agents")
    suspend fun getAgents(
        @Query("since") since: String? = null,
        @Query("includeDeleted") includeDeleted: Boolean = false,
        @Query("role") role: String? = null,
    ): List<AgentResponse>

    @POST("agents")
    suspend fun createAgent(@Body agent: AgentCreateRequest): Response<AgentResponse>

    @PATCH("agents/{id}")
    suspend fun updateAgent(
        @Path("id") id: String,
        @Body agent: AgentUpdateRequest,
    ): Response<AgentResponse>

    @DELETE("agents/{id}")
    suspend fun deleteAgent(@Path("id") id: String): Response<Unit>

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

    // ---- Notes ----
    // Flat /notes resource, identical shape to /fields. fieldId travels in the
    // create body, not the path. amountCents is a String end-to-end (money is
    // BigInt cents server-side, never a JSON number).

    @GET("notes")
    suspend fun getNotes(
        @Query("since") since: String? = null,
        @Query("includeDeleted") includeDeleted: Boolean = false,
    ): List<NoteResponse>

    @POST("notes")
    suspend fun createNote(@Body note: NoteCreateRequest): Response<NoteResponse>

    @PATCH("notes/{id}")
    suspend fun updateNote(
        @Path("id") id: String,
        @Body note: NoteUpdateRequest,
    ): Response<NoteResponse>

    @DELETE("notes/{id}")
    suspend fun deleteNote(@Path("id") id: String): Response<Unit>

    // ---- Orders (P1-T3) ----
    // Flat /orders resource, identical shape to /notes. farmerId travels in the
    // create body. unitPrice is a String of integer cents end-to-end (money is
    // BigInt cents server-side, never a JSON number). channel is REQUIRED.
    // The server rejects an extension_officer agentCode (officer allow-list).

    @GET("orders")
    suspend fun getOrders(
        @Query("since") since: String? = null,
        @Query("includeDeleted") includeDeleted: Boolean = false,
    ): List<OrderResponse>

    @POST("orders")
    suspend fun createOrder(@Body order: OrderCreateRequest): Response<OrderResponse>

    @PATCH("orders/{id}")
    suspend fun updateOrder(
        @Path("id") id: String,
        @Body order: OrderUpdateRequest,
    ): Response<OrderResponse>

    @DELETE("orders/{id}")
    suspend fun deleteOrder(@Path("id") id: String): Response<Unit>

    // ---- Ledger (Ticket 3.3) ----
    // Read-only P&L rollups over Notes. No writes. Cents arrive as Strings (money
    // is BigInt cents server-side) and are parsed to signed Long in
    // LedgerRepository, never via Double. netCents can be negative. These return
    // the body directly (not Response<>) so a non-2xx surfaces as HttpException
    // the ViewModel catches and shows on the snackbar — never silently.

    @GET("farms/{farmId}/ledger")
    suspend fun getFarmLedger(@Path("farmId") farmId: String): FarmLedgerResponse

    @GET("fields/{fieldId}/ledger")
    suspend fun getFieldLedger(@Path("fieldId") fieldId: String): FieldLedgerResponse

    // ---- Commission earnings (P1-T6 / T7) ----
    // The read-only earnings surface. INERT in Phase 1 (rates unset → accrued
    // zero, sachets counted). Returned as Response<> so the 200-with-null-body
    // case (a plain farmer or an extension_officer — no earnings construct) can be
    // read as body() == null rather than crashing deserialization. period is
    // "YYYY-MM"; omit it for the current month. The village_agent surface is the
    // only caller — an officer never reaches it (structurally unreachable, T7).
    @GET("commission/me")
    suspend fun getMyEarnings(
        @Query("period") period: String? = null,
    ): Response<EarningsResponse>
}
