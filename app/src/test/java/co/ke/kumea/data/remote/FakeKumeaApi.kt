package co.ke.kumea.data.remote

import co.ke.kumea.data.remote.dto.AgentCreateRequest
import co.ke.kumea.data.remote.dto.AgentResponse
import co.ke.kumea.data.remote.dto.AgentUpdateRequest
import co.ke.kumea.data.remote.dto.AuthResponse
import co.ke.kumea.data.remote.dto.FarmCreateRequest
import co.ke.kumea.data.remote.dto.FarmResponse
import co.ke.kumea.data.remote.dto.FarmLedgerResponse
import co.ke.kumea.data.remote.dto.FarmUpdateRequest
import co.ke.kumea.data.remote.dto.FieldCreateRequest
import co.ke.kumea.data.remote.dto.FieldLedgerResponse
import co.ke.kumea.data.remote.dto.FieldResponse
import co.ke.kumea.data.remote.dto.FieldUpdateRequest
import co.ke.kumea.data.remote.dto.HealthResponse
import co.ke.kumea.data.remote.dto.LoginRequest
import co.ke.kumea.data.remote.dto.LogoutRequest
import co.ke.kumea.data.remote.dto.MessageResponse
import co.ke.kumea.data.remote.dto.NoteCreateRequest
import co.ke.kumea.data.remote.dto.NoteResponse
import co.ke.kumea.data.remote.dto.NoteUpdateRequest
import co.ke.kumea.data.remote.dto.OrderCreateRequest
import co.ke.kumea.data.remote.dto.OrderResponse
import co.ke.kumea.data.remote.dto.OrderUpdateRequest
import co.ke.kumea.data.remote.dto.RefreshRequest
import co.ke.kumea.data.remote.dto.RegisterRequest
import co.ke.kumea.data.remote.dto.SendOtpRequest
import co.ke.kumea.data.remote.dto.SendOtpResponse
import co.ke.kumea.data.remote.dto.UserProfile
import co.ke.kumea.data.remote.dto.VerifyOtpRequest
import co.ke.kumea.data.remote.dto.VerifyOtpResponse
import retrofit2.Response

/**
 * Hand-rolled fake of KumeaApi for JVM unit tests.
 *
 * The project has no mocking library on the test classpath (mockk is not a
 * declared dependency and can't be resolved in the offline build), so tests use
 * plain fakes instead. Every method throws by default; a test subclasses this
 * and overrides only the endpoint it exercises.
 */
open class FakeKumeaApi : KumeaApi {
    private fun nope(): Nothing = throw NotImplementedError("FakeKumeaApi: not stubbed for this test")

    override suspend fun health(): HealthResponse = nope()
    override suspend fun sendOtp(body: SendOtpRequest): SendOtpResponse = nope()
    override suspend fun verifyOtp(body: VerifyOtpRequest): VerifyOtpResponse = nope()
    override suspend fun register(body: RegisterRequest): AuthResponse = nope()
    override suspend fun login(body: LoginRequest): AuthResponse = nope()
    override suspend fun refresh(body: RefreshRequest): AuthResponse = nope()
    override suspend fun logout(body: LogoutRequest): MessageResponse = nope()
    override suspend fun me(): UserProfile = nope()

    override suspend fun getAgents(since: String?, includeDeleted: Boolean, role: String?): List<AgentResponse> = nope()
    override suspend fun createAgent(agent: AgentCreateRequest): Response<AgentResponse> = nope()
    override suspend fun updateAgent(id: String, agent: AgentUpdateRequest): Response<AgentResponse> = nope()
    override suspend fun deleteAgent(id: String): Response<Unit> = nope()

    override suspend fun getFarms(since: String?, includeDeleted: Boolean): List<FarmResponse> = nope()
    override suspend fun createFarm(farm: FarmCreateRequest): Response<FarmResponse> = nope()
    override suspend fun updateFarm(id: String, farm: FarmUpdateRequest): Response<FarmResponse> = nope()
    override suspend fun deleteFarm(id: String): Response<Unit> = nope()

    override suspend fun getFields(since: String?, includeDeleted: Boolean): List<FieldResponse> = nope()
    override suspend fun createField(field: FieldCreateRequest): Response<FieldResponse> = nope()
    override suspend fun updateField(id: String, field: FieldUpdateRequest): Response<FieldResponse> = nope()
    override suspend fun deleteField(id: String): Response<Unit> = nope()

    override suspend fun getNotes(since: String?, includeDeleted: Boolean): List<NoteResponse> = nope()
    override suspend fun createNote(note: NoteCreateRequest): Response<NoteResponse> = nope()
    override suspend fun updateNote(id: String, note: NoteUpdateRequest): Response<NoteResponse> = nope()
    override suspend fun deleteNote(id: String): Response<Unit> = nope()

    override suspend fun getOrders(since: String?, includeDeleted: Boolean): List<OrderResponse> = nope()
    override suspend fun createOrder(order: OrderCreateRequest): Response<OrderResponse> = nope()
    override suspend fun updateOrder(id: String, order: OrderUpdateRequest): Response<OrderResponse> = nope()
    override suspend fun deleteOrder(id: String): Response<Unit> = nope()

    override suspend fun getFarmLedger(farmId: String): FarmLedgerResponse = nope()
    override suspend fun getFieldLedger(fieldId: String): FieldLedgerResponse = nope()
}
