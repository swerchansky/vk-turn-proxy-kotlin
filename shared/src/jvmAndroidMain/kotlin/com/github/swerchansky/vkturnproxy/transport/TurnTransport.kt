package com.github.swerchansky.vkturnproxy.transport

import com.github.swerchansky.vkturnproxy.stun.StunMessage
import java.io.Closeable

internal interface TurnTransport : Closeable {
    /** Send a STUN request and wait for the matching response (by transaction ID). */
    fun sendReceive(msg: StunMessage): StunMessage?

    /** Send raw bytes without waiting for a response (ChannelData or keepalive). */
    fun sendRaw(data: ByteArray)

    /** Receive the next raw frame. Blocks until data arrives or timeout. */
    fun receiveRaw(): ByteArray

    /** Set socket read timeout in milliseconds (0 = block forever). */
    fun setReceiveTimeout(ms: Int)
}
