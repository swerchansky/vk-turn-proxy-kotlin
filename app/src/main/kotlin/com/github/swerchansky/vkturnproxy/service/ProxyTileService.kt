package com.github.swerchansky.vkturnproxy.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.github.swerchansky.vkturnproxy.ui.main.MainActivity
import com.github.swerchansky.vkturnproxy.ui.main.ProxyState

@RequiresApi(Build.VERSION_CODES.N)
class ProxyTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        val state = ProxyService.state.value
        if (state is ProxyState.Connected || state is ProxyState.Connecting) {
            startService(Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_STOP
            })
            qsTile.state = Tile.STATE_INACTIVE
            qsTile.updateTile()
        } else {
            // Open app so user can configure and connect
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(intent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = ProxyService.state.value
        tile.state = when (state) {
            is ProxyState.Connected -> Tile.STATE_ACTIVE
            is ProxyState.Connecting -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
