package io.github.ldsee.zoomer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.view.MotionEvent
import android.view.Surface
import android.view.View

/**
 * Software (2D Canvas) rendering backend.
 *
 * Pipeline:
 *   MediaProjection -> VirtualDisplay -> ImageReader -> Bitmap -> Canvas matrix.
 *
 * Every frame is copied from the ImageReader into a reused Bitmap and drawn with
 * a Canvas matrix that applies the same normalized zoom/pan as the GPU backend.
 * Heavier than the GPU path (a per-frame pixel copy) and effectively frame-rate
 * limited, but it works anywhere a Canvas does - the dependable fallback.
 *
 * Implementation notes:
 *  - A single reused Bitmap avoids per-frame allocation churn (a prior source of
 *    instability). The ImageReader row stride is wider than the visible width, so
 *    the backing bitmap is allocated at stride width and drawn cropped.
 *  - Rendering happens on a custom View's onDraw; new frames trigger invalidate().
 */
class CpuZoomRenderer(
    context: Context,
    private val state: ZoomState,
    private val onGesture: () -> Unit
) : ZoomRenderer {

    private val softwareView = SoftwareView(context)
    override val view: View get() = softwareView

    private val gestureHandler = ZoomGestureHandler(context, state, onGesture)

    @Volatile private var passMode = false

    private var imageReader: ImageReader? = null
    private var dimensions: CaptureDimensions? = null
    private var captureSurfaceCallback: ((Surface) -> Unit)? = null

    // Reused frame bitmap (allocated at stride width) and the cropped view of it.
    @Volatile private var frameBitmap: Bitmap? = null
    private var bitmapStrideWidth = 0

    private val drawMatrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun attachCaptureTarget(
        dimensions: CaptureDimensions,
        onSurfaceReady: (Surface) -> Unit
    ) {
        this.dimensions = dimensions
        captureSurfaceCallback = onSurfaceReady
        val reader = ImageReader.newInstance(
            dimensions.width, dimensions.height, PixelFormat.RGBA_8888, 2
        )
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val strideW = rowStride / pixelStride

                var bmp = frameBitmap
                if (bmp == null || bitmapStrideWidth != strideW ||
                    bmp.height != image.height) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideW, image.height, Bitmap.Config.ARGB_8888)
                    frameBitmap = bmp
                    bitmapStrideWidth = strideW
                }
                bmp.copyPixelsFromBuffer(plane.buffer)
                softwareView.postInvalidate()
            } finally {
                image.close()
            }
        }, null)
        imageReader = reader
        onSurfaceReady(reader.surface)
    }

    override fun updateZoom(state: ZoomState) {
        softwareView.postInvalidate()
    }

    override fun resizeCapture(dimensions: CaptureDimensions) {
        // ImageReader can't be resized in place, so build a new one at the new
        // dimensions and hand its Surface back through the same callback. The
        // service points the VirtualDisplay at the new Surface. The old reader is
        // closed after so in-flight frames aren't lost mid-swap.
        this.dimensions = dimensions
        val old = imageReader
        val reader = ImageReader.newInstance(
            dimensions.width, dimensions.height, PixelFormat.RGBA_8888, 2
        )
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val strideW = rowStride / pixelStride

                var bmp = frameBitmap
                if (bmp == null || bitmapStrideWidth != strideW ||
                    bmp.height != image.height) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideW, image.height, Bitmap.Config.ARGB_8888)
                    frameBitmap = bmp
                    bitmapStrideWidth = strideW
                }
                bmp.copyPixelsFromBuffer(plane.buffer)
                softwareView.postInvalidate()
            } finally {
                image.close()
            }
        }, null)
        imageReader = reader
        captureSurfaceCallback?.invoke(reader.surface)
        old?.close()
    }

    override fun setPassMode(passMode: Boolean) {
        this.passMode = passMode
        softwareView.postInvalidate()
    }

    override fun release() {
        imageReader?.close()
        imageReader = null
        frameBitmap?.recycle()
        frameBitmap = null
    }

    private inner class SoftwareView(context: Context) : View(context) {

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            gestureHandler.setViewportSize(w, h)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return if (passMode) false else gestureHandler.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            if (passMode) {
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                return
            }
            canvas.drawColor(Color.BLACK)

            val bmp = frameBitmap ?: return
            val dims = dimensions ?: return

            val viewW = width.toFloat()
            val viewH = height.toFloat()
            if (viewW <= 0 || viewH <= 0) return

            // Base: map the captured image (cropped to its real width) to fill the
            // view. The bitmap is stride-wide, so scale X against the real width.
            val srcW = dims.width.toFloat()
            val srcH = dims.height.toFloat()

            drawMatrix.reset()
            // Fit-fill the real capture into the view.
            val baseScale = maxOf(viewW / srcW, viewH / srcH)
            drawMatrix.postScale(baseScale, baseScale)
            // Center the (fit) image in the view.
            val drawnW = srcW * baseScale
            val drawnH = srcH * baseScale
            drawMatrix.postTranslate((viewW - drawnW) / 2f, (viewH - drawnH) / 2f)

            // Apply user zoom/pan around the view center. Convert normalized pan
            // (NDC, span 2.0) to pixels; NDC Y is inverted vs screen Y.
            val cx = viewW / 2f
            val cy = viewH / 2f
            drawMatrix.postScale(state.scale, state.scale, cx, cy)
            val panXpx = state.translateXNorm / 2f * viewW
            val panYpx = -state.translateYNorm / 2f * viewH
            drawMatrix.postTranslate(panXpx, panYpx)

            // Draw only the real-width region of the stride-wide bitmap.
            canvas.save()
            canvas.concat(drawMatrix)
            canvas.drawBitmap(bmp, 0f, 0f, paint)
            canvas.restore()
        }
    }
}
