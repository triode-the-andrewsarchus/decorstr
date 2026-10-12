package com.decorstr.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

class DecorstrGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    var onFocusTap: ((x: Float, y: Float) -> Unit)? = null
    var onZoomScale: ((scaleFactor: Float) -> Unit)? = null
    var onToggleAspect: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onZoomScale?.invoke(detector.scaleFactor)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onToggleAspect?.invoke()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onFocusTap?.invoke(e.x, e.y)
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
        gestureDetector.onTouchEvent(event)
        return true
    }
}
