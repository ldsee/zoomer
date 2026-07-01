package io.github.ldsee.zoomer

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Hardware (OpenGL ES 2.0) rendering backend.
 *
 * Pipeline, entirely on the GPU:
 *   MediaProjection -> VirtualDisplay -> external OES texture (SurfaceTexture)
 *   -> full-screen quad drawn by a shader with a zoom/pan matrix.
 *
 * Design notes (each encodes something that is easy to get wrong):
 *  - The capture SurfaceTexture's default buffer size is set to the REAL display
 *    size, identical to the VirtualDisplay size, or the capture comes back blank.
 *  - The GLSurfaceView uses MEDIA_OVERLAY z-ordering, not z-order-on-top: in a
 *    system overlay window the latter composites the surface to black.
 *  - The surface is translucent; pass mode clears transparent (app shows
 *    through) while zoom mode paints every pixel opaque (no ghosting).
 *  - The required vertical flip is baked into the quad's texture coordinates and
 *    the texture matrix is left identity. Routing the flip through the
 *    SurfaceTexture transform matrix mis-sampled the texture on this pipeline.
 */
class GpuZoomRenderer(
    context: Context,
    private val state: ZoomState,
    private val onGesture: () -> Unit
) : ZoomRenderer {

    private val glSurfaceView = InnerGLView(context)
    private val renderer = InnerRenderer()

    override val view: android.view.View get() = glSurfaceView

    private val gestureHandler = ZoomGestureHandler(context, state, onGesture)

    @Volatile private var passMode = false
    @Volatile private var captureSurfaceCallback: ((Surface) -> Unit)? = null
    private var captureDimensions: CaptureDimensions? = null

    init {
        glSurfaceView.setEGLContextClientVersion(2)
        // Alpha channel + translucent holder so pass-mode transparency reveals the
        // app beneath.
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        glSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        // MEDIA_OVERLAY (NOT z-order-on-top) so the surface composites correctly,
        // not as black, inside the TYPE_APPLICATION_OVERLAY window.
        glSurfaceView.setZOrderMediaOverlay(true)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    override fun attachCaptureTarget(
        dimensions: CaptureDimensions,
        onSurfaceReady: (Surface) -> Unit
    ) {
        captureDimensions = dimensions
        captureSurfaceCallback = onSurfaceReady
        // The actual Surface is produced on the GL thread in onSurfaceCreated.
        glSurfaceView.requestRender()
    }

    override fun updateZoom(state: ZoomState) {
        glSurfaceView.requestRender()
    }

    override fun resizeCapture(dimensions: CaptureDimensions) {
        captureDimensions = dimensions
        // SurfaceTexture operations must run on the GL thread. Queue the buffer
        // resize there, then request a render so the new size takes effect.
        glSurfaceView.queueEvent {
            surfaceTexture?.setDefaultBufferSize(dimensions.width, dimensions.height)
        }
        glSurfaceView.requestRender()
    }

    override fun setPassMode(passMode: Boolean) {
        this.passMode = passMode
        glSurfaceView.requestRender()
    }

    override fun release() {
        renderer.release()
    }

    /** GLSurfaceView subclass that forwards touches to the shared gesture handler. */
    private inner class InnerGLView(context: Context) : GLSurfaceView(context) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            gestureHandler.setViewportSize(w, h)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            return if (passMode) false else gestureHandler.onTouchEvent(event)
        }
    }

    private inner class InnerRenderer : GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

        private var textureId = 0
        @Volatile private var surfaceTexture: SurfaceTexture? = null

        private var program = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        private var uMvpMatrixLoc = 0
        private var uTexMatrixLoc = 0
        private var uTextureLoc = 0

        private val vertexData: FloatBuffer
        private val texCoordData: FloatBuffer
        private val mvpMatrix = FloatArray(16)
        private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        private val frameLock = Object()
        @Volatile private var hasNewFrame = false

        private var viewportW = 0
        private var viewportH = 0

        init {
            val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            // Vertical flip baked in (Y inverted): bottom vertex samples texture top.
            val texs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            vertexData = ByteBuffer.allocateDirect(verts.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
            texCoordData = ByteBuffer.allocateDirect(texs.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texs); position(0) }
            Matrix.setIdentityM(mvpMatrix, 0)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uMvpMatrixLoc = GLES20.glGetUniformLocation(program, "uMvpMatrix")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val dims = captureDimensions ?: return
            val st = SurfaceTexture(textureId)
            // Buffer size MUST equal the VirtualDisplay size (the real display).
            st.setDefaultBufferSize(dims.width, dims.height)
            st.setOnFrameAvailableListener(this)
            surfaceTexture = st

            captureSurfaceCallback?.invoke(Surface(st))
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            viewportW = width
            viewportH = height
            gestureHandler.setViewportSize(width, height)
        }

        override fun onFrameAvailable(st: SurfaceTexture?) {
            synchronized(frameLock) { hasNewFrame = true }
            glSurfaceView.requestRender()
        }

        override fun onDrawFrame(gl: GL10?) {
            synchronized(frameLock) {
                if (hasNewFrame) {
                    surfaceTexture?.updateTexImage()
                    hasNewFrame = false
                }
            }

            if (passMode) {
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                return
            }

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            Matrix.setIdentityM(mvpMatrix, 0)
            Matrix.translateM(mvpMatrix, 0, state.translateXNorm, state.translateYNorm, 0f)
            Matrix.scaleM(mvpMatrix, 0, state.scale, state.scale, 1f)

            // Correct for the capture-texture vs viewport aspect mismatch. The
            // captured texture (the full physical display) and the GL viewport can
            // have different shapes - on this device the capture is 2248x2480 but
            // the viewport is 2248x2328, so mapping the texture onto the full quad
            // squashes it vertically. The squash is small at rest but magnifies
            // visibly when zooming (matching the symptom). We scale the geometry by
            // the ratio of the two aspect ratios so texture pixels stay square at
            // every zoom level; letterboxing appears instead of distortion.
            val texAspect = (captureDimensions?.width ?: viewportW).toFloat() /
                (captureDimensions?.height ?: viewportH).toFloat().coerceAtLeast(1f)
            val vpAspect = viewportW.toFloat() / viewportH.toFloat().coerceAtLeast(1f)
            val ratio = (vpAspect / texAspect)
            if (ratio >= 1f) {
                Matrix.scaleM(mvpMatrix, 0, 1f / ratio, 1f, 1f)
            } else {
                Matrix.scaleM(mvpMatrix, 0, 1f, ratio, 1f)
            }

            GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
            // Identity texture matrix; the necessary flip is baked into tex coords.
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, identityMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTextureLoc, 0)

            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexData)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordData)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        }

        fun release() {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
        }

        private fun buildProgram(vsSrc: String, fsSrc: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                throw RuntimeException("Program link failed: $log")
            }
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Shader compile failed: $log")
            }
            return shader
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
