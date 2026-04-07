package com.github.swerchansky.vkturnproxy.ui.connect

import androidx.lifecycle.viewModelScope
import com.github.swerchansky.vkturnproxy.data.preferences.AppPreferences
import com.github.swerchansky.vkturnproxy.data.repository.ProxyRepository
import com.github.swerchansky.vkturnproxy.domain.ProxyConnectionState
import com.github.swerchansky.vkturnproxy.service.ProxyService
import com.github.swerchansky.vkturnproxy.ui.base.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

class ConnectViewModel @Inject constructor(
    private val proxyRepository: ProxyRepository,
    private val appPreferences: AppPreferences,
) : BaseViewModel<ConnectIntent, ConnectSideEffect>() {

    private val _dataState = MutableStateFlow(
        ConnectDataState(
            link = appPreferences.link,
            peer = appPreferences.peer,
            listenPort = appPreferences.listenPort,
            nConnections = appPreferences.nConnections,
            serverHistory = appPreferences.serverHistory,
            favorites = appPreferences.favorites,
        )
    )

    val uiState: StateFlow<ConnectUiState> = _dataState
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _dataState.value.toUiState())

    private var timerJob: Job? = null

    init {
        observeRepository()
    }

    override fun handleIntent(intent: ConnectIntent) = when (intent) {
        is ConnectIntent.LinkChanged ->
            updateState { copy(link = intent.value) }
        is ConnectIntent.PeerChanged ->
            updateState { copy(peer = intent.value) }
        ConnectIntent.ConnectClicked ->
            onConnect()
        ConnectIntent.DisconnectClicked ->
            proxyRepository.disconnect()
        is ConnectIntent.AddFavoriteConfirmed ->
            saveFavorite(intent.name, intent.address)
        is ConnectIntent.FavoriteRemoved ->
            removeFavorite(intent.address)
        is ConnectIntent.FavoriteSelected ->
            updateState { copy(peer = intent.address) }
        is ConnectIntent.ClipboardLinkDetected ->
            emitSideEffect(ConnectSideEffect.ShowClipboardDetected(intent.link))
        is ConnectIntent.ClipboardLinkAccepted ->
            updateState { copy(link = intent.link) }
        ConnectIntent.PasteFromClipboard ->
            emitSideEffect(ConnectSideEffect.RequestClipboardPaste)
        ConnectIntent.DetailCardTapped ->
            emitSideEffect(ConnectSideEffect.OpenConnectionDetail)
        ConnectIntent.StarButtonClicked ->
            emitSideEffect(ConnectSideEffect.ShowFavoriteNameDialog(_dataState.value.peer))
        ConnectIntent.QuickOptionsRequested ->
            emitSideEffect(ConnectSideEffect.ShowQuickOptions)
        is ConnectIntent.QuickOptionsNConnectionsSet -> {
            appPreferences.nConnections = intent.n
            updateState { copy(nConnections = intent.n) }
        }
        is ConnectIntent.CaptchaCompleted ->
            ProxyService.submitCaptchaResult(intent.successToken)

        ConnectIntent.CaptchaCancelled ->
            ProxyService.cancelCaptcha()
    }

    private fun observeRepository() {
        viewModelScope.launch {
            proxyRepository.connectionState.collect { state ->
                updateState { copy(connectionState = state) }
                when (state) {
                    is ProxyConnectionState.Connected -> startTimer()
                    is ProxyConnectionState.CaptchaRequired ->
                        emitSideEffect(ConnectSideEffect.ShowCaptchaDialog(state.captchaUrl))
                    else -> stopTimer()
                }
            }
        }
        viewModelScope.launch {
            proxyRepository.stats.collect { stats ->
                updateState { copy(stats = stats) }
            }
        }
    }

    private fun onConnect() {
        val s = _dataState.value
        if (s.link.isBlank() || s.peer.isBlank()) {
            emitSideEffect(ConnectSideEffect.ShowError("Fill in all fields"))
            return
        }
        appPreferences.link = s.link
        appPreferences.peer = s.peer
        appPreferences.addToServerHistory(s.peer)
        updateState { copy(serverHistory = appPreferences.serverHistory) }

        val rawLink = s.link.split("join/").lastOrNull()
            ?.substringBefore("?")?.substringBefore("/")?.substringBefore("#") ?: s.link

        proxyRepository.connect(rawLink, s.peer, s.listenPort, s.nConnections)
    }

    private fun saveFavorite(name: String, address: String) {
        val updated = appPreferences.favorites.toMutableMap().apply { put(name, address) }
        appPreferences.favorites = updated
        updateState { copy(favorites = updated) }
    }

    private fun removeFavorite(address: String) {
        val updated = appPreferences.favorites.filterValues { it != address }
        appPreferences.favorites = updated
        updateState { copy(favorites = updated) }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val since = _dataState.value.stats.connectedSince
                if (since > 0L) {
                    val elapsed = (System.currentTimeMillis() - since) / 1000
                    updateState { copy(elapsedSeconds = elapsed) }
                }
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        updateState { copy(elapsedSeconds = 0L) }
    }

    private fun updateState(transform: ConnectDataState.() -> ConnectDataState) {
        _dataState.update { it.transform() }
    }
}
