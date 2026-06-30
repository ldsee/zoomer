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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * Entry screen: choose rendering mode (GPU/CPU), grant the two permissions
 * (overlay + screen capture), and start the overlay. Theme follows the OS
 * light/dark setting and can be overridden via a corner toggle.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var selectedMode = RenderMode.GPU

    private val captureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlay(result.resultCode, result.data!!)
        }
    }

    // On Android 13+ the foreground-service notification (which carries the only
    // reliable Stop/Close button) is suppressed unless the user has granted
    // POST_NOTIFICATIONS. We request it before starting so the overlay can always
    // be stopped. Whatever the user chooses, we continue - the overlay can still
    // be stopped via the Quick Settings tile even if they decline.
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        continueToOverlayPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the saved theme override BEFORE super/setContentView so there is
        // no flash of the wrong theme. Default is "follow system".
        AppCompatDelegate.setDefaultNightMode(loadThemeMode(this))
        super.onCreate(savedInstanceState)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        selectedMode = loadMode(this)
        setContentView(buildUi())
    }

    private fun buildUi(): ViewGroup {
        // FrameLayout root lets us pin the theme icon to the true top-right corner
        // independently of the centered content column.
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(48, 48, 48, 48)
        }

        // Centered content column (everything except the corner icon).
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        content.addView(TextView(this).apply {
            text = "Pinch Zoom Overlay"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        content.addView(TextView(this).apply {
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
        content.addView(group)

        content.addView(Button(this).apply {
            text = "Start Zoom Overlay"
            setPadding(0, 32, 0, 0)
            setOnClickListener { onStartClicked() }
        })

        content.addView(TextView(this).apply {
            text = "You can also start/stop from the Quick Settings tile."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        })

        // Add the centered content first, then the corner icon on top of it.
        frame.addView(content)

        // Theme toggle icon, pinned to the true top-right corner of the screen.
        frame.addView(android.widget.ImageButton(this).apply {
            setImageResource(themeIconRes(loadThemeMode(this@MainActivity)))
            val ta = theme.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            )
            setBackgroundResource(ta.getResourceId(0, 0))
            ta.recycle()
            contentDescription = "Toggle theme"
            setOnClickListener { cycleThemeMode(this) }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        })

        return frame
    }

    private fun onStartClicked() {
        // Step 1: ensure we can show the foreground-service notification (the
        // stop button). On Android 13+ this must be requested at runtime.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        continueToOverlayPermissions()
    }

    private fun continueToOverlayPermissions() {
        // Step 2: overlay permission, then screen-capture consent.
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

    // Cycles Light -> Dark -> Follow System -> Light, persists the choice,
    // applies it immediately, and recreates the screen so it takes effect now.
    private fun cycleThemeMode(button: android.widget.ImageButton) {
        val current = loadThemeMode(this)
        val next = when (current) {
            AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        saveThemeMode(this, next)
        AppCompatDelegate.setDefaultNightMode(next)
        button.setImageResource(themeIconRes(next))
        // Recreate so the whole screen redraws in the new theme immediately.
        recreate()
    }

    private fun themeIconRes(mode: Int): Int = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO -> R.drawable.ic_theme_light
        AppCompatDelegate.MODE_NIGHT_YES -> R.drawable.ic_theme_dark
        else -> R.drawable.ic_theme_auto
    }

    companion object {
        private const val PREFS = "zoom_prefs"
        private const val KEY_MODE = "render_mode"
        private const val KEY_THEME = "theme_mode"

        fun loadMode(context: Context): RenderMode {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return RenderMode.fromName(prefs.getString(KEY_MODE, RenderMode.GPU.name))
        }

        fun saveMode(context: Context, mode: RenderMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode.name).apply()
        }

        // Theme mode stored as the AppCompat night-mode int; defaults to
        // follow-system so the app respects the OS setting until told otherwise.
        fun loadThemeMode(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        fun saveThemeMode(context: Context, mode: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_THEME, mode).apply()
        }
    }
}
