package com.decorstr.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.decorstr.engine.DecorrelationEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DecorstrRenderer(
    private val context: Context,
    val decorEngine: DecorrelationEngine
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "DecorstrRenderer"
    }

    private var programId = 0
    private var vaoId = 0
    private var vboId = 0
    private var rawTextureId = 0

    // Uniform locations
    private var uRawTextureLoc = -1
    private var uTextureSizeLoc = -1
    private var uCfaPatternLoc = -1
    private var uBlackLevelLoc = -1
    private var uWhiteLevelLoc = -1
    private var uDecorMatrixLoc = -1
    private var uDecorBiasLoc = -1
    private var uColorSpaceLoc = -1
    private var uGammaLoc = -1
    private var uContrastLoc = -1
    private var uScaleLoc = -1
    private var uWbGainsLoc = -1

    // Frame configuration
    var cfaPattern: Int = 0 // 0=RGGB
    val blackLevels = floatArrayOf(64f, 64f, 64f, 64f)
    var whiteLevel: Float = 1023f
    val whiteBalanceGains = floatArrayOf(2.0f, 1.0f, 1.8f)
    var gamma: Float = 2.2f
    var contrast: Float = 1.0f

    // Camera orientation & Aspect ratio configuration
    var cameraRotation: Int = 90
    var isFrontCamera: Boolean = false
    var isAspectFill: Boolean = false
    private var isGeometryDirty: Boolean = true
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // Frame synchronization
    private val frameLock = Any()
    private var pendingFrameBuffer: ByteBuffer? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var isNewFrameAvailable = false

    fun queueRawFrame(buffer: ByteBuffer, width: Int, height: Int) {
        synchronized(frameLock) {
            pendingFrameBuffer = buffer
            frameWidth = width
            frameHeight = height
            isNewFrameAvailable = true
        }
    }

    fun setOrientation(rotation: Int, isFront: Boolean) {
        synchronized(frameLock) {
            if (cameraRotation != rotation || isFrontCamera != isFront) {
                cameraRotation = rotation
                isFrontCamera = isFront
                isGeometryDirty = true
            }
        }
    }

    fun toggleAspect() {
        isAspectFill = !isAspectFill
    }

    fun setWhiteBalanceGains(r: Float, g: Float, b: Float) {
        synchronized(frameLock) {
            whiteBalanceGains[0] = 0.8f * whiteBalanceGains[0] + 0.2f * r
            whiteBalanceGains[1] = 0.8f * whiteBalanceGains[1] + 0.2f * g
            whiteBalanceGains[2] = 0.8f * whiteBalanceGains[2] + 0.2f * b
        }
    }

    private var isOffscreenCapturePending = false
    private var offscreenCaptureCallback: ((Bitmap) -> Unit)? = null

    fun requestOffscreenCapture(callback: (Bitmap) -> Unit) {
        synchronized(frameLock) {
            offscreenCaptureCallback = callback
            isOffscreenCapturePending = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        setupShaders()
        setupQuad()
        setupTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (programId == 0) return

        var width = 0
        var height = 0

        // Upload new texture frame if available
        synchronized(frameLock) {
            width = frameWidth
            height = frameHeight
            if (isNewFrameAvailable && pendingFrameBuffer != null && width > 0 && height > 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTextureId)

                // R16UI format for 16-bit RAW Bayer pixels
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D,
                    0,
                    GLES30.GL_R16UI,
                    width,
                    height,
                    0,
                    GLES30.GL_RED_INTEGER,
                    GLES30.GL_UNSIGNED_SHORT,
                    pendingFrameBuffer
                )

                isNewFrameAvailable = false
            }
        }

        // If no camera frame has arrived yet, don't draw uninitialized quad
        if (width <= 0 || height <= 0) {
            return
        }

        if (isGeometryDirty && vboId != 0) {
            updateQuadVbo()
            isGeometryDirty = false
        }

        GLES30.glUseProgram(programId)

        // Bind texture unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTextureId)
        GLES30.glUniform1i(uRawTextureLoc, 0)

        // Bind uniform parameters
        GLES30.glUniform2f(uTextureSizeLoc, width.toFloat(), height.toFloat())
        GLES30.glUniform1i(uCfaPatternLoc, cfaPattern)
        GLES30.glUniform4f(uBlackLevelLoc, blackLevels[0], blackLevels[1], blackLevels[2], blackLevels[3])
        GLES30.glUniform1f(uWhiteLevelLoc, whiteLevel)

        GLES30.glUniformMatrix3fv(uDecorMatrixLoc, 1, true, decorEngine.currentMatrix, 0)
        GLES30.glUniform3fv(uDecorBiasLoc, 1, decorEngine.currentBias, 0)
        GLES30.glUniform1i(uColorSpaceLoc, decorEngine.activeColorSpace.id)
        GLES30.glUniform1f(uGammaLoc, gamma)
        GLES30.glUniform1f(uContrastLoc, contrast)
        GLES30.glUniform3f(uWbGainsLoc, whiteBalanceGains[0], whiteBalanceGains[1], whiteBalanceGains[2])

        // Calculate aspect scale to completely eliminate squishing
        val isRotated = (cameraRotation == 90 || cameraRotation == 270)
        val camW = if (isRotated) height.toFloat() else width.toFloat()
        val camH = if (isRotated) width.toFloat() else height.toFloat()

        var scaleX = 1.0f
        var scaleY = 1.0f

        if (surfaceWidth > 0 && surfaceHeight > 0 && camW > 0 && camH > 0) {
            val camAspect = camW / camH
            val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()

            if (isAspectFill) {
                if (camAspect > surfaceAspect) {
                    scaleX = camAspect / surfaceAspect
                    scaleY = 1.0f
                } else {
                    scaleX = 1.0f
                    scaleY = surfaceAspect / camAspect
                }
            } else {
                // Aspect Fit (Letterbox)
                if (camAspect > surfaceAspect) {
                    scaleX = 1.0f
                    scaleY = surfaceAspect / camAspect
                } else {
                    scaleX = camAspect / surfaceAspect
                    scaleY = 1.0f
                }
            }
        }

        GLES30.glUniform2f(uScaleLoc, scaleX, scaleY)

        // Draw quad
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        // Process full-resolution offscreen capture if requested
        val offscreenCb: ((Bitmap) -> Unit)? = synchronized(frameLock) {
            if (isOffscreenCapturePending) {
                val cb = offscreenCaptureCallback
                offscreenCaptureCallback = null
                isOffscreenCapturePending = false
                cb
            } else {
                null
            }
        }

        if (offscreenCb != null && width > 0 && height > 0) {
            renderOffscreenSnapshot(width, height, offscreenCb)
        }
    }

    private fun renderOffscreenSnapshot(rawWidth: Int, rawHeight: Int, callback: (Bitmap) -> Unit) {
        val isRotated = (cameraRotation == 90 || cameraRotation == 270)
        val targetW = if (isRotated) rawHeight else rawWidth
        val targetH = if (isRotated) rawWidth else rawHeight

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        val fboId = fbos[0]

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        val colorTexId = texs[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, targetW, targetH, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, colorTexId, 0
        )

        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Offscreen FBO incomplete status: $status")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteTextures(1, texs, 0)
            GLES30.glDeleteFramebuffers(1, fbos, 0)
            return
        }

        GLES30.glViewport(0, 0, targetW, targetH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(programId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTextureId)
        GLES30.glUniform1i(uRawTextureLoc, 0)

        GLES30.glUniform2f(uTextureSizeLoc, rawWidth.toFloat(), rawHeight.toFloat())
        GLES30.glUniform1i(uCfaPatternLoc, cfaPattern)
        GLES30.glUniform4f(uBlackLevelLoc, blackLevels[0], blackLevels[1], blackLevels[2], blackLevels[3])
        GLES30.glUniform1f(uWhiteLevelLoc, whiteLevel)
        GLES30.glUniformMatrix3fv(uDecorMatrixLoc, 1, true, decorEngine.currentMatrix, 0)
        GLES30.glUniform3fv(uDecorBiasLoc, 1, decorEngine.currentBias, 0)
        GLES30.glUniform1i(uColorSpaceLoc, decorEngine.activeColorSpace.id)
        GLES30.glUniform1f(uGammaLoc, gamma)
        GLES30.glUniform1f(uContrastLoc, contrast)
        GLES30.glUniform3f(uWbGainsLoc, whiteBalanceGains[0], whiteBalanceGains[1], whiteBalanceGains[2])

        // 1.0f scale fills the 12MP FBO completely
        GLES30.glUniform2f(uScaleLoc, 1.0f, 1.0f)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        // Read pixels
        val pixelBuffer = ByteBuffer.allocateDirect(targetW * targetH * 4)
            .order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, targetW, targetH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuffer)

        // Restore viewport and framebuffer
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glDeleteTextures(1, texs, 0)
        GLES30.glDeleteFramebuffers(1, fbos, 0)

        // Convert to Bitmap and invert vertically (glReadPixels is bottom-to-top)
        val intBuffer = pixelBuffer.asIntBuffer()
        val pixels = IntArray(targetW * targetH)
        intBuffer.get(pixels)

        val rowBuffer = IntArray(targetW)
        for (y in 0 until targetH / 2) {
            val top = y * targetW
            val bottom = (targetH - 1 - y) * targetW
            System.arraycopy(pixels, top, rowBuffer, 0, targetW)
            System.arraycopy(pixels, bottom, pixels, top, targetW)
            System.arraycopy(rowBuffer, 0, pixels, bottom, targetW)
        }

        val bitmap = Bitmap.createBitmap(pixels, targetW, targetH, Bitmap.Config.ARGB_8888)
        callback(bitmap)
    }

    private fun setupShaders() {
        try {
            val vertSource = loadAsset("shaders/decorstr.vert")
            val fragSource = loadAsset("shaders/decorstr.frag")

            val vertShader = compileShader(GLES30.GL_VERTEX_SHADER, vertSource)
            if (vertShader == 0) return

            val fragShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSource)
            if (fragShader == 0) return

            programId = GLES30.glCreateProgram()
            GLES30.glAttachShader(programId, vertShader)
            GLES30.glAttachShader(programId, fragShader)
            GLES30.glLinkProgram(programId)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(programId)
                Log.e(TAG, "Shader program link failed: $log")
                GLES30.glDeleteProgram(programId)
                programId = 0
                return
            }

            // Query uniforms
            uRawTextureLoc = GLES30.glGetUniformLocation(programId, "u_RawTexture")
            uTextureSizeLoc = GLES30.glGetUniformLocation(programId, "u_TextureSize")
            uCfaPatternLoc = GLES30.glGetUniformLocation(programId, "u_CfaPattern")
            uBlackLevelLoc = GLES30.glGetUniformLocation(programId, "u_BlackLevel")
            uWhiteLevelLoc = GLES30.glGetUniformLocation(programId, "u_WhiteLevel")
            uDecorMatrixLoc = GLES30.glGetUniformLocation(programId, "u_DecorrelationMatrix")
            uDecorBiasLoc = GLES30.glGetUniformLocation(programId, "u_DecorrelationBias")
            uColorSpaceLoc = GLES30.glGetUniformLocation(programId, "u_ColorSpace")
            uGammaLoc = GLES30.glGetUniformLocation(programId, "u_Gamma")
            uContrastLoc = GLES30.glGetUniformLocation(programId, "u_Contrast")
            uScaleLoc = GLES30.glGetUniformLocation(programId, "u_Scale")
            uWbGainsLoc = GLES30.glGetUniformLocation(programId, "u_WbGains")
            Log.i(TAG, "Shaders linked successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up shaders", e)
        }
    }

    private fun setupQuad() {
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]
        GLES30.glBindVertexArray(vaoId)

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vboId = vbos[0]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 16 * 4, null, GLES30.GL_DYNAMIC_DRAW)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, 0)

        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, 2 * 4)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        updateQuadVbo()
        isGeometryDirty = false
    }

    private fun updateQuadVbo() {
        val uvs = when (cameraRotation) {
            90 -> floatArrayOf(
                1f, 1f, // BL
                1f, 0f, // BR
                0f, 1f, // TL
                0f, 0f  // TR
            )
            180 -> floatArrayOf(
                1f, 0f, // BL
                0f, 0f, // BR
                1f, 1f, // TL
                0f, 1f  // TR
            )
            270 -> floatArrayOf(
                0f, 0f, // BL
                0f, 1f, // BR
                1f, 0f, // TL
                1f, 1f  // TR
            )
            else -> floatArrayOf(
                0f, 1f, // BL
                1f, 1f, // BR
                0f, 0f, // TL
                1f, 0f  // TR
            )
        }

        // If front camera, mirror horizontally
        if (isFrontCamera) {
            val tempU0 = uvs[0]; val tempV0 = uvs[1]
            uvs[0] = uvs[2]; uvs[1] = uvs[3]
            uvs[2] = tempU0; uvs[3] = tempV0

            val tempU2 = uvs[4]; val tempV2 = uvs[5]
            uvs[4] = uvs[6]; uvs[5] = uvs[7]
            uvs[6] = tempU2; uvs[7] = tempV2
        }

        val vertices = floatArrayOf(
            -1.0f, -1.0f, uvs[0], uvs[1],
             1.0f, -1.0f, uvs[2], uvs[3],
            -1.0f,  1.0f, uvs[4], uvs[5],
             1.0f,  1.0f, uvs[6], uvs[7]
        )

        vertexBuffer.clear()
        vertexBuffer.put(vertices).position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertices.size * 4, vertexBuffer)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun setupTexture() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        rawTextureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Initialize with a safe 2x2 placeholder texture
        val dummy = ByteBuffer.allocateDirect(2 * 2 * 2).order(ByteOrder.nativeOrder())
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R16UI,
            2,
            2,
            0,
            GLES30.GL_RED_INTEGER,
            GLES30.GL_UNSIGNED_SHORT,
            dummy
        )
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compilation error: $log")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun loadAsset(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }
}
