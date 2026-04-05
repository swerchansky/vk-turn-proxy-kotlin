package com.github.swerchansky.vkturnproxy.turn

import com.github.swerchansky.vkturnproxy.config.TurnProxyConfig
import com.github.swerchansky.vkturnproxy.logging.NoOpLogger
import com.github.swerchansky.vkturnproxy.logging.ProxyLogger
import com.github.swerchansky.vkturnproxy.transport.TurnTransport
import com.github.swerchansky.vkturnproxy.transport.TurnTransportFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.Closeable
import java.net.InetSocketAddress

/**
 * High-level TURN client facade (RFC 5766).
 *
 * Composes [TurnTransport], [TurnAuthenticator], [TurnAllocator] and [TurnChannelManager].
 *
 * Usage:
 * ```
 * val client = TurnClient.create(serverAddr, creds)
 * client.allocate()
 * client.channelBind(peerIp, peerPort)
 * coroutineScope {
 *     launch { client.runRefresh() }   // keeps allocation and channel alive
 *     // ... relay loop ...
 * }
 * client.close()
 * ```
 */
class TurnClient private constructor(
    private val transport: TurnTransport,
    private val auth: TurnAuthenticator,
    private val allocator: TurnAllocator,
    private val channelManager: TurnChannelManager,
    private val logger: ProxyLogger = NoOpLogger,
) : Closeable {

    companion object {
        private const val TAG = "TurnClient"

        fun create(
            serverAddr: InetSocketAddress,
            credentials: TurnCredentials,
            addressFamily: RequestedAddressFamily = RequestedAddressFamily.IPv4,
            logger: ProxyLogger = NoOpLogger,
        ): TurnClient {
            val transport = TurnTransportFactory.udp(serverAddr)
            val auth = TurnAuthenticator(credentials, logger)
            val allocator = TurnAllocator(addressFamily, logger)
            val channelManager = TurnChannelManager(logger)
            return TurnClient(transport, auth, allocator, channelManager, logger)
        }
    }

    // ── public API ─────────────────────────────────────────────────────────

    /** Allocates a relay socket. Must be called once before [channelBind]. */
    fun allocate() = allocator.allocate(transport, auth)

    /**
     * Binds a channel to the given peer for efficient ChannelData framing.
     * [channel] must be in 0x4000..0x7FFE range.
     */
    fun channelBind(peerIp: ByteArray, peerPort: Int, channel: Int = TurnProxyConfig.CHANNEL_MIN) =
        channelManager.channelBind(transport, auth, peerIp, peerPort, channel)

    /** Sends data via ChannelData framing (requires prior [channelBind]). */
    fun send(data: ByteArray) = channelManager.send(transport, data)

    /**
     * Reads the next packet arriving on the relay.
     * Returns payload bytes, or null for non-data STUN messages (caller should loop).
     */
    fun receive(): ByteArray? = channelManager.receive(transport, auth, allocator)

    /** Sets receive timeout on the underlying transport socket (0 = block forever). */
    fun setReceiveTimeout(ms: Int) = transport.setReceiveTimeout(ms)

    /** Returns the relay address assigned by the TURN server. */
    fun relayAddress(): InetSocketAddress = allocator.relayAddress()

    /**
     * Suspending function that sends periodic keepalives until cancelled.
     * Launch as a coroutine alongside the relay loop after [allocate].
     */
    suspend fun runRefresh() {
        val intervalMs = TurnProxyConfig.TURN_REFRESH_INTERVAL_MS
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            try {
                allocator.refresh(transport, auth)
                channelManager.refreshChannel(transport, auth)
                logger.info(TAG, "Keepalive sent · relay=${relayAddress()}")
            } catch (e: Exception) {
                logger.warn(TAG, "Keepalive error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    override fun close() {
        transport.close()
        logger.debug(TAG, "Closed (relay=${relayAddress()})")
    }
}
