package com.github.swerchansky.vkturnproxy.transport

import java.net.InetSocketAddress

internal object TurnTransportFactory {
    /** Create a UDP TURN transport (default transport). */
    fun udp(serverAddr: InetSocketAddress): TurnTransport = UdpTurnTransport(serverAddr)
}
