package com.github.swerchansky.vkturnproxy.ui.logs

import com.github.swerchansky.vkturnproxy.domain.ProxyConnectionState

enum class LogLevel { ALL, INFO, WARN, ERROR, SUCCESS }

/** Classifies a raw log line into a level for filtering. */
fun classifyLine(line: String): LogLevel {
    val content = if (line.contains("] ")) {
        line.substringAfter("] ").lowercase()
    } else {
        line.lowercase()
    }
    return when {
        content.contains("error") || content.contains("failed") ||
            content.contains("exception") -> LogLevel.ERROR
        content.contains("connected!") || content.contains("handshake ok") ||
            content.contains("credentials ok") || content.contains("established") ||
            content.contains("relay done") -> LogLevel.SUCCESS
        content.contains("disconnected") || content.contains("ended") ||
            content.contains("closed") || content.contains("stop") -> LogLevel.WARN
        else -> LogLevel.INFO
    }
}

data class LogsDataState(
    val rawLog: String = "",
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val lastSeenLineCount: Int = 0,
    val connectionState: ProxyConnectionState = ProxyConnectionState.Idle,
    val autoScroll: Boolean = true,
    val isFabVisible: Boolean = false,
    val showTimestamps: Boolean = true,
    val wordWrap: Boolean = true,
    val levelFilter: LogLevel = LogLevel.ALL,
)
