package co.ke.kumea.data.repository

import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.HealthResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthRepository @Inject constructor(
    private val api: KumeaApi,
) {
    suspend fun ping(): HealthResponse = api.health()
}
