package com.github.swerchansky.vkturnproxy.turn

// ── Message type constants ────────────────────────────────────────────────

internal object StunMethod {
    const val ALLOCATE: Int = 0x0003
    const val REFRESH: Int = 0x0004
    const val CHANNEL_BIND: Int = 0x0009
    const val CREATE_PERMISSION: Int = 0x0008
}

internal object StunClass {
    const val REQUEST: Int = 0x0000
    const val SUCCESS: Int = 0x0100
    const val ERROR: Int = 0x0110
}

internal object StunAttr {
    const val USERNAME: Int = 0x0006
    const val MESSAGE_INTEGRITY: Int = 0x0008
    const val ERROR_CODE: Int = 0x0009
    const val REALM: Int = 0x0014
    const val NONCE: Int = 0x0015
    const val XOR_PEER_ADDRESS: Int = 0x0012
    const val XOR_RELAYED_ADDRESS: Int = 0x0016
    const val REQUESTED_ADDRESS_FAMILY: Int = 0x0017
    const val REQUESTED_TRANSPORT: Int = 0x0019
    const val CHANNEL_NUMBER: Int = 0x000C
    const val LIFETIME: Int = 0x000D
}

internal const val STUN_MAGIC_COOKIE: Int = 0x2112A442.toInt()
internal const val CHANNEL_DATA_MIN: Int = 0x4000
internal const val CHANNEL_DATA_MAX: Int = 0x7FFF

// ── Message type encoding (RFC 5389 §6) ──────────────────────────────────

internal fun stunMsgType(method: Int, cls: Int): Int =
    ((method and 0x0F80) shl 2) or
            (cls and 0x0100) or
            ((method and 0x0070) shl 1) or
            (cls and 0x0010) or
            (method and 0x000F)

// ── StunMessage ───────────────────────────────────────────────────────────

/**
 * Minimal STUN/TURN message — encode + decode only (no crypto here).
 *
 * Wire format (RFC 5389 §6):
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |0 0|     STUN Message Type     |         Message Length        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Magic Cookie                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     Transaction ID (96 bits)                  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Attributes …                          |
 */
internal class StunMessage(
    val method: Int,
    val cls: Int,
    val transactionId: ByteArray = randomBytes(12),
) {
    private val attrs = mutableListOf<Pair<Int, ByteArray>>()

    fun addAttr(type: Int, value: ByteArray) { attrs.add(type to value) }
    fun addStringAttr(type: Int, value: String) = addAttr(type, value.encodeToByteArray())
    fun addUInt32Attr(type: Int, value: Int) = addAttr(type, intToBytes(value))

    fun getAttr(type: Int): ByteArray? = attrs.firstOrNull { it.first == type }?.second
    fun allAttrs(): List<Pair<Int, ByteArray>> = attrs

    /** Encode to wire bytes. */
    fun encode(): ByteArray {
        val body = encodeAttrs(attrs)
        return buildHeader(body.size) + body
    }

    /**
     * Encode for HMAC computation (RFC 5389 §15.4):
     * excludes MESSAGE-INTEGRITY and everything after it,
     * but sets length as if MESSAGE-INTEGRITY (20 bytes) were included.
     */
    fun encodeForHmac(): ByteArray {
        val attrsBeforeHmac = attrs.takeWhile { it.first != StunAttr.MESSAGE_INTEGRITY }
        val body = encodeAttrs(attrsBeforeHmac)
        // Length = body so far + 4 (attr header) + 20 (HMAC value)
        val lengthWithHmac = body.size + 4 + 20
        return buildHeader(lengthWithHmac) + body
    }

    private fun buildHeader(attrLength: Int): ByteArray {
        val buf = ByteArray(20)
        val msgType = stunMsgType(method, cls)
        buf[0] = (msgType ushr 8).toByte()
        buf[1] = (msgType and 0xFF).toByte()
        buf[2] = (attrLength ushr 8).toByte()
        buf[3] = (attrLength and 0xFF).toByte()
        buf[4] = 0x21; buf[5] = 0x12; buf[6] = 0xA4.toByte(); buf[7] = 0x42
        transactionId.copyInto(buf, 8)
        return buf
    }

    companion object {
        fun decode(data: ByteArray): StunMessage? {
            if (data.size < 20) return null
            if ((data[0].toInt() and 0xC0) != 0) return null
            val magic = readInt32(data, 4)
            if (magic != STUN_MAGIC_COOKIE) return null
            val length = readUInt16(data, 2)
            if (data.size < 20 + length) return null
            val msgType = readUInt16(data, 0)
            val method = ((msgType and 0x3E00) ushr 2) or ((msgType and 0x00E0) ushr 1) or (msgType and 0x000F)
            val cls = (msgType and 0x0100) or (msgType and 0x0010)
            val txId = data.copyOfRange(8, 20)
            val msg = StunMessage(method, cls, txId)
            var pos = 20
            while (pos + 4 <= 20 + length) {
                val attrType = readUInt16(data, pos)
                val attrLen = readUInt16(data, pos + 2)
                pos += 4
                if (pos + attrLen > data.size) break
                msg.addAttr(attrType, data.copyOfRange(pos, pos + attrLen))
                pos += attrLen + ((4 - attrLen % 4) % 4) // 4-byte align
            }
            return msg
        }

        fun isChannelData(data: ByteArray): Boolean {
            if (data.size < 4) return false
            val ch = readUInt16(data, 0)
            return ch in CHANNEL_DATA_MIN..CHANNEL_DATA_MAX
        }

        fun encodeChannelData(channel: Int, payload: ByteArray): ByteArray {
            val buf = ByteArray(4 + payload.size)
            buf[0] = (channel ushr 8).toByte()
            buf[1] = (channel and 0xFF).toByte()
            buf[2] = (payload.size ushr 8).toByte()
            buf[3] = (payload.size and 0xFF).toByte()
            payload.copyInto(buf, 4)
            return buf
        }

        fun decodeChannelData(data: ByteArray): Pair<Int, ByteArray>? {
            if (data.size < 4) return null
            val ch = readUInt16(data, 0)
            val len = readUInt16(data, 2)
            if (data.size < 4 + len) return null
            return ch to data.copyOfRange(4, 4 + len)
        }

        fun readInt32(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xFF shl 24) or (buf[off + 1].toInt() and 0xFF shl 16) or
                    (buf[off + 2].toInt() and 0xFF shl 8) or (buf[off + 3].toInt() and 0xFF)

        fun readUInt16(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xFF shl 8) or (buf[off + 1].toInt() and 0xFF)

        fun intToBytes(v: Int): ByteArray = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
        )

        private fun encodeAttrs(attrs: List<Pair<Int, ByteArray>>): ByteArray {
            val size = attrs.sumOf { (_, v) -> 4 + v.size + ((4 - v.size % 4) % 4) }
            val buf = ByteArray(size)
            var pos = 0
            for ((t, v) in attrs) {
                buf[pos] = (t ushr 8).toByte(); buf[pos + 1] = (t and 0xFF).toByte()
                buf[pos + 2] = (v.size ushr 8).toByte(); buf[pos + 3] = (v.size and 0xFF).toByte()
                pos += 4
                v.copyInto(buf, pos)
                pos += v.size + ((4 - v.size % 4) % 4)
            }
            return buf
        }
    }
}

