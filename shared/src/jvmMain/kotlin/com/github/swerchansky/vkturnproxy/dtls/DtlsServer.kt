package com.github.swerchansky.vkturnproxy.dtls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsContext
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsExtensionsUtils
import org.bouncycastle.tls.crypto.TlsCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.Closeable
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Date
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
     */
    fun accept(): DtlsServerConnection {
        val buf = ByteArray(2048)
        while (true) {
            val pkt = DatagramPacket(buf, buf.size)
            socket.receive(pkt)
            val clientAddr = InetSocketAddress(pkt.address, pkt.port)
            val clientSocket = DatagramSocket()
            clientSocket.connect(clientAddr)
            val transport = UdpDatagramTransport(clientSocket, clientAddr)
            return try {
                val dtls = protocol.accept(GoodTurnTlsServer(crypto, cred), transport)
                DtlsServerConnection(dtls, clientSocket, readTimeoutMs)
            } catch (e: Exception) {
                clientSocket.close()
                throw e
            }
        }
    }

    override fun close() = socket.close()
}

class DtlsServerConnection(
    private val dtlsTransport: DTLSTransport,
    private val socket: DatagramSocket,
    private val readTimeoutMs: Int,
) : Closeable {
    fun send(data: ByteArray) = dtlsTransport.send(data, 0, data.size)
    fun receive(buf: ByteArray): Int = dtlsTransport.receive(buf, 0, buf.size, readTimeoutMs)
    override fun close() {
        runCatching { dtlsTransport.close() }
        runCatching { socket.close() }
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
