package com.github.swerchansky.vkturnproxy.proxy

import java.net.DatagramSocket
import java.net.InetSocketAddress

data class TunnelParams(
    val link: String,
    val peerAddr: InetSocketAddress,
    val localSocket: DatagramSocket,
    val nConnections: Int,
    val useDtls: Boolean = true,
    val turnHostOverride: String? = null,
    val turnPortOverride: String? = null,
)
