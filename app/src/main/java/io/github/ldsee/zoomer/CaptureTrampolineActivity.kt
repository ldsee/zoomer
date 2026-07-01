package io.github.ldsee.zoomer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Invisible launcher for a zoom session. The Quick Settings tile (and the
 * notification's "Switch app" action) start this instead of the main screen:
 * it immediately shows the system capture-consent dialog over whatever the
 * user is doing, starts the overlay with the picked app, and vanishes. The
 * consent dialog itself is an Android privacy requirement and cannot be
 * skipped - this removes every other step around it.
 */
class CaptureTrampolineActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ZoomOverlayService::class.java).apply {
                putExtra(ZoomOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ZoomOverlayService.EXTRA_DATA, result.data)
                putExtra(
                    ZoomOverlayService.EXTRA_RENDER_MODE,
                    MainActivity.loadMode(this@CaptureTrampolineActivity).name
                )
            }
            startForegroundService(intent)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Open Zoomer once to grant permissions first.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        // Retargeting: if a session is already running, end it so the new pick
        // starts a fresh capture (Android consent is single-use per session).
        if (ZoomOverlayService.isRunning) {
            stopService(Intent(this, ZoomOverlayService::class.java))
        }
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(pm.createScreenCaptureIntent())
    }
}
