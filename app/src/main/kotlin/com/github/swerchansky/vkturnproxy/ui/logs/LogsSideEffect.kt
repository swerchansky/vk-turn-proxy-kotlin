package com.github.swerchansky.vkturnproxy.ui.logs

sealed class LogsSideEffect {
    data class ShareText(val text: String) : LogsSideEffect()
    data class CopyToClipboard(val text: String) : LogsSideEffect()
    object ScrollToBottom : LogsSideEffect()
    data class ShowLineContextMenu(val line: String) : LogsSideEffect()
}
