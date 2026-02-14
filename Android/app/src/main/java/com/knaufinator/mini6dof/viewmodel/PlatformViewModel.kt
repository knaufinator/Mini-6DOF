package com.knaufinator.mini6dof.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knaufinator.mini6dof.ble.BleManager
import com.knaufinator.mini6dof.ble.ConnectionState
import com.knaufinator.mini6dof.sensor.ImuManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ControlMode {
    MANUAL,  // 6 individual sliders
    IMU      // phone accelerometer/gyroscope
}

/**
 * Central ViewModel managing BLE connection, axis control, and IMU streaming.
 *
 * Binary protocol: 12 bytes = 6 × uint16 LE, range 0-4095, center 2047.
 * Axes: [surge, sway, heave, roll, pitch, yaw]
 */
class PlatformViewModel(application: Application) : AndroidViewModel(application) {

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

    // IMU max tilt angle that maps to 100% (degrees)
    private val _imuMaxAngle = MutableStateFlow(30.0f)
    val imuMaxAngle: StateFlow<Float> = _imuMaxAngle.asStateFlow()

    // Send rate in Hz
    private val _sendRateHz = MutableStateFlow(50)
    val sendRateHz: StateFlow<Int> = _sendRateHz.asStateFlow()

    // Packet counter
    private val _packetsSent = MutableStateFlow(0L)
    val packetsSent: StateFlow<Long> = _packetsSent.asStateFlow()

    private var sendJob: Job? = null

    init {
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
        if (mode == ControlMode.IMU) {
            imuManager.start()
        } else {
            imuManager.stop()
        }
        _controlMode.value = mode
    }

    fun setImuSensitivity(value: Float) {
        _imuSensitivity.value = value.coerceIn(0.1f, 5.0f)
    }

    fun setImuMaxAngle(value: Float) {
        _imuMaxAngle.value = value.coerceIn(5f, 90f)
    }

    fun setSendRate(hz: Int) {
        _sendRateHz.value = hz.coerceIn(1, 100)
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
                val channels = computeChannels()
                if (bleManager.sendMotionPacket(channels)) {
                    _packetsSent.value++
                }
                delay(1000L / _sendRateHz.value)
            }
        }
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

    companion object {
        val AXIS_NAMES = arrayOf("Surge", "Sway", "Heave", "Roll", "Pitch", "Yaw")
    }
}
