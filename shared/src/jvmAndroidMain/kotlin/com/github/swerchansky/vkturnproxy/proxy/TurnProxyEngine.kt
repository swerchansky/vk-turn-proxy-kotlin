package com.github.swerchansky.vkturnproxy.proxy

import com.github.swerchansky.vkturnproxy.credentials.CredentialProvider
import com.github.swerchansky.vkturnproxy.dtls.DtlsClient
import com.github.swerchansky.vkturnproxy.turn.RequestedAddressFamily
import com.github.swerchansky.vkturnproxy.turn.TurnClient
import com.github.swerchansky.vkturnproxy.turn.TurnCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

fun parseTurnProxyAddr(addr: String): InetSocketAddress {
    val lastColon = addr.lastIndexOf(':')
    require(lastColon > 0) { "Invalid address: $addr" }
    return InetSocketAddress(addr.substring(0, lastColon), addr.substring(lastColon + 1).toInt())
}

fun formatTurnProxyDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "%dm%02ds".format(s / 60, s % 60)
        else -> "%dh%02dm".format(s / 3600, (s % 3600) / 60)
    }
}

/**
 * Launches N parallel TURN connections with DTLS (or plain TURN) relay.
 *
 * The first connection completes fully before the remaining N-1 are started
 * (staggered 200 ms apart). Suspends until all connections close.
 *
 * @param link               Raw link token (already stripped of URL prefix).
 * @param peerAddr           Remote peer (server) address.
 * @param localSocket        Local UDP socket to forward traffic to/from.
 * @param provider           Credential provider (VK or Yandex).
 * @param nConnections       Total number of parallel connections.
 * @param useDtls            Wrap data in DTLS (default: true).
 * @param turnHostOverride   Override the TURN host extracted from credentials.
 * @param turnPortOverride   Override the TURN port extracted from credentials.
 * @param logger             Sink for human-readable log lines.
 * @param onStepChange       Called when the setup phase changes (first connection only).
 * @param onFirstReady       Called once the first connection is fully established,
 *                           with its relay address string.
 * @param onConnectionReady  Called each time any connection becomes ready.
 *                           Receives (connectedCount, totalConnections, relayAddr).
 * @param onPacketToServer   Called for every packet sent toward the TURN relay.
 * @param onPacketFromServer Called for every packet received from the TURN relay.
 */
suspend fun runProxyConnections(
    link: String,
    peerAddr: InetSocketAddress,
    localSocket: DatagramSocket,
    provider: CredentialProvider,
    nConnections: Int,
    useDtls: Boolean = true,
    turnHostOverride: String? = null,
    turnPortOverride: String? = null,
    logger: (String) -> Unit = {},
    onStepChange: ((String) -> Unit)? = null,
    onFirstReady: (relayAddr: String) -> Unit = {},
    onConnectionReady: (connectedCount: Int, total: Int, relayAddr: String) -> Unit = { _, _, _ -> },
    onPacketToServer: () -> Unit = {},
    onPacketFromServer: () -> Unit = {},
) {
    val firstReady = CompletableDeferred<String>()
    val readyCount = AtomicInteger(0)

    coroutineScope {
        // First connection: full step logging, blocks N-1 launch until DTLS is ready
        launch {
            runSingleTurnConnection(
                connIndex = 1, connTotal = nConnections,
                link = link, provider = provider,
                peerAddr = peerAddr, localSocket = localSocket,
                useDtls = useDtls,
                turnHostOverride = turnHostOverride, turnPortOverride = turnPortOverride,
                firstReady = firstReady,
                onStepChange = onStepChange,
                onReady = { relayAddr ->
                    val c = readyCount.incrementAndGet()
                    onConnectionReady(c, nConnections, relayAddr)
                },
                onPacketToServer = onPacketToServer,
                onPacketFromServer = onPacketFromServer,
                logger = logger,
            )
        }

        val relayAddr = firstReady.await()
        onFirstReady(relayAddr)

        // Remaining N-1 connections, staggered 200 ms apart
        repeat(nConnections - 1) { idx ->
            delay(200)
            launch {
                runSingleTurnConnection(
                    connIndex = idx + 2, connTotal = nConnections,
                    link = link, provider = provider,
                    peerAddr = peerAddr, localSocket = localSocket,
                    useDtls = useDtls,
                    turnHostOverride = turnHostOverride, turnPortOverride = turnPortOverride,
                    firstReady = null,
                    onStepChange = null,
                    onReady = { _ ->
                        val c = readyCount.incrementAndGet()
                        onConnectionReady(c, nConnections, relayAddr)
                    },
                    onPacketToServer = onPacketToServer,
                    onPacketFromServer = onPacketFromServer,
                    logger = logger,
                )
            }
        }
    }
}

