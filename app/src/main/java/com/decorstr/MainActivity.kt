package com.decorstr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: DecorstrGLSurfaceView
    private lateinit var renderer: DecorstrRenderer
    private lateinit var decorEngine: DecorrelationEngine
    private lateinit var cameraManager: RawCameraManager

    private lateinit var tvStatus: TextView
    private lateinit var tvSmoothingLabel: TextView
    private lateinit var tvIntensityLabel: TextView
    private lateinit var seekSmoothing: SeekBar
    private lateinit var seekIntensity: SeekBar
    private lateinit var btnLockMatrix: MaterialButton
    private lateinit var btnSwitchCam: Button
    private lateinit var btnCapture: MaterialButton
    private lateinit var chipGroupPresets: ChipGroup

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraPreview()
        } else {
            Toast.makeText(this, "Camera permission is required for real-time RAW preview", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        tvStatus = findViewById(R.id.tvStatus)
        tvSmoothingLabel = findViewById(R.id.tvSmoothingLabel)
        tvIntensityLabel = findViewById(R.id.tvIntensityLabel)
        seekSmoothing = findViewById(R.id.seekSmoothing)
        seekIntensity = findViewById(R.id.seekIntensity)
        btnLockMatrix = findViewById(R.id.btnLockMatrix)
        btnSwitchCam = findViewById(R.id.btnSwitchCam)
        btnCapture = findViewById(R.id.btnCapture)
        chipGroupPresets = findViewById(R.id.chipGroupPresets)

        // Initialize decorrelation engine and renderer
        decorEngine = DecorrelationEngine()
        renderer = DecorstrRenderer(this, decorEngine)
        glSurfaceView.setRenderer(renderer)

        // Initialize camera manager
        cameraManager = RawCameraManager(this, renderer, decorEngine) { fps ->
            runOnUiThread {
                tvStatus.text = String.format(
                    "%.1f FPS | %dx%d",
                    fps,
                    cameraManager.previewSize.width,
                    cameraManager.previewSize.height
                )
            }
        }

        setupControls()
        checkPermissionsAndStart()
    }

    private fun setupControls() {
        // Preset selector
        chipGroupPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
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
            cameraManager.switchCamera()
        }

        // Capture still image
        btnCapture.setOnClickListener {
            Toast.makeText(this, "Capturing decorrelated RAW snapshot...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPreview() {
        cameraManager.start()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraManager.start()
        }
    }

    override fun onPause() {
        cameraManager.stop()
        glSurfaceView.onPause()
        super.onPause()
    }
}
