package com.github.swerchansky.vkturnproxy.proxy

import com.github.swerchansky.vkturnproxy.config.TurnProxyConfig
import com.github.swerchansky.vkturnproxy.credentials.CredentialProvider
import com.github.swerchansky.vkturnproxy.dtls.DtlsClient
import com.github.swerchansky.vkturnproxy.logging.NoOpLogger
import com.github.swerchansky.vkturnproxy.logging.ProxyLogger
import com.github.swerchansky.vkturnproxy.turn.RequestedAddressFamily
import com.github.swerchansky.vkturnproxy.turn.TurnClient
import com.github.swerchansky.vkturnproxy.turn.TurnCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
 * Orchestrates N parallel TURN connections with DTLS (or plain TURN) relay.
 *
 * Emits [TunnelEvent]s as the tunnel progresses.
 * Exposes [toServerPackets] / [fromServerPackets] counters updated on every relayed packet.
 *
 * Usage:
 * ```
 * val engine = TurnProxyEngine(credentialProvider, logger)
 * engine.runConnections(params).collect { event -> ... }
 * ```
 */
class TurnProxyEngine(
    private val credentialProvider: CredentialProvider,
    private val logger: ProxyLogger = NoOpLogger,
) {
    val toServerPackets = AtomicLong(0)
    val fromServerPackets = AtomicLong(0)

    /**
     * Returns a [Flow] that runs all [TunnelParams.nConnections] parallel TURN connections
     * and emits [TunnelEvent]s until all connections close.
     *
     * The first connection completes fully before the remaining N-1 are started
     * (staggered [TurnProxyConfig.STAGGERED_CONNECTION_DELAY_MS] ms apart).
     */
    fun runConnections(params: TunnelParams): Flow<TunnelEvent> = channelFlow {
        val firstReady = CompletableDeferred<String>()
        val readyCount = AtomicInteger(0)
        toServerPackets.set(0)
        fromServerPackets.set(0)

        coroutineScope {
            // First connection: full step logging, signals firstReady when DTLS is up
            launch {
                runSingleConnection(
                    connIndex = 1, params = params,
                    firstReady = firstReady,
                    onReady = { relayAddr ->
                        val c = readyCount.incrementAndGet()
                        trySend(TunnelEvent.ConnectionReady(c, params.nConnections, relayAddr))
                    },
                )
            }

            val relayAddr = firstReady.await()
            send(TunnelEvent.FirstReady(relayAddr))

            // Remaining N-1 connections, staggered apart
            repeat(params.nConnections - 1) { idx ->
                delay(TurnProxyConfig.STAGGERED_CONNECTION_DELAY_MS)
                launch {
                    runSingleConnection(
                        connIndex = idx + 2, params = params,
                        firstReady = null,
                        onReady = { _ ->
                            val c = readyCount.incrementAndGet()
                            trySend(TunnelEvent.ConnectionReady(c, params.nConnections, relayAddr))
                        },
                    )
                }
            }
        }
    }

    // ── Single connection ──────────────────────────────────────────────────

    private suspend fun ProducerScope<TunnelEvent>.runSingleConnection(
        connIndex: Int,
        params: TunnelParams,
        firstReady: CompletableDeferred<String>?,
        onReady: (String) -> Unit,
    ) {
        val isFirst = firstReady != null
        val tag = "[$connIndex/${params.nConnections}]"
        val startMs = System.currentTimeMillis()

        // Step 1: credentials
        if (isFirst) trySend(TunnelEvent.StepChanged("Resolving DNS"))
        val creds = try {
            if (isFirst) logger.info(tag, "Getting TURN credentials...")
            credentialProvider.getCredentials(params.link)
        } catch (e: Exception) {
            logger.info(tag, "Credentials failed: ${e.message}")
            firstReady?.completeExceptionally(e)
            return
        }

        val turnAddr = buildTurnAddr(creds, params.turnHostOverride, params.turnPortOverride)
        if (isFirst) logger.info(tag, "Credentials OK · TURN: $turnAddr · user: ${creds.username}")

        // Step 2: TURN allocation
        if (isFirst) trySend(TunnelEvent.StepChanged("Connecting TURN"))
        val addrFamily = if (params.peerAddr.address.address.size == 4) RequestedAddressFamily.IPv4
        else RequestedAddressFamily.IPv6

        val turnClient = try {
            if (isFirst) logger.info(tag, "Connecting to TURN (UDP)...")
            TurnClient.create(turnAddr, creds, addrFamily, logger = logger)
        } catch (e: Exception) {
            logger.info(tag, "TURN connect failed: ${e.javaClass.simpleName}: ${e.message}")
            firstReady?.completeExceptionally(e)
            return
        }

        try {
            turnClient.allocate()
            val relayStr = turnClient.relayAddress().toString()
            if (isFirst) logger.info(
                tag,
                "TURN relay: $relayStr · channel → ${params.peerAddr.address.hostAddress}:${params.peerAddr.port}"
            )
            turnClient.channelBind(params.peerAddr.address.address, params.peerAddr.port)

            val relayAddr = turnClient.relayAddress().toString()

            if (params.useDtls) {
                runDtlsRelay(
                    tag = tag, isFirst = isFirst, startMs = startMs,
                    turnClient = turnClient, relayAddr = relayAddr,
                    localSocket = params.localSocket,
                    firstReady = firstReady, onReady = onReady,
                )
            } else {
                runPlainRelay(
                    tag = tag, isFirst = isFirst, startMs = startMs,
                    turnClient = turnClient, relayAddr = relayAddr,
                    localSocket = params.localSocket,
                    firstReady = firstReady, onReady = onReady,
                )
            }
        } catch (e: Exception) {
            logger.info(tag, "Connection error: ${e.javaClass.simpleName}: ${e.message}")
            firstReady?.completeExceptionally(e)
        } finally {
            turnClient.close()
        }
    }

    // ── DTLS relay ─────────────────────────────────────────────────────────

    private suspend fun ProducerScope<TunnelEvent>.runDtlsRelay(
        tag: String,
        isFirst: Boolean,
        startMs: Long,
        turnClient: TurnClient,
        relayAddr: String,
        localSocket: DatagramSocket,
        firstReady: CompletableDeferred<String>?,
        onReady: (String) -> Unit,
    ) {
        if (isFirst) trySend(TunnelEvent.StepChanged("DTLS Handshake"))
        val dtls = DtlsClient(logger = logger)
        try {
            // connectOverTurn is blocking — run it on the current thread
            val dtlsStart = System.currentTimeMillis()
            if (isFirst) logger.info(tag, "DTLS handshake...")
            try {
                dtls.connectOverTurn(turnClient)
            } catch (e: Exception) {
                logger.info(tag, "DTLS failed: ${e.javaClass.simpleName}: ${e.message}")
                firstReady?.completeExceptionally(e)
                return
            }
            val dtlsMs = System.currentTimeMillis() - dtlsStart
            if (isFirst) {
                logger.info(
                    tag,
                    "DTLS OK in ${dtlsMs}ms · setup: ${System.currentTimeMillis() - startMs}ms total"
                )
            } else {
                logger.info(
                    tag,
                    "Connected ✓ relay: $relayAddr · ${System.currentTimeMillis() - startMs}ms"
                )
            }

            firstReady?.complete(relayAddr)
            onReady(relayAddr)

            val buf = ByteArray(TurnProxyConfig.PACKET_BUFFER_SIZE)
            val dtlsBuf = ByteArray(TurnProxyConfig.PACKET_BUFFER_SIZE)
            val lastLocalAddr = AtomicReference<InetSocketAddress>()
            var toServer = 0
            var fromServer = 0

            // Run relay + refresh; cancel refresh when relay loop finishes
            coroutineScope {
                val relayJob = launch {
                    coroutineScope {
                        launch {
                            runCatching {
                                while (true) {
                                    val pkt = DatagramPacket(buf, buf.size)
                                    localSocket.receive(pkt)
                                    lastLocalAddr.set(InetSocketAddress(pkt.address, pkt.port))
                                    toServer++
                                    toServerPackets.incrementAndGet()
                                    dtls.send(buf.copyOf(pkt.length))
                                }
                            }.onFailure {
                                if (isFirst) logger.info(
                                    tag,
                                    "WG→TURN closed: ${it.javaClass.simpleName}"
                                )
                            }
                        }
                        launch {
                            runCatching {
                                while (true) {
                                    val n = dtls.receive(dtlsBuf)
                                    if (n < 0) break
                                    if (n == 1 && dtlsBuf[0] == 0.toByte()) continue
                                    fromServer++
                                    fromServerPackets.incrementAndGet()
                                    val addr = lastLocalAddr.get() ?: continue
                                    localSocket.send(DatagramPacket(dtlsBuf, n, addr))
                                }
                            }.onFailure {
                                if (isFirst) logger.info(
                                    tag,
                                    "TURN→WG closed: ${it.javaClass.simpleName}"
                                )
                            }
                        }
                    }
                }
                val refreshJob = launch { turnClient.runRefresh() }
                relayJob.join()
                refreshJob.cancel()
            }

            logger.info(
                tag,
                "Relay done · ↑$toServer ↓$fromServer pkts · up: ${formatTurnProxyDuration(System.currentTimeMillis() - startMs)}"
            )
        } finally {
            dtls.close()
        }
    }

    // ── Plain TURN relay (--no-dtls) ────────────────────────────────────────

    private suspend fun runPlainRelay(
        tag: String,
        isFirst: Boolean,
        startMs: Long,
        turnClient: TurnClient,
        relayAddr: String,
        localSocket: DatagramSocket,
        firstReady: CompletableDeferred<String>?,
        onReady: (String) -> Unit,
    ) {
        firstReady?.complete(relayAddr)
        onReady(relayAddr)
        if (isFirst) logger.info(tag, "Relay active (no DTLS)")

        val buf = ByteArray(TurnProxyConfig.PACKET_BUFFER_SIZE)
        val lastLocalAddr = AtomicReference<InetSocketAddress>()
        var toServer = 0
        var fromServer = 0

        // Run relay + refresh; cancel refresh when relay loop finishes
        coroutineScope {
            val relayJob = launch {
                coroutineScope {
                    launch {
                        runCatching {
                            while (true) {
                                val pkt = DatagramPacket(buf, buf.size)
                                localSocket.receive(pkt)
                                lastLocalAddr.set(InetSocketAddress(pkt.address, pkt.port))
                                toServer++
                                toServerPackets.incrementAndGet()
                                turnClient.send(buf.copyOf(pkt.length))
                            }
                        }.onFailure {
                            if (isFirst) logger.info(
                                tag,
                                "WG→TURN closed: ${it.javaClass.simpleName}"
                            )
                        }
                    }
                    launch {
                        runCatching {
                            while (true) {
                                val data = turnClient.receive() ?: continue
                                fromServer++
                                fromServerPackets.incrementAndGet()
                                val addr = lastLocalAddr.get() ?: continue
                                localSocket.send(DatagramPacket(data, data.size, addr))
                            }
                        }.onFailure {
                            if (isFirst) logger.info(
                                tag,
                                "TURN→WG closed: ${it.javaClass.simpleName}"
                            )
                        }
                    }
                }
            }
            val refreshJob = launch { turnClient.runRefresh() }
            relayJob.join()
            refreshJob.cancel()
        }

        logger.info(
            tag,
            "Relay done · ↑$toServer ↓$fromServer pkts · up: ${formatTurnProxyDuration(System.currentTimeMillis() - startMs)}"
        )
    }
}

// ── Internal helpers ───────────────────────────────────────────────────────

private fun buildTurnAddr(creds: TurnCredentials, hostOverride: String?, portOverride: String?): InetSocketAddress {
    val parts = creds.address.split(":")
    val host = hostOverride ?: parts[0]
    val port = portOverride?.toInt() ?: parts.getOrNull(1)?.toInt() ?: TurnProxyConfig.TURN_DEFAULT_PORT
    return InetSocketAddress(host, port)
}
