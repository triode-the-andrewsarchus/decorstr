package com.decorstr.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import com.decorstr.engine.DecorrelationEngine
import com.decorstr.gl.DecorstrRenderer
import java.nio.ByteBuffer

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

    // Available RAW camera IDs
    private val rawCameraIds = mutableListOf<String>()
    private var currentCameraIndex = 0

    // Lifecycle state
    private var isStarted = false

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
    }

    private fun enumerateCameras() {
        rawCameraIds.clear()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()

                val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                if (hasRaw && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    rawCameraIds.add(id)
                    Log.i(TAG, "Found RAW-capable back camera: $id")
                }
            }

            if (rawCameraIds.isEmpty()) {
                for (id in cameraManager.cameraIdList) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                        rawCameraIds.add(id)
                    }
                }
            }
            Log.i(TAG, "Total RAW cameras available: ${rawCameraIds.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed enumerating cameras", e)
        }
    }

    @Synchronized
    fun start() {
        if (isStarted) return
        isStarted = true
        startBackgroundThread()
        if (rawCameraIds.isNotEmpty()) {
            openCamera(rawCameraIds[currentCameraIndex])
        } else {
            Log.e(TAG, "No RAW-capable cameras available on device!")
        }
    }

    @Synchronized
    fun stop() {
        if (!isStarted) return
        isStarted = false
        closeCamera()
        stopBackgroundThread()
    }

    fun switchCamera() {
        if (rawCameraIds.size <= 1) return
        currentCameraIndex = (currentCameraIndex + 1) % rawCameraIds.size
        closeCamera()
        if (isStarted) {
            openCamera(rawCameraIds[currentCameraIndex])
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String) {
        if (!isStarted) return
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            inspectAndConfigureMetadata(chars)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!isStarted) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error on camera $cameraId")
                    camera.close()
                    cameraDevice = null
                    // Retry once after 500ms if error was due to temporary background/busy state
                    if (isStarted && (error == ERROR_CAMERA_DEVICE || error == ERROR_CAMERA_DISABLED)) {
                        backgroundHandler?.postDelayed({
                            if (isStarted && cameraDevice == null) {
                                openCamera(cameraId)
                            }
                        }, 500)
                    }
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "CameraAccessException opening camera $cameraId: ${e.message}, retrying in 500ms")
            backgroundHandler?.postDelayed({
                if (isStarted && cameraDevice == null) {
                    openCamera(cameraId)
                }
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera $cameraId", e)
        }
    }

    private fun inspectAndConfigureMetadata(chars: CameraCharacteristics) {
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

        Log.i(TAG, "Configured CFA: $activeCfaPattern, BlackLevel: ${activeBlackLevels.contentToString()}, WhiteLevel: $activeWhiteLevel, Orientation: $sensorOrientation, isFront: $isFront")
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
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
            }

            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
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

            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTimestamp >= 1000) {
                val fps = frameCount * 1000.0f / (now - lastFpsTimestamp)
                frameCount = 0
                lastFpsTimestamp = now
                onFrameProcessed?.invoke(fps)
            }
        } finally {
            image.close()
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("DecorstrCameraBg").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
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
