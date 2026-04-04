package com.github.swerchansky.vkturnproxy.credentials

import com.github.swerchansky.vkturnproxy.error.TurnProxyError
import com.github.swerchansky.vkturnproxy.logging.NoOpLogger
import com.github.swerchansky.vkturnproxy.logging.ProxyLogger
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

private val USER_AGENTS = listOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0",
    "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 YaBrowser/24.1.0.0 Yowser/2.5 Safari/537.36",
    "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 YaBrowser/24.1.2.0 Yowser/2.5 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 OPR/112.0.0.0",
    "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 OPR/111.0.0.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
)

private val FIRST_NAMES = listOf(
    "Александр", "Дмитрий", "Максим", "Сергей", "Андрей", "Алексей", "Артём", "Илья",
    "Кирилл", "Михаил", "Никита", "Матвей", "Роман", "Егор", "Арсений", "Иван",
    "Денис", "Даниил", "Тимофей", "Владислав", "Игорь", "Павел", "Руслан", "Марк",
    "Анна", "Мария", "Елена", "Дарья", "Анастасия", "Екатерина", "Виктория", "Ольга",
    "Наталья", "Юлия", "Татьяна", "Светлана", "Ирина", "Ксения", "Алина", "Елизавета",
)

private val LAST_NAMES = listOf(
    "Иванов", "Смирнов", "Кузнецов", "Попов", "Васильев", "Петров", "Соколов", "Михайлов",
    "Новиков", "Федоров", "Морозов", "Волков", "Алексеев", "Лебедев", "Семенов", "Егоров",
    "Павлов", "Козлов", "Степанов", "Николаев", "Орлов", "Андреев", "Макаров", "Никитин",
    "Захаров", "Зайцев", "Соловьев", "Борисов", "Яковлев", "Григорьев", "Романов", "Воробьев",
)

private fun randomUserAgent() = USER_AGENTS[Random.nextInt(USER_AGENTS.size)]

private fun randomName(): String {
    val first = FIRST_NAMES[Random.nextInt(FIRST_NAMES.size)]
    if (Random.nextFloat() < 0.3f) return first
    val last = LAST_NAMES[Random.nextInt(LAST_NAMES.size)]
    val lastTwo = first.takeLast(2)
    return if (lastTwo == "ая" || lastTwo == "ья" || first.last() == 'а' || first.last() == 'я')
        "$first ${last}а"
    else
        "$first $last"
}

/**
 * Extracts TURN credentials from a VK call invite link.
 *
 * Flow (4 POST requests):
 *  1. login.vk.ru    → anonymous token1
 *  2. api.vk.ru      → calls token2  (with automatic Not Robot captcha solving on error_code=14)
 *  3. calls.okcdn.ru → session token3 (auth.anonymLogin)
 *  4. calls.okcdn.ru → TURN creds (vchat.joinConversationByLink)
 */
class VkCredentialProvider(
    private val client: HttpClient,
    private val captchaSolver: VkCaptchaSolver,
    private val logger: ProxyLogger = NoOpLogger,
) : CredentialProvider {

    private companion object {
        const val TAG = "VkCredentials"
    }

    override suspend fun getCredentials(link: String): TurnCredentials {
        val userAgent = randomUserAgent()
        val name = randomName()
        logger.debug(TAG, "Identity: name=\"$name\" ua=\"${userAgent.take(40)}...\"")

        // Step 1: anonymous token
        val resp1 = doPost(
            url = "https://login.vk.ru/?act=get_anonym_token",
            body = "client_id=6287487&token_type=messages&client_secret=QbYic1K3lEV5kTGiqlq2" +
                    "&version=1&app_id=6287487",
            userAgent = userAgent,
        )
        if (resp1["data"] == null) throw TurnProxyError.CredentialFetchFailed("Step 1 (anon token) failed, got: $resp1")
        val token1 = resp1["data"]!!.jsonObject["access_token"]!!.jsonPrimitive.content

        // Step 2: calls token (with automatic captcha solving on error_code=14)
        val step2Url = "https://api.vk.ru/method/calls.getAnonymousToken?v=5.275&client_id=6287487"
        val encodedName = name.encodeVkParam()
        val step2Body = "vk_join_link=https://vk.ru/call/join/$link&name=$encodedName&access_token=$token1"
        var resp2 = doPost(url = step2Url, body = step2Body, userAgent = userAgent)

        val captchaError = resp2["error"]?.jsonObject?.let { VkCaptchaError.parse(it) }
        if (captchaError != null && captchaError.isNotRobotCaptcha) {
            logger.info(TAG, "Captcha error_code=14 detected, solving...")
            val successToken = captchaSolver.solve(captchaError)
            logger.info(TAG, "PoW succeeded, retrying with success_token")
            val retryBody = "$step2Body" +
                "&captcha_key=" +
                "&captcha_sid=${captchaError.captchaSid}" +
                "&is_sound_captcha=0" +
                "&success_token=$successToken" +
                "&captcha_ts=${captchaError.captchaTs}" +
                "&captcha_attempt=${captchaError.captchaAttempt}"
            resp2 = doPost(url = step2Url, body = retryBody, userAgent = userAgent)
            logger.debug(TAG, "Captcha retry response error=${resp2["error"]}")
        }
        if (resp2["response"] == null) throw TurnProxyError.CredentialFetchFailed("Step 2 (calls token) failed, got: $resp2")
        val token2 = resp2["response"]!!.jsonObject["token"]!!.jsonPrimitive.content

        // Step 3: OkCDN session
        val deviceId = generateUUID()
        val resp3 = doPost(
            url = "https://calls.okcdn.ru/fb.do",
            body = "session_data=%7B%22version%22%3A2%2C%22device_id%22%3A%22$deviceId" +
                    "%22%2C%22client_version%22%3A1.1%2C%22client_type%22%3A%22SDK_JS%22%7D" +
                    "&method=auth.anonymLogin&format=JSON&application_key=CGMMEJLGDIHBABABA",
            userAgent = userAgent,
        )
        if (resp3["session_key"] == null) throw TurnProxyError.CredentialFetchFailed("Step 3 (OkCDN session) failed, got: $resp3")
        val token3 = resp3["session_key"]!!.jsonPrimitive.content

        // Step 4: join conference → TURN creds
        val resp4 = doPost(
            url = "https://calls.okcdn.ru/fb.do",
            body = "joinLink=$link&isVideo=false&protocolVersion=5&anonymToken=$token2" +
                    "&method=vchat.joinConversationByLink&format=JSON" +
                    "&application_key=CGMMEJLGDIHBABABA&session_key=$token3",
            userAgent = userAgent,
        )
        if (resp4["turn_server"] == null) throw TurnProxyError.CredentialFetchFailed("Step 4 (join conference) failed, got: $resp4")
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

    private suspend fun doPost(url: String, body: String, userAgent: String): JsonObject {
        val response: String = client.post(url) {
            headers {
                append("User-Agent", userAgent)
            }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }.body()
        return Json.parseToJsonElement(response).jsonObject
    }
}

/** Percent-encodes a string for use in application/x-www-form-urlencoded bodies. */
private fun String.encodeVkParam(): String = buildString {
    for (ch in this@encodeVkParam) {
        when {
            ch.isLetterOrDigit() || ch in "-._~" -> append(ch)
            else -> ch.toString().encodeToByteArray().forEach { b ->
                append('%')
                append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF])
                append("0123456789ABCDEF"[b.toInt() and 0xF])
            }
        }
    }
}

internal expect fun generateUUID(): String
