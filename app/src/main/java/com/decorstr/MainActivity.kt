package com.decorstr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.decorstr.camera.RawCameraManager
import com.decorstr.engine.ColorSpace
import com.decorstr.engine.DecorrelationEngine
import com.decorstr.gl.DecorstrGLSurfaceView
import com.decorstr.gl.DecorstrRenderer
import com.decorstr.sensors.StabilityTracker
import com.decorstr.ui.FocusRingView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: DecorstrGLSurfaceView
    private lateinit var focusRingView: FocusRingView
    private lateinit var renderer: DecorstrRenderer
    private lateinit var decorEngine: DecorrelationEngine
    private lateinit var cameraManager: RawCameraManager
    private lateinit var stabilityTracker: StabilityTracker

    private var lastFps = 0.0f
    private var lastStableState = false

    private lateinit var tvStatus: TextView
    private lateinit var tvSmoothingLabel: TextView
    private lateinit var tvIntensityLabel: TextView
    private lateinit var seekSmoothing: SeekBar
    private lateinit var seekIntensity: SeekBar
    private lateinit var btnLockMatrix: MaterialButton
    private lateinit var btnSwitchCam: Button
    private lateinit var btnAspect: Button
    private lateinit var btnCapture: MaterialButton
    private lateinit var chipGroupPresets: ChipGroup

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            cameraManager.start()
        } else {
            Toast.makeText(this, "Camera permission is required for real-time RAW preview", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and display over keyguard/lock screen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        tvStatus = findViewById(R.id.tvStatus)
        tvSmoothingLabel = findViewById(R.id.tvSmoothingLabel)
        tvIntensityLabel = findViewById(R.id.tvIntensityLabel)
        seekSmoothing = findViewById(R.id.seekSmoothing)
        seekIntensity = findViewById(R.id.seekIntensity)
        btnLockMatrix = findViewById(R.id.btnLockMatrix)
        btnSwitchCam = findViewById(R.id.btnSwitchCam)
        btnAspect = findViewById(R.id.btnAspect)
        btnCapture = findViewById(R.id.btnCapture)
        chipGroupPresets = findViewById(R.id.chipGroupPresets)
        focusRingView = findViewById(R.id.focusRingView)

        // Initialize decorrelation engine and renderer
        decorEngine = DecorrelationEngine()
        renderer = DecorstrRenderer(this, decorEngine)
        glSurfaceView.setRenderer(renderer)

        // Single tap preview surface triggers touch-to-focus
        glSurfaceView.onFocusTap = { x, y ->
            focusRingView.showAt(x, y)
            val normCoord = renderer.mapScreenToNormalizedSensor(x, y)
            if (normCoord != null) {
                cameraManager.triggerFocus(normCoord.first, normCoord.second) { success ->
                    focusRingView.onFocusLocked(success)
                }
            } else {
                focusRingView.onFocusLocked(false)
            }
        }

        // Double tap preview surface toggles Fit/Fill
        glSurfaceView.onToggleAspect = {
            runOnUiThread {
                cameraManager.resetFocus()
                focusRingView.hide()
                renderer.toggleAspect()
                btnAspect.text = if (renderer.isAspectFill) "FILL" else "FIT"
                val mode = if (renderer.isAspectFill) "Fill (Crop)" else "Fit (Letterbox)"
                Toast.makeText(this, "Aspect: $mode", Toast.LENGTH_SHORT).show()
            }
        }

        // Initialize stability tracker for live preview temporal denoiser
        stabilityTracker = StabilityTracker(this) { score, isStable ->
            renderer.stabilityScore = score
            if (isStable != lastStableState) {
                lastStableState = isStable
                runOnUiThread {
                    updateStatusText()
                }
            }
        }

        // Initialize camera manager
        cameraManager = RawCameraManager(this, renderer, decorEngine) { fps ->
            runOnUiThread {
                lastFps = fps
                updateStatusText()
            }
        }

        setupControls()
    }

    private fun updateStatusText() {
        if (lastStableState) {
            tvStatus.text = String.format("%.1f FPS | STABLE ✦", lastFps)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        } else {
            tvStatus.text = String.format("%.1f FPS", lastFps)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.white))
        }
    }

    private fun setupControls() {
        // Preset selector
        chipGroupPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            renderer.resetAccumulation()
            when (checkedIds[0]) {
                R.id.chipLab -> {
                    decorEngine.activeColorSpace = ColorSpace.LAB
                    decorEngine.reset()
                }
                R.id.chipRgb -> {
                    decorEngine.activeColorSpace = ColorSpace.RGB_ZCA
                    decorEngine.reset()
                }
                R.id.chipPca -> {
                    decorEngine.activeColorSpace = ColorSpace.DIRECT_PCA
                    decorEngine.reset()
                }
                R.id.chipNormal -> {
                    decorEngine.activeColorSpace = ColorSpace.RAW_NORMAL
                }
            }
        }

        // Temporal Smoothing Slider (1 to 100 -> 0.01 to 1.0)
        seekSmoothing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = (progress.coerceAtLeast(1) / 100.0f)
                decorEngine.alphaSmoothing = alpha
                tvSmoothingLabel.text = String.format("Smoothing: %.2f", alpha)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Intensity / Stretch standard deviation slider (5 to 100 -> 0.05 to 1.0)
        seekIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sigma = (progress.coerceAtLeast(5) / 100.0f)
                decorEngine.targetSigma = sigma
                renderer.contrast = (progress / 25.0f).coerceIn(0.5f, 2.0f)
                tvIntensityLabel.text = String.format("Intensity: %.2f", sigma)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Lock / Freeze Matrix
        btnLockMatrix.setOnClickListener {
            renderer.resetAccumulation()
            val locked = !decorEngine.isTransformLocked
            decorEngine.isTransformLocked = locked
            if (locked) {
                btnLockMatrix.text = getString(R.string.btn_unlock)
                btnLockMatrix.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))
            } else {
                btnLockMatrix.text = getString(R.string.btn_lock)
                btnLockMatrix.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_gold))
            }
        }

        // Switch Camera
        btnSwitchCam.setOnClickListener {
            focusRingView.hide()
            renderer.resetAccumulation()
            cameraManager.resetFocus()
            cameraManager.switchCamera()
        }

        // Toggle Aspect Fit / Fill
        btnAspect.setOnClickListener {
            renderer.toggleAspect()
            btnAspect.text = if (renderer.isAspectFill) "FILL" else "FIT"
            val mode = if (renderer.isAspectFill) "Fill (Crop)" else "Fit (Letterbox)"
            Toast.makeText(this, "Aspect: $mode", Toast.LENGTH_SHORT).show()
        }

        // Capture still image (Full-resolution Decorrelated JPEG + RAW DNG)
        btnCapture.setOnClickListener {
            btnCapture.isEnabled = false
            Toast.makeText(this, "Capturing 12MP Photo + RAW DNG...", Toast.LENGTH_SHORT).show()
            cameraManager.captureStill { jpegUri, dngUri ->
                runOnUiThread {
                    btnCapture.isEnabled = true
                    if (jpegUri != null && dngUri != null) {
                        Toast.makeText(this, "Saved Photo & DNG to Pictures/Decorstr!", Toast.LENGTH_LONG).show()
                    } else if (jpegUri != null) {
                        Toast.makeText(this, "Saved Photo to Pictures/Decorstr!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Capture failed, try again", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        renderer.resetAccumulation()
        stabilityTracker.start()
        btnCapture.isEnabled = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraManager.start()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        focusRingView.hide()
        stabilityTracker.stop()
        cameraManager.stop()
        glSurfaceView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        cameraManager.destroy()
        super.onDestroy()
    }
}
