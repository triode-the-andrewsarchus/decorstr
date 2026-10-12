package com.decorstr.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Modern touch-to-focus reticle overlay.
 * Animates in on tap, updates color on focus lock, and fades out cleanly.
 */
class FocusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var fadeOutRunnable: Runnable? = null

    private val colorFocusing = Color.WHITE
    private val colorLocked = Color.parseColor("#00E5FF")   // Cyan
    private val colorFailed = Color.parseColor("#FF5252")   // Red

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
        strokeCap = Paint.Cap.ROUND
        color = colorFocusing
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
        strokeCap = Paint.Cap.ROUND
        color = colorFocusing
    }

    init {
        visibility = GONE
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    fun showAt(targetX: Float, targetY: Float) {
        fadeOutRunnable?.let { mainHandler.removeCallbacks(it) }
        animate().cancel()

        ringPaint.color = colorFocusing
        tickPaint.color = colorFocusing

        val halfW = (if (width > 0) width else dpToPx(80f).toInt()) / 2f
        val halfH = (if (height > 0) height else dpToPx(80f).toInt()) / 2f

        translationX = targetX - halfW
        translationY = targetY - halfH
        scaleX = 1.35f
        scaleY = 1.35f
        alpha = 0f
        visibility = VISIBLE

        animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        invalidate()
    }

    fun onFocusLocked(success: Boolean) {
        val targetColor = if (success) colorLocked else colorFailed
        ringPaint.color = targetColor
        tickPaint.color = targetColor
        invalidate()

        if (success) {
            // Subtle pop animation on lock
            val scaleUpX = PropertyValuesHolder.ofFloat(SCALE_X, 1.0f, 1.08f, 1.0f)
            val scaleUpY = PropertyValuesHolder.ofFloat(SCALE_Y, 1.0f, 1.08f, 1.0f)
            ObjectAnimator.ofPropertyValuesHolder(this, scaleUpX, scaleUpY).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                start()
            }
        }

        // Schedule fade out
        fadeOutRunnable?.let { mainHandler.removeCallbacks(it) }
        fadeOutRunnable = Runnable {
            animate()
                .alpha(0f)
                .setDuration(300)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = GONE
                        animate().setListener(null)
                    }
                })
                .start()
        }
        mainHandler.postDelayed(fadeOutRunnable!!, 1000)
    }

    fun hide() {
        fadeOutRunnable?.let { mainHandler.removeCallbacks(it) }
        animate().cancel()
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(cx, cy) - dpToPx(8f)).coerceAtLeast(dpToPx(10f))

        // Outer focus circle
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // 4 focus crosshair tick marks
        val tickLength = dpToPx(6f)
        val tickGap = dpToPx(4f)

        // Top tick
        canvas.drawLine(cx, cy - radius - tickGap - tickLength, cx, cy - radius - tickGap, tickPaint)
        // Bottom tick
        canvas.drawLine(cx, cy + radius + tickGap, cx, cy + radius + tickGap + tickLength, tickPaint)
        // Left tick
        canvas.drawLine(cx - radius - tickGap - tickLength, cy, cx - radius - tickGap, cy, tickPaint)
        // Right tick
        canvas.drawLine(cx + radius + tickGap, cy, cx + radius + tickGap + tickLength, cy, tickPaint)
    }
}
