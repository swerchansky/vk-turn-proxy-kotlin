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
    val logConfig = object {}.javaClass.classLoader?.getResourceAsStream("logging.properties")
    if (logConfig != null) {
        java.util.logging.LogManager.getLogManager().readConfiguration(logConfig)
    }
    Security.addProvider(BouncyCastleProvider())
    ClientCommand().main(args)
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "%dm%02ds".format(s / 60, s % 60)
        else -> "%dh%02dm".format(s / 3600, (s % 3600) / 60)
    }
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
        val providerName = if (isVk) "VK" else "Yandex"
        val transport = if (useUdp) "UDP" else "TCP"
        val mode = if (noDtls) "plain" else "DTLS/$transport"

        val rawLink = (if (isVk) vkLink!! else yandexLink!!)
            .let { if (isVk) it.split("join/").last() else it.split("j/").last() }
            .substringBefore("?").substringBefore("/").substringBefore("#")

        val n = if (nConnections > 0) nConnections else if (isVk) 16 else 1

        log.info("Provider: $providerName · $n connections ($mode) · listen: $listen → peer: $peer")

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
            log.info("Shutting down...")
            scope.cancel()
            httpClient.close()
        })

        runBlocking(Dispatchers.IO) {
            val localSocket = DatagramSocket(listenAddr)

            if (noDtls) {
                repeat(n) { idx ->
                    if (idx > 0) delay(200)
                    scope.launch {
                        runTurnConnectionLoop(
                            connIndex = idx + 1, connTotal = n,
                            link = rawLink, provider = provider,
                            peerAddr = peerAddr, localSocket = localSocket,
                            turnHostOverride = turnHost, turnPortOverride = turnPort,
                            useUdp = useUdp,
                        )
                    }
                }
            } else {
                val firstReady = CompletableDeferred<Unit>()
                scope.launch {
                    runDtlsTurnConnection(
                        connIndex = 1, connTotal = n,
                        link = rawLink, provider = provider,
                        peerAddr = peerAddr, localSocket = localSocket,
                        turnHostOverride = turnHost, turnPortOverride = turnPort,
                        useUdp = useUdp, onDtlsReady = firstReady,
                    )
                }
                firstReady.await()
                log.info("Tunnel ready — starting ${n - 1} more connections...")
                repeat(n - 1) { idx ->
                    delay(200)
                    scope.launch {
                        runDtlsTurnConnection(
                            connIndex = idx + 2, connTotal = n,
                            link = rawLink, provider = provider,
                            peerAddr = peerAddr, localSocket = localSocket,
                            turnHostOverride = turnHost, turnPortOverride = turnPort,
                            useUdp = useUdp, onDtlsReady = null,
                        )
                    }
                }
            }

            scope.coroutineContext[Job]!!.join()
        }
    }
}

// ── DTLS + TURN connection ─────────────────────────────────────────────────

