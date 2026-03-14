package com.plwd.audiochannelguard

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class AudioFixTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val monitor = AudioGuardService.getMonitor()
        if (monitor != null) {
            monitor.fixNow()
        } else if (AudioGuardApp.isGuardEnabled(this)) {
            AudioGuardService.start(this)
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val monitor = AudioGuardService.getMonitor()

        if (monitor == null) {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = "未运行"
        } else {
            val headset = monitor.findConnectedHeadset()
            if (headset != null) {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = headset.productName?.toString() ?: "已连接"
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "未连接耳机"
            }
        }

        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }
}
