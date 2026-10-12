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

    // Available RAW camera IDs (e.g. main wide, ultra-wide, telephoto)
    private val rawCameraIds = mutableListOf<String>()
    private var currentCameraIndex = 0

    // Sensor metadata
    var selectedRawFormat = ImageFormat.RAW_SENSOR
    var activeCfaPattern = 0
    val activeBlackLevels = floatArrayOf(64f, 64f, 64f, 64f)
    var activeWhiteLevel = 1023f
    var previewSize = Size(4000, 3000)

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

            // Fallback: if no back camera reported RAW, check front or all
            if (rawCameraIds.isEmpty()) {
                for (id in cameraManager.cameraIdList) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                        rawCameraIds.add(id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed enumerating cameras", e)
        }
    }

    fun start() {
        startBackgroundThread()
        if (rawCameraIds.isNotEmpty()) {
            openCamera(rawCameraIds[currentCameraIndex])
        } else {
            Log.e(TAG, "No RAW-capable cameras available on device!")
        }
    }

    fun stop() {
        closeCamera()
        stopBackgroundThread()
    }

    fun switchCamera() {
        if (rawCameraIds.size <= 1) return
        currentCameraIndex = (currentCameraIndex + 1) % rawCameraIds.size
        closeCamera()
        openCamera(rawCameraIds[currentCameraIndex])
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String) {
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            inspectAndConfigureMetadata(chars)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
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

        // 2. Select resolution: S26+ / Samsung sensors have ultra-high (50MP) and binned (12MP)
        val rawSizes = map.getOutputSizes(selectedRawFormat)?.sortedByDescending { it.width * it.height } ?: emptyList()
        Log.i(TAG, "Available RAW sizes: ${rawSizes.joinToString { "${it.width}x${it.height}" }}")

        // Pick the binned (~12MP) size for fluid 30-60 FPS live preview if multi-resolution detected
        previewSize = rawSizes.firstOrNull { (it.width * it.height) <= MAX_PREVIEW_PIXELS }
            ?: rawSizes.lastOrNull()
            ?: Size(4000, 3000)

        Log.i(TAG, "Selected preview size: ${previewSize.width}x${previewSize.height} format: $selectedRawFormat")

        // 3. CFA (Color Filter Arrangement) pattern: 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
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
        activeWhiteLevel = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
        renderer.whiteLevel = activeWhiteLevel

        Log.i(TAG, "Configured CFA: $activeCfaPattern, BlackLevel: ${activeBlackLevels.contentToString()}, WhiteLevel: $activeWhiteLevel")
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        try {
            imageReader?.close()
            // Allocate ImageReader with maxImages = 3 for smooth pipeline
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
                // Continuous 3A controls
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

            // Rewind buffer and send to OpenGL texture
            buffer.rewind()
            renderer.queueRawFrame(buffer, image.width, image.height)

            // Track FPS
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
        backgroundThread = HandlerThread("DecorstrCameraBg").apply {
            start()
            backgroundHandler = Handler(looper)
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
