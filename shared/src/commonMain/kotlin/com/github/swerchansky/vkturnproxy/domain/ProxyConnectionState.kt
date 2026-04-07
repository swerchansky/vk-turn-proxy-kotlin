package com.github.swerchansky.vkturnproxy.domain

import com.github.swerchansky.vkturnproxy.error.TurnProxyError

sealed class ProxyConnectionState {
    object Idle : ProxyConnectionState()
    data class Connecting(
        val step: String,
        val connectedCount: Int = 0,
        val totalConnections: Int = 0,
    ) : ProxyConnectionState()

    data class Connected(
        val turnAddr: String,
        val connectedCount: Int = 0,
        val totalConnections: Int = 0,
    ) : ProxyConnectionState()

    data class CaptchaRequired(val captchaUrl: String) : ProxyConnectionState()
    data class Error(val message: String, val cause: TurnProxyError? = null) :
        ProxyConnectionState()
}
