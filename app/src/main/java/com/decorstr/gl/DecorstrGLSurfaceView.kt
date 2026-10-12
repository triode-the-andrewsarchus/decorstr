package com.decorstr.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector

class DecorstrGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    var onFocusTap: ((x: Float, y: Float) -> Unit)? = null
    var onZoomScale: ((scaleFactor: Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onZoomScale?.invoke(detector.scaleFactor)
            return true
        }
    })

    init {
        // Request OpenGL ES 3.0 context
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress && event.action == MotionEvent.ACTION_UP) {
            onFocusTap?.invoke(event.x, event.y)
        }
        return true
    }
}
