package com.knaufinator.mini6dof.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knaufinator.mini6dof.ble.BleManager
import com.knaufinator.mini6dof.ble.ConnectionState
import com.knaufinator.mini6dof.sensor.ImuManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ControlMode {
    MANUAL,     // 6 individual sliders
    IMU,        // phone orientation → pre-computed motion commands
    RAW_ACCEL   // raw accel/gyro → firmware does motion cueing
}

/**
 * Central ViewModel managing BLE connection, axis control, and IMU streaming.
 *
 * Binary protocol: 12 bytes = 6 × uint16 LE, range 0-4095, center 2047.
 * Axes: [surge, sway, heave, roll, pitch, yaw]
 */
class PlatformViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("mini6dof_settings", Context.MODE_PRIVATE)

    val bleManager = BleManager(application)
    val imuManager = ImuManager(application)

    // Control mode
    private val _controlMode = MutableStateFlow(ControlMode.MANUAL)
    val controlMode: StateFlow<ControlMode> = _controlMode.asStateFlow()

    // Manual axis values: -100% to +100% as floats
    private val _axisValues = MutableStateFlow(FloatArray(6) { 0f })
    val axisValues: StateFlow<FloatArray> = _axisValues.asStateFlow()

    // IMU sensitivity (multiplier applied to sensor angles)
    private val _imuSensitivity = MutableStateFlow(1.0f)
    val imuSensitivity: StateFlow<Float> = _imuSensitivity.asStateFlow()

    // Max angle clamp for orientation axes (degrees)
    private val _imuMaxAngle = MutableStateFlow(3.0f)
    val imuMaxAngle: StateFlow<Float> = _imuMaxAngle.asStateFlow()

    // Per-axis config for all 6 axes: [surge, sway, heave, roll, pitch, yaw]
    private val _axisScale = MutableStateFlow(floatArrayOf(0.3f, 0.3f, 0.3f, 0.5f, 0.5f, 0.5f))
    val axisScale: StateFlow<FloatArray> = _axisScale.asStateFlow()

    private val _axisInvert = MutableStateFlow(BooleanArray(6) { false })
    val axisInvert: StateFlow<BooleanArray> = _axisInvert.asStateFlow()

    private val _axisEnabled = MutableStateFlow(booleanArrayOf(false, false, false, true, true, false))
    val axisEnabled: StateFlow<BooleanArray> = _axisEnabled.asStateFlow()

    // Live values being sent (for UI display)
    private val _liveSentValues = MutableStateFlow(FloatArray(6) { 0f })
    val liveSentValues: StateFlow<FloatArray> = _liveSentValues.asStateFlow()

    // Send rate in Hz
    private val _sendRateHz = MutableStateFlow(50)
    val sendRateHz: StateFlow<Int> = _sendRateHz.asStateFlow()

    // Packet counter
    private val _packetsSent = MutableStateFlow(0L)
    val packetsSent: StateFlow<Long> = _packetsSent.asStateFlow()

    private var sendJob: Job? = null

    init {
        loadPrefs()

        // Auto-start IMU sensors for the Sensor screen (independent of BLE)
        imuManager.start()

        // Start streaming loop when connected
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    startStreaming()
                } else {
                    stopStreaming()
                }
            }
        }
    }

    // ── Manual Axis Control ──────────────────────────────────────────

    fun setAxisValue(index: Int, value: Float) {
        if (index !in 0..5) return
        val arr = _axisValues.value.copyOf()
        arr[index] = value.coerceIn(-100f, 100f)
        _axisValues.value = arr
    }

    fun homeAllAxes() {
        _axisValues.value = FloatArray(6) { 0f }
    }

    // ── Control Mode ─────────────────────────────────────────────────

    fun setControlMode(mode: ControlMode) {
        when (mode) {
            ControlMode.IMU, ControlMode.RAW_ACCEL -> imuManager.start()
            ControlMode.MANUAL -> imuManager.stop()
        }
        _controlMode.value = mode
    }

    fun setImuSensitivity(value: Float) {
        _imuSensitivity.value = value.coerceIn(0.1f, 5.0f)
    }

    fun setImuMaxAngle(value: Float) {
        _imuMaxAngle.value = value.coerceIn(1f, 6f)
        savePrefs()
    }

    fun setAxisScale(index: Int, value: Float) {
        if (index !in 0..5) return
        val arr = _axisScale.value.copyOf()
        arr[index] = value.coerceIn(0.01f, 2.0f)
        _axisScale.value = arr
        savePrefs()
    }

    fun setAxisInvert(index: Int, inverted: Boolean) {
        if (index !in 0..5) return
        val arr = _axisInvert.value.copyOf()
        arr[index] = inverted
        _axisInvert.value = arr
        savePrefs()
    }

    fun setAxisEnabled(index: Int, enabled: Boolean) {
        if (index !in 0..5) return
        val arr = _axisEnabled.value.copyOf()
        arr[index] = enabled
        _axisEnabled.value = arr
        savePrefs()
    }

    fun setSendRate(hz: Int) {
        _sendRateHz.value = hz.coerceIn(1, 200)
        savePrefs()
        // Restart streaming with new rate if active
        if (sendJob?.isActive == true) {
            stopStreaming()
            startStreaming()
        }
    }

    fun resetImuReference() {
        imuManager.resetReference()
    }

    // ── Streaming ────────────────────────────────────────────────────

    private fun startStreaming() {
        if (sendJob?.isActive == true) return
        _packetsSent.value = 0

        sendJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val sent = when (_controlMode.value) {
                    ControlMode.RAW_ACCEL -> sendRawAccel()
                    else -> {
                        val channels = computeChannels()
                        bleManager.sendMotionPacket(channels)
                    }
                }
                if (sent) _packetsSent.value++
                delay(1000L / _sendRateHz.value)
            }
        }
    }

    /**
     * Send 6-axis data on the accel BLE characteristic.
     * Payload: [roll_deg, pitch_deg, yaw_deg, accelX, accelY, accelZ] as 6 x float32 LE.
     *
     * Rotation axes (3-5): orientation degrees, clamped to maxAngle.
     * Translation axes (0-2): raw accelerometer m/s², firmware converts to mm.
     * Each axis individually enableable, scalable, invertable.
     */
    private fun sendRawAccel(): Boolean {
        val imu = imuManager.imuData.value
        val scale = _axisScale.value
        val invert = _axisInvert.value
        val enabled = _axisEnabled.value
        val maxAngle = _imuMaxAngle.value

        val sign = { i: Int -> if (invert[i]) -1f else 1f }

        // Translation: raw accel m/s² (subtract gravity on Z), apply scale+invert+enable
        val surge = if (enabled[0]) (imu.accelX * scale[0] * sign(0)) else 0f
        val sway  = if (enabled[1]) (imu.accelY * scale[1] * sign(1)) else 0f
        val heave = if (enabled[2]) ((imu.accelZ - 9.81f) * scale[2] * sign(2)) else 0f

        // Rotation: orientation degrees, apply scale+invert+enable, clamp to maxAngle
        val roll  = if (enabled[3]) (imu.roll  * scale[3] * sign(3)).coerceIn(-maxAngle, maxAngle) else 0f
        val pitch = if (enabled[4]) (imu.pitch * scale[4] * sign(4)).coerceIn(-maxAngle, maxAngle) else 0f
        val yaw   = if (enabled[5]) (imu.yaw   * scale[5] * sign(5)).coerceIn(-maxAngle, maxAngle) else 0f

        // Update live values for UI
        _liveSentValues.value = floatArrayOf(surge, sway, heave, roll, pitch, yaw)

        // BLE packet: [roll, pitch, yaw, surge_ms2, sway_ms2, heave_ms2]
        return bleManager.sendAccelPacket(
            roll, pitch, yaw,
            surge, sway, heave
        )
    }

    private fun stopStreaming() {
        sendJob?.cancel()
        sendJob = null
    }

    /**
     * Compute 6-channel uint16 values from current control state.
     * Maps -100%..+100% → 0..4095, center=2047.
     */
    private fun computeChannels(): IntArray {
        val pct = when (_controlMode.value) {
            ControlMode.MANUAL -> _axisValues.value
            ControlMode.IMU -> computeImuAxes()
            ControlMode.RAW_ACCEL -> FloatArray(6) { 0f }
        }

        return IntArray(6) { i ->
            val clamped = pct[i].coerceIn(-100f, 100f)
            // -100% → 0, 0% → 2047, +100% → 4095
            ((clamped / 100f) * 2047f + 2047f).toInt().coerceIn(0, 4095)
        }
    }

    /**
     * Map IMU sensor data to 6 platform axes.
     * Roll/Pitch/Yaw from phone orientation, surge/sway/heave zeroed.
     */
    private fun computeImuAxes(): FloatArray {
        val imu = imuManager.imuData.value
        val sens = _imuSensitivity.value
        val maxAngle = _imuMaxAngle.value

        // Map phone angles to -100%..+100% based on max angle
        val rollPct = (imu.roll * sens / maxAngle * 100f).coerceIn(-100f, 100f)
        val pitchPct = (imu.pitch * sens / maxAngle * 100f).coerceIn(-100f, 100f)
        val yawPct = (imu.yaw * sens / maxAngle * 100f).coerceIn(-100f, 100f)

        // surge=0, sway=0, heave=0, roll, pitch, yaw
        return floatArrayOf(0f, 0f, 0f, rollPct, pitchPct, yawPct)
    }

    // ── Commands ─────────────────────────────────────────────────────

    fun sendCommand(cmd: String) {
        bleManager.sendCommand(cmd)
    }

    override fun onCleared() {
        super.onCleared()
        stopStreaming()
        imuManager.stop()
        bleManager.destroy()
    }

    // ── Preferences ──────────────────────────────────────────────

    private fun savePrefs() {
        prefs.edit().apply {
            putFloat("maxAngle", _imuMaxAngle.value)
            putInt("sendRate", _sendRateHz.value)
            for (i in 0..5) {
                putFloat("scale_$i", _axisScale.value[i])
                putBoolean("invert_$i", _axisInvert.value[i])
                putBoolean("enabled_$i", _axisEnabled.value[i])
            }
            apply()
        }
    }

    private fun loadPrefs() {
        if (!prefs.contains("maxAngle")) return  // first launch, use defaults
        _imuMaxAngle.value = prefs.getFloat("maxAngle", 3.0f)
        _sendRateHz.value = prefs.getInt("sendRate", 50)
        val scale = _axisScale.value.copyOf()
        val invert = _axisInvert.value.copyOf()
        val enabled = _axisEnabled.value.copyOf()
        for (i in 0..5) {
            scale[i] = prefs.getFloat("scale_$i", scale[i])
            invert[i] = prefs.getBoolean("invert_$i", invert[i])
            enabled[i] = prefs.getBoolean("enabled_$i", enabled[i])
        }
        _axisScale.value = scale
        _axisInvert.value = invert
        _axisEnabled.value = enabled
    }

    companion object {
        val AXIS_NAMES = arrayOf("Surge", "Sway", "Heave", "Roll", "Pitch", "Yaw")
    }
}
