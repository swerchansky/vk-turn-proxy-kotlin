package com.github.swerchansky.vkturnproxy.ui.settings

import androidx.appcompat.app.AppCompatDelegate

data class SettingsDataState(
    val listenPort: Int = 9000,
    val nConnections: Int = 0,
    val autoScroll: Boolean = true,
    val saveLogs: Boolean = false,
    val notifications: Boolean = true,
    val themeMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
    val versionName: String = "",
)
