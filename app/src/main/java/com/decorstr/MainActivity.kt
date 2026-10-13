package com.decorstr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.decorstr.camera.CameraChoice
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
    private var cameraDropdown: ListPopupWindow? = null

    private var lastFps = 0.0f
    private var lastStableState = false
    private var bannerHideRunnable: Runnable? = null
    private val bannerHandler = Handler(Looper.getMainLooper())

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
            showBannerToast("Camera permission is required for real-time RAW preview", isLong = true)
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
                showBannerToast("Aspect: $mode")
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
            val preset = when (checkedIds[0]) {
                R.id.chipLab -> ColorSpace.LAB
                R.id.chipCrgb -> ColorSpace.CRGB
                R.id.chipYrd -> ColorSpace.YRD
                R.id.chipYre -> ColorSpace.YRE
                R.id.chipYye -> ColorSpace.YYE
                R.id.chipYbk -> ColorSpace.YBK
                R.id.chipYwe -> ColorSpace.YWE
                R.id.chipYdt -> ColorSpace.YDT
                R.id.chipRgb0 -> ColorSpace.RGB0
                R.id.chipPca -> ColorSpace.DIRECT_PCA
                R.id.chipNormal -> ColorSpace.RAW_NORMAL
                else -> ColorSpace.LAB
            }
            decorEngine.activeColorSpace = preset
            if (preset != ColorSpace.RAW_NORMAL) {
                decorEngine.reset()
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

        // Switch Camera Dropdown
        btnSwitchCam.setOnClickListener {
            showCameraDropdown()
        }

        // Toggle Aspect Fit / Fill
        btnAspect.setOnClickListener {
            renderer.toggleAspect()
            btnAspect.text = if (renderer.isAspectFill) "FILL" else "FIT"
            val mode = if (renderer.isAspectFill) "Fill (Crop)" else "Fit (Letterbox)"
            showBannerToast("Aspect: $mode")
        }

        // Capture still image (Full-resolution Decorrelated JPEG + RAW DNG)
        btnCapture.setOnClickListener {
            btnCapture.isEnabled = false
            showBannerToast("Capturing 12MP Photo + RAW DNG...")
            cameraManager.captureStill { jpegUri, dngUri ->
                runOnUiThread {
                    btnCapture.isEnabled = true
                    if (jpegUri != null && dngUri != null) {
                        showBannerToast("Saved Photo & DNG to Pictures/Decorstr!", isLong = true)
                    } else if (jpegUri != null) {
                        showBannerToast("Saved Photo to Pictures/Decorstr!", isLong = true)
                    } else {
                        showBannerToast("Capture failed, try again")
                    }
                }
            }
        }
    }

    private fun showBannerToast(message: String, isLong: Boolean = false) {
        val banner = findViewById<CardView>(R.id.bannerNotification) ?: return
        val tvBanner = findViewById<TextView>(R.id.tvBannerText) ?: return

        bannerHideRunnable?.let { bannerHandler.removeCallbacks(it) }

        tvBanner.text = message
        banner.alpha = 0f
        banner.visibility = View.VISIBLE
        banner.animate()
            .alpha(1f)
            .setDuration(150)
            .start()

        val delayMs = if (isLong) 3000L else 1800L
        val runnable = Runnable {
            banner.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    banner.visibility = View.GONE
                }
                .start()
        }
        bannerHideRunnable = runnable
        bannerHandler.postDelayed(runnable, delayMs)
    }

    private fun showCameraDropdown() {
        val choices = cameraManager.cameraChoices
        if (choices.isEmpty()) {
            showBannerToast("No RAW cameras available")
            return
        }

        val adapter = object : ArrayAdapter<CameraChoice>(this, R.layout.item_camera_choice, choices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_camera_choice, parent, false)
                val item = choices[position]
                val rb = view.findViewById<RadioButton>(R.id.rbCamera)
                val tvTitle = view.findViewById<TextView>(R.id.tvCameraTitle)
                val tvSubtitle = view.findViewById<TextView>(R.id.tvCameraSubtitle)

                tvTitle.text = item.title
                tvSubtitle.text = item.subtitle
                rb.isChecked = (position == cameraManager.currentCameraIndex)
                return view
            }
        }

        cameraDropdown?.dismiss()
        val popup = ListPopupWindow(this).apply {
            anchorView = btnSwitchCam
            setDropDownGravity(Gravity.END)
            setAdapter(adapter)
            isModal = true
            width = (240 * resources.displayMetrics.density).toInt()
            verticalOffset = (6 * resources.displayMetrics.density).toInt()
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dropdown_menu))
            setOnItemClickListener { _, _, position, _ ->
                dismiss()
                if (position != cameraManager.currentCameraIndex) {
                    renderer.resetAccumulation()
                    cameraManager.resetFocus()
                    focusRingView.hide()
                    cameraManager.selectCamera(position)
                    val chosen = choices[position]
                    showBannerToast("Switched to ${chosen.title}")
                }
            }
        }
        cameraDropdown = popup
        popup.show()
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
        cameraDropdown?.dismiss()
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
