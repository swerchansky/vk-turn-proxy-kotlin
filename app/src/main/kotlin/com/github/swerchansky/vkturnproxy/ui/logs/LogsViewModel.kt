package com.github.swerchansky.vkturnproxy.ui.logs

import androidx.lifecycle.viewModelScope
import com.github.swerchansky.vkturnproxy.data.preferences.AppPreferences
import com.github.swerchansky.vkturnproxy.data.repository.ProxyRepository
import com.github.swerchansky.vkturnproxy.ui.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

class LogsViewModel @Inject constructor(
    private val proxyRepository: ProxyRepository,
    private val appPreferences: AppPreferences,
) : BaseViewModel<LogsIntent, LogsSideEffect>() {

    private val _dataState = MutableStateFlow(
        LogsDataState(autoScroll = appPreferences.autoScroll)
    )

    val uiState: StateFlow<LogsUiState> = _dataState
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _dataState.value.toUiState())

    init {
        viewModelScope.launch {
            proxyRepository.log.collect { raw ->
                _dataState.update { it.copy(rawLog = raw) }
                if (_dataState.value.autoScroll && raw.isNotEmpty()) {
                    emitSideEffect(LogsSideEffect.ScrollToBottom)
                }
            }
        }
        viewModelScope.launch {
            proxyRepository.connectionState.collect { state ->
                _dataState.update { it.copy(connectionState = state) }
            }
        }
    }

    override fun handleIntent(intent: LogsIntent) { when (intent) {
        is LogsIntent.SearchQueryChanged ->
            _dataState.update { it.copy(searchQuery = intent.query) }

        LogsIntent.SearchToggled -> {
            val nowActive = !_dataState.value.isSearchActive
            _dataState.update {
                it.copy(isSearchActive = nowActive, searchQuery = if (nowActive) it.searchQuery else "")
            }
        }

        LogsIntent.ClearLogsClicked ->
            proxyRepository.clearLog()

        LogsIntent.ShareLogsClicked -> {
            val log = _dataState.value.rawLog
            if (log.isNotEmpty()) emitSideEffect(LogsSideEffect.ShareText(log))
        }

        LogsIntent.CopyAllClicked -> {
            val log = _dataState.value.rawLog
            if (log.isNotEmpty()) emitSideEffect(LogsSideEffect.CopyToClipboard(log))
        }

        LogsIntent.TabResumed -> {
            val lines = if (_dataState.value.rawLog.isEmpty()) 0
                        else _dataState.value.rawLog.lines().size
            _dataState.update { it.copy(lastSeenLineCount = lines) }
        }

        LogsIntent.ScrolledToBottom ->
            _dataState.update { it.copy(isFabVisible = false, autoScroll = true) }

        LogsIntent.ScrolledUp ->
            _dataState.update { it.copy(isFabVisible = true, autoScroll = false) }

        is LogsIntent.LevelFilterChanged ->
            _dataState.update { it.copy(levelFilter = intent.level) }

        LogsIntent.TimestampToggled ->
            _dataState.update { it.copy(showTimestamps = !it.showTimestamps) }

        LogsIntent.WordWrapToggled ->
            _dataState.update { it.copy(wordWrap = !it.wordWrap) }

        is LogsIntent.LineLongPressed ->
            emitSideEffect(LogsSideEffect.ShowLineContextMenu(intent.line))
    } }
}
