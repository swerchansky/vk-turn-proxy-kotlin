package com.github.swerchansky.vkturnproxy.stun

// ── Decode ────────────────────────────────────────────────────────────────

/** Deserialize a STUN message from wire bytes. Returns null if not a valid STUN message. */
internal fun decodeStunMessage(data: ByteArray): StunMessage? {
    if (data.size < 20) return null
    if ((data[0].toInt() and 0xC0) != 0) return null
    val magic = readInt32(data, 4)
    if (magic != STUN_MAGIC_COOKIE) return null
    val length = readUInt16(data, 2)
    if (data.size < 20 + length) return null
    val msgType = readUInt16(data, 0)
    val method =
        ((msgType and 0x3E00) ushr 2) or ((msgType and 0x00E0) ushr 1) or (msgType and 0x000F)
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
        pos += attrLen + ((4 - attrLen % 4) % 4)
    }
    return msg
}

/** Returns true if the first two bytes indicate a ChannelData frame. */
internal fun isChannelData(data: ByteArray): Boolean {
    if (data.size < 4) return false
    val ch = readUInt16(data, 0)
    return ch in CHANNEL_DATA_MIN..CHANNEL_DATA_MAX
}

/** Decode a ChannelData frame. Returns (channelNumber, payload) or null. */
internal fun decodeChannelData(data: ByteArray): Pair<Int, ByteArray>? {
    if (data.size < 4) return null
    val ch = readUInt16(data, 0)
    val len = readUInt16(data, 2)
    if (data.size < 4 + len) return null
    return ch to data.copyOfRange(4, 4 + len)
}

/** Decode XOR-ADDRESS for IPv4: returns (ipBytes, port) or null. */
internal fun decodeXorIpv4Address(attr: ByteArray): Pair<ByteArray, Int>? {
    if (attr.size < 8 || attr[1].toInt() and 0xFF != 0x01) return null
    val xorPort = readUInt16(attr, 2)
    val port = xorPort xor (STUN_MAGIC_COOKIE ushr 16)
    val magic = STUN_MAGIC_COOKIE
    val ip = ByteArray(4)
    ip[0] = (attr[4].toInt() xor (magic ushr 24)).toByte()
    ip[1] = (attr[5].toInt() xor (magic ushr 16)).toByte()
    ip[2] = (attr[6].toInt() xor (magic ushr 8)).toByte()
    ip[3] = (attr[7].toInt() xor magic).toByte()
    return ip to (port and 0xFFFF)
}

/** Decode ERROR-CODE attribute to human-readable string. */
internal fun decodeErrorCode(attr: ByteArray?): String {
    if (attr == null || attr.size < 4) return "unknown"
    return "${(attr[2].toInt() and 0xFF) * 100 + (attr[3].toInt() and 0xFF)} " +
            if (attr.size > 4) attr.copyOfRange(4, attr.size).decodeToString() else ""
}
