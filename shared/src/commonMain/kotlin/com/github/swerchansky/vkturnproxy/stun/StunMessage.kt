package com.github.swerchansky.vkturnproxy.stun

/**
 * STUN/TURN message — data holder only (encode/decode in StunEncoder/StunDecoder).
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

    fun addAttr(type: Int, value: ByteArray) {
        attrs.add(type to value)
    }

    fun addStringAttr(type: Int, value: String) = addAttr(type, value.encodeToByteArray())

    fun getAttr(type: Int): ByteArray? = attrs.firstOrNull { it.first == type }?.second
    fun allAttrs(): List<Pair<Int, ByteArray>> = attrs
}

internal expect fun randomBytes(size: Int): ByteArray
