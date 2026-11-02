package com.decorstr.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.util.Log
import android.util.Size
import com.decorstr.engine.DecorrelationEngine
import com.decorstr.engine.Matrix3x3
import com.decorstr.engine.Vec3
import com.decorstr.gl.DecorstrRenderer
import com.decorstr.storage.MediaSaver
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class CameraChoice(
    val index: Int,
    val logicalId: String,
    val physicalId: String? = null,
    val title: String,
    val subtitle: String
) {
    val id: String get() = physicalId ?: logicalId
}

class RawCameraManager(
    private val context: Context,
    private val renderer: DecorstrRenderer,
    private val decorEngine: DecorrelationEngine,
    private val onFrameProcessed: ((fps: Float) -> Unit)? = null
) {

    companion object {
        private const val TAG = "RawCameraManager"
        private const val MAX_PREVIEW_PIXELS = 4500 * 3500 // ~15MP ceiling for 30-60 FPS live preview
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Storage and capture state
    private val mediaSaver = MediaSaver(context)
    private val captureExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var latestCaptureResult: TotalCaptureResult? = null
    private var activeCharacteristics: CameraCharacteristics? = null

    // Touch-to-focus state
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    @Volatile private var isFocusTriggerActive = false
    @Volatile private var hasSeenActiveScan = false
    private var focusCallback: ((Boolean) -> Unit)? = null
    private var focusTriggerTimestamp = 0L

    @Volatile private var isCaptureRequested = false
    private var pendingCaptureCallback: ((Uri?, Uri?) -> Unit)? = null

    @Volatile private var inFlightCaptureLatch: CountDownLatch? = null

    fun captureStill(callback: (Uri?, Uri?) -> Unit) {
        synchronized(this) {
            pendingCaptureCallback = callback
            isCaptureRequested = true
        }
    }

    // Available RAW camera choices
    val cameraChoices = mutableListOf<CameraChoice>()
    var currentCameraIndex = 0
        private set

    // Lifecycle state
    @Volatile private var isStarted = false

    // Sensor metadata
    var selectedRawFormat = ImageFormat.RAW_SENSOR
    var activeCfaPattern = 0
    val activeBlackLevels = floatArrayOf(64f, 64f, 64f, 64f)
    var activeWhiteLevel = 1023f
    var previewSize = Size(4080, 3060)

    // Performance tracking
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()

    init {
        enumerateCameras()
        startBackgroundThread()
    }

    private fun enumerateCameras() {
        cameraChoices.clear()
        try {
            data class RawCandidate(
                val logicalId: String,
                val physicalId: String?,
                val chars: CameraCharacteristics,
                val facing: Int,
                val fl: Float,
                val equiv35mm: Int,
                val rank: Int
            )

            val candidates = mutableListOf<RawCandidate>()
            val publicIds = cameraManager.cameraIdList
            val seenPhysicalIds = mutableSetOf<String>()

            // Find primary back camera focal length for zoom calculation
            var mainBackEquiv = 24
            for (id in publicIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        val fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        if (sensorSize != null && fl > 0f) {
                            val diag = kotlin.math.sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
                            if (diag > 0.01f) {
                                val eq = (fl * (43.27f / diag)).toInt()
                                if (eq in 20..35) {
                                    mainBackEquiv = eq
                                    break
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            for (id in publicIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                    val isLogical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                    val availFls = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val fl = if (availFls != null && availFls.size > 1 && sensorSize != null) {
                        val diag = kotlin.math.sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
                        availFls.firstOrNull { f ->
                            val eq = if (diag > 0.01f) (f * (43.27f / diag)).toInt() else 0
                            eq in 21..35 || (f >= 3.0f && f < 6.5f)
                        } ?: availFls.firstOrNull() ?: 0f
                    } else {
                        availFls?.firstOrNull() ?: 0f
                    }
                    val equiv35mm = if (sensorSize != null && fl > 0f) {
                        val diag = kotlin.math.sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
                        if (diag > 0.01f) (fl * (43.27f / diag)).toInt() else 0
                    } else 0

                    if (hasRaw) {
                        val rank = when {
                            facing == CameraCharacteristics.LENS_FACING_BACK -> {
                                when {
                                    equiv35mm in 21..35 || (fl >= 3.0f && fl < 6.5f) -> 0 // Main 1x
                                    equiv35mm in 1..20 || (fl > 0f && fl < 3.0f) -> 1    // Ultra-Wide 0.6x
                                    equiv35mm > 35 || fl >= 6.5f -> 2                    // Telephoto 3x
                                    else -> 3
                                }
                            }
                            else -> 10
                        }
                        candidates.add(RawCandidate(id, null, chars, facing, fl, equiv35mm, rank))
                    }

                    // Check physical cameras if this is a logical multi-camera device
                    if (isLogical) {
                        val physIds = chars.physicalCameraIds
                        for (pid in physIds) {
                            if (publicIds.contains(pid)) continue // Already exposed as standalone public camera
                            if (seenPhysicalIds.contains(pid)) continue

                            try {
                                val pChars = cameraManager.getCameraCharacteristics(pid)
                                val pCaps = pChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                                val pHasRaw = pCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                                if (pHasRaw) {
                                    val pFacing = pChars.get(CameraCharacteristics.LENS_FACING) ?: facing
                                    val pFl = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                                    val pSensorSize = pChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                                    val pEquiv = if (pSensorSize != null && pFl > 0f) {
                                        val diag = kotlin.math.sqrt(pSensorSize.width * pSensorSize.width + pSensorSize.height * pSensorSize.height)
                                        if (diag > 0.01f) (pFl * (43.27f / diag)).toInt() else 0
                                    } else 0

                                    // Skip if physical camera duplicates the parent logical camera's default RAW sensor
                                    if (hasRaw && abs(pFl - fl) < 0.2f && pFacing == facing) {
                                        continue
                                    }

                                    val rank = when {
                                        pFacing == CameraCharacteristics.LENS_FACING_BACK -> {
                                            when {
                                                pEquiv in 21..35 || (pFl >= 3.0f && pFl < 6.5f) -> 0
                                                pEquiv in 1..20 || (pFl > 0f && pFl < 3.0f) -> 1
                                                pEquiv > 35 || pFl >= 6.5f -> 2
                                                else -> 3
                                            }
                                        }
                                        else -> 10
                                    }

                                    seenPhysicalIds.add(pid)
                                    candidates.add(RawCandidate(id, pid, pChars, pFacing, pFl, pEquiv, rank))
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed probing physical camera $pid of logical $id: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error enumerating camera $id: ${e.message}")
                }
            }

            // Sort: Main 1x first, then Ultra-Wide, Telephoto, other back, then front
            candidates.sortWith(compareBy({ it.rank }, { it.fl }, { it.logicalId }, { it.physicalId ?: "" }))

            for ((idx, cand) in candidates.withIndex()) {
                val title: String
                val subtitle: String
                val displayId = cand.physicalId ?: cand.logicalId

                if (cand.facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    title = "Front Camera"
                    subtitle = "Camera $displayId • Selfie (RAW)"
                } else {
                    when {
                        cand.equiv35mm in 1..20 || (cand.fl > 0f && cand.fl < 3.0f) -> {
                            title = "Ultra-Wide (0.6x)"
                            subtitle = "Camera $displayId • ${if (cand.equiv35mm > 0) "${cand.equiv35mm}mm equiv" else "${cand.fl}mm"}"
                        }
                        cand.equiv35mm in 21..35 || (cand.fl >= 3.0f && cand.fl < 6.5f) -> {
                            title = "Wide (1x) • Main"
                            subtitle = "Camera $displayId • ${if (cand.equiv35mm > 0) "${cand.equiv35mm}mm equiv" else "${cand.fl}mm"}"
                        }
                        cand.equiv35mm > 35 || cand.fl >= 6.5f -> {
                            val zoomFactor = if (mainBackEquiv > 0 && cand.equiv35mm > 0) {
                                val z = cand.equiv35mm.toFloat() / mainBackEquiv.toFloat()
                                "${(z + 0.2f).toInt()}x"
                            } else "3x"
                            title = "Telephoto ($zoomFactor)"
                            subtitle = "Camera $displayId • ${if (cand.equiv35mm > 0) "${cand.equiv35mm}mm equiv" else "${cand.fl}mm"}"
                        }
                        else -> {
                            title = "Back Camera $displayId"
                            subtitle = "Camera $displayId • RAW"
                        }
                    }
                }

                cameraChoices.add(CameraChoice(idx, cand.logicalId, cand.physicalId, title, subtitle))
                Log.i(TAG, "Registered RAW camera #$idx: logical=${cand.logicalId} physical=${cand.physicalId} title='$title' sub='$subtitle'")
            }

            Log.i(TAG, "Total RAW cameras available: ${cameraChoices.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed enumerating cameras", e)
        }
    }

    fun start() {
        startBackgroundThread()
        isStarted = true
        backgroundHandler?.post {
            if (isStarted && cameraDevice == null) {
                val choice = cameraChoices.getOrNull(currentCameraIndex)
                if (choice != null) {
                    openCameraInternal(choice)
                } else {
                    Log.e(TAG, "No RAW-capable cameras available on device!")
                }
            }
        }
    }

    fun stop() {
        isStarted = false
        backgroundHandler?.post {
            closeCameraInternal()
        }
    }

    fun destroy() {
        isStarted = false
        backgroundHandler?.post {
            closeCameraInternal()
            captureExecutor.shutdown()
            stopBackgroundThreadInternal()
        }
    }

    fun selectCamera(index: Int) {
        if (index !in cameraChoices.indices || index == currentCameraIndex) return
        currentCameraIndex = index
        backgroundHandler?.post {
            closeCameraInternal()
            if (isStarted) {
                val choice = cameraChoices.getOrNull(currentCameraIndex)
                if (choice != null) {
                    openCameraInternal(choice)
                }
            }
        }
    }

    fun switchCamera() {
        if (cameraChoices.size <= 1) return
        selectCamera((currentCameraIndex + 1) % cameraChoices.size)
    }

    @SuppressLint("MissingPermission")
    private fun openCameraInternal(choice: CameraChoice) {
        if (!isStarted) return
        if (cameraDevice != null) {
            Log.w(TAG, "Camera already open: ${cameraDevice?.id}")
            return
        }
        try {
            val logicalChars = cameraManager.getCameraCharacteristics(choice.logicalId)
            val chars = if (choice.physicalId != null) {
                cameraManager.getCameraCharacteristics(choice.physicalId)
            } else {
                logicalChars
            }
            activeCharacteristics = chars
            inspectAndConfigureMetadata(chars)

            cameraManager.openCamera(choice.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!isStarted) {
                        Log.i(TAG, "Camera opened after stop, closing immediately: ${camera.id}")
                        camera.close()
                        cameraDevice = null
                        return
                    }
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected: ${camera.id}")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error on camera ${choice.id} (logical ${choice.logicalId})")
                    camera.close()
                    cameraDevice = null
                    // Retry once after 300ms if error was due to temporary background/busy state
                    if (isStarted && (error == ERROR_CAMERA_DEVICE ||
                                     error == ERROR_CAMERA_DISABLED ||
                                     error == ERROR_CAMERA_IN_USE ||
                                     error == ERROR_MAX_CAMERAS_IN_USE)) {
                        backgroundHandler?.postDelayed({
                            if (isStarted && cameraDevice == null) {
                                Log.i(TAG, "Retrying open camera ${choice.id} after error $error")
                                openCameraInternal(choice)
                            }
                        }, 300)
                    }
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "CameraAccessException opening camera ${choice.id}: ${e.message}, retrying in 300ms")
            backgroundHandler?.postDelayed({
                if (isStarted && cameraDevice == null) {
                    openCameraInternal(choice)
                }
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera ${choice.id}", e)
        }
    }

    private fun inspectAndConfigureMetadata(chars: CameraCharacteristics) {
        activeCharacteristics = chars
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        // 1. Determine optimal RAW format
        val supportedFormats = map.outputFormats
        selectedRawFormat = when {
            supportedFormats.contains(ImageFormat.RAW_SENSOR) -> ImageFormat.RAW_SENSOR
            supportedFormats.contains(ImageFormat.RAW10) -> ImageFormat.RAW10
            else -> ImageFormat.RAW_SENSOR
        }

        // 2. Select resolution
        val rawSizes = map.getOutputSizes(selectedRawFormat)?.sortedByDescending { it.width * it.height } ?: emptyList()
        previewSize = rawSizes.firstOrNull { (it.width * it.height) <= MAX_PREVIEW_PIXELS }
            ?: rawSizes.lastOrNull()
            ?: Size(4080, 3060)

        Log.i(TAG, "Selected preview size: ${previewSize.width}x${previewSize.height} format: $selectedRawFormat")

        // 3. CFA pattern: 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
        activeCfaPattern = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        renderer.cfaPattern = activeCfaPattern

        // 4. Black Level
        val blackLevelPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        if (blackLevelPattern != null) {
            for (i in 0..3) {
                activeBlackLevels[i] = blackLevelPattern.getOffsetForIndex(i % 2, i / 2).toFloat()
                renderer.blackLevels[i] = activeBlackLevels[i]
            }
        }

        // 5. White Level
        activeWhiteLevel = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 4095).toFloat()
        renderer.whiteLevel = activeWhiteLevel

        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val lensFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val isFront = (lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
        renderer.setOrientation(sensorOrientation, isFront)

        // Compute per-camera calibrated neutral white balance gains from SENSOR_COLOR_TRANSFORM_1
        val calibratedGains = computeCalibratedWbGains(chars)
        renderer.resetWhiteBalanceGains(calibratedGains[0], 1.0f, calibratedGains[2])
        decorEngine.resetWhiteBalanceGains(calibratedGains[0], 1.0f, calibratedGains[2])
        decorEngine.reset()

        Log.i(TAG, "Configured CFA: $activeCfaPattern, BlackLevel: ${activeBlackLevels.contentToString()}, WhiteLevel: $activeWhiteLevel, Orientation: $sensorOrientation, isFront: $isFront, WB: ${calibratedGains.contentToString()}")
    }

    /**
     * Generically computes calibrated neutral white balance gains from CameraCharacteristics
     * adhering strictly to Android Camera2 and Adobe DNG (ISO 12234-2 / TIFF-EP) standards.
     * Fully compatible with any Android manufacturer and sensor type.
     */
    private fun computeCalibratedWbGains(chars: CameraCharacteristics): FloatArray {
        fun getIlluminantWhitePoint(ill: Int): Vec3 {
            return when (ill) {
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE -> Vec3(0.95047f, 1.00000f, 1.08883f)
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50 -> Vec3(0.96422f, 1.00000f, 0.82521f)
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55 -> Vec3(0.95682f, 1.00000f, 0.92090f)
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75 -> Vec3(0.94972f, 1.00000f, 1.22638f)
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> Vec3(1.09850f, 1.00000f, 0.35585f)
                else -> Vec3(0.95047f, 1.00000f, 1.08883f) // Standard D65 daylight
            }
        }

        fun isDaylight(ill: Int): Boolean {
            return ill in listOf(
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE,
                CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75
            )
        }

        fun toMatrix3x3(cst: ColorSpaceTransform?): Matrix3x3? {
            if (cst == null) return null
            val m = FloatArray(9)
            for (row in 0..2) {
                for (col in 0..2) {
                    m[row * 3 + col] = cst.getElement(col, row).toFloat()
                }
            }
            return Matrix3x3(m)
        }

        // 1. Evaluate SENSOR_COLOR_TRANSFORM1 and SENSOR_COLOR_TRANSFORM2
        val ct1 = toMatrix3x3(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))
        val ct2 = toMatrix3x3(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))
        val cal1 = toMatrix3x3(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
        val cal2 = toMatrix3x3(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))

        val ill1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt() ?: 0
        val ill2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 0

        // Prioritize daylight/neutral reference illuminant
        val chosenPair = when {
            isDaylight(ill1) && ct1 != null -> Triple(ct1, cal1, ill1)
            isDaylight(ill2) && ct2 != null -> Triple(ct2, cal2, ill2)
            ct1 != null -> Triple(ct1, cal1, ill1)
            ct2 != null -> Triple(ct2, cal2, ill2)
            else -> null
        }

        if (chosenPair != null) {
            val (ct, cal, ill) = chosenPair
            val totalTransform = if (cal != null) cal * ct else ct
            val wp = getIlluminantWhitePoint(ill)
            val sensorResp = totalTransform * wp

            val sR = sensorResp.x
            val sG = sensorResp.y
            val sB = sensorResp.z

            // Validate non-degenerate and not uncalibrated identity
            val isIdentity = abs(sR - wp.x) < 0.05f && abs(sG - wp.y) < 0.05f && abs(sB - wp.z) < 0.05f
            if (sR > 0.02f && sG > 0.02f && sB > 0.02f && !isIdentity) {
                val rGain = (sG / sR).coerceIn(1.1f, 4.0f)
                val bGain = (sG / sB).coerceIn(1.0f, 4.0f)
                Log.i(TAG, "Generic Calibrated WB gains (Illuminant $ill): R=$rGain, G=1.0, B=$bGain")
                return floatArrayOf(rGain, 1.0f, bGain)
            }
        }

        // 2. Fallback: SENSOR_FORWARD_MATRIX1 / SENSOR_FORWARD_MATRIX2
        // In DNG, ForwardMatrix maps Camera Space -> CIE XYZ (D50).
        // Therefore, Camera Space = ForwardMatrix^-1 * XYZ_D50.
        val fm1 = toMatrix3x3(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
        val fm2 = toMatrix3x3(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))
        val fm = fm1 ?: fm2
        if (fm != null) {
            val inv = fm.inverse()
            if (inv != null) {
                val d50 = Vec3(0.96422f, 1.00000f, 0.82521f)
                val sensorResp = inv * d50
                if (sensorResp.x > 0.02f && sensorResp.y > 0.02f && sensorResp.z > 0.02f) {
                    val rGain = (sensorResp.y / sensorResp.x).coerceIn(1.1f, 4.0f)
                    val bGain = (sensorResp.y / sensorResp.z).coerceIn(1.0f, 4.0f)
                    Log.i(TAG, "Generic ForwardMatrix WB gains: R=$rGain, G=1.0, B=$bGain")
                    return floatArrayOf(rGain, 1.0f, bGain)
                }
            }
        }

        // 3. Fallback: Standard CMOS Silicon Bayer sensitivity under daylight (D65)
        Log.i(TAG, "Using generic Bayer D65 baseline fallback gains")
        return floatArrayOf(2.2f, 1.0f, 1.6f)
    }

    private val repeatingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            latestCaptureResult = result

            val currentChoice = cameraChoices.getOrNull(currentCameraIndex)
            val effectiveResult: CaptureResult = if (currentChoice?.physicalId != null) {
                result.physicalCameraResults[currentChoice.physicalId] ?: result
            } else {
                result
            }

            // 1. Check SENSOR_NEUTRAL_COLOR_POINT (official Camera2 / DNG standard for RAW white balance)
            val neutral = effectiveResult.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT) ?: result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            var hasAppliedGains = false
            if (neutral != null && neutral.size >= 3) {
                val nr = neutral[0].toFloat()
                val ng = neutral[1].toFloat()
                val nb = neutral[2].toFloat()
                val isDummy = abs(nr - ng) < 0.001f && abs(nb - ng) < 0.001f
                if (nr > 0.001f && ng > 0.001f && nb > 0.001f && !isDummy) {
                    val rNorm = (ng / nr).coerceIn(1.0f, 4.0f)
                    val bNorm = (ng / nb).coerceIn(0.8f, 4.0f)
                    renderer.setWhiteBalanceGains(rNorm, 1.0f, bNorm)
                    decorEngine.setWhiteBalanceGains(rNorm, 1.0f, bNorm)
                    hasAppliedGains = true
                }
            }

            // 2. Check COLOR_CORRECTION_GAINS if neutral color point was not provided
            if (!hasAppliedGains) {
                val gains = effectiveResult.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                if (gains != null) {
                    val r = gains.red
                    val g = (gains.greenEven + gains.greenOdd) * 0.5f
                    val b = gains.blue
                    if (g > 0.001f) {
                        val rNorm = r / g
                        val bNorm = b / g
                        // When HAL returns dummy identity [1, 1, 1, 1] (as secondary cameras do for RAW streams),
                        // ignore identity to preserve the sensor's calibrated neutral baseline.
                        val isIdentity = abs(rNorm - 1.0f) < 0.05f && abs(bNorm - 1.0f) < 0.05f
                        if (!isIdentity) {
                            renderer.setWhiteBalanceGains(rNorm, 1.0f, bNorm)
                            decorEngine.setWhiteBalanceGains(rNorm, 1.0f, bNorm)
                        }
                    }
                }
            }

            if (isFocusTriggerActive) {
                val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: CaptureResult.CONTROL_AF_STATE_INACTIVE
                val elapsed = System.currentTimeMillis() - focusTriggerTimestamp

                if (afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN) {
                    hasSeenActiveScan = true
                }

                val isTerminalState = (hasSeenActiveScan || elapsed > 350) &&
                    (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                     afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)

                if (isTerminalState || elapsed > 3000) {
                    isFocusTriggerActive = false
                    val success = (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) ||
                                  (afState != CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED && elapsed > 3000)
                    Log.i(TAG, "AF completed: state=$afState success=$success elapsed=${elapsed}ms")
                    val cb = focusCallback
                    focusCallback = null
                    cb?.let { mainHandler.post { it.invoke(success) } }
                }
            }
        }
    }

    fun triggerFocus(normX: Float, normY: Float, callback: ((Boolean) -> Unit)? = null) {
        backgroundHandler?.post {
            val session = captureSession ?: run {
                mainHandler.post { callback?.invoke(false) }
                return@post
            }
            val builder = previewRequestBuilder ?: run {
                mainHandler.post { callback?.invoke(false) }
                return@post
            }
            val chars = activeCharacteristics ?: run {
                mainHandler.post { callback?.invoke(false) }
                return@post
            }

            val maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            if (maxAf == 0 && maxAe == 0) {
                Log.i(TAG, "Device does not support AF/AE metering regions")
                mainHandler.post { callback?.invoke(false) }
                return@post
            }

            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: Rect(0, 0, previewSize.width, previewSize.height)

            val sensorX = (normX * activeArray.width()).toInt().coerceIn(0, activeArray.width() - 1)
            val sensorY = (normY * activeArray.height()).toInt().coerceIn(0, activeArray.height() - 1)

            val halfW = (activeArray.width() * 0.06f).toInt().coerceAtLeast(60)
            val halfH = (activeArray.height() * 0.06f).toInt().coerceAtLeast(60)

            val left = (sensorX - halfW).coerceIn(activeArray.left, activeArray.right - 1)
            val top = (sensorY - halfH).coerceIn(activeArray.top, activeArray.bottom - 1)
            val right = (sensorX + halfW).coerceIn(left + 1, activeArray.right)
            val bottom = (sensorY + halfH).coerceIn(top + 1, activeArray.bottom)

            val meterRect = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)
            val regions = arrayOf(meterRect)
            Log.i(TAG, "Triggering AF at sensor ($sensorX, $sensorY) rect: [$left, $top, $right, $bottom] maxAf: $maxAf maxAe: $maxAe")

            isFocusTriggerActive = true
            hasSeenActiveScan = false
            focusCallback = callback
            focusTriggerTimestamp = System.currentTimeMillis()

            try {
                // 1. Cancel ongoing AF trigger to reset AF state machine
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                session.capture(builder.build(), null, backgroundHandler)

                // 2. Set regions and AUTO mode in repeating stream
                if (maxAf > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                }
                if (maxAe > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
                }
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, backgroundHandler)

                // 3. Trigger AF scan and AE precapture
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                if (maxAe > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }
                session.capture(builder.build(), repeatingCaptureCallback, backgroundHandler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                if (maxAe > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering focus", e)
                isFocusTriggerActive = false
                val cb = focusCallback
                focusCallback = null
                cb?.let { mainHandler.post { it.invoke(false) } }
            }
        }
    }

    fun resetFocus() {
        backgroundHandler?.post {
            val session = captureSession ?: return@post
            val builder = previewRequestBuilder ?: return@post
            val chars = activeCharacteristics ?: return@post
            try {
                isFocusTriggerActive = false
                focusCallback = null
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                val maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
                val maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
                if (maxAf > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                }
                if (maxAe > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
                }
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, backgroundHandler)
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting focus", e)
            }
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        if (!isStarted) return
        val currentChoice = cameraChoices.getOrNull(currentCameraIndex) ?: return
        val physicalId = currentChoice.physicalId

        try {
            imageReader?.close()
            imageReader = ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                selectedRawFormat,
                3
            ).apply {
                setOnImageAvailableListener({ reader ->
                    onRawFrameAvailable(reader)
                }, backgroundHandler)
            }

            val surface = imageReader!!.surface
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            }
            previewRequestBuilder = requestBuilder

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfig = OutputConfiguration(surface)
                if (physicalId != null) {
                    outputConfig.setPhysicalCameraId(physicalId)
                    Log.i(TAG, "Configured OutputConfiguration for physical camera ID: $physicalId")
                }
                val executor = Executor { command -> backgroundHandler?.post(command) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (!isStarted || cameraDevice == null) {
                                session.close()
                                return
                            }
                            captureSession = session
                            try {
                                session.setRepeatingRequest(
                                    requestBuilder.build(),
                                    repeatingCaptureCallback,
                                    backgroundHandler
                                )
                                Log.i(TAG, "Capture session active for ${currentChoice.title}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start repeating request", e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Capture session configuration failed for ${currentChoice.title}")
                        }
                    }
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (!isStarted || cameraDevice == null) {
                                session.close()
                                return
                            }
                            captureSession = session
                            try {
                                session.setRepeatingRequest(
                                    requestBuilder.build(),
                                    repeatingCaptureCallback,
                                    backgroundHandler
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start repeating request", e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Capture session configuration failed")
                        }
                    },
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating capture session", e)
        }
    }

    private fun onRawFrameAvailable(reader: ImageReader) {
        val image: Image = try {
            reader.acquireLatestImage() ?: return
        } catch (e: Exception) {
            return
        }

        if (!isStarted) {
            image.close()
            return
        }

        var shouldCapture = false
        var onCaptured: ((Uri?, Uri?) -> Unit)? = null
        synchronized(this) {
            if (isCaptureRequested) {
                shouldCapture = true
                isCaptureRequested = false
                onCaptured = pendingCaptureCallback
                pendingCaptureCallback = null
            }
        }

        val chars = activeCharacteristics
        val capResult = latestCaptureResult
        val canCapture = shouldCapture && chars != null && capResult != null

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // Process covariance, compute whitening transform, and copy/unpack frame to renderer
            if (selectedRawFormat == ImageFormat.RAW_SENSOR) {
                val rowStridePixels = rowStride / (pixelStride.coerceAtLeast(2))
                decorEngine.processRaw16(
                    buffer,
                    image.width,
                    image.height,
                    rowStridePixels,
                    activeCfaPattern,
                    activeBlackLevels,
                    activeWhiteLevel
                )
                buffer.rewind()
                renderer.queueRawFrame(buffer, image.width, image.height, rowStridePixels)
            } else if (selectedRawFormat == ImageFormat.RAW10) {
                decorEngine.processRaw10(
                    buffer,
                    image.width,
                    image.height,
                    rowStride,
                    activeCfaPattern,
                    activeBlackLevels,
                    activeWhiteLevel
                )
                buffer.rewind()
                renderer.queueRaw10Frame(buffer, image.width, image.height, rowStride)
            }

            if (canCapture) {
                val rotation = renderer.cameraRotation
                val colorSpaceName = decorEngine.activeColorSpace.name
                val captureLatch = CountDownLatch(1)
                inFlightCaptureLatch = captureLatch
                var savedDngUri: Uri? = null
                val isRawSensorDngSupported = (selectedRawFormat == ImageFormat.RAW_SENSOR)

                // 1. Submit RAW DNG saving immediately (takes ownership of image, closes it in finally)
                captureExecutor.execute {
                    try {
                        if (isRawSensorDngSupported) {
                            savedDngUri = mediaSaver.saveRawDng(
                                image,
                                chars!!,
                                capResult!!,
                                rotation
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving RAW DNG", e)
                    } finally {
                        image.close()
                        captureLatch.countDown()
                        inFlightCaptureLatch = null
                    }
                }

                // 2. Request offscreen stretched snapshot from renderer
                renderer.requestOffscreenCapture { stretchedBitmap ->
                    // Task 2 executes FIFO on captureExecutor after Task 1 completes
                    captureExecutor.execute {
                        var savedJpegUri: Uri? = null
                        try {
                            savedJpegUri = mediaSaver.saveDecorrelatedJpeg(
                                stretchedBitmap,
                                colorSpaceName
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving stretched JPEG", e)
                        } finally {
                            stretchedBitmap.recycle()
                            onCaptured?.invoke(savedJpegUri, savedDngUri)
                        }
                    }
                }
                return // Image will be closed by Task 1 in captureExecutor
            } else if (shouldCapture) {
                onCaptured?.invoke(null, null)
            }

            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTimestamp >= 1000) {
                val fps = frameCount * 1000.0f / (now - lastFpsTimestamp)
                frameCount = 0
                lastFpsTimestamp = now
                onFrameProcessed?.invoke(fps)
            }
        } finally {
            if (!canCapture) {
                image.close()
            }
        }
    }

    private fun closeCameraInternal() {
        try {
            inFlightCaptureLatch?.await(1000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted waiting for in-flight capture", e)
        }
        try {
            previewRequestBuilder = null
            isFocusTriggerActive = false
            focusCallback = null
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera internal", e)
        }
    }

    @Synchronized
    private fun startBackgroundThread() {
        if (backgroundThread == null || !backgroundThread!!.isAlive) {
            backgroundThread = HandlerThread("DecorstrCameraBg").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThreadInternal() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
}
