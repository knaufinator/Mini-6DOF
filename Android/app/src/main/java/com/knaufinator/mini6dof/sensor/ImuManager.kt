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

enum class FusionMode { ROTATION_VECTOR, COMPLEMENTARY, NONE }

data class ImuData(
    val roll: Float = 0f,          // degrees, tilt left/right
    val pitch: Float = 0f,         // degrees, tilt forward/back
    val yaw: Float = 0f,           // degrees, rotation about vertical
    val accelX: Float = 0f,        // m/s², raw accelerometer
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val gyroX: Float = 0f,         // rad/s, raw gyroscope
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val sampleRateHz: Float = 0f,  // measured accel delivery rate
    val sampleCount: Long = 0L,
    val fusionMode: FusionMode = FusionMode.NONE
)

/**
 * Manages phone accelerometer, gyroscope, and game rotation vector sensors.
 * Targets 100 Hz sample rate. Uses TYPE_GAME_ROTATION_VECTOR for orientation
 * when available (hardware sensor fusion, no magnetometer drift), falls back
 * to a complementary filter combining accel + gyro.
 */
class ImuManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gameRotVec = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val _imuData = MutableStateFlow(ImuData())
    val imuData: StateFlow<ImuData> = _imuData.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    val hasAccelerometer: Boolean get() = accelerometer != null
    val hasGyroscope: Boolean get() = gyroscope != null
    val hasRotationVector: Boolean get() = gameRotVec != null

    val fusionMode: FusionMode get() = when {
        gameRotVec != null -> FusionMode.ROTATION_VECTOR
        gyroscope != null  -> FusionMode.COMPLEMENTARY
        else               -> FusionMode.NONE
    }

    // Raw sensor values
    private var accel = FloatArray(3)
    private var gyro = FloatArray(3)

    // Orientation state
    private var orientRoll = 0f
    private var orientPitch = 0f
    private var orientYaw = 0f

    // Complementary filter fallback
    private var lastGyroTimestamp = 0L
    private val COMP_ALPHA = 0.98f

    // Reference orientation (zeroed on start / reset)
    private var refRoll = 0f
    private var refPitch = 0f
    private var refYaw = 0f
    private var hasReference = false

    // Sample rate tracking
    private var sampleCount = 0L
    private var lastAccelNs = 0L
    private var rateSum = 0f
    private var rateSamples = 0
    private var measuredRate = 0f

    // Rotation vector helpers
    private val rotMat = FloatArray(9)
    private val orientArr = FloatArray(3)

    fun start() {
        if (_isActive.value) return
        resetInternalState()

        accelerometer?.let {
            sensorManager.registerListener(this, it, TARGET_PERIOD_US)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, TARGET_PERIOD_US)
        }
        gameRotVec?.let {
            sensorManager.registerListener(this, it, TARGET_PERIOD_US)
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
        orientYaw = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccel(event)
            Sensor.TYPE_GYROSCOPE     -> handleGyro(event)
            Sensor.TYPE_GAME_ROTATION_VECTOR -> handleRotationVector(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Accelerometer ──────────────────────────────────────────────────

    private fun handleAccel(event: SensorEvent) {
        accel[0] = event.values[0]
        accel[1] = event.values[1]
        accel[2] = event.values[2]

        // Measure actual delivery rate (averaged over 50 samples)
        if (lastAccelNs != 0L) {
            val dt = (event.timestamp - lastAccelNs) * 1e-9f
            if (dt in 0.0005f..1.0f) {
                rateSum += 1f / dt
                rateSamples++
                if (rateSamples >= 50) {
                    measuredRate = rateSum / rateSamples
                    rateSum = 0f
                    rateSamples = 0
                }
            }
        }
        lastAccelNs = event.timestamp
        sampleCount++

        // Complementary filter orientation (only when no rotation vector sensor)
        if (gameRotVec == null) {
            updateComplementaryFilter(event.timestamp)
        }

        emitData()
    }

    // ── Gyroscope ──────────────────────────────────────────────────────

    private fun handleGyro(event: SensorEvent) {
        gyro[0] = event.values[0]
        gyro[1] = event.values[1]
        gyro[2] = event.values[2]
        lastGyroTimestamp = event.timestamp
    }

    // ── Game Rotation Vector (hardware sensor fusion) ──────────────────

    private fun handleRotationVector(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotMat, event.values)
        SensorManager.getOrientation(rotMat, orientArr)

        // orientArr: [azimuth/yaw, pitch, roll] in radians
        val rawYaw   = orientArr[0] * RAD_TO_DEG
        val rawPitch = orientArr[1] * RAD_TO_DEG
        val rawRoll  = orientArr[2] * RAD_TO_DEG

        if (!hasReference) {
            refRoll  = rawRoll
            refPitch = rawPitch
            refYaw   = rawYaw
            hasReference = true
        }

        orientRoll  = rawRoll  - refRoll
        orientPitch = rawPitch - refPitch
        orientYaw   = rawYaw   - refYaw

        // Wrap yaw to [-180, 180]
        if (orientYaw >  180f) orientYaw -= 360f
        if (orientYaw < -180f) orientYaw += 360f

        emitData()
    }

    // ── Complementary filter fallback ──────────────────────────────────

    private fun updateComplementaryFilter(timestampNs: Long) {
        val ax = accel[0]; val ay = accel[1]; val az = accel[2]
        val accelRoll  =  atan2(ax, sqrt(ay * ay + az * az)) * RAD_TO_DEG
        val accelPitch = -atan2(ay, sqrt(ax * ax + az * az)) * RAD_TO_DEG

        if (!hasReference) {
            refRoll  = accelRoll
            refPitch = accelPitch
            hasReference = true
            orientRoll  = 0f
            orientPitch = 0f
            return
        }

        if (lastGyroTimestamp != 0L) {
            val dt = (timestampNs - lastGyroTimestamp) * 1e-9f
            if (dt in 0.001f..0.1f) {
                orientRoll  = COMP_ALPHA * (orientRoll  + gyro[0] * dt * RAD_TO_DEG) +
                              (1f - COMP_ALPHA) * (accelRoll  - refRoll)
                orientPitch = COMP_ALPHA * (orientPitch + gyro[1] * dt * RAD_TO_DEG) +
                              (1f - COMP_ALPHA) * (accelPitch - refPitch)
                orientYaw  += gyro[2] * dt * RAD_TO_DEG
                orientYaw  *= 0.998f  // slow drift decay
            }
        } else {
            orientRoll  = accelRoll  - refRoll
            orientPitch = accelPitch - refPitch
        }
    }

    // ── Emit ───────────────────────────────────────────────────────────

    private fun emitData() {
        _imuData.value = ImuData(
            roll  = orientRoll,
            pitch = orientPitch,
            yaw   = orientYaw,
            accelX = accel[0],
            accelY = accel[1],
            accelZ = accel[2],
            gyroX = gyro[0],
            gyroY = gyro[1],
            gyroZ = gyro[2],
            sampleRateHz = measuredRate,
            sampleCount  = sampleCount,
            fusionMode   = fusionMode
        )
    }

    private fun resetInternalState() {
        hasReference = false
        orientRoll = 0f; orientPitch = 0f; orientYaw = 0f
        lastGyroTimestamp = 0L
        lastAccelNs = 0L
        sampleCount = 0L
        rateSum = 0f; rateSamples = 0; measuredRate = 0f
        accel = FloatArray(3)
        gyro  = FloatArray(3)
    }

    companion object {
        private const val RAD_TO_DEG = 57.2957795f
        private const val TARGET_PERIOD_US = 10_000  // 100 Hz target
    }
}
