package com.decorstr.engine

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.sqrt

class DecorrelationEngine {

    private val nativeEngine = NativeDecorrelation()

    // Configuration
    @Volatile var activeColorSpace: ColorSpace = ColorSpace.LAB
    @Volatile var alphaSmoothing: Float = 0.15f
    @Volatile var isTransformLocked: Boolean = false
    @Volatile var targetSigma: Float = 0.25f

    // Current transformation outputs for OpenGL shader uniforms
    // Matrix is 3x3 (row-major), Bias is 3D vector
    val currentMatrix = FloatArray(9) { if (it % 4 == 0) 1.0f else 0.0f }
    val currentBias = FloatArray(3) { 0.0f }
    val currentEigenvalues = FloatArray(3) { 1.0f }

    // Fallback Kotlin state (used if native library is unavailable or in JVM unit tests)
    private val smoothCov = FloatArray(9)
    private val smoothMean = FloatArray(3)
    private var prevEigenvectors: FloatArray? = null
    private var isFallbackInitialized = false

    @Synchronized
    fun reset() {
        nativeEngine.reset()
        smoothCov.fill(0f)
        smoothMean.fill(0f)
        prevEigenvectors = null
        isFallbackInitialized = false
        currentMatrix.fill(0f)
        currentMatrix[0] = 1f; currentMatrix[4] = 1f; currentMatrix[8] = 1f
        currentBias.fill(0f)
        currentEigenvalues.fill(1f)
    }

    @Synchronized
    fun getTransformSnapshot(outMatrix: FloatArray, outBias: FloatArray): ColorSpace {
        System.arraycopy(currentMatrix, 0, outMatrix, 0, 9)
        System.arraycopy(currentBias, 0, outBias, 0, 3)
        return activeColorSpace
    }

    // White balance multipliers (R, G, B)
    val whiteBalanceGains = floatArrayOf(2.0f, 1.0f, 1.8f)

    @Synchronized
    fun setWhiteBalanceGains(r: Float, g: Float, b: Float) {
        whiteBalanceGains[0] = 0.8f * whiteBalanceGains[0] + 0.2f * r
        whiteBalanceGains[1] = 0.8f * whiteBalanceGains[1] + 0.2f * g
        whiteBalanceGains[2] = 0.8f * whiteBalanceGains[2] + 0.2f * b
    }

    @Synchronized
    fun resetWhiteBalanceGains(r: Float, g: Float, b: Float) {
        whiteBalanceGains[0] = r
        whiteBalanceGains[1] = g
        whiteBalanceGains[2] = b
    }

    /**
     * Process RAW16 (ImageFormat.RAW_SENSOR) frame.
     * Updates [currentMatrix], [currentBias], and [currentEigenvalues].
     */
    @Synchronized
    fun processRaw16(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStridePixels: Int,
        bayerPattern: Int,
        blackLevels: FloatArray,
        whiteLevel: Float
    ) {
        val result = nativeEngine.processBayer16(
            buffer,
            width,
            height,
            rowStridePixels,
            bayerPattern,
            blackLevels,
            whiteLevel,
            whiteBalanceGains,
            activeColorSpace,
            alphaSmoothing,
            isTransformLocked,
            targetSigma
        )

        if (result != null && result.size >= 15) {
            System.arraycopy(result, 0, currentMatrix, 0, 9)
            System.arraycopy(result, 9, currentBias, 0, 3)
            System.arraycopy(result, 12, currentEigenvalues, 0, 3)
        } else {
            // Kotlin fallback implementation
            processFallbackFromBayer16(
                buffer, width, height, rowStridePixels, bayerPattern, blackLevels, whiteLevel
            )
        }
    }

    /**
     * Process RAW10 (ImageFormat.RAW10) frame.
     */
    @Synchronized
    fun processRaw10(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        bayerPattern: Int,
        blackLevels: FloatArray,
        whiteLevel: Float
    ) {
        val result = nativeEngine.processBayer10(
            buffer,
            width,
            height,
            rowStrideBytes,
            bayerPattern,
            blackLevels,
            whiteLevel,
            whiteBalanceGains,
            activeColorSpace,
            alphaSmoothing,
            isTransformLocked,
            targetSigma
        )

        if (result != null && result.size >= 15) {
            System.arraycopy(result, 0, currentMatrix, 0, 9)
            System.arraycopy(result, 9, currentBias, 0, 3)
            System.arraycopy(result, 12, currentEigenvalues, 0, 3)
        }
    }

