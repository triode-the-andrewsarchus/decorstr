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
        wbGains: FloatArray,
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
            wbGains,
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
        wbGains: FloatArray,
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
            wbGains,
            colorSpace.id,
            alphaSmoothing,
            lockTransform,
            targetSigma
        )
    }

    fun unpackRaw10To16(
        raw10Buffer: ByteBuffer,
        out16Buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int
    ): Boolean {
        if (isNativeLoaded && raw10Buffer.isDirect && out16Buffer.isDirect) {
            if (nativeUnpackRaw10To16(raw10Buffer, out16Buffer, width, height, rowStrideBytes)) {
                return true
            }
        }
        val minSrcBytes = (height - 1) * rowStrideBytes + (width / 4) * 5
        val minDstShorts = width * height
        if (width <= 0 || height <= 0 || raw10Buffer.capacity() < minSrcBytes || out16Buffer.capacity() < minDstShorts * 2) {
            return false
        }
        val dstShorts = out16Buffer.asShortBuffer()
        val alignedWidth = width and 3.inv()
        for (y in 0 until height) {
            val rowOff = y * rowStrideBytes
            val dstRowOff = y * width
            var byteIdx = rowOff
            var x = 0
            while (x < alignedWidth) {
                val b0 = raw10Buffer.get(byteIdx + 0).toInt() and 0xFF
                val b1 = raw10Buffer.get(byteIdx + 1).toInt() and 0xFF
                val b2 = raw10Buffer.get(byteIdx + 2).toInt() and 0xFF
                val b3 = raw10Buffer.get(byteIdx + 3).toInt() and 0xFF
                val b4 = raw10Buffer.get(byteIdx + 4).toInt() and 0xFF
                dstShorts.put(dstRowOff + x + 0, ((b0 shl 2) or ((b4 ushr 0) and 0x03)).toShort())
                dstShorts.put(dstRowOff + x + 1, ((b1 shl 2) or ((b4 ushr 2) and 0x03)).toShort())
                dstShorts.put(dstRowOff + x + 2, ((b2 shl 2) or ((b4 ushr 4) and 0x03)).toShort())
                dstShorts.put(dstRowOff + x + 3, ((b3 shl 2) or ((b4 ushr 6) and 0x03)).toShort())
                x += 4
                byteIdx += 5
            }
            while (x < width) {
                dstShorts.put(dstRowOff + x, 0)
                x++
            }
        }
        return true
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
        wbGains: FloatArray,
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
        wbGains: FloatArray,
        colorSpaceMode: Int,
        alphaSmoothing: Float,
        lockTransform: Boolean,
        targetSigma: Float
    ): FloatArray?

    private external fun nativeUnpackRaw10To16(
        raw10Buffer: ByteBuffer,
        out16Buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int
    ): Boolean
}
