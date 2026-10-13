package com.decorstr.engine

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
}

class Matrix3x3(val data: FloatArray = FloatArray(9)) {
    init {
        require(data.size == 9) { "Matrix3x3 must have 9 elements" }
    }

    operator fun get(row: Int, col: Int): Float = data[row * 3 + col]
    operator fun set(row: Int, col: Int, value: Float) { data[row * 3 + col] = value }

    operator fun times(v: Vec3): Vec3 {
        return Vec3(
            data[0] * v.x + data[1] * v.y + data[2] * v.z,
            data[3] * v.x + data[4] * v.y + data[5] * v.z,
            data[6] * v.x + data[7] * v.y + data[8] * v.z
        )
    }

    operator fun times(o: Matrix3x3): Matrix3x3 {
        val res = FloatArray(9)
        for (i in 0..2) {
            for (j in 0..2) {
                var s = 0.0f
                for (k in 0..2) {
                    s += this[i, k] * o[k, j]
                }
                res[i * 3 + j] = s
            }
        }
        return Matrix3x3(res)
    }

    fun transpose(): Matrix3x3 {
        val res = FloatArray(9)
        for (i in 0..2) {
            for (j in 0..2) {
                res[j * 3 + i] = this[i, j]
            }
        }
        return Matrix3x3(res)
    }

    fun determinant(): Float {
        return this[0, 0] * (this[1, 1] * this[2, 2] - this[1, 2] * this[2, 1]) -
               this[0, 1] * (this[1, 0] * this[2, 2] - this[1, 2] * this[2, 0]) +
               this[0, 2] * (this[1, 0] * this[2, 1] - this[1, 1] * this[2, 0])
    }

    fun inverse(): Matrix3x3? {
        val det = determinant()
        if (abs(det) < 1e-7f) return null
        val invDet = 1.0f / det
        val res = FloatArray(9)
        res[0] = (this[1, 1] * this[2, 2] - this[1, 2] * this[2, 1]) * invDet
        res[1] = (this[0, 2] * this[2, 1] - this[0, 1] * this[2, 2]) * invDet
        res[2] = (this[0, 1] * this[1, 2] - this[0, 2] * this[1, 1]) * invDet

        res[3] = (this[1, 2] * this[2, 0] - this[1, 0] * this[2, 2]) * invDet
        res[4] = (this[0, 0] * this[2, 2] - this[0, 2] * this[2, 0]) * invDet
        res[5] = (this[0, 2] * this[1, 0] - this[0, 0] * this[1, 2]) * invDet

        res[6] = (this[1, 0] * this[2, 1] - this[1, 1] * this[2, 0]) * invDet
        res[7] = (this[0, 1] * this[2, 0] - this[0, 0] * this[2, 1]) * invDet
        res[8] = (this[0, 0] * this[1, 1] - this[0, 1] * this[1, 0]) * invDet
        return Matrix3x3(res)
    }

    companion object {
        fun identity(): Matrix3x3 = Matrix3x3(
            floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        )

        fun diagonal(d0: Float, d1: Float, d2: Float): Matrix3x3 = Matrix3x3(
            floatArrayOf(
                d0, 0f, 0f,
                0f, d1, 0f,
                0f, 0f, d2
            )
        )

        /**
         * Solves eigendecomposition of a symmetric 3x3 matrix via Jacobi rotations.
         * Returns Pair of eigenvalues (descending) and eigenvectors (columns of V).
         */
        fun eigenSymmetric(cov: FloatArray, prevV: FloatArray? = null): Pair<FloatArray, FloatArray> {
            val A = Array(3) { FloatArray(3) }
            for (i in 0..2) {
                for (j in 0..2) {
                    A[i][j] = cov[i * 3 + j]
                }
            }

            val V = Array(3) { FloatArray(3) }
            for (i in 0..2) V[i][i] = 1.0f

            for (iter in 0 until 30) {
                var p = 0
                var q = 1
                var maxOff = abs(A[0][1])
                if (abs(A[0][2]) > maxOff) { p = 0; q = 2; maxOff = abs(A[0][2]) }
                if (abs(A[1][2]) > maxOff) { p = 1; q = 2; maxOff = abs(A[1][2]) }

                if (maxOff < 1e-8f) break

                val theta = 0.5f * (A[q][q] - A[p][p]) / A[p][q]
                val t = if (theta >= 0.0f) 1.0f / (theta + sqrt(theta * theta + 1.0f))
                        else -1.0f / (-theta + sqrt(theta * theta + 1.0f))
                val c = 1.0f / sqrt(t * t + 1.0f)
                val s = t * c

                val app = A[p][p]
                val aqq = A[q][q]
                val apq = A[p][q]

                A[p][p] = c * c * app - 2.0f * s * c * apq + s * s * aqq
                A[q][q] = s * s * app + 2.0f * s * c * apq + c * c * aqq
                A[p][q] = 0.0f; A[q][p] = 0.0f

                for (r in 0..2) {
                    if (r != p && r != q) {
                        val arp = A[r][p]
                        val arq = A[r][q]
                        A[r][p] = c * arp - s * arq
                        A[p][r] = A[r][p]
                        A[r][q] = s * arp + c * arq
                        A[q][r] = A[r][q]
                    }
                }

                for (r in 0..2) {
                    val vrp = V[r][p]
                    val vrq = V[r][q]
                    V[r][p] = c * vrp - s * vrq
                    V[r][q] = s * vrp + c * vrq
                }
            }

            val lambdas = floatArrayOf(A[0][0], A[1][1], A[2][2])
            val indices = arrayOf(0, 1, 2)
            indices.sortWith { a, b -> lambdas[b].compareTo(lambdas[a]) }

            val sortedLambdas = FloatArray(3)
            val sortedV = FloatArray(9)
            for (i in 0..2) {
                val idx = indices[i]
                sortedLambdas[i] = max(0.0f, lambdas[idx])
                sortedV[0 * 3 + i] = V[0][idx]
                sortedV[1 * 3 + i] = V[1][idx]
                sortedV[2 * 3 + i] = V[2][idx]
            }

            // Align eigenvector signs against prevV
            if (prevV != null) {
                for (i in 0..2) {
                    val dot = sortedV[0 * 3 + i] * prevV[0 * 3 + i] +
                              sortedV[1 * 3 + i] * prevV[1 * 3 + i] +
                              sortedV[2 * 3 + i] * prevV[2 * 3 + i]
                    if (dot < 0.0f) {
                        sortedV[0 * 3 + i] = -sortedV[0 * 3 + i]
                        sortedV[1 * 3 + i] = -sortedV[1 * 3 + i]
                        sortedV[2 * 3 + i] = -sortedV[2 * 3 + i]
                    }
                }
            }

            return Pair(sortedLambdas, sortedV)
        }
    }
}
