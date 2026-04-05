package com.github.swerchansky.vkturnproxy.credentials.vk

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Low-level HTTP client for VK API calls.
 * Responsible only for transport: serializes the request and deserializes the JSON response.
 */
internal class VkApiClient(private val httpClient: HttpClient) {

    suspend fun post(url: String, body: String, userAgent: String): JsonObject {
        val response: String = httpClient.post(url) {
            headers {
                append("User-Agent", userAgent)
            }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }.body()
        return Json.parseToJsonElement(response).jsonObject
    }
}
