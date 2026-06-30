package io.github.ldsee.zoomer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that hosts the zoom overlay.
 *
 * Responsibilities:
 *  - Pick the rendering backend (CPU or GPU) from the launch request.
 *  - Add the backend's view to a system overlay window.
 *  - Drive the shared CaptureEngine once the backend's capture surface is ready.
 *  - Toggle pass mode (transparent + non-touchable) vs zoom mode (opaque) from
 *    the notification, preserving zoom state across toggles.
 *
 * Both backends are driven through the identical ZoomRenderer interface, so this
 * service contains no backend-specific logic.
 */
class ZoomOverlayService : Service() {

    companion object {
        private const val TAG = "ZoomOverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "ZoomChannel"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_DATA = "DATA"
        const val EXTRA_RENDER_MODE = "RENDER_MODE"

        const val ACTION_TOGGLE_MODE = "io.github.ldsee.zoomer.TOGGLE_MODE"
        const val ACTION_CLOSE = "io.github.ldsee.zoomer.CLOSE"

        @Volatile var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var captureEngine: CaptureEngine
    private var renderer: ZoomRenderer? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val zoomState = ZoomState()
    private var renderMode = RenderMode.GPU
    private var passMode = false

    private var resultCode = 0
    private var resultData: Intent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_MODE -> { togglePassMode(); return START_NOT_STICKY }
            ACTION_CLOSE -> { stopSelf(); return START_NOT_STICKY }
        }

        resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        resultData = intent?.getParcelableExtra(EXTRA_DATA)
        renderMode = RenderMode.fromName(intent?.getStringExtra(EXTRA_RENDER_MODE))

        createNotificationChannel()
        startForegroundWithNotification()

        if (resultData == null) {
            Log.e(TAG, "No projection permission data; stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        captureEngine = CaptureEngine(this, mainHandler).apply {
            onProjectionStopped = { stopSelf() }
        }

        setupOverlay()
        isRunning = true
        requestTileUpdate()
        return START_NOT_STICKY
    }

    private fun setupOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            // Translucent so pass mode can reveal the app beneath.
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        overlayParams = params

        val dimensions = captureEngine.resolveDimensions()

        val backend: ZoomRenderer = when (renderMode) {
            RenderMode.GPU -> GpuZoomRenderer(this, zoomState) { onZoomChanged() }
            RenderMode.CPU -> CpuZoomRenderer(this, zoomState) { onZoomChanged() }
        }
        renderer = backend

        try {
            windowManager.addView(backend.view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view (overlay permission?).", e)
            stopSelf()
            return
        }

        backend.attachCaptureTarget(dimensions) { surface ->
            mainHandler.post { onCaptureSurfaceReady(dimensions, surface) }
        }
    }

    private fun onCaptureSurfaceReady(dimensions: CaptureDimensions, surface: Surface) {
        val data = resultData ?: return
        val ok = captureEngine.start(resultCode, data, dimensions, surface)
        if (!ok) stopSelf()
    }

    private fun onZoomChanged() {
        renderer?.updateZoom(zoomState)
    }

    private fun togglePassMode() {
        passMode = !passMode
        val params = overlayParams ?: return
        val backend = renderer ?: return

        backend.setPassMode(passMode)
        params.flags = if (passMode) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        // Android 12+ shows an "is displaying over other apps" warning whenever a
        // FLAG_NOT_TOUCHABLE overlay obscures the app underneath. A fully
        // transparent window (alpha 0) is an explicit exemption, so in pass mode
        // we drop the WINDOW alpha to 0 - the renderer already draws nothing then,
        // so nothing is lost visually, and the warning no longer fires. Back in
        // zoom mode we restore full opacity so the zoomed image is visible.
        params.alpha = if (passMode) 0f else 1f
        try {
            windowManager.updateViewLayout(backend.view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update overlay for pass mode.", e)
        }
        updateNotification()
    }

    private fun startForegroundWithNotification() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val toggleLabel = if (passMode) "Resume zoom" else "Tap the app"
        val status = if (passMode) {
            "Pass mode: app shown normally, taps work."
        } else {
            "Zoom mode (${renderMode.name}): pinch to zoom, drag to pan."
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Pinch Zoom Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(0, toggleLabel, servicePendingIntent(ACTION_TOGGLE_MODE, 1))
            .addAction(0, "Close", servicePendingIntent(ACTION_CLOSE, 2))
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ZoomOverlayService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestTileUpdate() {
        try {
            TileService.requestListeningState(
                this, ComponentName(this, ZoomQuickSettingsTile::class.java)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Tile update failed.", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "Pinch Zoom Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        requestTileUpdate()
        renderer?.let {
            it.release()
            if (it.view.isAttachedToWindow) {
                try { windowManager.removeView(it.view) } catch (_: Exception) {}
            }
        }
        renderer = null
        if (::captureEngine.isInitialized) captureEngine.release()
    }
}
