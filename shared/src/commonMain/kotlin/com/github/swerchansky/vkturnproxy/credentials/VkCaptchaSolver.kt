package com.github.swerchansky.vkturnproxy.credentials

import com.github.swerchansky.vkturnproxy.error.TurnProxyError
import com.github.swerchansky.vkturnproxy.logging.NoOpLogger
import com.github.swerchansky.vkturnproxy.logging.ProxyLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

internal expect fun sha256Hex(input: String): String

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"

private const val STEP_DELAY_MS = 200L

/**
 * Solves VK's "Not Robot" captcha (error_code=14).
 *
 * Automatic path (checkbox captcha):
 *  1. Fetch captcha HTML → extract powInput + difficulty
 *  2. Solve SHA-256 PoW
 *  3. captchaNotRobot API (4 steps) → success_token
 *  Returns: success_token string (non-null)
 *
 * Fallback path (slider captcha, status=BOT):
 *  [onFallbackRequired] is called with the captcha URL and must suspend until
 *  the user completes the captcha in a WebView (detected via URL redirect to call page).
 *  Returns: null → caller must do a plain fresh retry of the API call.
 *  VK's backend marks the session verified after WebView solve, so a fresh call succeeds.
 */
class VkCaptchaSolver(
    private val client: HttpClient,
    private val logger: ProxyLogger = NoOpLogger,
) {

    private companion object {
        const val TAG = "VkCaptcha"
    }

    /**
     * Returns success_token on success.
     * Throws if PoW fails (e.g. status=BOT — slider captcha that cannot be solved automatically).
     */
    suspend fun solve(error: VkCaptchaError): String {
        require(error.isNotRobotCaptcha) { "Not a solvable captcha error: $error" }
        logger.info(TAG, "Solving Not Robot Captcha, sid=${error.captchaSid}")

        val (powInput, difficulty) = fetchPowChallenge(error.redirectUri)
        logger.info(TAG, "PoW challenge: difficulty=$difficulty")

        val hash = solvePoW(powInput, difficulty)
        logger.info(TAG, "PoW solved: ${hash.take(16)}...")

        val autoToken = callCaptchaNotRobotApi(sessionToken = error.sessionToken, hash = hash)
            ?: throw TurnProxyError.CredentialFetchFailed("captchaNotRobot returned non-OK status (slider captcha) — cannot solve automatically")

        logger.info(TAG, "Automatic solve succeeded")
        return autoToken
    }

    private suspend fun fetchPowChallenge(redirectUri: String): Pair<String, Int> {
        val html: String = client.get(redirectUri) {
            headers {
                append("User-Agent", BROWSER_USER_AGENT)
                append("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                append("Accept-Language", "en-US,en;q=0.9")
            }
        }.body()

        val powInput = Regex("""const\s+powInput\s*=\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: throw TurnProxyError.CredentialFetchFailed("powInput not found in captcha HTML")

        val difficulty = Regex("""startsWith\('0'\.repeat\((\d+)\)\)""")
            .find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 2

        return powInput to difficulty
    }

    private fun solvePoW(powInput: String, difficulty: Int): String {
        val target = "0".repeat(difficulty)
        for (nonce in 1..10_000_000) {
            val hash = sha256Hex(powInput + nonce)
            if (hash.startsWith(target)) return hash
        }
        throw TurnProxyError.CredentialFetchFailed("PoW solve failed: no solution found within 10M iterations (difficulty=$difficulty)")
    }

    /** Returns success_token if status=OK, null if status=BOT or other non-OK. */
    private suspend fun callCaptchaNotRobotApi(sessionToken: String, hash: String): String? {
        val baseParams = "session_token=${sessionToken.encodeURLParameter()}&domain=vk.com&adFp=&access_token="
        val browserFp = buildBrowserFp()

        logger.info(TAG, "API 1/4: settings")
        vkApiPost("captchaNotRobot.settings", baseParams)
        delay(STEP_DELAY_MS)

        logger.info(TAG, "API 2/4: componentDone")
        val deviceJson = """{"screenWidth":1920,"screenHeight":1080,"screenAvailWidth":1920,"screenAvailHeight":1032,"innerWidth":1920,"innerHeight":945,"devicePixelRatio":1,"language":"en-US","languages":["en-US"],"webdriver":false,"hardwareConcurrency":16,"deviceMemory":8,"connectionEffectiveType":"4g","notificationsPermission":"denied"}"""
        vkApiPost(
            "captchaNotRobot.componentDone",
            "$baseParams&browser_fp=$browserFp&device=${deviceJson.encodeURLParameter()}",
        )
        delay(STEP_DELAY_MS)

        logger.info(TAG, "API 3/4: check")
        val checkResp = vkApiPost("captchaNotRobot.check", buildCheckParams(baseParams, browserFp, hash))
        delay(STEP_DELAY_MS)

        val successToken = extractSuccessToken(checkResp)
        if (successToken != null) {
            endSession(baseParams)
            return successToken
        }

        val status = checkResp["response"]?.jsonObject?.get("status")?.jsonPrimitive?.content
        logger.info(TAG, "check returned status=$status, switching to WebView fallback")
        return null
    }

    private fun buildCheckParams(baseParams: String, browserFp: String, hash: String): String {
        val cursorJson = """[{"x":950,"y":500},{"x":945,"y":510},{"x":940,"y":520},{"x":938,"y":525},{"x":938,"y":525}]"""
        val downlink = "[9.5,9.5,9.5,9.5,9.5,9.5,9.5,9.5,9.5,9.5,9.5,9.5,9.5,9.5,9.5,9.5]"
        val answer = "e30="
        val debugInfo = "d44f534ce8deb56ba20be52e05c433309b49ee4d2a70602deeb17a1954257785"
        return "$baseParams" +
            "&accelerometer=${"[]".encodeURLParameter()}" +
            "&gyroscope=${"[]".encodeURLParameter()}" +
            "&motion=${"[]".encodeURLParameter()}" +
            "&cursor=${cursorJson.encodeURLParameter()}" +
            "&taps=${"[]".encodeURLParameter()}" +
            "&connectionRtt=${"[]".encodeURLParameter()}" +
            "&connectionDownlink=${downlink.encodeURLParameter()}" +
            "&browser_fp=$browserFp" +
            "&hash=$hash" +
            "&answer=$answer" +
            "&debug_info=$debugInfo"
    }

    private fun extractSuccessToken(resp: JsonObject): String? {
        val obj = resp["response"]?.jsonObject ?: return null
        if (obj["status"]?.jsonPrimitive?.content != "OK") return null
        return obj["success_token"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
    }

    private suspend fun endSession(baseParams: String) {
        logger.info(TAG, "API 4/4: endSession")
        try {
            vkApiPost("captchaNotRobot.endSession", baseParams)
        } catch (e: Exception) {
            logger.info(TAG, "endSession failed (non-critical): ${e.message}")
        }
    }

    private suspend fun vkApiPost(method: String, body: String): JsonObject {
        val response: String = client.post("https://api.vk.ru/method/$method?v=5.131") {
            headers {
                append("User-Agent", BROWSER_USER_AGENT)
                append("Origin", "https://vk.ru")
                append("Referer", "https://vk.ru/")
                append("sec-ch-ua-platform", "\"Linux\"")
                append("sec-ch-ua", "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"")
                append("sec-ch-ua-mobile", "?0")
                append("DNT", "1")
                append("Sec-Fetch-Site", "same-site")
                append("Sec-Fetch-Mode", "cors")
                append("Sec-Fetch-Dest", "empty")
                append("Sec-GPC", "1")
            }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }.body()
        return Json.parseToJsonElement(response).jsonObject
    }

    private fun buildBrowserFp(): String {
        val hi = Random.nextLong().and(0x7FFFFFFFFFFFFFFF)
        val lo = Random.nextLong().and(0x7FFFFFFFFFFFFFFF)
        return "%016x%016x".format(hi, lo)
    }
}
