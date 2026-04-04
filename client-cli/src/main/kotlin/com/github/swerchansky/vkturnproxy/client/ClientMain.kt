package com.github.swerchansky.vkturnproxy.client

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.swerchansky.vkturnproxy.credentials.vk.VkCaptchaHandler
import com.github.swerchansky.vkturnproxy.credentials.vk.VkCredentialProvider
import com.github.swerchansky.vkturnproxy.logging.JvmProxyLogger
import com.github.swerchansky.vkturnproxy.proxy.TunnelEvent
import com.github.swerchansky.vkturnproxy.proxy.TunnelParams
import com.github.swerchansky.vkturnproxy.proxy.TurnProxyEngine
import com.github.swerchansky.vkturnproxy.proxy.parseTurnProxyAddr
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.net.DatagramSocket
import java.security.Security

private val log = JvmProxyLogger("client")

fun main(args: Array<String>) {
    val logConfig = object {}.javaClass.classLoader?.getResourceAsStream("logging.properties")
    if (logConfig != null) {
        java.util.logging.LogManager.getLogManager().readConfiguration(logConfig)
    }
    Security.addProvider(BouncyCastleProvider())
    ClientCommand().main(args)
}

private class ClientCommand : CliktCommand(name = "client") {

    val peer by option("--peer", help = "Server address (host:port)").required()
    val vkLink by option(
        "--vk-link",
        help = "VK call invite link (https://vk.com/call/join/...)"
    ).required()
    val listen by option("--listen", help = "Local UDP address").default("127.0.0.1:9000")
    val nConnections by option("--n", help = "Parallel TURN connections (default: 16)").int()
        .default(16)
    val turnHost by option("--turn", help = "Override TURN server IP")
    val turnPort by option("--port", help = "Override TURN server port")
    val noDtls by option(
        "--no-dtls",
        help = "Disable DTLS obfuscation (DO NOT USE in production)"
    ).flag()

    override fun run() {
        val peerAddr = parseTurnProxyAddr(peer)
        val listenAddr = parseTurnProxyAddr(listen)
        val mode = if (noDtls) "plain" else "DTLS/UDP"

        val rawLink = vkLink
            .split("join/").last()
            .substringBefore("?").substringBefore("/").substringBefore("#")

        log.info("client", "Provider: VK · $nConnections connections ($mode) · listen: $listen → peer: $peer")

        val httpClient = HttpClient(CIO) {
            install(WebSockets)
            install(ContentNegotiation) { json() }
            engine { requestTimeout = 20_000 }
        }

        val provider = VkCredentialProvider(
            httpClient,
            VkCaptchaHandler(httpClient, logger = log),
            logger = log,
        )

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("client", "Shutting down...")
            scope.cancel()
            httpClient.close()
        })

        runBlocking(Dispatchers.IO) {
            val localSocket = DatagramSocket(listenAddr)
            val engine = TurnProxyEngine(provider, log)
            engine.runConnections(
                TunnelParams(
                    link = rawLink,
                    peerAddr = peerAddr,
                    localSocket = localSocket,
                    nConnections = nConnections,
                    useDtls = !noDtls,
                    turnHostOverride = turnHost,
                    turnPortOverride = turnPort,
                )
            ).collect { event ->
                when (event) {
                    is TunnelEvent.FirstReady ->
                        log.info(
                            "client",
                            "Tunnel ready · relay: ${event.relayAddr} · listening on $listen"
                        )

                    is TunnelEvent.ConnectionReady ->
                        if (event.count < event.total)
                            log.info(
                                "client",
                                "Establishing connections ${event.count}/${event.total}..."
                            )
                        else
                            log.info(
                                "client",
                                "All ${event.total}/${event.total} connections established"
                            )

                    else -> {}
                }
            }
        }
    }
}