private suspend fun runDtlsTurnConnection(
    connIndex: Int,
    connTotal: Int,
    link: String,
    provider: CredentialProvider,
    peerAddr: InetSocketAddress,
    localSocket: DatagramSocket,
    turnHostOverride: String?,
    turnPortOverride: String?,
    useUdp: Boolean,
    onDtlsReady: CompletableDeferred<Unit>?,
) {
    val isFirst = onDtlsReady != null
    val tag = "[$connIndex/$connTotal]"
    val startMs = System.currentTimeMillis()

    // Step 1: credentials
    val creds = try {
        if (isFirst) log.info("$tag Getting TURN credentials...")
        provider.getCredentials(link)
    } catch (e: Exception) {
        log.severe("$tag Failed to get TURN credentials: ${e::class.qualifiedName}: ${e.message}")
        onDtlsReady?.completeExceptionally(e)
        return
    }

    val turnAddr = buildTurnAddr(creds, turnHostOverride, turnPortOverride)
    if (isFirst) log.info("$tag Credentials OK · TURN: $turnAddr · user: ${creds.username}")

    // Step 2: TURN allocation
    val addrFamily = if (peerAddr.address.address.size == 4) RequestedAddressFamily.IPv4
                     else RequestedAddressFamily.IPv6
    val turnClient = try {
        if (isFirst) log.info("$tag Connecting to TURN (${if (useUdp) "UDP" else "TCP"})...")
        TurnClient.connect(turnAddr, creds, useUdp, addrFamily)
    } catch (e: Exception) {
        log.warning("$tag TURN connect failed: ${e.message}")
        onDtlsReady?.completeExceptionally(e)
        return
    }

    val dtls = DtlsClient()
    try {
        turnClient.allocate()
        val relayStr = turnClient.relayAddress().toString()
        if (isFirst) log.info("$tag TURN relay: $relayStr · channel → ${peerAddr.address.hostAddress}:${peerAddr.port}")
        turnClient.channelBind(peerAddr.address.address, peerAddr.port)

        // Step 3: DTLS handshake
        val dtlsStart = System.currentTimeMillis()
        if (isFirst) log.info("$tag DTLS handshake...")
        try {
            dtls.connectOverTurn(turnClient)
            val dtlsMs = System.currentTimeMillis() - dtlsStart
            if (isFirst) {
                log.info("$tag DTLS OK in ${dtlsMs}ms · total setup: ${System.currentTimeMillis() - startMs}ms")
            } else {
                log.info("$tag Connected ✓ relay: $relayStr · ${System.currentTimeMillis() - startMs}ms")
            }
        } catch (e: Exception) {
            log.warning("$tag DTLS handshake failed: ${e.message}")
            onDtlsReady?.completeExceptionally(e)
            return
        }

        onDtlsReady?.complete(Unit)
        if (isFirst) log.info("$tag Relay active · listening on ${localSocket.localSocketAddress}")

        // Step 4: relay (no per-packet logs)
        val buf = ByteArray(1600)
        val dtlsBuf = ByteArray(1600)
        val lastLocalAddr = AtomicReference<InetSocketAddress>()
        var toServerPkts = 0
        var fromServerPkts = 0

        coroutineScope {
            launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val pkt = DatagramPacket(buf, buf.size)
                        localSocket.receive(pkt)
                        lastLocalAddr.set(InetSocketAddress(pkt.address, pkt.port))
                        toServerPkts++
                        dtls.send(buf.copyOf(pkt.length))
                    }
                } catch (e: Exception) {
                    log.fine("$tag WG→DTLS ended: ${e.javaClass.simpleName}")
                }
            }

            launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val n = dtls.receive(dtlsBuf)
                        if (n < 0) break
                        fromServerPkts++
                        val addr = lastLocalAddr.get() ?: continue
                        localSocket.send(DatagramPacket(dtlsBuf, n, addr))
                    }
                } catch (e: Exception) {
                    log.fine("$tag DTLS→WG ended: ${e.javaClass.simpleName}")
                }
            }
        }

        val dur = formatDuration(System.currentTimeMillis() - startMs)
        log.info("$tag Relay done · ↑$toServerPkts ↓$fromServerPkts pkts · up: $dur")
    } finally {
        dtls.close()
        turnClient.close()
    }
}

// ── Direct TURN connection (--no-dtls) ────────────────────────────────────

private suspend fun runTurnConnectionLoop(
    connIndex: Int,
    connTotal: Int,
    link: String,
    provider: CredentialProvider,
    peerAddr: InetSocketAddress,
    localSocket: DatagramSocket,
    turnHostOverride: String?,
    turnPortOverride: String?,
    useUdp: Boolean,
) {
    val tag = "[$connIndex/$connTotal]"
    val startMs = System.currentTimeMillis()

    val creds = try {
        if (connIndex == 1) log.info("$tag Getting TURN credentials...")
        provider.getCredentials(link)
    } catch (e: Exception) {
        log.severe("$tag Credentials failed: ${e.message}")
        return
    }

    val turnAddr = buildTurnAddr(creds, turnHostOverride, turnPortOverride)
    val addrFamily = if (peerAddr.address.address.size == 4) RequestedAddressFamily.IPv4
                     else RequestedAddressFamily.IPv6
    val turnClient = try {
        TurnClient.connect(turnAddr, creds, useUdp, addrFamily)
    } catch (e: Exception) {
        log.warning("$tag TURN connect failed: ${e.message}")
        return
    }

    try {
        turnClient.allocate()
        log.info("$tag TURN relay: ${turnClient.relayAddress()} · ${System.currentTimeMillis() - startMs}ms")
        turnClient.channelBind(peerAddr.address.address, peerAddr.port)

        val buf = ByteArray(1600)
        val lastLocalAddr = AtomicReference<InetSocketAddress>()
        var toServerPkts = 0
        var fromServerPkts = 0

        coroutineScope {
            launch(Dispatchers.IO) {
                runCatching {
                    while (true) {
                        val pkt = DatagramPacket(buf, buf.size)
                        localSocket.receive(pkt)
                        lastLocalAddr.set(InetSocketAddress(pkt.address, pkt.port))
                        toServerPkts++
                        turnClient.send(buf.copyOf(pkt.length))
                    }
                }.onFailure { log.fine("$tag WG→TURN ended: ${it.javaClass.simpleName}") }
                turnClient.close()
            }

            launch(Dispatchers.IO) {
                runCatching {
                    while (true) {
                        val data = turnClient.receive() ?: continue
                        fromServerPkts++
                        val addr = lastLocalAddr.get() ?: continue
                        localSocket.send(DatagramPacket(data, data.size, addr))
                    }
                }.onFailure { log.fine("$tag TURN→WG ended: ${it.javaClass.simpleName}") }
            }
        }

        val dur = formatDuration(System.currentTimeMillis() - startMs)
        log.info("$tag Relay done · ↑$toServerPkts ↓$fromServerPkts pkts · up: $dur")
    } finally {
        turnClient.close()
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

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
