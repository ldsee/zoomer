package io.github.ldsee.zoomer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Entry screen: choose rendering mode (GPU/CPU), grant the two permissions
 * (overlay + screen capture), and start the overlay.
 */
class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var selectedMode = RenderMode.GPU

    private val captureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlay(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        selectedMode = loadMode(this)
        setContentView(buildUi())
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Pinch Zoom Overlay"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        root.addView(TextView(this).apply {
            text = "Rendering mode"
            textSize = 16f
            setPadding(0, 16, 0, 8)
        })

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val gpuButton = RadioButton(this).apply {
            text = "GPU (hardware, smooth - recommended)"
            isChecked = selectedMode == RenderMode.GPU
        }
        val cpuButton = RadioButton(this).apply {
            text = "CPU (software, most compatible)"
            isChecked = selectedMode == RenderMode.CPU
        }
        group.addView(gpuButton)
        group.addView(cpuButton)
        group.setOnCheckedChangeListener { _, _ ->
            selectedMode = if (gpuButton.isChecked) RenderMode.GPU else RenderMode.CPU
            saveMode(this, selectedMode)
        }
        root.addView(group)

        root.addView(Button(this).apply {
            text = "Start Zoom Overlay"
            setPadding(0, 32, 0, 0)
            setOnClickListener { onStartClicked() }
        })

        root.addView(TextView(this).apply {
            text = "You can also start/stop from the Quick Settings tile."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        })

        return root
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startOverlay(resultCode: Int, data: Intent) {
        val intent = Intent(this, ZoomOverlayService::class.java).apply {
            putExtra(ZoomOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ZoomOverlayService.EXTRA_DATA, data)
            putExtra(ZoomOverlayService.EXTRA_RENDER_MODE, selectedMode.name)
        }
        startForegroundService(intent)
        moveTaskToBack(true)
    }

    companion object {
        private const val PREFS = "zoom_prefs"
        private const val KEY_MODE = "render_mode"

        fun loadMode(context: Context): RenderMode {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return RenderMode.fromName(prefs.getString(KEY_MODE, RenderMode.GPU.name))
        }

        fun saveMode(context: Context, mode: RenderMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode.name).apply()
        }
    }
}
