package com.github.swerchansky.vkturnproxy.proxy

/** Events emitted by [TurnProxyEngine.runConnections] as the tunnel progresses. */
sealed class TunnelEvent {
    /** Setup phase changed (first connection only). */
    data class StepChanged(val step: String) : TunnelEvent()

    /** First connection is fully established (DTLS handshake complete). */
    data class FirstReady(val relayAddr: String) : TunnelEvent()

    /** Any connection became ready, or setup is fully settled (some may have failed). */
    data class ConnectionReady(
        val count: Int,
        val total: Int,
        val relayAddr: String,
        /** True when all connections have either connected or failed (setup is complete). */
        val allSettled: Boolean = false,
    ) : TunnelEvent()
}
