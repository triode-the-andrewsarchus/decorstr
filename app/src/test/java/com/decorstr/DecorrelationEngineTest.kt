package com.decorstr

import com.decorstr.engine.ColorSpace
import com.decorstr.engine.DecorrelationEngine
import com.decorstr.engine.Matrix3x3
import com.decorstr.engine.Vec3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DecorrelationEngineTest {

    @Test
    fun testMatrix3x3Multiplication() {
        val m1 = Matrix3x3(
            floatArrayOf(
                1f, 2f, 3f,
                4f, 5f, 6f,
                7f, 8f, 9f
            )
        )
        val eye = Matrix3x3.identity()
        val prod = m1 * eye
        for (i in 0..8) {
            assertEquals(m1.data[i], prod.data[i], 1e-6f)
        }
    }

    @Test
    fun testSymmetricEigendecomposition() {
        // Real symmetric positive definite matrix
        // Covariance with known eigenvalues
        val cov = floatArrayOf(
            4.0f, 1.0f, 0.5f,
            1.0f, 3.0f, 0.2f,
            0.5f, 0.2f, 2.0f
        )

        val (lambdas, V) = Matrix3x3.eigenSymmetric(cov)

        // 1. Eigenvalues must be sorted descending and positive
        assertTrue("Eigenvalues should be descending", lambdas[0] >= lambdas[1] && lambdas[1] >= lambdas[2])
        assertTrue("Eigenvalues should be positive", lambdas[2] > 0.0f)

        // 2. Orthonormality check: V^T * V = I
        val vMat = Matrix3x3(V)
        val vTrans = vMat.transpose()
        val vProd = vTrans * vMat
        val eye = Matrix3x3.identity()
        for (i in 0..8) {
            assertEquals("V must be orthogonal", eye.data[i], vProd.data[i], 1e-4f)
        }

        // 3. Spectral decomposition check: V * diag(λ) * V^T = Cov
        val lambdaMat = Matrix3x3.diagonal(lambdas[0], lambdas[1], lambdas[2])
        val reconstructed = vMat * lambdaMat * vTrans
        for (i in 0..8) {
            assertEquals("Reconstructed covariance should match original", cov[i], reconstructed.data[i], 1e-4f)
        }
    }

    @Test
    fun testEigenvectorSignAlignment() {
        val cov = floatArrayOf(
            5.0f, 0.5f, 0.0f,
            0.5f, 2.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )

        val (_, v1) = Matrix3x3.eigenSymmetric(cov)

        // Second run passing v1 as prevV: signs should remain aligned (dot product > 0)
        val (_, v2) = Matrix3x3.eigenSymmetric(cov, v1)
        for (i in 0..2) {
            val dot = v1[0 * 3 + i] * v2[0 * 3 + i] +
                      v1[1 * 3 + i] * v2[1 * 3 + i] +
                      v1[2 * 3 + i] * v2[2 * 3 + i]
            assertTrue("Eigenvector sign must be positive dot product with previous frame", dot > 0.99f)
        }
    }

    @Test
    fun testWhiteningTransformAndCentering() {
        val engine = DecorrelationEngine()
        engine.alphaSmoothing = 1.0f // Instant for test
        engine.targetSigma = 0.25f

        val sampleCov = floatArrayOf(
            0.10f, 0.05f, 0.02f,
            0.05f, 0.08f, 0.03f,
            0.02f, 0.03f, 0.06f
        )
        val sampleMean = floatArrayOf(0.40f, 0.50f, 0.35f)

        engine.updateFromCovariance(sampleCov, sampleMean, ColorSpace.RGB_ZCA)

        val W = Matrix3x3(engine.currentMatrix)
        val b = Vec3(engine.currentBias[0], engine.currentBias[1], engine.currentBias[2])

        // Verify transformed mean maps to target mean (0.5, 0.5, 0.5)
        val meanVec = Vec3(sampleMean[0], sampleMean[1], sampleMean[2])
        val transformedMean = (W * meanVec) + b
        assertEquals(0.5f, transformedMean.x, 1e-4f)
        assertEquals(0.5f, transformedMean.y, 1e-4f)
        assertEquals(0.5f, transformedMean.z, 1e-4f)

        // Verify whitening property: W * Cov * W^T = sigma^2 * I
        val covMat = Matrix3x3(sampleCov)
        val whitenedCov = W * covMat * W.transpose()

        val expectedVar = engine.targetSigma * engine.targetSigma
        assertEquals("Whitened channel 0 variance", expectedVar, whitenedCov[0, 0], 1e-3f)
        assertEquals("Whitened channel 1 variance", expectedVar, whitenedCov[1, 1], 1e-3f)
        assertEquals("Whitened channel 2 variance", expectedVar, whitenedCov[2, 2], 1e-3f)

        // Off-diagonals should be decorrelated (near zero)
        assertEquals("Cross-correlation 0-1 should be 0", 0.0f, whitenedCov[0, 1], 1e-3f)
        assertEquals("Cross-correlation 0-2 should be 0", 0.0f, whitenedCov[0, 2], 1e-3f)
        assertEquals("Cross-correlation 1-2 should be 0", 0.0f, whitenedCov[1, 2], 1e-3f)
    }

    @Test
    fun testTemporalSmoothingAndLock() {
        val engine = DecorrelationEngine()
        engine.alphaSmoothing = 0.20f // 20% update per step

        val cov1 = floatArrayOf(
            0.1f, 0f, 0f,
            0f, 0.1f, 0f,
            0f, 0f, 0.1f
        )
        val mean1 = floatArrayOf(0.5f, 0.5f, 0.5f)

        engine.updateFromCovariance(cov1, mean1, ColorSpace.RGB_ZCA)
        val initialBias0 = engine.currentBias[0]

        // Abrupt change in scene
        val cov2 = floatArrayOf(
            0.5f, 0f, 0f,
            0f, 0.5f, 0f,
            0f, 0f, 0.5f
        )
        val mean2 = floatArrayOf(0.8f, 0.8f, 0.8f)

        engine.updateFromCovariance(cov2, mean2, ColorSpace.RGB_ZCA)
        // Eigenvalues should not jump immediately from 0.1 to 0.5, but be smoothed:
        // Expected smoothed value: 0.8 * 0.1 + 0.2 * 0.5 = 0.18
        assertEquals(0.18f, engine.currentEigenvalues[0], 1e-3f)

        // Lock transform
        engine.isTransformLocked = true
        val lockedMatrix0 = engine.currentMatrix[0]

        // Even with huge change, locked transform must stay identical
        val cov3 = floatArrayOf(
            1.0f, 0f, 0f,
            0f, 1.0f, 0f,
            0f, 0f, 1.0f
        )
        engine.updateFromCovariance(cov3, floatArrayOf(0.1f, 0.1f, 0.1f), ColorSpace.RGB_ZCA)
        assertEquals("Matrix must not change when locked", lockedMatrix0, engine.currentMatrix[0], 1e-6f)
    }

    @Test
    fun testMatrix3x3InverseAndDeterminant() {
        val m = Matrix3x3(
            floatArrayOf(
                2.0f, 1.0f, 0.0f,
                0.0f, 3.0f, 1.0f,
                1.0f, 0.0f, 2.0f
            )
        )
        // det = 2*(6-0) - 1*(0-1) + 0 = 12 + 1 = 13
        assertEquals(13.0f, m.determinant(), 1e-5f)

        val inv = m.inverse()
        assertNotNull("Inverse should not be null", inv)
        val prod = m * inv!!
        val eye = Matrix3x3.identity()
        for (i in 0..8) {
            assertEquals("M * M^-1 must be identity", eye.data[i], prod.data[i], 1e-5f)
        }
    }

    @Test
    fun testGenericWhiteBalanceD65() {
        // Standard D65 white point: X=0.95047, Y=1.00000, Z=1.08883
        val d65 = Vec3(0.95047f, 1.00000f, 1.08883f)

        // Typical generic Bayer color transform mapping XYZ -> Sensor
        // where Green has ~2x quantum efficiency relative to Red and Blue
        val ct = Matrix3x3(
            floatArrayOf(
                0.55f, 0.05f, -0.05f,
                -0.10f, 1.15f, 0.05f,
                -0.05f, 0.10f, 0.65f
            )
        )

        val resp = ct * d65
        assertTrue("Sensor R must be positive", resp.x > 0f)
        assertTrue("Sensor G must be positive", resp.y > 0f)
        assertTrue("Sensor B must be positive", resp.z > 0f)

        val rGain = resp.y / resp.x
        val bGain = resp.y / resp.z

        // R gain should be ~1.8 - 2.5, B gain ~1.2 - 2.0
        assertTrue("R gain should be within realistic Bayer range", rGain in 1.2f..3.5f)
        assertTrue("B gain should be within realistic Bayer range", bGain in 1.1f..3.5f)
    }

    @Test
    fun testRightHandedEigenvectorsDeterminant() {
        val cov = floatArrayOf(
            3.0f, 0.8f, 0.4f,
            0.8f, 2.0f, 0.3f,
            0.4f, 0.3f, 1.0f
        )
        // Pass a prevV with negative determinant to force a reflection before the det check
        val reflectedPrevV = floatArrayOf(
            1f, 0f,  0f,
            0f, 1f,  0f,
            0f, 0f, -1f
        )
        val (_, V) = Matrix3x3.eigenSymmetric(cov, reflectedPrevV)
        val vMat = Matrix3x3(V)
        assertEquals("Eigenvector matrix must be right-handed (det = +1)", 1.0f, vMat.determinant(), 1e-4f)
    }
}

