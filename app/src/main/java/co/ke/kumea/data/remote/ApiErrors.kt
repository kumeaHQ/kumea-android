package co.ke.kumea.data.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extract the machine-readable `code` from a NestJS error body. The service
 * guards throw `{"code":"agent_code_not_found","message":"..."}`-shaped bodies;
 * the FK-defer logic in OrderRepository/FarmRepository keys off that code to tell
 * "FK parent not synced yet" (defer + retry) apart from a permanent rejection.
 *
 * Returns null when the body is blank, malformed, or carries no `code` (e.g. a
 * 404 whose body is `{"statusCode":404,"message":"Farmer not found",...}`, or a
 * class-validator 400 whose `message` is an array). Never throws.
 */
fun parseErrorCode(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return try {
        Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
