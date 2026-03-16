package com.plwd.audiochannelguard

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class AudioFixTile : TileService() {

    companion object {
        private val tileRefreshHandler = Handler(Looper.getMainLooper())

        fun requestTileRefresh(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                TileService.requestListeningState(
                    appContext,
                    ComponentName(appContext, AudioFixTile::class.java)
                )
            }
        }

        fun requestTileRefreshDelayed(context: Context, delayMillis: Long = 700L) {
            val appContext = context.applicationContext
            tileRefreshHandler.postDelayed(
                { requestTileRefresh(appContext) },
                delayMillis
            )
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        AudioGuardApp.setTileAdded(this, true)
        updateTile()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        AudioGuardApp.setTileAdded(this, false)
    }

    override fun onStartListening() {
        super.onStartListening()
        AudioGuardApp.setTileAdded(this, true)
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (AudioGuardApp.isTampered) {
            updateTile()
            return
        }

        val enableGuard = !AudioGuardApp.isGuardEnabled(this)
        AudioGuardApp.setGuardEnabledSync(this, enableGuard)
        if (enableGuard) {
            AudioGuardService.start(this)
            ServiceGuard.schedulePeriodicCheck(this)
        } else {
            AudioGuardService.stop(this)
        }
        updateTile()
        requestTileRefreshDelayed(this)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val presentation = createPresentation()
        tile.state = presentation.state
        tile.label = getString(R.string.tile_label)
        tile.subtitle = presentation.subtitle
        tile.updateTile()
    }

    private fun createPresentation(): TilePresentation {
        if (AudioGuardApp.isTampered) {
            return TilePresentation(
                state = Tile.STATE_UNAVAILABLE,
                subtitle = "签名异常"
            )
        }

        if (!AudioGuardApp.isGuardEnabled(this)) {
            return TilePresentation(
                state = Tile.STATE_INACTIVE,
                subtitle = "保护关闭"
            )
        }

        val monitor = AudioGuardService.getMonitor()
            ?: return TilePresentation(
                state = Tile.STATE_ACTIVE,
                subtitle = "准备中"
            )

        val subtitle = when {
            monitor.canManuallyReleaseHeldRoute() -> "外放占用"
            monitor.getHeldRouteMessage() != null -> "观察中"
            else -> when (monitor.getStatus()) {
                GuardStatus.NORMAL -> monitor.findConnectedHeadset()?.productName?.toString() ?: "保护中"
                GuardStatus.FIXED -> "已收回"
                GuardStatus.FIXED_BUT_SPEAKER_ROUTE -> "已收回"
                GuardStatus.HIJACKED -> "疑似外放"
                GuardStatus.NO_HEADSET -> "未接耳机"
            }
        }

        return TilePresentation(
            state = Tile.STATE_ACTIVE,
            subtitle = subtitle
        )
    }
}

private data class TilePresentation(
    val state: Int,
    val subtitle: String,
)
