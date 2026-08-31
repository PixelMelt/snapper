package com.snapper.android.app

import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.snapper.android.R
import com.snapper.android.overlay.SnapActions

abstract class BaseTileService : TileService() {
    internal abstract val action: String

    override fun onStartListening() {
        super.onStartListening()
        val tile: Tile? = qsTile
        if (tile != null) {
            tile.state = if (Settings.canDrawOverlays(this)) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val launch = Intent(this, CaptureActivity::class.java)
            .putExtra(CaptureActivity.EXTRA_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, action.hashCode(), launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(pending)
    }
}

class NormalTileService : BaseTileService() {
    override val action: String = SnapActions.ACTION_NORMAL
}

class FreezeTileService : BaseTileService() {
    override val action: String = SnapActions.ACTION_FREEZE
}

class InstantTileService : BaseTileService() {
    override val action: String = SnapActions.ACTION_INSTANT
}

class OpenLastTileService : BaseTileService() {
    override val action: String = SnapActions.ACTION_OPEN_LAST
}

class HistoryTileService : BaseTileService() {
    override val action: String = SnapActions.ACTION_HISTORY
}

class CloseAllTileService : BaseTileService() {
    override val action: String = SnapActions.ACTION_CLOSE_ALL
}
