package io.github.ldsee.zoomer

import android.view.Surface

/**
 * Shared core types for the zoom overlay.
 *
 * The app supports two interchangeable rendering backends, chosen at runtime:
 *
 *  - [RenderMode.GPU]: hardware OpenGL ES. MediaProjection feeds an external
 *    texture sampled by a shader with a zoom/pan matrix. No CPU pixel copies,
 *    smooth at high frame rates. Best on devices where a SurfaceView composites
 *    correctly inside a system overlay window (most modern devices).
 *
 *  - [RenderMode.CPU]: software 2D. MediaProjection feeds an ImageReader; each
 *    frame is copied into a Bitmap and drawn through a Canvas matrix. Heavier and
 *    frame-rate limited, but maximally compatible - a reliable fallback.
 *
 * Both backends implement [ZoomRenderer] so the service treats them identically.
 */
enum class RenderMode {
    GPU,
    CPU;

    companion object {
        fun fromName(name: String?): RenderMode =
            entries.firstOrNull { it.name == name } ?: GPU
    }
}

/**
 * The zoom/pan state, shared and identical across both backends so switching
 * renderers (or toggling pass mode) preserves exactly what the user set.
 *
 * Values are in a normalized, backend-agnostic form:
 *  - [scale]: 1.0 = fit, up to [MAX_SCALE].
 *  - [translateXNorm], [translateYNorm]: pan offset in normalized device units,
 *    where the full viewport spans 2.0 on each axis (OpenGL NDC convention). The
 *    CPU backend converts these to pixels; the GPU backend uses them directly.
 */
data class ZoomState(
    var scale: Float = DEFAULT_SCALE,
    var translateXNorm: Float = 0f,
    var translateYNorm: Float = 0f
) {
    fun reset() {
        scale = DEFAULT_SCALE
        translateXNorm = 0f
        translateYNorm = 0f
    }

    fun copyFrom(other: ZoomState) {
        scale = other.scale
        translateXNorm = other.translateXNorm
        translateYNorm = other.translateYNorm
    }

    companion object {
        const val MIN_SCALE = 1.0f
        const val MAX_SCALE = 6.0f
        const val DEFAULT_SCALE = 1.0f
        const val DEFAULT_FILL_SCALE = 1.6f
    }
}

/**
 * Dimensions of the screen capture. Always derived ONCE from the real physical
 * display (getRealMetrics) and used identically for the capture surface buffer
 * size and the VirtualDisplay - a mismatch here makes the capture come back
 * blank, which was a subtle, expensive bug to find. Keeping it in one value type
 * passed everywhere prevents that class of mistake.
 */
data class CaptureDimensions(
    val width: Int,
    val height: Int,
    val densityDpi: Int
)

/**
 * A rendering backend. The overlay service drives any implementation through
 * this identical lifecycle, so CPU and GPU are fully interchangeable.
 */
interface ZoomRenderer {

    /**
     * The View that displays the rendered output. Added to the overlay window by
     * the service. Each backend returns its natural view type (a GLSurfaceView or
     * a custom software View) behind this common type.
     */
    val view: android.view.View

    /**
     * Called once the backend's capture Surface is ready for the VirtualDisplay.
     * The backend creates its capture target (SurfaceTexture or ImageReader
     * surface) and hands it back through [onSurfaceReady]. The service then wires
     * it to MediaProjection exactly once.
     */
    fun attachCaptureTarget(dimensions: CaptureDimensions, onSurfaceReady: (Surface) -> Unit)

    /** Apply new zoom/pan state. Cheap; called on every gesture frame. */
    fun updateZoom(state: ZoomState)

    /**
     * Pass mode: when true the renderer shows nothing (transparent) so the real
     * app shows through and receives touches; when false it paints the zoomed
     * capture opaquely. Zoom state is preserved across toggles.
     */
    fun setPassMode(passMode: Boolean)

    /** Release all GPU/native resources. */
    fun release()
}
