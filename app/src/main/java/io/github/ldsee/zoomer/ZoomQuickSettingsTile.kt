package io.github.ldsee.zoomer

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * One-tap sessions from Quick Settings. Idle: tap opens the capture-consent
 * dialog directly over whatever is on screen (via the invisible trampoline) -
 * pick the app and zooming starts. Running: tap stops the session. The tile
 * reflects live state via ZoomOverlayService.isRunning.
 */
class ZoomQuickSettingsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (ZoomOverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Zoomer"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (ZoomOverlayService.isRunning) {
            stopService(Intent(this, ZoomOverlayService::class.java))
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
            return
        }
        if (isLocked) {
            unlockAndRun { launchTrampoline() }
        } else {
            launchTrampoline()
        }
    }

    private fun launchTrampoline() {
        val intent = Intent(this, CaptureTrampolineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 10, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
