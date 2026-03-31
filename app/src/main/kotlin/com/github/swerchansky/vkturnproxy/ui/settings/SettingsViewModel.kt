package com.github.swerchansky.vkturnproxy.ui.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.github.swerchansky.vkturnproxy.data.preferences.AppPreferences
import com.github.swerchansky.vkturnproxy.ui.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val app: Application,
) : BaseViewModel<SettingsIntent, SettingsSideEffect>() {

    @Suppress("DEPRECATION")
    private val versionName: String =
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""

    private val _dataState = MutableStateFlow(
        SettingsDataState(
            listenPort = appPreferences.listenPort,
            nConnections = appPreferences.nConnections,
            autoScroll = appPreferences.autoScroll,
            saveLogs = appPreferences.saveLogs,
            notifications = appPreferences.notifications,
            themeMode = appPreferences.themeMode,
            versionName = versionName,
        )
    )

    val uiState: StateFlow<SettingsUiState> = _dataState
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _dataState.value.toUiState())

    override fun handleIntent(intent: SettingsIntent) = when (intent) {
        is SettingsIntent.ListenPortChanged -> {
            appPreferences.listenPort = intent.port
            _dataState.update { it.copy(listenPort = intent.port) }
        }
        is SettingsIntent.NConnectionsChanged -> {
            appPreferences.nConnections = intent.n
            _dataState.update { it.copy(nConnections = intent.n) }
        }
        is SettingsIntent.AutoScrollChanged -> {
            appPreferences.autoScroll = intent.enabled
            _dataState.update { it.copy(autoScroll = intent.enabled) }
        }
        is SettingsIntent.SaveLogsChanged -> {
            appPreferences.saveLogs = intent.enabled
            _dataState.update { it.copy(saveLogs = intent.enabled) }
        }
        is SettingsIntent.NotificationsChanged -> {
            appPreferences.notifications = intent.enabled
            _dataState.update { it.copy(notifications = intent.enabled) }
        }
        is SettingsIntent.ThemeModeChanged -> {
            appPreferences.themeMode = intent.mode
            _dataState.update { it.copy(themeMode = intent.mode) }
            emitSideEffect(SettingsSideEffect.ApplyTheme(intent.mode))
        }
        SettingsIntent.GitHubClicked ->
            emitSideEffect(SettingsSideEffect.OpenUrl("https://github.com/swerchansky/vk-turn-proxy-kotlin"))
    }
}
