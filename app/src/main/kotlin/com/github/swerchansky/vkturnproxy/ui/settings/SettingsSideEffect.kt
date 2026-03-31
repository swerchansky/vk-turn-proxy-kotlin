package com.github.swerchansky.vkturnproxy.ui.settings

sealed class SettingsSideEffect {
    data class OpenUrl(val url: String) : SettingsSideEffect()
    data class ApplyTheme(val mode: Int) : SettingsSideEffect()
}
