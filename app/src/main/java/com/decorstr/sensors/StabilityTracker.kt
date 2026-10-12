package com.decorstr.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Tracks physical device stability using high-rate IMU gyroscope.
 * Outputs a continuous stability factor [0.0 (fast motion) to 1.0 (rock solid)].
 */
class StabilityTracker(
    context: Context,
    private val onStabilityChanged: ((stability: Float, isStable: Boolean) -> Unit)? = null
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile var stability: Float = 0.0f
        private set

    @Volatile var isStable: Boolean = false
        private set

    private var filteredAngularSpeed = 0.5f

    // Angular velocity thresholds in radians/second
    // Typical hand tremor: ~0.02 - 0.04 rad/s
    // Slow intentional pan: ~0.10 - 0.20 rad/s
    private val motionThresholdHigh = 0.12f
    private val motionThresholdLow = 0.030f

    fun start() {
        gyro?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            val wx = event.values[0]
            val wy = event.values[1]
            val wz = event.values[2]
            val rawSpeed = sqrt(wx * wx + wy * wy + wz * wz)

            // Fast attack on motion, smooth decay back to stillness
            val alpha = if (rawSpeed > filteredAngularSpeed) 0.5f else 0.15f
            filteredAngularSpeed = (1.0f - alpha) * filteredAngularSpeed + alpha * rawSpeed

            // Compute normalized stability score [0.0, 1.0]
            val score = ((motionThresholdHigh - filteredAngularSpeed) / (motionThresholdHigh - motionThresholdLow))
                .coerceIn(0.0f, 1.0f)

            stability = score
            val stableNow = score >= 0.5f
            if (stableNow != isStable) {
                isStable = stableNow
            }
            onStabilityChanged?.invoke(score, isStable)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
