package com.github.swerchansky.vkturnproxy.dtls

import com.github.swerchansky.vkturnproxy.turn.TurnClient
import org.bouncycastle.tls.DatagramTransport
import java.net.SocketTimeoutException

/**
 * Adapts [TurnClient] as a [DatagramTransport] for BouncyCastle DTLS.
 * All DTLS packets are sent/received through the TURN relay channel.
 */
internal class TurnDatagramTransport(
    private val turnClient: TurnClient,
    private val logger: (String) -> Unit = {},
) : DatagramTransport {

    private var sentCount = 0
    private var recvCount = 0

    override fun getSendLimit(): Int = 1500
    override fun getReceiveLimit(): Int = 1500

    override fun send(data: ByteArray, off: Int, len: Int) {
        sentCount++
        logger("DTLS→TURN send #$sentCount ${len}B  hdr=${data.drop(off).take(3).joinToString("") { "%02x".format(it) }}")
        val payload = if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
        turnClient.send(payload)
    }

    override fun receive(data: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
        val isPostHandshake = waitMillis > 5_000
        if (isPostHandshake) logger("DTLS←TURN: post-handshake receive() timeout=${waitMillis}ms")
        else logger("DTLS←TURN: handshake receive() timeout=${waitMillis}ms")
        val deadline = System.currentTimeMillis() + waitMillis.coerceAtLeast(1)
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) {
                logger("DTLS←TURN: deadline expired after ${waitMillis}ms, returning -1")
                return -1
            }
            // Cap individual socket timeout to avoid blocking past deadline
            turnClient.setReceiveTimeout(remaining.coerceAtMost(2_000))
            try {
                val payload = turnClient.receive()
                if (payload == null) {
                    logger("DTLS←TURN: non-ChannelData STUN message received, skipping (remaining=${remaining}ms)")
                    continue
                }
                recvCount++
                logger("DTLS←TURN recv #$recvCount ${payload.size}B  hdr=${payload.take(3).joinToString("") { "%02x".format(it) }}")
                val copyLen = minOf(len, payload.size)
                if (payload.size > len) logger("DTLS←TURN WARN: payload ${payload.size}B > buf $len B, truncating!")
                payload.copyInto(data, off, 0, copyLen)
                return copyLen
            } catch (_: SocketTimeoutException) {
                // Socket poll timed out — loop and check overall deadline
                logger("DTLS←TURN: socket poll timeout (remaining=${(deadline - System.currentTimeMillis()).toInt()}ms), looping")
                continue
            }
        }
    }

    override fun close() {} // TurnClient lifecycle is managed externally
}
