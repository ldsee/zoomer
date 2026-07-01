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
                // Zooming out shrinks the pannable range; re-clamp so the content
                // can't stay pushed off-screen after a zoom-out.
                clampTranslation()
                onChanged()
                return true
            }
        }
    )

    /**
     * Constrain the pan offset so the magnified content can never be dragged past
     * the point where its edge meets the screen edge (no black void, no content
     * floating in a black border).
     *
     * At scale s the content is s times the viewport. In NDC (viewport spans 2.0)
     * the content overflows by (s - 1) on each side, so the translation magnitude
     * on each axis is limited to (s - 1). At s = 1 the limit is 0: the content
     * exactly fills the screen and panning is disabled, which is correct.
     */
    private fun clampTranslation() {
        val maxOffset = (state.scale - 1f).coerceAtLeast(0f)
        state.translateXNorm = state.translateXNorm.coerceIn(-maxOffset, maxOffset)
        state.translateYNorm = state.translateYNorm.coerceIn(-maxOffset, maxOffset)
    }

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
                        // Divide by scale so a finger drag moves the same amount of
                        // on-screen content at every zoom level (at high zoom the
                        // content is magnified, so the same pixel drag should move a
                        // smaller slice of it).
                        state.translateXNorm += (x - lastX) / viewportWidth * 2f / state.scale
                        state.translateYNorm += -(y - lastY) / viewportHeight * 2f / state.scale
                        clampTranslation()
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
