package com.decorstr.engine

import java.nio.ByteBuffer

class NativeDecorrelation {

    companion object {
        private const val TAG = "NativeDecorrelation"
        private var isNativeLoaded = false

        init {
            try {
                System.loadLibrary("decorstr_native")
                isNativeLoaded = true
                logI("Native decorstr library loaded successfully")
            } catch (e: Throwable) {
                logW("Native library not loaded, using Kotlin fallback: ${e.message}")
                isNativeLoaded = false
            }
        }

        fun isAvailable(): Boolean = isNativeLoaded

        private fun logI(msg: String) {
            try {
                android.util.Log.i(TAG, msg)
            } catch (t: Throwable) {
                println("[$TAG INFO] $msg")
            }
        }

        private fun logW(msg: String) {
            try {
                android.util.Log.w(TAG, msg)
            } catch (t: Throwable) {
                println("[$TAG WARN] $msg")
            }
        }

        private fun logE(msg: String, throwable: Throwable? = null) {
            try {
                android.util.Log.e(TAG, msg, throwable)
            } catch (t: Throwable) {
                System.err.println("[$TAG ERROR] $msg")
                throwable?.printStackTrace()
            }
        }
    }

    init {
        if (isNativeLoaded) {
            try {
                nativeInit()
            } catch (e: Throwable) {
                logE("Error initializing native engine", e)
            }
        }
    }

    fun reset() {
        if (isNativeLoaded) {
            nativeReset()
        }
    }

    fun processBayer16(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStridePixels: Int,
        bayerPattern: Int,
        blackLevels: FloatArray,
        whiteLevel: Float,
        colorSpace: ColorSpace,
        alphaSmoothing: Float,
        lockTransform: Boolean,
        targetSigma: Float
    ): FloatArray? {
        if (!isNativeLoaded) return null
        return nativeProcessBayer16(
            buffer,
            width,
            height,
            rowStridePixels,
            bayerPattern,
            blackLevels,
            whiteLevel,
            colorSpace.id,
            alphaSmoothing,
            lockTransform,
            targetSigma
        )
    }

    fun processBayer10(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        bayerPattern: Int,
        blackLevels: FloatArray,
        whiteLevel: Float,
        colorSpace: ColorSpace,
        alphaSmoothing: Float,
        lockTransform: Boolean,
        targetSigma: Float
    ): FloatArray? {
        if (!isNativeLoaded) return null
        return nativeProcessBayer10(
            buffer,
            width,
            height,
            rowStrideBytes,
            bayerPattern,
            blackLevels,
            whiteLevel,
            colorSpace.id,
            alphaSmoothing,
            lockTransform,
            targetSigma
        )
    }

    private external fun nativeInit()
    private external fun nativeReset()
    private external fun nativeProcessBayer16(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStridePixels: Int,
        bayerPattern: Int,
        blackLevels: FloatArray,
        whiteLevel: Float,
        colorSpaceMode: Int,
        alphaSmoothing: Float,
        lockTransform: Boolean,
        targetSigma: Float
    ): FloatArray?

    private external fun nativeProcessBayer10(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        bayerPattern: Int,
        blackLevels: FloatArray,
        whiteLevel: Float,
        colorSpaceMode: Int,
        alphaSmoothing: Float,
        lockTransform: Boolean,
        targetSigma: Float
    ): FloatArray?
}
