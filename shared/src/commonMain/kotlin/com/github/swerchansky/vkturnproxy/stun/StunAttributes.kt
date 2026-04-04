package com.github.swerchansky.vkturnproxy.stun

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

internal const val STUN_MAGIC_COOKIE: Int = 0x2112A442
internal const val CHANNEL_DATA_MIN: Int = 0x4000
internal const val CHANNEL_DATA_MAX: Int = 0x7FFF

// ── Message type encoding (RFC 5389 §6) ──────────────────────────────────

internal fun stunMsgType(method: Int, cls: Int): Int =
    ((method and 0x0F80) shl 2) or
            (cls and 0x0100) or
            ((method and 0x0070) shl 1) or
            (cls and 0x0010) or
            (method and 0x000F)

// ── Binary read helpers ───────────────────────────────────────────────────

internal fun readUInt16(buf: ByteArray, off: Int): Int =
    (buf[off].toInt() and 0xFF shl 8) or (buf[off + 1].toInt() and 0xFF)

internal fun readInt32(buf: ByteArray, off: Int): Int =
    (buf[off].toInt() and 0xFF shl 24) or (buf[off + 1].toInt() and 0xFF shl 16) or
            (buf[off + 2].toInt() and 0xFF shl 8) or (buf[off + 3].toInt() and 0xFF)

internal fun intToBytes(v: Int): ByteArray = byteArrayOf(
    (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
)
