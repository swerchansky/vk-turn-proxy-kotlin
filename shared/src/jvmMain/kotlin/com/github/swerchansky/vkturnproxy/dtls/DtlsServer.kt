package com.github.swerchansky.vkturnproxy.dtls

import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsExtensionsUtils
import org.bouncycastle.tls.crypto.TlsCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Hashtable

/**
 * DTLS 1.2 server using BouncyCastle bctls.
 *
 * Matches the Go pion/dtls server config:
 *  - Cipher: TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
 *  - Self-signed ECDSA-P256 certificate
 *  - ExtendedMasterSecret required
 *  - 8-byte random Connection ID (RandomCIDGenerator(8) equivalent)
 */
class DtlsServer(
    listenAddr: InetSocketAddress,
    private val handshakeTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 30 * 60 * 1000,
) : Closeable {

    private val socket = DatagramSocket(listenAddr)
    private val crypto = BcTlsCrypto(SecureRandom())
    private val protocol = DTLSServerProtocol()
    private val cred = SelfSignedEcdsaCred(crypto)

    init { ensureBcProvider() }

    /**
     * Blocks until a client connects and completes the DTLS handshake.
     *
     * Uses the shared server socket so the client always sends to the same port.
     * The first received datagram (ClientHello) is replayed into the DTLS handshake.
     */
    fun accept(): DtlsServerConnection {
        val buf = ByteArray(2048)
        while (true) {
            socket.soTimeout = 0 // block indefinitely for first packet
            val pkt = DatagramPacket(buf, buf.size)
            socket.receive(pkt)
            val clientAddr = InetSocketAddress(pkt.address, pkt.port)
            val firstPacket = buf.copyOf(pkt.length)

            val transport = object : DatagramTransport {
                private var firstConsumed = false

                override fun getSendLimit() = 1200
                override fun getReceiveLimit() = 1200

                override fun send(data: ByteArray, off: Int, len: Int) {
                    socket.send(DatagramPacket(data, off, len, clientAddr))
                }

                override fun receive(data: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
                    if (!firstConsumed) {
                        firstConsumed = true
                        val copyLen = minOf(len, firstPacket.size)
                        firstPacket.copyInto(data, off, 0, copyLen)
                        return copyLen
                    }
                    socket.soTimeout = waitMillis
                    val incoming = DatagramPacket(data, off, len)
                    return try {
                        socket.receive(incoming)
                        incoming.length
                    } catch (_: SocketTimeoutException) {
                        -1
                    }
                }

                override fun close() {} // shared socket — do not close
            }

            return try {
                val dtls = protocol.accept(GoodTurnTlsServer(crypto, cred), transport)
                DtlsServerConnection(dtls, readTimeoutMs)
            } catch (e: Exception) {
                throw e
            }
        }
    }

    override fun close() = socket.close()
}

class DtlsServerConnection(
    private val dtlsTransport: DTLSTransport,
    private val readTimeoutMs: Int,
) : Closeable {
    fun send(data: ByteArray) = dtlsTransport.send(data, 0, data.size)
    fun receive(buf: ByteArray): Int = dtlsTransport.receive(buf, 0, buf.size, readTimeoutMs)
    override fun close() {
        runCatching { dtlsTransport.close() }
    }
}

// ── TLS server ────────────────────────────────────────────────────────────

private class GoodTurnTlsServer(
    crypto: BcTlsCrypto,
    private val cred: SelfSignedEcdsaCred,
) : DefaultTlsServer(crypto) {

    override fun getCipherSuites(): IntArray =
        intArrayOf(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)

    // 8-byte random CID (matches pion RandomCIDGenerator(8))
    override fun getServerExtensions(): Hashtable<Int, ByteArray> {
        @Suppress("UNCHECKED_CAST")
        val ext = super.getServerExtensions() as? Hashtable<Int, ByteArray> ?: Hashtable()
        val cid = ByteArray(8).also { SecureRandom().nextBytes(it) }
        TlsExtensionsUtils.addConnectionIDExtension(ext, cid)
        return ext
    }

    override fun getCertificateRequest() = null // no client cert required

    override fun getECDSASignerCredentials(): TlsCredentialedSigner = cred.build(context)
}
