package com.github.swerchansky.vkturnproxy.turn

import com.github.swerchansky.vkturnproxy.config.TurnProxyConfig
import com.github.swerchansky.vkturnproxy.error.TurnProxyError
import com.github.swerchansky.vkturnproxy.logging.NoOpLogger
import com.github.swerchansky.vkturnproxy.logging.ProxyLogger
import com.github.swerchansky.vkturnproxy.stun.StunAttr
import com.github.swerchansky.vkturnproxy.stun.StunClass
import com.github.swerchansky.vkturnproxy.stun.StunMessage
import com.github.swerchansky.vkturnproxy.stun.StunMethod
import com.github.swerchansky.vkturnproxy.stun.addXorIpv4Attr
import com.github.swerchansky.vkturnproxy.stun.decodeChannelData
import com.github.swerchansky.vkturnproxy.stun.decodeErrorCode
import com.github.swerchansky.vkturnproxy.stun.decodeStunMessage
import com.github.swerchansky.vkturnproxy.stun.encode
import com.github.swerchansky.vkturnproxy.stun.encodeChannelData
import com.github.swerchansky.vkturnproxy.stun.isChannelData
import com.github.swerchansky.vkturnproxy.transport.TurnTransport

/**
 * Manages TURN channel binding and ChannelData send/receive (RFC 5766 §11).
 */
internal class TurnChannelManager(
    private val logger: ProxyLogger = NoOpLogger,
) {
    private companion object {
        const val TAG = "TurnChannel"
    }

    var boundChannel: Int = -1
        private set
    private var boundPeerIp: ByteArray = ByteArray(4)
    private var boundPeerPort: Int = 0
    private var sendCount = 0
    private var recvCount = 0

    /**
     * Binds a channel to [peerIp]:[peerPort] for efficient ChannelData framing.
     * [channel] must be in 0x4000..0x7FFE range.
     */
    fun channelBind(
        transport: TurnTransport,
        auth: TurnAuthenticator,
        peerIp: ByteArray,
        peerPort: Int,
        channel: Int = TurnProxyConfig.CHANNEL_MIN,
    ) {
        check(channel in TurnProxyConfig.CHANNEL_MIN..TurnProxyConfig.CHANNEL_MAX) { "Invalid channel number" }
        val peerStr = peerIp.joinToString(".") { (it.toInt() and 0xFF).toString() } + ":$peerPort"
        logger.debug(TAG, "ChannelBind: channel=0x${channel.toString(16)} peer=$peerStr")

        boundChannel = channel
        boundPeerIp = peerIp.copyOf()
        boundPeerPort = peerPort

        val req = StunMessage(StunMethod.CHANNEL_BIND, StunClass.REQUEST)
        val chBuf = ByteArray(4)
        chBuf[0] = (channel ushr 8).toByte()
        chBuf[1] = (channel and 0xFF).toByte()
        req.addAttr(StunAttr.CHANNEL_NUMBER, chBuf)
        req.addXorIpv4Attr(StunAttr.XOR_PEER_ADDRESS, peerIp, peerPort)
        auth.addAuth(req)

        val resp = transport.sendReceive(req)
            ?: throw TurnProxyError.TurnAllocationFailed("ChannelBind: no response")
        if (resp.cls != StunClass.SUCCESS)
            throw TurnProxyError.TurnAllocationFailed(
                "ChannelBind: ${
                    decodeErrorCode(
                        resp.getAttr(
                            StunAttr.ERROR_CODE
                        )
                    )
                }"
            )
        logger.info(TAG, "ChannelBind OK: channel=0x${channel.toString(16)} peer=$peerStr")
    }

    /** Sends data via ChannelData framing (requires prior [channelBind]). */
    fun send(transport: TurnTransport, data: ByteArray) {
        check(boundChannel >= 0) { "Must call channelBind before send" }
        sendCount++
        if (sendCount <= 5 || sendCount % 100 == 0)
            logger.debug(
                TAG,
                "Send #$sendCount ${data.size}B via ch=0x${boundChannel.toString(16)}"
            )
        transport.sendRaw(encodeChannelData(boundChannel, data))
    }

    /**
     * Reads the next packet arriving on the relay.
     * Returns payload bytes, or null for non-ChannelData STUN messages (caller should loop).
     * Handles 438 Stale Nonce by scheduling an immediate refresh via [auth].
     */
    fun receive(
        transport: TurnTransport,
        auth: TurnAuthenticator,
        allocator: TurnAllocator
    ): ByteArray? {
        val raw = transport.receiveRaw()
        if (isChannelData(raw)) {
            val (ch, payload) = decodeChannelData(raw) ?: return null
            if (ch == boundChannel) {
                recvCount++
                if (recvCount <= 5 || recvCount % 100 == 0)
                    logger.debug(
                        TAG,
                        "Recv #$recvCount ${payload.size}B from ch=0x${ch.toString(16)}"
                    )
                return payload
            }
            logger.warn(
                TAG,
                "Recv: unexpected ch=0x${ch.toString(16)} (bound=0x${boundChannel.toString(16)}), dropping"
            )
            return null
        }
        val msg = decodeStunMessage(raw)
        if (msg != null) {
            val errCode = auth.parseErrorCode(msg)
            if (msg.cls == StunClass.ERROR && errCode == 438) {
                val newNonce = msg.getAttr(StunAttr.NONCE)?.decodeToString()
                if (!newNonce.isNullOrEmpty()) {
                    logger.debug(TAG, "438 Stale Nonce → updating nonce and retrying refresh")
                    auth.nonce = newNonce
                    Thread({ retryRefresh(transport, auth, allocator) }, "turn-nonce-refresh")
                        .also { it.isDaemon = true }.start()
                } else {
                    logger.warn(TAG, "438 Stale Nonce but response has no NONCE attr")
                }
            } else {
                logger.debug(
                    TAG,
                    "Non-ChannelData STUN method=0x${msg.method.toString(16)} cls=0x${
                        msg.cls.toString(16)
                    }, skipping"
                )
            }
        } else {
            logger.warn(TAG, "Unrecognised ${raw.size}B frame, skipping")
        }
        return null
    }

    /**
     * Refreshes the channel binding (10-min lifetime per RFC 5766 §11.3).
     * Not renewed by the allocation Refresh — must be sent separately.
     */
    fun refreshChannel(transport: TurnTransport, auth: TurnAuthenticator) {
        if (boundChannel < 0) return
        val chReq = StunMessage(StunMethod.CHANNEL_BIND, StunClass.REQUEST)
        val chBuf = ByteArray(4)
        chBuf[0] = (boundChannel ushr 8).toByte()
        chBuf[1] = (boundChannel and 0xFF).toByte()
        chReq.addAttr(StunAttr.CHANNEL_NUMBER, chBuf)
        chReq.addXorIpv4Attr(StunAttr.XOR_PEER_ADDRESS, boundPeerIp, boundPeerPort)
        auth.addAuth(chReq)
        transport.sendRaw(chReq.encode())
    }

    private fun retryRefresh(
        transport: TurnTransport,
        auth: TurnAuthenticator,
        allocator: TurnAllocator
    ) {
        try {
            allocator.refresh(transport, auth)
            refreshChannel(transport, auth)
        } catch (e: Exception) {
            logger.warn(TAG, "Nonce-refresh retry failed: ${e.message}")
        }
    }
}
