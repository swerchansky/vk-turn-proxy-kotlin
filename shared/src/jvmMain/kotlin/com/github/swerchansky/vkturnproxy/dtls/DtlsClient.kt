package com.github.swerchansky.vkturnproxy.dtls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsContext
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsExtensionsUtils
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.Closeable
import java.math.BigInteger
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.Hashtable

/**
 * DTLS 1.2 client using BouncyCastle bctls.
 *
 * Matches the Go pion/dtls client config:
 *  - Cipher: TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
 *  - Self-signed ECDSA-P256 certificate
 *  - InsecureSkipVerify (no server cert verification)
 *  - ExtendedMasterSecret required
 *  - DTLS Connection ID extension (RFC 9146), empty CID (OnlySendCIDGenerator equivalent)
 */
class DtlsClient(
    private val serverAddr: InetSocketAddress,
    private val handshakeTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 30 * 60 * 1000,
) : Closeable {

    private lateinit var socket: DatagramSocket
    private lateinit var dtlsTransport: DTLSTransport

    init { ensureBcProvider() }

    fun connect() {
        socket = DatagramSocket()
        socket.connect(serverAddr)
        val crypto = BcTlsCrypto(SecureRandom())
        val protocol = DTLSClientProtocol()
        val transport = UdpDatagramTransport(socket, serverAddr)
        dtlsTransport = protocol.connect(GoodTurnTlsClient(crypto), transport)
    }

    fun send(data: ByteArray) = dtlsTransport.send(data, 0, data.size)

    /** Returns bytes read, or -1 on timeout/close. */
    fun receive(buf: ByteArray): Int = dtlsTransport.receive(buf, 0, buf.size, readTimeoutMs)

    override fun close() {
        runCatching { dtlsTransport.close() }
        runCatching { socket.close() }
    }
}

// ── BouncyCastle TLS client ────────────────────────────────────────────────

private class GoodTurnTlsClient(private val crypto: BcTlsCrypto) : DefaultTlsClient(crypto) {

    private val cred = SelfSignedEcdsaCred(crypto)

    override fun getCipherSuites(): IntArray =
        intArrayOf(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)

    // Empty CID = "I support CID but don't require one" (matches pion OnlySendCIDGenerator)
    override fun getClientExtensions(): Hashtable<Int, ByteArray> {
        @Suppress("UNCHECKED_CAST")
        val ext = super.getClientExtensions() as? Hashtable<Int, ByteArray> ?: Hashtable()
        TlsExtensionsUtils.addConnectionIDExtension(ext, ByteArray(0))
        return ext
    }

    override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
        override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) {
            // InsecureSkipVerify — accept any server certificate
        }

        override fun getClientCredentials(req: CertificateRequest?): TlsCredentials =
            cred.build(context)
    }
}

// ── Self-signed ECDSA credentials ─────────────────────────────────────────

internal class SelfSignedEcdsaCred(private val crypto: BcTlsCrypto) {

    private val keyPair: KeyPair = run {
        val kpg = KeyPairGenerator.getInstance("EC", "BC")
        kpg.initialize(ECGenParameterSpec("prime256v1"), SecureRandom())
        kpg.generateKeyPair()
    }

    private val certHolder = run {
        val now = Date()
        val notAfter = Date(now.time + 365L * 24 * 3600 * 1000)
        JcaX509v3CertificateBuilder(
            X500Name("CN=good-turn"),
            BigInteger.valueOf(SecureRandom().nextLong().and(Long.MAX_VALUE)),
            now, notAfter,
            X500Name("CN=good-turn"),
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(keyPair.private))
    }

    fun build(ctx: TlsContext): TlsCredentialedSigner {
        val bcCert = BcTlsCertificate(crypto, certHolder.toASN1Structure())
        val tlsCert = Certificate(arrayOf<TlsCertificate>(bcCert))
        val bcPrivKey = PrivateKeyFactory.createKey(keyPair.private.encoded)
        val sigAlg = SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)
        return BcDefaultTlsCredentialedSigner(
            TlsCryptoParameters(ctx), crypto, bcPrivKey, tlsCert, sigAlg,
        )
    }
}

// ── BC provider ────────────────────────────────────────────────────────────

internal fun ensureBcProvider() {
    if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
}
