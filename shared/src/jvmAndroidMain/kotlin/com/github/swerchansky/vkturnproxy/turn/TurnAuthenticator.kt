package com.github.swerchansky.vkturnproxy.turn

import com.github.swerchansky.vkturnproxy.logging.NoOpLogger
import com.github.swerchansky.vkturnproxy.logging.ProxyLogger
import com.github.swerchansky.vkturnproxy.stun.StunAttr
import com.github.swerchansky.vkturnproxy.stun.StunMessage
import com.github.swerchansky.vkturnproxy.stun.encodeForHmac
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Manages TURN long-term credential auth (RFC 5389 §10.2):
 * realm/nonce tracking, MD5 key derivation, HMAC-SHA1 message integrity.
 */
internal class TurnAuthenticator(
    private val credentials: TurnCredentials,
    private val logger: ProxyLogger = NoOpLogger,
) {
    private companion object {
        const val TAG = "TurnAuth"
    }

    var realm: String = ""
    @Volatile
    var nonce: String = ""

    /**
     * Appends USERNAME, REALM, NONCE (when known) and MESSAGE-INTEGRITY to [msg].
     */
    fun addAuth(msg: StunMessage) {
        if (realm.isNotEmpty()) {
            msg.addStringAttr(StunAttr.USERNAME, credentials.username)
            msg.addStringAttr(StunAttr.REALM, realm)
            msg.addStringAttr(StunAttr.NONCE, nonce)
        }
        val key = buildKey()
        val macData = msg.encodeForHmac()
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(key, "HmacSHA1"))
        }.doFinal(macData)
        logger.debug(TAG, "MI: user='${credentials.username}' realm='$realm' mac=${mac.hex()}")
        msg.addAttr(StunAttr.MESSAGE_INTEGRITY, mac)
    }

    /** Parse numeric error code from ERROR-CODE attribute. */
    fun parseErrorCode(msg: StunMessage): Int {
        val attr = msg.getAttr(StunAttr.ERROR_CODE) ?: return 0
        if (attr.size < 4) return 0
        return (attr[2].toInt() and 0xFF) * 100 + (attr[3].toInt() and 0xFF)
    }

    // MD5(username ":" realm ":" password) per RFC 5389
    private fun buildKey(): ByteArray {
        val input = "${credentials.username}:$realm:${credentials.password}"
        return MessageDigest.getInstance("MD5").digest(input.encodeToByteArray())
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
