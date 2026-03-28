package com.github.swerchansky.vkturnproxy.client

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.swerchansky.vkturnproxy.credentials.CredentialProvider
import com.github.swerchansky.vkturnproxy.credentials.VkCredentialProvider
import com.github.swerchansky.vkturnproxy.credentials.YandexCredentialProvider
import com.github.swerchansky.vkturnproxy.dtls.DtlsClient
import com.github.swerchansky.vkturnproxy.turn.RequestedAddressFamily
import com.github.swerchansky.vkturnproxy.turn.TurnClient
import com.github.swerchansky.vkturnproxy.turn.TurnCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("client")

fun main(args: Array<String>) {
    ClientCommand().main(args)
}

private class ClientCommand : CliktCommand(name = "client") {

    val peer by option("--peer", help = "Server address (host:port)").required()
    val vkLink by option("--vk-link", help = "VK call invite link (https://vk.com/call/join/...)")
    val yandexLink by option("--yandex-link", help = "Yandex Telemost link (https://telemost.yandex.ru/j/...)")
    val listen by option("--listen", help = "Local UDP address").default("127.0.0.1:9000")
    val nConnections by option("--n", help = "Parallel TURN connections (default: 16 VK / 1 Yandex)").int().default(0)
    val turnHost by option("--turn", help = "Override TURN server IP")
    val turnPort by option("--port", help = "Override TURN server port")
    val useUdp by option("--udp", help = "Use UDP for TURN (default: TCP)").flag()
    val noDtls by option("--no-dtls", help = "Disable DTLS obfuscation (DO NOT USE in production)").flag()

    override fun run() {
        require((vkLink == null) != (yandexLink == null)) {
            "Exactly one of --vk-link or --yandex-link is required"
        }

        val peerAddr = parseAddr(peer)
        val listenAddr = parseAddr(listen)
        val isVk = vkLink != null

        val rawLink = (if (isVk) vkLink!! else yandexLink!!)
            .let { if (isVk) it.split("join/").last() else it.split("j/").last() }
            .substringBefore("?").substringBefore("/").substringBefore("#")

        val n = if (nConnections > 0) nConnections else if (isVk) 16 else 1

        val httpClient = HttpClient(CIO) {
            install(WebSockets)
            install(ContentNegotiation) { json() }
            engine { requestTimeout = 20_000 }
        }

        val provider: CredentialProvider = if (isVk) {
            VkCredentialProvider(httpClient)
        } else {
            YandexCredentialProvider(httpClient)
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Terminating...")
            scope.cancel()
            httpClient.close()
        })

        runBlocking(Dispatchers.IO) {
            val localSocket = DatagramSocket(listenAddr)

            if (noDtls) {
                repeat(n) { idx ->
                    if (idx > 0) delay(200)
                    scope.launch {
                        runTurnConnectionLoop(rawLink, provider, peerAddr, localSocket, turnHost, turnPort, useUdp)
                    }
                }
            } else {
                val firstReady = CompletableDeferred<Unit>()
                scope.launch {
                    runDtlsTurnConnection(rawLink, provider, peerAddr, localSocket, turnHost, turnPort, useUdp, firstReady)
                }
                firstReady.await()
                log.info("First DTLS connection ready — starting ${n - 1} more")
                repeat(n - 1) {
                    delay(200)
                    scope.launch {
                        runDtlsTurnConnection(rawLink, provider, peerAddr, localSocket, turnHost, turnPort, useUdp, null)
                    }
                }
            }

            scope.coroutineContext[Job]!!.join()
        }
    }
}

// ── DTLS + TURN connection ─────────────────────────────────────────────────

@Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
private suspend fun runDtlsTurnConnection(
    link: String,
    provider: CredentialProvider,
    peerAddr: InetSocketAddress,
    localSocket: DatagramSocket,
    turnHostOverride: String?,
    turnPortOverride: String?,
    useUdp: Boolean,
    onDtlsReady: CompletableDeferred<Unit>?,
) {
    val creds = try {
        provider.getCredentials(link)
    } catch (e: Exception) {
        log.severe("Failed to get TURN credentials: ${e.message}")
        return
    }
    val turnAddr = buildTurnAddr(creds, turnHostOverride, turnPortOverride)
    println(turnAddr.address.hostAddress)

    val dtls = DtlsClient(peerAddr)
    try {
        dtls.connect()
        log.info("DTLS handshake complete")
        onDtlsReady?.complete(Unit)
    } catch (e: Exception) {
        log.warning("DTLS connect failed: ${e.message}")
        dtls.close()
        return
    }

    val addrFamily = if (peerAddr.address.address.size == 4) RequestedAddressFamily.IPv4 else RequestedAddressFamily.IPv6
    val turnClient = try {
        TurnClient.connect(turnAddr, creds, useUdp, addrFamily)
    } catch (e: Exception) {
        log.warning("TURN connect failed: ${e.message}")
        dtls.close()
        return
    }

    try {
        turnClient.allocate()
        log.info("TURN relay: ${turnClient.relayAddress()}")
        turnClient.channelBind(peerAddr.address.address, peerAddr.port)

        val buf = ByteArray(1600)
        val dtlsBuf = ByteArray(1600)
        val lastLocalAddr = AtomicReference<InetSocketAddress>()

        // localSocket → DTLS → TURN
        val job1 = CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                while (true) {
                    val pkt = DatagramPacket(buf, buf.size)
                    localSocket.receive(pkt)
                    lastLocalAddr.set(InetSocketAddress(pkt.address, pkt.port))
                    dtls.send(buf.copyOf(pkt.length))
                }
            }
        }

        // TURN → DTLS → localSocket
        val job2 = CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                while (true) {
                    val data = turnClient.receive() ?: continue
                    dtls.send(data)
                    val n = dtls.receive(dtlsBuf)
                    if (n < 0) break
                    val addr = lastLocalAddr.get() ?: continue
                    localSocket.send(DatagramPacket(dtlsBuf, n, addr))
                }
            }
        }

        job1.join()
        job2.cancel()
    } finally {
        dtls.close()
        turnClient.close()
    }
}

// ── Direct TURN connection (--no-dtls) ────────────────────────────────────

@Suppress("LoopWithTooManyJumpStatements")
private suspend fun runTurnConnectionLoop(
    link: String,
    provider: CredentialProvider,
    peerAddr: InetSocketAddress,
    localSocket: DatagramSocket,
    turnHostOverride: String?,
    turnPortOverride: String?,
    useUdp: Boolean,
) {
    val creds = try {
        provider.getCredentials(link)
    } catch (e: Exception) {
        log.severe("Failed to get TURN credentials: ${e.message}")
        return
    }
    val turnAddr = buildTurnAddr(creds, turnHostOverride, turnPortOverride)
    println(turnAddr.address.hostAddress)

    val addrFamily = if (peerAddr.address.address.size == 4) RequestedAddressFamily.IPv4 else RequestedAddressFamily.IPv6
    val turnClient = TurnClient.connect(turnAddr, creds, useUdp, addrFamily)

    try {
        turnClient.allocate()
        log.info("TURN relay: ${turnClient.relayAddress()}")
        turnClient.channelBind(peerAddr.address.address, peerAddr.port)

        val buf = ByteArray(1600)
        val lastLocalAddr = AtomicReference<InetSocketAddress>()

        val job1 = CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                while (true) {
                    val pkt = DatagramPacket(buf, buf.size)
                    localSocket.receive(pkt)
                    lastLocalAddr.set(InetSocketAddress(pkt.address, pkt.port))
                    turnClient.send(buf.copyOf(pkt.length))
                }
            }
        }

        val job2 = CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                while (true) {
                    val data = turnClient.receive() ?: continue
                    val addr = lastLocalAddr.get() ?: continue
                    localSocket.send(DatagramPacket(data, data.size, addr))
                }
            }
        }

        job1.join()
        job2.cancel()
    } finally {
        turnClient.close()
    }
}

// ── helpers ────────────────────────────────────────────────────────────────

private fun buildTurnAddr(creds: TurnCredentials, hostOverride: String?, portOverride: String?): InetSocketAddress {
    val parts = creds.address.split(":")
    val host = hostOverride ?: parts[0]
    val port = portOverride?.toInt() ?: parts.getOrNull(1)?.toInt() ?: 3478
    return InetSocketAddress(host, port)
}

private fun parseAddr(addr: String): InetSocketAddress {
    val lastColon = addr.lastIndexOf(':')
    require(lastColon > 0) { "Invalid address: $addr" }
    return InetSocketAddress(addr.substring(0, lastColon), addr.substring(lastColon + 1).toInt())
}