/**
 * Runs a single TURN connection (with optional DTLS) until the relay closes.
 *
 * Steps:
 *  1. Fetch credentials via [provider].
 *  2. Connect to the TURN server and allocate a relay.
 *  3. If [useDtls]: perform DTLS handshake over the TURN relay.
 *  4. Bidirectional relay between [localSocket] and the TURN relay.
 *
 * @param firstReady  If non-null, completed with the relay address string once the
 *                    connection is fully established (DTLS handshake done). The caller
 *                    awaits this to know when to start additional connections.
 */
suspend fun runSingleTurnConnection(
    connIndex: Int,
    connTotal: Int,
    link: String,
    provider: CredentialProvider,
    peerAddr: InetSocketAddress,
    localSocket: DatagramSocket,
    useDtls: Boolean = true,
    turnHostOverride: String? = null,
    turnPortOverride: String? = null,
    firstReady: CompletableDeferred<String>? = null,
    onStepChange: ((String) -> Unit)? = null,
    onReady: (String) -> Unit = {},
    onPacketToServer: () -> Unit = {},
    onPacketFromServer: () -> Unit = {},
    logger: (String) -> Unit = {},
) {
    val isFirst = firstReady != null
    val tag = "[$connIndex/$connTotal]"
    val startMs = System.currentTimeMillis()

    // ── Step 1: credentials ────────────────────────────────────────────────
    onStepChange?.invoke("Resolving DNS")
    val creds = try {
        if (isFirst) logger("$tag Getting TURN credentials...")
        provider.getCredentials(link)
    } catch (e: Exception) {
        logger("$tag Credentials failed: ${e.message}")
        firstReady?.completeExceptionally(e)
        return
    }

    val turnAddr = buildTurnAddr(creds, turnHostOverride, turnPortOverride)
    if (isFirst) logger("$tag Credentials OK · TURN: $turnAddr · user: ${creds.username}")

    // ── Step 2: TURN allocation ────────────────────────────────────────────
    onStepChange?.invoke("Connecting TURN")
    val addrFamily = if (peerAddr.address.address.size == 4) RequestedAddressFamily.IPv4
                     else RequestedAddressFamily.IPv6

    val turnClient = try {
        if (isFirst) logger("$tag Connecting to TURN (UDP)...")
        TurnClient.connect(turnAddr, creds, addrFamily, logger = { logger("$tag $it") })
    } catch (e: Exception) {
        logger("$tag TURN connect failed: ${e.javaClass.simpleName}: ${e.message}")
        firstReady?.completeExceptionally(e)
        return
    }

    try {
        turnClient.allocate()
        val relayStr = turnClient.relayAddress().toString()
        if (isFirst) logger("$tag TURN relay: $relayStr · channel → ${peerAddr.address.hostAddress}:${peerAddr.port}")
        turnClient.channelBind(peerAddr.address.address, peerAddr.port)

        val relayAddr = turnClient.relayAddress().toString()

        if (useDtls) {
            runDtlsRelay(
                tag = tag, isFirst = isFirst, startMs = startMs,
                turnClient = turnClient, relayAddr = relayAddr,
                localSocket = localSocket,
                firstReady = firstReady, onReady = onReady,
                onStepChange = onStepChange,
                onPacketToServer = onPacketToServer, onPacketFromServer = onPacketFromServer,
                logger = logger,
            )
        } else {
            runPlainRelay(
                tag = tag, isFirst = isFirst, startMs = startMs,
                turnClient = turnClient, relayAddr = relayAddr,
                localSocket = localSocket,
                firstReady = firstReady, onReady = onReady,
                onPacketToServer = onPacketToServer, onPacketFromServer = onPacketFromServer,
                logger = logger,
            )
        }
    } catch (e: Exception) {
        logger("$tag Connection error: ${e.javaClass.simpleName}: ${e.message}")
        firstReady?.completeExceptionally(e)
    } finally {
        turnClient.close()
    }
}

// ── DTLS relay ─────────────────────────────────────────────────────────────

