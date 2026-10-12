package com.decorstr.gl

import android.content.Context
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

        // Fullscreen quad vertices [x, y, u, v]
        private val QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f,        0.0f, 1.0f,
             1.0f, -1.0f,        1.0f, 1.0f,
            -1.0f,  1.0f,        0.0f, 0.0f,
             1.0f,  1.0f,        1.0f, 0.0f
        )
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

    // Frame configuration
    var cfaPattern: Int = 0 // 0=RGGB
    val blackLevels = floatArrayOf(64f, 64f, 64f, 64f)
    var whiteLevel: Float = 1023f
    var gamma: Float = 2.2f
    var contrast: Float = 1.0f

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

        // Draw quad
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
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

        val buffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(QUAD_VERTICES).position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD_VERTICES.size * 4, buffer, GLES30.GL_STATIC_DRAW)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, 0)

        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, 2 * 4)

        GLES30.glBindVertexArray(0)
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
