package com.github.swerchansky.vkturnproxy.transport

import com.github.swerchansky.vkturnproxy.stun.StunMessage
import com.github.swerchansky.vkturnproxy.stun.decodeStunMessage
import com.github.swerchansky.vkturnproxy.stun.encode
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

private const val DEFAULT_RECEIVE_TIMEOUT_MS = 5_000
private const val MAX_STUN_MSG_SIZE = 65535

/**
 * UDP transport for TURN — each STUN message is a single datagram.
 */
internal class UdpTurnTransport(serverAddr: InetSocketAddress) : TurnTransport {

    private val socket = DatagramSocket().also {
        it.connect(serverAddr)
        it.soTimeout = DEFAULT_RECEIVE_TIMEOUT_MS
    }

    override fun sendReceive(msg: StunMessage): StunMessage? {
        sendRaw(msg.encode())
        repeat(3) {
            try {
                val raw = receiveRaw()
                val resp = decodeStunMessage(raw) ?: return@repeat
                if (resp.transactionId.contentEquals(msg.transactionId)) return resp
            } catch (_: java.net.SocketTimeoutException) {
                return@repeat
            }
        }
        return null
    }

    override fun sendRaw(data: ByteArray) {
        socket.send(DatagramPacket(data, data.size))
    }

    override fun receiveRaw(): ByteArray {
        val buf = ByteArray(MAX_STUN_MSG_SIZE)
        val pkt = DatagramPacket(buf, buf.size)
        socket.receive(pkt)
        return pkt.data.copyOf(pkt.length)
    }

    override fun setReceiveTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    override fun close() = socket.close()
}
