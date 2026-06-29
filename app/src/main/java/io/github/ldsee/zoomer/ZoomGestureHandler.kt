package io.github.ldsee.zoomer

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * Translates raw touch events into updates on a shared [ZoomState], with a
 * consistent feel used by BOTH rendering backends:
 *
 *  - Pinch to zoom, clamped to [ZoomState.MIN_SCALE]..[ZoomState.MAX_SCALE].
 *  - One-finger drag to pan, in normalized device units.
 *  - A top strip is reserved so a downward swipe there pulls the notification
 *    shade instead of panning.
 *  - A small movement threshold prevents jitter from disturbing a set zoom.
 *
 * The owning view calls [onTouchEvent] and provides its pixel size via
 * [setViewportSize]; on any change [onChanged] fires so the renderer can apply
 * the new state.
 */
class ZoomGestureHandler(
    context: Context,
    private val state: ZoomState,
    private val onChanged: () -> Unit
) {
    private var viewportWidth = 1
    private var viewportHeight = 1

    private val density = context.resources.displayMetrics.density
    private val topShadeReservePx = 48f * density
    private val panThresholdPx = 16f * density

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isPanning = false
    private var panEngaged = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                state.scale = (state.scale * detector.scaleFactor)
                    .coerceIn(ZoomState.MIN_SCALE, ZoomState.MAX_SCALE)
                onChanged()
                return true
            }
        }
    )

    fun setViewportSize(width: Int, height: Int) {
        if (width > 0) viewportWidth = width
        if (height > 0) viewportHeight = height
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                lastX = x; lastY = y
                panEngaged = false
                // Don't start a pan if the gesture began in the shade-reserve strip.
                isPanning = y > topShadeReservePx
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    if (!panEngaged) {
                        val moved = kotlin.math.abs(x - downX) > panThresholdPx ||
                            kotlin.math.abs(y - downY) > panThresholdPx
                        if (moved) {
                            panEngaged = true
                            lastX = x; lastY = y
                        }
                    }
                    if (panEngaged) {
                        // Pixel delta -> normalized device units. The viewport spans
                        // 2.0 per axis; screen Y is inverted relative to NDC Y.
                        state.translateXNorm += (x - lastX) / viewportWidth * 2f
                        state.translateYNorm += -(y - lastY) / viewportHeight * 2f
                        lastX = x; lastY = y
                        onChanged()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                panEngaged = false
            }
        }
        return true
    }
}
