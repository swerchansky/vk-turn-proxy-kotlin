package com.github.swerchansky.vkturnproxy.di

import com.github.swerchansky.vkturnproxy.credentials.vk.VkCaptchaHandler
import com.github.swerchansky.vkturnproxy.credentials.vk.VkCredentialProvider
import com.github.swerchansky.vkturnproxy.logging.ProxyLogger
import dagger.Module
import dagger.Provides
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Singleton

@Module
class NetworkModule {

    @Provides @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) { json() }
        engine { requestTimeout = 20_000 }
    }

    @Provides @Singleton
    fun provideVkCaptchaHandler(client: HttpClient, logger: ProxyLogger): VkCaptchaHandler =
        VkCaptchaHandler(client, logger = logger)

    @Provides @Singleton
    fun provideVkCredentialProvider(
        client: HttpClient,
        captchaHandler: VkCaptchaHandler,
        logger: ProxyLogger
    ): VkCredentialProvider =
        VkCredentialProvider(client = client, captchaHandler = captchaHandler, logger = logger)

}
