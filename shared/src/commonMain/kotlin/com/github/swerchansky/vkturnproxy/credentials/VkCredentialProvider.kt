package com.github.swerchansky.vkturnproxy.credentials

import com.github.swerchansky.vkturnproxy.turn.TurnCredentials
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"

/**
 * Extracts TURN credentials from a VK call invite link.
 *
 * Flow (4 POST requests):
 *  1. login.vk.ru    → anonymous token1
 *  2. api.vk.ru      → calls token2
 *  3. calls.okcdn.ru → session token3 (auth.anonymLogin)
 *  4. calls.okcdn.ru → TURN creds (vchat.joinConversationByLink)
 */
class VkCredentialProvider(private val client: HttpClient) : CredentialProvider {

    override suspend fun getCredentials(link: String): TurnCredentials {
        // Step 1: anonymous token
        val resp1 = doPost(
            url = "https://login.vk.ru/?act=get_anonym_token",
            body = "client_id=6287487&token_type=messages&client_secret=QbYic1K3lEV5kTGiqlq2" +
                    "&version=1&app_id=6287487",
        )
        check(resp1["data"] != null) { "Step 1 failed, got: $resp1" }
        val token1 = resp1["data"]!!.jsonObject["access_token"]!!.jsonPrimitive.content

        // Step 2: calls token
        val resp2 = doPost(
            url = "https://api.vk.ru/method/calls.getAnonymousToken?v=5.274&client_id=6287487",
            body = "vk_join_link=https://vk.com/call/join/$link&name=123&access_token=$token1",
        )
        check(resp2["response"] != null) { "Step 2 failed, got: $resp2" }
        val token2 = resp2["response"]!!.jsonObject["token"]!!.jsonPrimitive.content

        // Step 3: OkCDN session
        val deviceId = generateUUID()
        val resp3 = doPost(
            url = "https://calls.okcdn.ru/fb.do",
            body = "session_data=%7B%22version%22%3A2%2C%22device_id%22%3A%22$deviceId" +
                    "%22%2C%22client_version%22%3A1.1%2C%22client_type%22%3A%22SDK_JS%22%7D" +
                    "&method=auth.anonymLogin&format=JSON&application_key=CGMMEJLGDIHBABABA",
        )
        check(resp3["session_key"] != null) { "Step 3 failed, got: $resp3" }
        val token3 = resp3["session_key"]!!.jsonPrimitive.content

        // Step 4: join conference → TURN creds
        val resp4 = doPost(
            url = "https://calls.okcdn.ru/fb.do",
            body = "joinLink=$link&isVideo=false&protocolVersion=5&anonymToken=$token2" +
                    "&method=vchat.joinConversationByLink&format=JSON" +
                    "&application_key=CGMMEJLGDIHBABABA&session_key=$token3",
        )
        check(resp4["turn_server"] != null) { "Step 4 failed, got: $resp4" }
        val turnServer = resp4["turn_server"]!!.jsonObject
        val username = turnServer["username"]!!.jsonPrimitive.content
        val password = turnServer["credential"]!!.jsonPrimitive.content
        val rawUrl = turnServer["urls"]!!.jsonArray[0].jsonPrimitive.content

        val address = rawUrl
            .split("?")[0]
            .removePrefix("turns:")
            .removePrefix("turn:")

        return TurnCredentials(username, password, address)
    }

    private suspend fun doPost(url: String, body: String): JsonObject {
        val response: String = client.post(url) {
            headers {
                append("User-Agent", USER_AGENT)
            }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }.body()
        return Json.parseToJsonElement(response).jsonObject
    }
}

internal expect fun generateUUID(): String
