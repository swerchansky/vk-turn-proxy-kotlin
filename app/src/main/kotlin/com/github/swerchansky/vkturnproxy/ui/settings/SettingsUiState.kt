package com.github.swerchansky.vkturnproxy.ui.settings

import androidx.appcompat.app.AppCompatDelegate

data class SettingsUiState(
    val listenPortText: String = "9000",
    val listenAddressHint: String = "127.0.0.1:9000",
    val nConnectionsText: String = "0",
    val autoScroll: Boolean = true,
    val saveLogs: Boolean = false,
    val notifications: Boolean = true,
    val themeLabel: String = "System",
    val themeModeIndex: Int = 0,
    val versionLabel: String = "",
)

// ── Pure mapper ───────────────────────────────────────────────────────────────

fun SettingsDataState.toUiState() = SettingsUiState(
    listenPortText = listenPort.toString(),
    listenAddressHint = "127.0.0.1:$listenPort",
    nConnectionsText = nConnections.toString(),
    autoScroll = autoScroll,
    saveLogs = saveLogs,
    notifications = notifications,
    themeLabel = when (themeMode) {
        AppCompatDelegate.MODE_NIGHT_NO -> "Light"
        AppCompatDelegate.MODE_NIGHT_YES -> "Dark"
        else -> "System"
    },
    themeModeIndex = when (themeMode) {
        AppCompatDelegate.MODE_NIGHT_NO -> 1
        AppCompatDelegate.MODE_NIGHT_YES -> 2
        else -> 0
    },
    versionLabel = "v$versionName",
)
