package com.github.swerchansky.vkturnproxy.stun

// ── Encode ────────────────────────────────────────────────────────────────

/** Serialize message to wire bytes. */
internal fun StunMessage.encode(): ByteArray {
    val body = encodeAttrs(allAttrs())
    return buildHeader(body.size) + body
}

/**
 * Encode for HMAC computation (RFC 5389 §15.4):
 * excludes MESSAGE-INTEGRITY and everything after it,
 * but sets length as if MESSAGE-INTEGRITY (20 bytes) were included.
 */
internal fun StunMessage.encodeForHmac(): ByteArray {
    val attrsBeforeHmac = allAttrs().takeWhile { it.first != StunAttr.MESSAGE_INTEGRITY }
    val body = encodeAttrs(attrsBeforeHmac)
    val lengthWithHmac = body.size + 4 + 20
    return buildHeader(lengthWithHmac) + body
}

/** Encode a ChannelData frame (RFC 5766 §11.4). */
internal fun encodeChannelData(channel: Int, payload: ByteArray): ByteArray {
    val buf = ByteArray(4 + payload.size)
    buf[0] = (channel ushr 8).toByte()
    buf[1] = (channel and 0xFF).toByte()
    buf[2] = (payload.size ushr 8).toByte()
    buf[3] = (payload.size and 0xFF).toByte()
    payload.copyInto(buf, 4)
    return buf
}

/** Add XOR-PEER-ADDRESS / XOR-RELAYED-ADDRESS attribute for IPv4. */
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

// ── Private helpers ───────────────────────────────────────────────────────

private fun StunMessage.buildHeader(attrLength: Int): ByteArray {
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
