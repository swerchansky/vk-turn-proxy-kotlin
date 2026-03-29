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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("client")

fun main(args: Array<String>) {
    // Load logging config from classpath
    val logConfig = object {}.javaClass.classLoader?.getResourceAsStream("logging.properties")
    if (logConfig != null) {
        java.util.logging.LogManager.getLogManager().readConfiguration(logConfig)
    }
    Security.addProvider(BouncyCastleProvider())
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

@Suppress("ReturnCount")
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
        log.severe("Failed to get TURN credentials: ${e::class.qualifiedName}: ${e.message}")
        e.printStackTrace()
        return
    }
    val turnAddr = buildTurnAddr(creds, turnHostOverride, turnPortOverride)
    println(turnAddr.address.hostAddress)

    // 1. TURN must be set up first — DTLS handshake travels through the relay
    val addrFamily = if (peerAddr.address.address.size == 4) RequestedAddressFamily.IPv4 else RequestedAddressFamily.IPv6
    val turnClient = try {
        TurnClient.connect(turnAddr, creds, useUdp, addrFamily)
    } catch (e: Exception) {
        log.warning("TURN connect failed: ${e.message}")
        return
    }

    val dtls = DtlsClient()
    try {
        turnClient.allocate()
        log.info("Connected! Relay: ${turnClient.relayAddress()}")
        turnClient.channelBind(peerAddr.address.address, peerAddr.port)

        // 2. DTLS handshake over TURN relay (TurnDatagramTransport wraps turnClient)
        try {
            val startMs = System.currentTimeMillis()
            dtls.connectOverTurn(turnClient) { msg -> println(msg) }
            log.info("DTLS handshake OK after ${System.currentTimeMillis() - startMs}ms")
        } catch (e: Exception) {
            log.warning("DTLS handshake failed: ${e.message}")
            return
        }
        onDtlsReady?.complete(Unit)
        log.info("Relay started — listening on ${localSocket.localSocketAddress}, peer=$peerAddr")

        val buf = ByteArray(1600)
        val dtlsBuf = ByteArray(1600)
        val lastLocalAddr = AtomicReference<InetSocketAddress>()
        var toServerPkts = 0
        var fromServerPkts = 0

        // Both relay directions — coroutineScope cancels all children if either exits
        coroutineScope {
            // localSocket → DTLS (encrypt) → TURN → server
            launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val pkt = DatagramPacket(buf, buf.size)
                        localSocket.receive(pkt)
                        val from = InetSocketAddress(pkt.address, pkt.port)
                        lastLocalAddr.set(from)
                        toServerPkts++
                        if (toServerPkts <= 5 || toServerPkts % 100 == 0)
                            log.info("→server pkt #$toServerPkts ${pkt.length}B from $from")
                        dtls.send(buf.copyOf(pkt.length))
                    }
                } catch (e: Exception) {
                    log.warning("localSocket→DTLS ended after $toServerPkts pkts: ${e.javaClass.name}: ${e.message}")
                }
            }

            // server → TURN → DTLS (decrypt) → localSocket
            launch(Dispatchers.IO) {
                log.info("←server direction: waiting for first DTLS packet from server...")
                try {
                    while (true) {
                        val n = dtls.receive(dtlsBuf)
                        if (n < 0) {
                            log.info("←server: dtls.receive() returned -1 (timeout), exiting relay")
                            break
                        }
                        fromServerPkts++
                        val addr = lastLocalAddr.get()
                        if (fromServerPkts <= 5 || fromServerPkts % 100 == 0)
                            log.info("←server pkt #$fromServerPkts ${n}B → localAddr=${addr}")
                        if (addr == null) {
                            log.warning("←server: no local WireGuard addr yet, dropping pkt #$fromServerPkts")
                            continue
                        }
                        localSocket.send(DatagramPacket(dtlsBuf, n, addr))
                    }
                } catch (e: Exception) {
                    log.warning("DTLS→localSocket ended after $fromServerPkts pkts: ${e.javaClass.name}: ${e.message}")
                }
            }
        }
        log.info("Relay done: →server=$toServerPkts pkts, ←server=$fromServerPkts pkts")
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
