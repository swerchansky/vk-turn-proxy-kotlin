package com.github.swerchansky.vkturnproxy.dtls

import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.crypto.TlsCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * DTLS 1.2 server using BouncyCastle bctls.
 *
 * A background demultiplexer thread reads all UDP datagrams from the shared
 * socket and routes each packet to the correct per-client queue, keyed by
 * InetSocketAddress. This allows multiple concurrent DTLS connections on a
 * single UDP port.
 */
class DtlsServer(
    listenAddr: InetSocketAddress,
    private val readTimeoutMs: Int = 30 * 60 * 1000,
) : Closeable {

    private val socket = DatagramSocket(listenAddr)
    private val crypto = BcTlsCrypto(SecureRandom())
    private val protocol = DTLSServerProtocol()
    private val cred = SelfSignedEcdsaCred(crypto)
    private val log = Logger.getLogger("dtls-server")

    // Per-client packet queues
    private val connQueues = ConcurrentHashMap<InetSocketAddress, LinkedBlockingQueue<ByteArray>>()
    // Addresses of brand-new clients (first packet seen), consumed by accept()
    private val newClients = LinkedBlockingQueue<InetSocketAddress>()

    init {
        Thread(::readLoop, "dtls-server-demux").also { it.isDaemon = true }.start()
    }

    private fun readLoop() {
        val buf = ByteArray(2048)
        while (!socket.isClosed) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                val addr = InetSocketAddress(pkt.address, pkt.port)
                val data = buf.copyOf(pkt.length)
                // computeIfAbsent is atomic: the lambda runs only on first encounter
                connQueues.computeIfAbsent(addr) {
                    newClients.offer(addr)
                    LinkedBlockingQueue()
                }.offer(data)
            } catch (_: SocketException) {
                if (!socket.isClosed) log.warning("DtlsServer demux: socket error")
                break
            } catch (e: Exception) {
                if (!socket.isClosed) log.warning("DtlsServer demux: ${e.message}")
            }
        }
    }

    /**
     * Blocks until a new client connects and completes the DTLS handshake.
     * Multiple calls can run concurrently (each handles its own client).
     */
    fun accept(): DtlsServerConnection {
        val clientAddr = newClients.take()
        val queue = connQueues[clientAddr]!!
        log.info("DTLS: new client $clientAddr — starting handshake")

        val transport = object : DatagramTransport {
            override fun getSendLimit() = 1400
            override fun getReceiveLimit() = 1400

            override fun send(data: ByteArray, off: Int, len: Int) {
                socket.send(DatagramPacket(data, off, len, clientAddr))
            }

            override fun receive(data: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
                val packet = queue.poll(waitMillis.coerceAtLeast(1).toLong(), TimeUnit.MILLISECONDS)
                    ?: return -1
                val copyLen = minOf(len, packet.size)
                if (packet.size > len) {
                    log.warning("DTLS-server: packet from $clientAddr ${packet.size}B > buf ${len}B, truncating!")
                }
                packet.copyInto(data, off, 0, copyLen)
                return copyLen
            }

            override fun close() {
                log.fine("DTLS-server: transport closed for $clientAddr")
                connQueues.remove(clientAddr)
            }
        }

        return try {
            val dtls = protocol.accept(GoodTurnTlsServer(crypto, cred), transport)
            log.info("DTLS: handshake OK with $clientAddr")
            DtlsServerConnection(dtls, readTimeoutMs) { connQueues.remove(clientAddr) }
        } catch (e: Exception) {
            connQueues.remove(clientAddr)
            log.warning("DTLS: handshake failed with $clientAddr — ${e::class.simpleName}: ${e.message}")
            throw e
        }
    }

    override fun close() = socket.close()
}

class DtlsServerConnection(
    private val dtlsTransport: DTLSTransport,
    private val readTimeoutMs: Int,
    private val onClose: () -> Unit = {},
) : Closeable {
    fun send(data: ByteArray) = dtlsTransport.send(data, 0, data.size)
    fun receive(buf: ByteArray): Int = dtlsTransport.receive(buf, 0, buf.size, readTimeoutMs)
    override fun close() {
        runCatching { dtlsTransport.close() }
        onClose()
    }
}

// ── TLS server ────────────────────────────────────────────────────────────

private class GoodTurnTlsServer(
    crypto: BcTlsCrypto,
    private val cred: SelfSignedEcdsaCred,
) : DefaultTlsServer(crypto) {

    override fun getSupportedVersions(): Array<ProtocolVersion> =
        arrayOf(ProtocolVersion.DTLSv12)

    override fun getCipherSuites(): IntArray =
        intArrayOf(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)

    override fun getCertificateRequest() = null // no client cert required

    override fun getECDSASignerCredentials(): TlsCredentialedSigner = cred.build(context)
}
