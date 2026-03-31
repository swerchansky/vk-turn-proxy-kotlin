package com.github.swerchansky.vkturnproxy.ui.logs

sealed class LogsIntent {
    data class SearchQueryChanged(val query: String) : LogsIntent()
    object SearchToggled : LogsIntent()
    object ClearLogsClicked : LogsIntent()
    object ShareLogsClicked : LogsIntent()
    object CopyAllClicked : LogsIntent()
    object TabResumed : LogsIntent()
    object ScrolledToBottom : LogsIntent()
    object ScrolledUp : LogsIntent()
    data class LevelFilterChanged(val level: LogLevel) : LogsIntent()
    object TimestampToggled : LogsIntent()
    object WordWrapToggled : LogsIntent()
    data class LineLongPressed(val line: String) : LogsIntent()
}
