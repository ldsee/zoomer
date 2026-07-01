package io.github.ldsee.zoomer

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager

/**
 * Owns the MediaProjection + VirtualDisplay lifecycle, exactly once.
 *
 * This is deliberately separate from the renderers because the projection is the
 * single most fragile part of the system:
 *  - Capture dimensions must come from the REAL display (getRealMetrics) and be
 *    used identically for the renderer's capture surface and the VirtualDisplay,
 *    or frames come back blank.
 *  - createVirtualDisplay may be invoked only ONCE per projection; calling it
 *    again (e.g. if a surface is recreated) throws a SecurityException. The
 *    [start] method is guarded so it runs once and is safe to call again.
 */
class CaptureEngine(
    private val context: Context,
    private val handler: Handler
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var started = false

    var onProjectionStopped: (() -> Unit)? = null

    /** Capture size from the real physical display. Stable for the session. */
    fun resolveDimensions(): CaptureDimensions {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return CaptureDimensions(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    /**
     * Create the projection and virtual display once, targeting [surface].
     * Subsequent calls are ignored (returns true if already started).
     */
    fun start(
        resultCode: Int,
        data: Intent,
        dimensions: CaptureDimensions,
        surface: Surface
    ): Boolean {
        if (started) {
            Log.i(TAG, "start() called again; projection already running, ignoring.")
            return true
        }

        val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = pm.getMediaProjection(resultCode, data) ?: run {
            Log.e(TAG, "getMediaProjection returned null.")
            return false
        }
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped.")
                onProjectionStopped?.invoke()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "ZoomCapture",
            dimensions.width,
            dimensions.height,
            dimensions.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            handler
        )

        if (virtualDisplay == null) {
            Log.e(TAG, "createVirtualDisplay returned null.")
            return false
        }
        started = true
        Log.i(TAG, "Capture started at ${dimensions.width}x${dimensions.height}.")
        return true
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        started = false
    }

    /**
     * Resize the existing capture to new dimensions (after a rotation or fold).
     *
     * Crucially this REUSES the current MediaProjection and VirtualDisplay:
     * VirtualDisplay.resize() changes the mirrored resolution in place, so we
     * never call getMediaProjection()/createVirtualDisplay() a second time (which
     * would throw SecurityException). The caller is responsible for updating the
     * capture Surface's buffer size to match, so the mirrored frames land in a
     * correctly sized buffer instead of wrapping (which showed as doubled content).
     */
    fun resize(dimensions: CaptureDimensions) {
        val vd = virtualDisplay ?: return
        vd.resize(dimensions.width, dimensions.height, dimensions.densityDpi)
        Log.i(TAG, "Capture resized to ${dimensions.width}x${dimensions.height}.")
    }

    /**
     * Point the existing VirtualDisplay at a new output Surface. Used when a
     * backend must recreate its capture target on rotation (the CPU backend's
     * ImageReader can't be resized in place, so it hands back a fresh Surface).
     */
    fun setSurface(surface: Surface) {
        virtualDisplay?.surface = surface
    }

    companion object {
        private const val TAG = "CaptureEngine"
    }
}
