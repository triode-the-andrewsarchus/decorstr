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
import android.util.Log
import android.util.Size
import com.decorstr.engine.DecorrelationEngine
import com.decorstr.gl.DecorstrRenderer
import com.decorstr.storage.MediaSaver
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CameraChoice(
    val index: Int,
    val id: String,
    val title: String,
    val subtitle: String
)

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

    // Available RAW camera IDs and choices
    private val rawCameraIds = mutableListOf<String>()
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
        rawCameraIds.clear()
        cameraChoices.clear()
        try {
            val candidates = mutableListOf<String>()
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                if (hasRaw) {
                    candidates.add(id)
                }
            }

            // Sort back cameras first, then front
            candidates.sortBy { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) 0 else 1
            }

            for ((idx, id) in candidates.withIndex()) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val fl = focalLengths?.firstOrNull() ?: 0f
                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

                val equiv35mm = if (sensorSize != null && fl > 0f) {
                    val sensorDiag = kotlin.math.sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
                    if (sensorDiag > 0.01f) {
                        (fl * (43.27f / sensorDiag)).toInt()
                    } else 0
                } else 0

                val title: String
                val subtitle: String

                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    title = "Front Camera"
                    subtitle = "Camera $id • Selfie (RAW)"
                } else {
                    when {
                        equiv35mm in 1..20 || (fl > 0f && fl < 3.0f) -> {
                            title = "Ultra-Wide (0.6x)"
                            subtitle = "Camera $id • ${if (equiv35mm > 0) "${equiv35mm}mm equiv" else "${fl}mm"}"
                        }
                        equiv35mm in 21..35 || (fl >= 3.0f && fl < 6.5f) -> {
                            title = "Wide (1x) • Main"
                            subtitle = "Camera $id • ${if (equiv35mm > 0) "${equiv35mm}mm equiv" else "${fl}mm"}"
                        }
                        equiv35mm > 35 || fl >= 6.5f -> {
                            title = "Telephoto (3x)"
                            subtitle = "Camera $id • ${if (equiv35mm > 0) "${equiv35mm}mm equiv" else "${fl}mm"}"
                        }
                        else -> {
                            title = "Back Camera $id"
                            subtitle = "Camera $id • RAW"
                        }
                    }
                }

                rawCameraIds.add(id)
                cameraChoices.add(CameraChoice(idx, id, title, subtitle))
                Log.i(TAG, "Found RAW camera: id=$id title='$title' fl=${fl}mm equiv=${equiv35mm}mm")
            }

            Log.i(TAG, "Total RAW cameras available: ${rawCameraIds.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed enumerating cameras", e)
        }
    }

    fun start() {
        startBackgroundThread()
        isStarted = true
        backgroundHandler?.post {
            if (isStarted && cameraDevice == null) {
                if (rawCameraIds.isNotEmpty()) {
                    openCameraInternal(rawCameraIds[currentCameraIndex])
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
        if (index !in rawCameraIds.indices || index == currentCameraIndex) return
        currentCameraIndex = index
        backgroundHandler?.post {
            closeCameraInternal()
            if (isStarted) {
                openCameraInternal(rawCameraIds[currentCameraIndex])
            }
        }
    }

    fun switchCamera() {
        if (rawCameraIds.size <= 1) return
        selectCamera((currentCameraIndex + 1) % rawCameraIds.size)
    }

    @SuppressLint("MissingPermission")
    private fun openCameraInternal(cameraId: String) {
        if (!isStarted) return
        if (cameraDevice != null) {
            Log.w(TAG, "Camera already open: ${cameraDevice?.id}")
            return
        }
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            activeCharacteristics = chars
            inspectAndConfigureMetadata(chars)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
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
                    Log.e(TAG, "Camera error: $error on camera $cameraId")
                    camera.close()
                    cameraDevice = null
                    // Retry once after 300ms if error was due to temporary background/busy state
                    if (isStarted && (error == ERROR_CAMERA_DEVICE ||
                                     error == ERROR_CAMERA_DISABLED ||
                                     error == ERROR_CAMERA_IN_USE ||
                                     error == ERROR_MAX_CAMERAS_IN_USE)) {
                        backgroundHandler?.postDelayed({
                            if (isStarted && cameraDevice == null) {
                                Log.i(TAG, "Retrying open camera $cameraId after error $error")
                                openCameraInternal(cameraId)
                            }
                        }, 300)
                    }
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "CameraAccessException opening camera $cameraId: ${e.message}, retrying in 300ms")
            backgroundHandler?.postDelayed({
                if (isStarted && cameraDevice == null) {
                    openCameraInternal(cameraId)
                }
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera $cameraId", e)
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
            supportedFormats.contains(ImageFormat.RAW12) -> ImageFormat.RAW12
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

    private fun computeCalibratedWbGains(chars: CameraCharacteristics): FloatArray {
        val ct = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        if (ct != null) {
            // Standard CIE D65 Daylight illuminant XYZ white point (DNG reference)
            val xn = 0.95047f
            val yn = 1.00000f
            val zn = 1.08883f

            val r00 = ct.getElement(0, 0).toFloat()
            val r01 = ct.getElement(1, 0).toFloat()
            val r02 = ct.getElement(2, 0).toFloat()

            val r10 = ct.getElement(0, 1).toFloat()
            val r11 = ct.getElement(1, 1).toFloat()
            val r12 = ct.getElement(2, 1).toFloat()

            val r20 = ct.getElement(0, 2).toFloat()
            val r21 = ct.getElement(1, 2).toFloat()
            val r22 = ct.getElement(2, 2).toFloat()

            val sensorR = r00 * xn + r01 * yn + r02 * zn
            val sensorG = r10 * xn + r11 * yn + r12 * zn
            val sensorB = r20 * xn + r21 * yn + r22 * zn

            if (sensorR > 0.02f && sensorG > 0.02f && sensorB > 0.02f) {
                val rGain = (sensorG / sensorR).coerceIn(1.2f, 3.8f)
                val bGain = (sensorG / sensorB).coerceIn(1.1f, 3.8f)
                Log.i(TAG, "Calibrated D65 WB gains: R=$rGain, G=1.0, B=$bGain")
                return floatArrayOf(rGain, 1.0f, bGain)
            }
        }
        return floatArrayOf(2.2f, 1.0f, 1.8f)
    }

    private val repeatingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            latestCaptureResult = result
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            if (gains != null) {
                val r = gains.red
                val g = (gains.greenEven + gains.greenOdd) * 0.5f
                val b = gains.blue
                if (g > 0.001f) {
                    val rNorm = r / g
                    val bNorm = b / g
                    // When HAL returns identity [1, 1, 1, 1] (as secondary cameras do for RAW streams),
                    // ignore identity to preserve the sensor's calibrated D65 neutral baseline.
                    val isIdentity = kotlin.math.abs(rNorm - 1.0f) < 0.05f && kotlin.math.abs(bNorm - 1.0f) < 0.05f
                    if (!isIdentity) {
                        renderer.setWhiteBalanceGains(rNorm, 1.0f, bNorm)
                        decorEngine.setWhiteBalanceGains(rNorm, 1.0f, bNorm)
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

            // Process covariance and compute whitening transform
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
            }

            buffer.rewind()
            renderer.queueRawFrame(buffer, image.width, image.height)

            if (canCapture) {
                val rotation = renderer.cameraRotation
                val colorSpaceName = decorEngine.activeColorSpace.name
                val captureLatch = CountDownLatch(1)
                inFlightCaptureLatch = captureLatch
                var savedDngUri: Uri? = null

                // 1. Submit RAW DNG saving immediately (takes ownership of image, closes it in finally)
                captureExecutor.execute {
                    try {
                        savedDngUri = mediaSaver.saveRawDng(
                            image,
                            chars!!,
                            capResult!!,
                            rotation
                        )
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
