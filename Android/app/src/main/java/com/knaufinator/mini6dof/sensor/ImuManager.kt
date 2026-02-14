package com.knaufinator.mini6dof.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.sqrt

data class ImuData(
    val roll: Float = 0f,     // degrees, phone tilt left/right
    val pitch: Float = 0f,    // degrees, phone tilt forward/back
    val yaw: Float = 0f,      // degrees, phone rotation around vertical
    val accelX: Float = 0f,   // m/s², lateral
    val accelY: Float = 0f,   // m/s², longitudinal
    val accelZ: Float = 0f,   // m/s², vertical
    val gyroX: Float = 0f,    // rad/s
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f
)

/**
 * Manages phone accelerometer and gyroscope sensors.
 * Computes tilt angles from gravity vector for roll/pitch,
 * integrates gyroscope Z for yaw with decay.
 */
class ImuManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _imuData = MutableStateFlow(ImuData())
    val imuData: StateFlow<ImuData> = _imuData.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    val hasAccelerometer: Boolean get() = accelerometer != null
    val hasGyroscope: Boolean get() = gyroscope != null

    // Current sensor values
    private var accel = FloatArray(3)
    private var gyro = FloatArray(3)
    private var yawAngle = 0f
    private var lastGyroTimestamp = 0L

    // Reference orientation (zeroed when starting)
    private var refRoll = 0f
    private var refPitch = 0f
    private var hasReference = false

    // Low-pass filter coefficient for accelerometer noise
    private val alpha = 0.8f

    fun start() {
        if (_isActive.value) return
        hasReference = false
        yawAngle = 0f
        lastGyroTimestamp = 0L

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        _isActive.value = true
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        _isActive.value = false
        _imuData.value = ImuData()
    }

    fun resetReference() {
        hasReference = false
        yawAngle = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Low-pass filter to smooth out vibrations
                accel[0] = alpha * accel[0] + (1 - alpha) * event.values[0]
                accel[1] = alpha * accel[1] + (1 - alpha) * event.values[1]
                accel[2] = alpha * accel[2] + (1 - alpha) * event.values[2]
                updateOrientation()
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyro[0] = event.values[0]
                gyro[1] = event.values[1]
                gyro[2] = event.values[2]

                // Integrate yaw from gyroscope Z axis
                if (lastGyroTimestamp != 0L) {
                    val dt = (event.timestamp - lastGyroTimestamp) * 1e-9f
                    if (dt in 0.001f..0.1f) {
                        yawAngle += gyro[2] * dt * RAD_TO_DEG
                        // Decay yaw back to zero slowly (washout)
                        yawAngle *= 0.995f
                    }
                }
                lastGyroTimestamp = event.timestamp
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateOrientation() {
        // Compute tilt from gravity vector
        // Phone coordinate system: X=right, Y=up(screen), Z=out(screen)
        // When phone is held upright in portrait:
        //   Tilt forward/back → pitch (around X axis)
        //   Tilt left/right → roll (around Y axis)
        val ax = accel[0]
        val ay = accel[1]
        val az = accel[2]

        val rawRoll = atan2(ax, sqrt(ay * ay + az * az)) * RAD_TO_DEG
        val rawPitch = atan2(-ay, sqrt(ax * ax + az * az)) * RAD_TO_DEG

        // Set reference on first reading
        if (!hasReference) {
            refRoll = rawRoll
            refPitch = rawPitch
            hasReference = true
        }

        _imuData.value = ImuData(
            roll = rawRoll - refRoll,
            pitch = rawPitch - refPitch,
            yaw = yawAngle,
            accelX = ax,
            accelY = ay,
            accelZ = az,
            gyroX = gyro[0],
            gyroY = gyro[1],
            gyroZ = gyro[2]
        )
    }

    companion object {
        private const val RAD_TO_DEG = 57.2957795f
    }
}