private suspend fun runDtlsRelay(
    tag: String,
    isFirst: Boolean,
    startMs: Long,
    turnClient: TurnClient,
    relayAddr: String,
    localSocket: DatagramSocket,
    firstReady: CompletableDeferred<String>?,
    onReady: (String) -> Unit,
    onStepChange: ((String) -> Unit)?,
    onPacketToServer: () -> Unit,
    onPacketFromServer: () -> Unit,
    logger: (String) -> Unit,
) {
    onStepChange?.invoke("DTLS Handshake")
    val dtls = DtlsClient()
    try {
        val dtlsStart = System.currentTimeMillis()
        if (isFirst) logger("$tag DTLS handshake...")
        try {
            dtls.connectOverTurn(turnClient)
        } catch (e: Exception) {
            logger("$tag DTLS failed: ${e.javaClass.simpleName}: ${e.message}")
            firstReady?.completeExceptionally(e)
            return
        }
        val dtlsMs = System.currentTimeMillis() - dtlsStart
        if (isFirst) {
            logger("$tag DTLS OK in ${dtlsMs}ms · setup: ${System.currentTimeMillis() - startMs}ms total")
        } else {
            logger("$tag Connected ✓ relay: $relayAddr · ${System.currentTimeMillis() - startMs}ms")
        }

        firstReady?.complete(relayAddr)
        onReady(relayAddr)

        val buf = ByteArray(1600)
        val dtlsBuf = ByteArray(1600)
        val lastLocalAddr = AtomicReference<InetSocketAddress>()
        var toServerPkts = 0
        var fromServerPkts = 0

        coroutineScope {
            launch {
                runCatching {
                    while (true) {
                        val pkt = DatagramPacket(buf, buf.size)
                        localSocket.receive(pkt)
                        lastLocalAddr.set(InetSocketAddress(pkt.address, pkt.port))
                        toServerPkts++
                        onPacketToServer()
                        dtls.send(buf.copyOf(pkt.length))
                    }
                }.onFailure {
                    if (isFirst) logger("$tag WG→TURN closed: ${it.javaClass.simpleName}")
                }
            }

            launch {
                runCatching {
                    while (true) {
                        val n = dtls.receive(dtlsBuf)
                        if (n < 0) break
                        // Skip single-byte DTLS keepalive packets (0x00)
                        if (n == 1 && dtlsBuf[0] == 0.toByte()) continue
                        fromServerPkts++
                        onPacketFromServer()
                        val addr = lastLocalAddr.get() ?: continue
                        localSocket.send(DatagramPacket(dtlsBuf, n, addr))
                    }
                }.onFailure {
                    if (isFirst) logger("$tag TURN→WG closed: ${it.javaClass.simpleName}")
                }
            }
        }

        logger("$tag Relay done · ↑$toServerPkts ↓$fromServerPkts pkts · up: ${formatTurnProxyDuration(System.currentTimeMillis() - startMs)}")
    } finally {
        dtls.close()
    }
}

// ── Plain TURN relay (--no-dtls) ───────────────────────────────────────────

private suspend fun runPlainRelay(
    tag: String,
    isFirst: Boolean,
    startMs: Long,
    turnClient: TurnClient,
    relayAddr: String,
    localSocket: DatagramSocket,
    firstReady: CompletableDeferred<String>?,
    onReady: (String) -> Unit,
    onPacketToServer: () -> Unit,
    onPacketFromServer: () -> Unit,
    logger: (String) -> Unit,
) {
    firstReady?.complete(relayAddr)
    onReady(relayAddr)
    if (isFirst) logger("$tag Relay active (no DTLS)")

    val buf = ByteArray(1600)
    val lastLocalAddr = AtomicReference<InetSocketAddress>()
    var toServerPkts = 0
    var fromServerPkts = 0

    coroutineScope {
        launch {
            runCatching {
                while (true) {
                    val pkt = DatagramPacket(buf, buf.size)
                    localSocket.receive(pkt)
                    lastLocalAddr.set(InetSocketAddress(pkt.address, pkt.port))
                    toServerPkts++
                    onPacketToServer()
                    turnClient.send(buf.copyOf(pkt.length))
                }
            }.onFailure {
                if (isFirst) logger("$tag WG→TURN closed: ${it.javaClass.simpleName}")
            }
        }

        launch {
            runCatching {
                while (true) {
                    val data = turnClient.receive() ?: continue
                    fromServerPkts++
                    onPacketFromServer()
                    val addr = lastLocalAddr.get() ?: continue
                    localSocket.send(DatagramPacket(data, data.size, addr))
                }
            }.onFailure {
                if (isFirst) logger("$tag TURN→WG closed: ${it.javaClass.simpleName}")
            }
        }
    }

    logger("$tag Relay done · ↑$toServerPkts ↓$fromServerPkts pkts · up: ${formatTurnProxyDuration(System.currentTimeMillis() - startMs)}")
}

// ── Internal helpers ───────────────────────────────────────────────────────

private fun buildTurnAddr(creds: TurnCredentials, hostOverride: String?, portOverride: String?): InetSocketAddress {
    val parts = creds.address.split(":")
    val host = hostOverride ?: parts[0]
    val port = portOverride?.toInt() ?: parts.getOrNull(1)?.toInt() ?: 3478
    return InetSocketAddress(host, port)
}
