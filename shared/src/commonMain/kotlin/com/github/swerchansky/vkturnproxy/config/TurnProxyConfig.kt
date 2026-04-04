package com.github.swerchansky.vkturnproxy.config

object TurnProxyConfig {
    const val PACKET_BUFFER_SIZE = 1600
    const val TURN_DEFAULT_PORT = 3478
    const val TURN_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    const val TURN_ALLOCATION_LIFETIME_S = 600
    const val CHANNEL_MIN = 0x4000
    const val CHANNEL_MAX = 0x7FFE
    const val DNS_CACHE_SIZE = 100
    const val DNS_CACHE_TTL_HOURS = 10L
    const val STAGGERED_CONNECTION_DELAY_MS = 200L
    const val MAX_SERVER_HISTORY = 5
}
