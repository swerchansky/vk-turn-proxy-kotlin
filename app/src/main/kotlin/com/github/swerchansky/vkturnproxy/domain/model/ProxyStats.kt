package com.github.swerchansky.vkturnproxy.domain.model

data class ProxyStats(
    val toServerPkts: Long = 0,
    val fromServerPkts: Long = 0,
    val connectedSince: Long = 0,
    val toServerPps: Float = 0f,
    val fromServerPps: Float = 0f,
    val relayAddr: String = "",
)
