package com.github.swerchansky.vkturnproxy.transport

import com.github.swerchansky.vkturnproxy.error.TurnProxyError
import com.github.swerchansky.vkturnproxy.stun.StunMessage
import com.github.swerchansky.vkturnproxy.stun.decodeStunMessage
import com.github.swerchansky.vkturnproxy.stun.encode
import com.github.swerchansky.vkturnproxy.stun.readUInt16
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP transport for TURN with RFC 4571 (2-byte length-prefixed) framing.
 */
internal class TcpTurnTransport(
    addr: InetSocketAddress,
    connectTimeoutMs: Int = 10_000,
) : TurnTransport {

    private val socket = Socket().also { it.connect(addr, connectTimeoutMs) }
    private val out: OutputStream = socket.getOutputStream()
    private val inp: InputStream = socket.getInputStream()

    override fun sendReceive(msg: StunMessage): StunMessage? {
        sendRaw(msg.encode())
        repeat(10) {
            val raw = receiveRaw()
            val resp = decodeStunMessage(raw) ?: return@repeat
            if (resp.transactionId.contentEquals(msg.transactionId)) return resp
        }
        return null
    }

    override fun sendRaw(data: ByteArray) {
        // RFC 4571: 2-byte big-endian length prefix
        val frame = ByteArray(2 + data.size)
        frame[0] = (data.size ushr 8).toByte()
        frame[1] = (data.size and 0xFF).toByte()
        data.copyInto(frame, 2)
        synchronized(out) { out.write(frame); out.flush() }
    }

    override fun receiveRaw(): ByteArray {
        val lenBuf = ByteArray(2)
        readFully(inp, lenBuf)
        val len = readUInt16(lenBuf, 0)
        val buf = ByteArray(len)
        readFully(inp, buf)
        return buf
    }

    override fun setReceiveTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    override fun close() = socket.close()

    private fun readFully(stream: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = stream.read(buf, read, buf.size - read)
            if (n < 0) throw TurnProxyError.TransportError("Connection closed while reading TURN response")
            read += n
        }
    }
}
