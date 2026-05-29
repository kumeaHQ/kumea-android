package co.ke.kumea.data.remote

import co.ke.kumea.data.remote.dto.FarmCreateRequest
import co.ke.kumea.data.remote.dto.FarmResponse
import co.ke.kumea.data.remote.dto.FarmUpdateRequest
import co.ke.kumea.data.remote.dto.HealthResponse
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
}
