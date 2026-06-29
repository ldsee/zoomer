package io.github.ldsee.zoomer

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: tap to start the overlay (launches MainActivity to gather
 * the screen-capture permission), or to stop it if running. Reflects running
 * state. Uses the render mode the user last selected.
 */
class ZoomQuickSettingsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (ZoomOverlayService.isRunning) {
            startService(
                Intent(this, ZoomOverlayService::class.java).apply {
                    action = ZoomOverlayService.ACTION_CLOSE
                }
            )
        } else {
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launch)
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (ZoomOverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (ZoomOverlayService.isRunning) "Zoom: On" else "Zoom: Off"
        tile.updateTile()
    }
}
