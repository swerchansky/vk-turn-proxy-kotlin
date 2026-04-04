package com.github.swerchansky.vkturnproxy.ui.connect

import com.github.swerchansky.vkturnproxy.domain.ProxyConnectionState
import com.github.swerchansky.vkturnproxy.domain.ProxyStats

enum class StatusColor { IDLE, CONNECTING, CONNECTED, ERROR }

private fun stepIndex(step: String): Int = when {
    step.contains("DNS", ignoreCase = true) -> 1
    step.contains("TURN", ignoreCase = true) -> 2
    step.contains("DTLS", ignoreCase = true) || step.contains("Handshake", ignoreCase = true) -> 3
    step.contains("Establishing", ignoreCase = true) -> 4
    else -> 0
}

data class ConnectUiState(
    val statusColor: StatusColor = StatusColor.IDLE,
    val statusLabel: String = "",
    val sessionTimer: String = "00:00:00",
    val isTimerVisible: Boolean = false,
    val isStatsRowVisible: Boolean = false,
    val isGraphVisible: Boolean = false,
    val isTapDetailsVisible: Boolean = false,
    val isStepperVisible: Boolean = false,
    val stepperIndex: Int = 0,
    val stepperConnectedCount: Int = 0,
    val stepperTotalConnections: Int = 0,
    val packetsSent: String = "0",
    val packetsReceived: String = "0",
    val graphStats: ProxyStats = ProxyStats(),
    val isActivityIndicatorVisible: Boolean = false,
    val link: String = "",
    val peer: String = "",
    val serverHistoryItems: List<String> = emptyList(),
    val actionButtonLabel: String = "",
    val actionButtonEnabled: Boolean = false,
    val nConnections: Int = 16,
)

@Suppress("CyclomaticComplexMethod")
fun ConnectDataState.toUiState() = ConnectUiState(
    statusColor = when (connectionState) {
        ProxyConnectionState.Idle -> StatusColor.IDLE
        is ProxyConnectionState.Connecting -> StatusColor.CONNECTING
        is ProxyConnectionState.Connected -> StatusColor.CONNECTED
        is ProxyConnectionState.Error -> StatusColor.ERROR
    },
    statusLabel = when (connectionState) {
        ProxyConnectionState.Idle -> "Idle"
        is ProxyConnectionState.Connecting -> {
            val cs = connectionState as ProxyConnectionState.Connecting
            if (cs.connectedCount > 0) "${cs.connectedCount}/${cs.totalConnections}" else cs.step
        }
        is ProxyConnectionState.Connected -> "Connected"
        is ProxyConnectionState.Error -> "Error"
    },
    sessionTimer = formatDuration(elapsedSeconds),
    isTimerVisible = connectionState is ProxyConnectionState.Connected,
    isStatsRowVisible = connectionState is ProxyConnectionState.Connected,
    isGraphVisible = connectionState is ProxyConnectionState.Connected,
    isTapDetailsVisible = connectionState is ProxyConnectionState.Connected,
    isStepperVisible = connectionState is ProxyConnectionState.Connecting,
    stepperIndex = when (connectionState) {
        is ProxyConnectionState.Connecting ->
            stepIndex((connectionState as ProxyConnectionState.Connecting).step)
        else -> 0
    },
    stepperConnectedCount = (connectionState as? ProxyConnectionState.Connecting)?.connectedCount ?: 0,
    stepperTotalConnections = (connectionState as? ProxyConnectionState.Connecting)?.totalConnections ?: 0,
    packetsSent = formatPackets(stats.toServerPkts),
    packetsReceived = formatPackets(stats.fromServerPkts),
    graphStats = stats,
    isActivityIndicatorVisible = connectionState is ProxyConnectionState.Connected &&
        (stats.toServerPps > 0f || stats.fromServerPps > 0f),
    link = link,
    peer = peer,
    serverHistoryItems = buildDropdownItems(favorites, serverHistory),
    actionButtonLabel = when (connectionState) {
        is ProxyConnectionState.Connected, is ProxyConnectionState.Connecting -> "Disconnect"
        else -> "Connect"
    },
    actionButtonEnabled = link.isNotBlank() && peer.isNotBlank(),
    nConnections = if (nConnections > 0) nConnections else 16,
)

private fun buildDropdownItems(
    favorites: Map<String, String>,
    history: List<String>,
): List<String> {
    val favItems = favorites.entries.map { "${it.key} (${it.value})" }
    return (favItems + history).distinct()
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun formatPackets(count: Long): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
    count >= 1_000 -> String.format("%.1fK", count / 1_000f)
    else -> count.toString()
}
