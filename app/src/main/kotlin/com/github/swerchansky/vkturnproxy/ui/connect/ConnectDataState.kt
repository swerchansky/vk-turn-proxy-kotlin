package com.github.swerchansky.vkturnproxy.ui.connect

import com.github.swerchansky.vkturnproxy.domain.ProxyConnectionState
import com.github.swerchansky.vkturnproxy.domain.ProxyStats

data class ConnectDataState(
    val connectionState: ProxyConnectionState = ProxyConnectionState.Idle,
    val stats: ProxyStats = ProxyStats(),
    val link: String = "",
    val peer: String = "",
    val serverHistory: List<String> = emptyList(),
    val favorites: Map<String, String> = emptyMap(),
    val listenPort: Int = 9000,
    val nConnections: Int = 0,
    val elapsedSeconds: Long = 0L,
)
