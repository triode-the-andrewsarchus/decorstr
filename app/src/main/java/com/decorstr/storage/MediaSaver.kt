package com.decorstr.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.ExifInterface
import android.media.Image
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaSaver(private val context: Context) {

    companion object {
        private const val TAG = "MediaSaver"
    }

    /**
     * Saves a 12MP stretched photo to MediaStore (Pictures/Decorstr).
     * Appears immediately in Samsung Gallery and Google Photos.
     */
    fun saveDecorrelatedJpeg(bitmap: Bitmap, presetName: String): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "Decorstr_${presetName}_${timeStamp}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Decorstr")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                Log.i(TAG, "Successfully saved stretched photo to $uri ($filename)")
                return uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving JPEG to MediaStore", e)
                context.contentResolver.delete(uri, null, null)
            }
        }
        return null
    }

    /**
     * Saves raw Bayer sensor frame as a standard Adobe DNG to MediaStore (Pictures/Decorstr).
     */
    fun saveRawDng(
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult,
        cameraRotationDegrees: Int
    ): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "Decorstr_RAW_${timeStamp}.dng"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Decorstr")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    val dngCreator = DngCreator(characteristics, captureResult)
                    val exifOrientation = when ((cameraRotationDegrees + 360) % 360) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }
                    dngCreator.setOrientation(exifOrientation)
                    dngCreator.writeImage(out, image)
                    dngCreator.close()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                Log.i(TAG, "Successfully saved RAW DNG to $uri ($filename)")
                return uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving DNG to MediaStore", e)
                context.contentResolver.delete(uri, null, null)
            }
        }
        return null
    }
}
