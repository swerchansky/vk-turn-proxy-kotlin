package com.github.swerchansky.vkturnproxy.credentials

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

data class VkCaptchaError(
    val errorCode: Int,
    val captchaSid: String,
    val redirectUri: String,
    val sessionToken: String,
    val captchaTs: String,
    val captchaAttempt: String,
) {
    val isNotRobotCaptcha: Boolean
        get() = errorCode == 14 && redirectUri.isNotEmpty() && sessionToken.isNotEmpty()

    companion object {
        fun parse(error: JsonObject): VkCaptchaError? {
            val code = error["error_code"]?.jsonPrimitive?.intOrNull ?: return null
            val redirectUri = error["redirect_uri"]?.jsonPrimitive?.content.orEmpty()
            val sessionToken = if (redirectUri.isNotEmpty()) extractQueryParam(redirectUri, "session_token") else ""
            return VkCaptchaError(
                errorCode = code,
                captchaSid = error["captcha_sid"]?.jsonPrimitive?.content.orEmpty(),
                redirectUri = redirectUri,
                sessionToken = sessionToken,
                captchaTs = error["captcha_ts"]?.jsonPrimitive?.content.orEmpty(),
                captchaAttempt = error["captcha_attempt"]?.jsonPrimitive?.content.orEmpty(),
            )
        }

        private fun extractQueryParam(url: String, param: String): String {
            val marker = "$param="
            val start = url.indexOf(marker).takeIf { it != -1 } ?: return ""
            val valueStart = start + marker.length
            val end = url.indexOf('&', valueStart)
            return if (end == -1) url.substring(valueStart) else url.substring(valueStart, end)
        }
    }
}
