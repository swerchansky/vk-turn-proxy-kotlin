package com.github.swerchansky.vkturnproxy.domain.model

sealed class ProxyConnectionState {
    object Idle : ProxyConnectionState()
    data class Connecting(
        val step: String,
        val connectedCount: Int = 0,
        val totalConnections: Int = 0,
    ) : ProxyConnectionState()
    data class Connected(val turnAddr: String) : ProxyConnectionState()
    data class Error(val message: String) : ProxyConnectionState()
}
