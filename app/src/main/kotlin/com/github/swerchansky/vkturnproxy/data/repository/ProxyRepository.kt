package com.github.swerchansky.vkturnproxy.data.repository

import android.app.Application
import android.content.Intent
import com.github.swerchansky.vkturnproxy.domain.ProxyConnectionState
import com.github.swerchansky.vkturnproxy.domain.ProxyStats
import com.github.swerchansky.vkturnproxy.service.ProxyService
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxyRepository @Inject constructor(private val app: Application) {

    val connectionState: StateFlow<ProxyConnectionState> = ProxyService.state
    val stats: StateFlow<ProxyStats> = ProxyService.stats
    val log: StateFlow<String> = ProxyService.log

    fun connect(link: String, peer: String, port: Int, nConnections: Int) {
        app.startForegroundService(Intent(app, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
            putExtra(ProxyService.EXTRA_LINK, link)
            putExtra(ProxyService.EXTRA_PEER, peer)
            putExtra(ProxyService.EXTRA_PORT, port)
            putExtra(ProxyService.EXTRA_N, nConnections)
        })
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