    /**
     * Pure-Kotlin covariance, smoothing, and whitening calculation.
     * Directly testable in standard JVM unit tests!
     */
    @Synchronized
    fun updateFromCovariance(
        sampleCov: FloatArray,
        sampleMean: FloatArray,
        colorSpace: ColorSpace = activeColorSpace
    ) {
        if (colorSpace == ColorSpace.RAW_NORMAL) {
            currentMatrix.fill(0f)
            currentMatrix[0] = 1f; currentMatrix[4] = 1f; currentMatrix[8] = 1f
            currentBias.fill(0f)
            return
        }

        val alpha = alphaSmoothing.coerceIn(0.001f, 1.0f)

        if (!isFallbackInitialized) {
            System.arraycopy(sampleCov, 0, smoothCov, 0, 9)
            System.arraycopy(sampleMean, 0, smoothMean, 0, 3)
            isFallbackInitialized = true
        } else if (!isTransformLocked) {
            for (i in 0..8) {
                smoothCov[i] = (1.0f - alpha) * smoothCov[i] + alpha * sampleCov[i]
            }
            for (i in 0..2) {
                smoothMean[i] = (1.0f - alpha) * smoothMean[i] + alpha * sampleMean[i]
            }
        }

        // Eigendecomposition: smoothCov = V * diag(λ) * V^T
        val (lambdas, V) = Matrix3x3.eigenSymmetric(smoothCov, prevEigenvectors)
        prevEigenvectors = V.clone()
        System.arraycopy(lambdas, 0, currentEigenvalues, 0, 3)

        // Whitening scale: s_i = min(16.0, targetSigma / sqrt(max(λ_i, 2e-4)))
        val S = FloatArray(3) { i ->
            (targetSigma / sqrt(max(lambdas[i], 0.0002f))).coerceAtMost(16.0f)
        }

        if (colorSpace == ColorSpace.DIRECT_PCA) {
            // W = S * V^T
            for (i in 0..2) {
                for (j in 0..2) {
                    currentMatrix[i * 3 + j] = S[i] * V[j * 3 + i]
                }
            }
        } else {
            // ZCA Whitening: W = V * S * V^T
            for (j in 0..2) {
                for (k in 0..2) {
                    var s = 0.0f
                    for (m in 0..2) {
                        s += V[j * 3 + m] * S[m] * V[k * 3 + m]
                    }
                    currentMatrix[j * 3 + k] = s
                }
            }
        }

        // Bias calculation: b = mu_target - W * mu_smooth
        if (colorSpace == ColorSpace.RGB0) {
            currentBias.fill(0f)
        } else {
            val targetMean = floatArrayOf(0.5f, 0.5f, 0.5f)
            for (i in 0..2) {
                val wMu = currentMatrix[i * 3 + 0] * smoothMean[0] +
                          currentMatrix[i * 3 + 1] * smoothMean[1] +
                          currentMatrix[i * 3 + 2] * smoothMean[2]
                currentBias[i] = targetMean[i] - wMu
            }
        }
    }

    private fun processFallbackFromBayer16(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStridePixels: Int,
        bayerPattern: Int,
        blackLevels: FloatArray,
        whiteLevel: Float
    ) {
        val shortBuffer = buffer.asShortBuffer()
        val targetSamplesX = 120
        val targetSamplesY = 90
        val stepX = max(2, (width / targetSamplesX) and 1.inv())
        val stepY = max(2, (height / targetSamplesY) and 1.inv())

        val sum = DoubleArray(3)
        val sumSq = DoubleArray(9)
        var count = 0

        val normRange = FloatArray(4) { i -> max(1.0f, whiteLevel - blackLevels[i]) }

        for (y in 0 until (height - 1) step stepY) {
            val row0Off = y * rowStridePixels
            val row1Off = (y + 1) * rowStridePixels

            for (x in 0 until (width - 1) step stepX) {
                val p00Raw = (shortBuffer.get(row0Off + x).toInt() and 0xFFFF).toFloat()
                val p01Raw = (shortBuffer.get(row0Off + x + 1).toInt() and 0xFFFF).toFloat()
                val p10Raw = (shortBuffer.get(row1Off + x).toInt() and 0xFFFF).toFloat()
                val p11Raw = (shortBuffer.get(row1Off + x + 1).toInt() and 0xFFFF).toFloat()

                val p00 = ((p00Raw - blackLevels[0]) / normRange[0]).coerceIn(0f, 1f)
                val p01 = ((p01Raw - blackLevels[1]) / normRange[1]).coerceIn(0f, 1f)
                val p10 = ((p10Raw - blackLevels[2]) / normRange[2]).coerceIn(0f, 1f)
                val p11 = ((p11Raw - blackLevels[3]) / normRange[3]).coerceIn(0f, 1f)

                val r: Float
                val g: Float
                val b: Float
                when (bayerPattern) {
                    0 -> { r = p00; g = (p01 + p10) * 0.5f; b = p11 } // RGGB
                    1 -> { g = (p00 + p11) * 0.5f; r = p01; b = p10 } // GRBG
                    2 -> { g = (p00 + p11) * 0.5f; b = p01; r = p10 } // GBRG
                    else -> { b = p00; g = (p01 + p10) * 0.5f; r = p11 } // BGGR
                }

                val rWb = (r * whiteBalanceGains[0]).coerceIn(0f, 1f)
                val gWb = (g * whiteBalanceGains[1]).coerceIn(0f, 1f)
                val bWb = (b * whiteBalanceGains[2]).coerceIn(0f, 1f)

                if ((rWb > 0.98f && gWb > 0.98f && bWb > 0.98f) || (rWb < 0.01f && gWb < 0.01f && bWb < 0.01f)) {
                    continue
                }

                sum[0] += rWb.toDouble(); sum[1] += gWb.toDouble(); sum[2] += bWb.toDouble()
                sumSq[0] += (rWb * rWb).toDouble(); sumSq[1] += (rWb * gWb).toDouble(); sumSq[2] += (rWb * bWb).toDouble()
                sumSq[3] += (gWb * rWb).toDouble(); sumSq[4] += (gWb * gWb).toDouble(); sumSq[5] += (gWb * bWb).toDouble()
                sumSq[6] += (bWb * rWb).toDouble(); sumSq[7] += (bWb * gWb).toDouble(); sumSq[8] += (bWb * bWb).toDouble()
                count++
            }
        }

        if (count > 32) {
            val invN = 1.0 / count
            val mean = FloatArray(3) { i -> (sum[i] * invN).toFloat() }
            val cov = FloatArray(9)
            for (i in 0..2) {
                for (j in 0..2) {
                    cov[i * 3 + j] = ((sumSq[i * 3 + j] * invN) - (mean[i] * mean[j])).toFloat()
                }
            }
            updateFromCovariance(cov, mean, activeColorSpace)
        }
    }
}