internal expect fun randomBytes(size: Int): ByteArray

// ── XOR-ADDRESS helpers ───────────────────────────────────────────────────

/** Encode XOR-PEER-ADDRESS / XOR-RELAYED-ADDRESS for IPv4. */
internal fun StunMessage.addXorIpv4Attr(type: Int, ip: ByteArray, port: Int) {
    val buf = ByteArray(8)
    buf[0] = 0x00; buf[1] = 0x01 // IPv4 family
    val xorPort = (port xor (STUN_MAGIC_COOKIE ushr 16)) and 0xFFFF
    buf[2] = (xorPort ushr 8).toByte(); buf[3] = (xorPort and 0xFF).toByte()
    val magic = STUN_MAGIC_COOKIE
    buf[4] = (ip[0].toInt() xor (magic ushr 24)).toByte()
    buf[5] = (ip[1].toInt() xor (magic ushr 16)).toByte()
    buf[6] = (ip[2].toInt() xor (magic ushr 8)).toByte()
    buf[7] = (ip[3].toInt() xor magic).toByte()
    addAttr(type, buf)
}

/** Decode XOR-ADDRESS for IPv4: returns (ipBytes, port). */
internal fun decodeXorIpv4Address(attr: ByteArray): Pair<ByteArray, Int>? {
    if (attr.size < 8 || attr[1].toInt() and 0xFF != 0x01) return null
    val xorPort = StunMessage.readUInt16(attr, 2)
    val port = xorPort xor (STUN_MAGIC_COOKIE ushr 16)
    val magic = STUN_MAGIC_COOKIE
    val ip = ByteArray(4)
    ip[0] = (attr[4].toInt() xor (magic ushr 24)).toByte()
    ip[1] = (attr[5].toInt() xor (magic ushr 16)).toByte()
    ip[2] = (attr[6].toInt() xor (magic ushr 8)).toByte()
    ip[3] = (attr[7].toInt() xor magic).toByte()
    return ip to (port and 0xFFFF)
}

internal fun decodeErrorCode(attr: ByteArray?): String {
    if (attr == null || attr.size < 4) return "unknown"
    return "${(attr[2].toInt() and 0xFF) * 100 + (attr[3].toInt() and 0xFF)} " +
            if (attr.size > 4) attr.copyOfRange(4, attr.size).decodeToString() else ""
}
