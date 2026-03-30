package com.github.swerchansky.vkturnproxy.ui.main

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.github.swerchansky.vkturnproxy.service.ProxyService
import com.github.swerchansky.vkturnproxy.service.ProxyStats
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

sealed class ProxyState {
    object Idle : ProxyState()
    data class Connecting(val step: String) : ProxyState()
    data class Connected(val turnAddr: String) : ProxyState()
    data class Error(val message: String) : ProxyState()
}

class MainViewModel @Inject constructor(
    private val app: Application,
) : ViewModel() {

    val state: StateFlow<ProxyState> = ProxyService.state
    val log: StateFlow<String> = ProxyService.log
    val stats: StateFlow<ProxyStats> = ProxyService.stats

    fun connect(rawLink: String, peerAddress: String, listenPort: Int, nConnections: Int) {
        val intent = Intent(app, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
            putExtra(ProxyService.EXTRA_LINK, rawLink)
            putExtra(ProxyService.EXTRA_PEER, peerAddress)
            putExtra(ProxyService.EXTRA_PORT, listenPort)
            putExtra(ProxyService.EXTRA_IS_VK, true)
            putExtra(ProxyService.EXTRA_N, nConnections)
        }
        app.startForegroundService(intent)
    }

    fun disconnect() {
        app.startService(Intent(app, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        })
    }

    fun clearLog() {
        ProxyService.log.value = ""
    }
}
