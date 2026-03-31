package com.github.swerchansky.vkturnproxy.dtls

import com.github.swerchansky.vkturnproxy.turn.TurnClient
import org.bouncycastle.tls.DatagramTransport
import java.net.SocketTimeoutException
import java.util.logging.Logger

/**
 * Adapts [TurnClient] as a [DatagramTransport] for BouncyCastle DTLS.
 * All DTLS packets are sent/received through the TURN relay channel.
 */
internal class TurnDatagramTransport(
    private val turnClient: TurnClient,
) : DatagramTransport {

    private val log = Logger.getLogger("turn-dtls-transport")

    override fun getSendLimit(): Int = 1500
    override fun getReceiveLimit(): Int = 1500

    override fun send(data: ByteArray, off: Int, len: Int) {
        val payload = if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
        turnClient.send(payload)
    }

    override fun receive(data: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
        val deadline = System.currentTimeMillis() + waitMillis.coerceAtLeast(1)
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) {
                log.fine("DTLS←TURN: deadline expired after ${waitMillis}ms")
                return -1
            }
            turnClient.setReceiveTimeout(remaining.coerceAtMost(2_000))
            try {
                val payload = turnClient.receive()
                if (payload == null) {
                    // Non-ChannelData (STUN keepalive/indication) — normal, skip silently
                    continue
                }
                val copyLen = minOf(len, payload.size)
                if (payload.size > len) {
                    log.warning("DTLS←TURN: payload ${payload.size}B > buffer ${len}B, truncating!")
                }
                payload.copyInto(data, off, 0, copyLen)
                return copyLen
            } catch (_: SocketTimeoutException) {
                continue
            }
        }
    }

    override fun close() {} // TurnClient lifecycle is managed externally
}
