package com.github.swerchansky.vkturnproxy.credentials.vk

import com.github.swerchansky.vkturnproxy.credentials.CredentialProvider
import com.github.swerchansky.vkturnproxy.logging.NoOpLogger
import com.github.swerchansky.vkturnproxy.logging.ProxyLogger
import com.github.swerchansky.vkturnproxy.turn.TurnCredentials
import io.ktor.client.HttpClient

/**
 * [CredentialProvider] that extracts TURN credentials from a VK call invite link.
 *
 * Delegates to [VkTokenFlow] for orchestration and [VkCaptchaHandler] for captcha solving.
 */
class VkCredentialProvider(
    client: HttpClient,
    captchaHandler: VkCaptchaHandler,
    logger: ProxyLogger = NoOpLogger,
) : CredentialProvider {

    private val tokenFlow = VkTokenFlow(VkApiClient(client), captchaHandler, logger)

    override suspend fun getCredentials(link: String): TurnCredentials =
        tokenFlow.fetchCredentials(link)
}
