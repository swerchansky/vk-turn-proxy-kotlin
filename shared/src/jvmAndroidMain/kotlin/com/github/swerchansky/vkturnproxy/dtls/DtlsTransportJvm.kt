package com.github.swerchansky.vkturnproxy.dtls

import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.TlsTimeoutException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

internal class UdpDatagramTransport(
    private val socket: DatagramSocket,
    private val remote: InetSocketAddress,
    private val mtu: Int = 1500,
) : DatagramTransport {
    override fun getReceiveLimit(): Int = mtu - 28
    override fun getSendLimit(): Int = mtu - 28

    override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
        socket.soTimeout = waitMillis.coerceAtLeast(1)
        val pkt = DatagramPacket(buf, off, len)
        return try {
            socket.receive(pkt)
            pkt.length
        } catch (_: java.net.SocketTimeoutException) {
            throw TlsTimeoutException("DTLS receive timeout")
        }
    }

    override fun send(buf: ByteArray, off: Int, len: Int) {
        socket.send(DatagramPacket(buf, off, len, remote))
    }

    override fun close() { /* socket managed externally */ }
}
