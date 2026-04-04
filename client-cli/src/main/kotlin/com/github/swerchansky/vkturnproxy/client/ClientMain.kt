package com.github.swerchansky.vkturnproxy.client

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.swerchansky.vkturnproxy.credentials.VkCaptchaSolver
import com.github.swerchansky.vkturnproxy.credentials.VkCredentialProvider
import com.github.swerchansky.vkturnproxy.proxy.parseTurnProxyAddr
import com.github.swerchansky.vkturnproxy.proxy.runProxyConnections
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
import java.net.DatagramSocket
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("client")

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

        log.info("Provider: VK · $nConnections connections ($mode) · listen: $listen → peer: $peer")

        val httpClient = HttpClient(CIO) {
            install(WebSockets)
            install(ContentNegotiation) { json() }
            engine { requestTimeout = 20_000 }
        }

        val provider = VkCredentialProvider(
            httpClient,
            VkCaptchaSolver(httpClient, logger = { log.info(it) }),
            logger = { log.info(it) },
        )

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Shutting down...")
            scope.cancel()
            httpClient.close()
        })

        runBlocking(Dispatchers.IO) {
            val localSocket = DatagramSocket(listenAddr)
            runProxyConnections(
                link = rawLink,
                peerAddr = peerAddr,
                localSocket = localSocket,
                provider = provider,
                nConnections = nConnections,
                useDtls = !noDtls,
                turnHostOverride = turnHost,
                turnPortOverride = turnPort,
                logger = { log.info(it) },
                onFirstReady = { relayAddr ->
                    log.info("Tunnel ready · relay: $relayAddr · listening on $listen")
                },
                onConnectionReady = { c, total, _ ->
                    if (c < total) log.info("Establishing connections $c/$total...")
                    else log.info("All $total/$total connections established")
                },
            )
        }
    }
}
