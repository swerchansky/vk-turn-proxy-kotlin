package com.github.swerchansky.vkturnproxy.dtls

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECNamedDomainParameters
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcECContentSignerBuilder
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import com.github.swerchansky.vkturnproxy.turn.TurnClient
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsContext
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsCredentialedSigner
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
import java.security.SecureRandom
import java.util.Date

/**
 * DTLS 1.2 client using BouncyCastle bctls.
 * Uses pure BC crypto API (no JCE) for Android compatibility.
 */
class DtlsClient(
    private val serverAddr: InetSocketAddress? = null,
    private val readTimeoutMs: Int = 30 * 60 * 1000,
) : Closeable {

    private var socket: DatagramSocket? = null
    private var dtlsTransport: DTLSTransport? = null

    /** Connect directly via UDP to [serverAddr]. */
    fun connect() {
        requireNotNull(serverAddr) { "serverAddr required for direct UDP connect" }
        val s = DatagramSocket().also { it.connect(serverAddr) }
        socket = s
        connectWith(UdpDatagramTransport(s, serverAddr))
    }

    /** Connect using a custom [DatagramTransport] (e.g. TURN relay). */
    fun connect(transport: DatagramTransport) = connectWith(transport)

    /** Connect through a TURN relay — DTLS packets are sent/received via [turnClient]. */
    fun connectOverTurn(turnClient: TurnClient, logger: (String) -> Unit = {}) =
        connectWith(TurnDatagramTransport(turnClient, logger), logger)

    private fun connectWith(transport: DatagramTransport, logger: (String) -> Unit = {}) {
        val crypto = BcTlsCrypto(SecureRandom())
        logger("DTLS-client: starting BouncyCastle handshake (DTLS 1.2, TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)")
        val t0 = System.currentTimeMillis()
        dtlsTransport = DTLSClientProtocol().connect(GoodTurnTlsClient(crypto), transport)
        logger("DTLS-client: handshake complete in ${System.currentTimeMillis() - t0}ms")
    }

    fun send(data: ByteArray) {
        dtlsTransport!!.send(data, 0, data.size)
    }

    /** Returns bytes read, or -1 on timeout/close. */
    fun receive(buf: ByteArray): Int {
        val n = dtlsTransport!!.receive(buf, 0, buf.size, readTimeoutMs)
        return n
    }

    override fun close() {
        runCatching { dtlsTransport?.close() }
        runCatching { socket?.close() }
    }
}

// ── BouncyCastle TLS client ────────────────────────────────────────────────

private class GoodTurnTlsClient(private val crypto: BcTlsCrypto) : DefaultTlsClient(crypto) {

    private val cred = SelfSignedEcdsaCred(crypto)

    override fun getSupportedVersions(): Array<ProtocolVersion> =
        arrayOf(ProtocolVersion.DTLSv12)

    override fun getCipherSuites(): IntArray =
        intArrayOf(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)

    override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
        override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) {
            // InsecureSkipVerify — accept any server certificate
        }

        override fun getClientCredentials(req: CertificateRequest?): TlsCredentials =
            cred.build(context)
    }
}

// ── Self-signed ECDSA credentials (pure BC API, no JCE) ───────────────────

internal class SelfSignedEcdsaCred(private val crypto: BcTlsCrypto) {

    private val keyPair: AsymmetricCipherKeyPair = run {
        val x9 = SECNamedCurves.getByOID(SECObjectIdentifiers.secp256r1)
            ?: error("secp256r1 curve not found in SECNamedCurves")
        val domain = ECNamedDomainParameters(SECObjectIdentifiers.secp256r1, x9.curve, x9.g, x9.n, x9.h)
        val gen = ECKeyPairGenerator()
        gen.init(ECKeyGenerationParameters(domain, SecureRandom()))
        gen.generateKeyPair()
    }

    private val certHolder = run {
        val pubKeyInfo = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(keyPair.public)
        val now = Date()
        val notAfter = Date(now.time + 365L * 24 * 3600 * 1000)
        val sigAlgId = DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withECDSA")
        val digestAlgId = DefaultDigestAlgorithmIdentifierFinder().find(sigAlgId)
        X509v3CertificateBuilder(
            X500Name("CN=good-turn"),
            BigInteger.valueOf(SecureRandom().nextLong().and(Long.MAX_VALUE)),
            now, notAfter,
            X500Name("CN=good-turn"),
            pubKeyInfo,
        ).build(BcECContentSignerBuilder(sigAlgId, digestAlgId).build(keyPair.private))
    }

    fun build(ctx: TlsContext): TlsCredentialedSigner {
        val bcCert = BcTlsCertificate(crypto, certHolder.toASN1Structure())
        val tlsCert = Certificate(arrayOf<TlsCertificate>(bcCert))
        val sigAlg = SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)
        return BcDefaultTlsCredentialedSigner(TlsCryptoParameters(ctx), crypto, keyPair.private, tlsCert, sigAlg)
    }
}
