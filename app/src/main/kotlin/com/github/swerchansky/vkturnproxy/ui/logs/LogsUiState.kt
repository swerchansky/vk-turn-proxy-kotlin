package com.github.swerchansky.vkturnproxy.ui.logs

import com.github.swerchansky.vkturnproxy.domain.ProxyConnectionState
import com.github.swerchansky.vkturnproxy.ui.connect.StatusColor

data class LogLine(
    val raw: String,
    val level: LogLevel,
)

data class LogsUiState(
    val lines: List<LogLine> = emptyList(),
    val lineCountLabel: String = "",
    val unreadCount: Int = 0,
    val searchQuery: String = "",
    val isSearchBarVisible: Boolean = false,
    val autoScroll: Boolean = true,
    val isLive: Boolean = true,
    val statusDotColor: StatusColor = StatusColor.IDLE,
    val statusLabel: String = "Idle",
    val isFabVisible: Boolean = false,
    val isEmpty: Boolean = true,
    val isShareVisible: Boolean = false,
    val showTimestamps: Boolean = true,
    val wordWrap: Boolean = true,
    val levelFilter: LogLevel = LogLevel.ALL,
)

// ── Pure mapper ───────────────────────────────────────────────────────────────

fun LogsDataState.toUiState(): LogsUiState {
    val allLines = if (rawLog.isEmpty()) emptyList()
    else rawLog.lines().filter { it.isNotBlank() }.map { line ->
        LogLine(raw = line, level = classifyLine(line))
    }
    val totalLines = allLines.size
    val filteredByLevel = if (levelFilter == LogLevel.ALL) allLines
                          else allLines.filter { it.level == levelFilter }
    val displayedLines = if (searchQuery.isNotEmpty())
        filteredByLevel.filter { searchQuery.lowercase() in it.raw.lowercase() }
    else filteredByLevel

    val kb = rawLog.toByteArray().size / 1024f
    val lineCountLabel = when {
        totalLines == 0 -> ""
        displayedLines.size < totalLines ->
            "${displayedLines.size}/${totalLines} lines · %.1f KB".format(kb)
        else -> "$totalLines lines · %.1f KB".format(kb)
    }

    return LogsUiState(
        lines = displayedLines,
        lineCountLabel = lineCountLabel,
        unreadCount = maxOf(0, totalLines - lastSeenLineCount),
        searchQuery = searchQuery,
        isSearchBarVisible = isSearchActive,
        autoScroll = autoScroll,
        isLive = autoScroll,
        statusDotColor = connectionState.toStatusColor(),
        statusLabel = connectionState.toLabel(),
        isFabVisible = isFabVisible && rawLog.isNotEmpty(),
        isEmpty = rawLog.isEmpty(),
        isShareVisible = rawLog.isNotEmpty(),
        showTimestamps = showTimestamps,
        wordWrap = wordWrap,
        levelFilter = levelFilter,
    )
}

private fun ProxyConnectionState.toStatusColor() = when (this) {
    ProxyConnectionState.Idle -> StatusColor.IDLE
    is ProxyConnectionState.Connecting -> StatusColor.CONNECTING
    is ProxyConnectionState.Connected -> StatusColor.CONNECTED
    is ProxyConnectionState.Error -> StatusColor.ERROR
}

private fun ProxyConnectionState.toLabel() = when (this) {
    ProxyConnectionState.Idle -> "Idle"
    is ProxyConnectionState.Connecting -> "Connecting..."
    is ProxyConnectionState.Connected -> "Connected"
    is ProxyConnectionState.Error -> "Error"
}
