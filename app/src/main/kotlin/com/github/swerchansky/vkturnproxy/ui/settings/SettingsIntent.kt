package com.github.swerchansky.vkturnproxy.ui.settings

sealed class SettingsIntent {
    data class ListenPortChanged(val port: Int) : SettingsIntent()
    data class NConnectionsChanged(val n: Int) : SettingsIntent()
    data class AutoScrollChanged(val enabled: Boolean) : SettingsIntent()
    data class SaveLogsChanged(val enabled: Boolean) : SettingsIntent()
    data class NotificationsChanged(val enabled: Boolean) : SettingsIntent()
    data class ThemeModeChanged(val mode: Int) : SettingsIntent()
    object GitHubClicked : SettingsIntent()
}
