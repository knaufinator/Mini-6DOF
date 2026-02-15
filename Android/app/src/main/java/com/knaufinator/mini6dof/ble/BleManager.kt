package com.knaufinator.mini6dof.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"

        // Must match firmware BLE service/characteristic UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("42100001-0001-1000-8000-00805f9b34fb")
        val MOTION_CHAR_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val STATUS_CHAR_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val ACCEL_CHAR_UUID: UUID = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var motionCharacteristic: BluetoothGattCharacteristic? = null
    private var accelCharacteristic: BluetoothGattCharacteristic? = null

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _statusMessages = MutableStateFlow<List<String>>(emptyList())
    val statusMessages: StateFlow<List<String>> = _statusMessages.asStateFlow()

    private val deviceMap = mutableMapOf<String, ScannedDevice>()

    val isBluetoothEnabled: Boolean get() = bluetoothAdapter?.isEnabled == true

    // ── Scanning ─────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            val address = device.address
            val rssi = result.rssi

            deviceMap[address] = ScannedDevice(name, address, rssi)
            _scannedDevices.value = deviceMap.values.toList().sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            _isScanning.value = false
            addStatus("Scan failed (error $errorCode)")
        }
    }

    fun startScan() {
        if (_isScanning.value) return
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            addStatus("Bluetooth not available")
            return
        }

        deviceMap.clear()
        _scannedDevices.value = emptyList()
        bleScanner = bluetoothAdapter.bluetoothLeScanner

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(filters, settings, scanCallback)
        _isScanning.value = true
        addStatus("Scanning for Mini6DOF devices...")
    }

    fun stopScan() {
        if (!_isScanning.value) return
        bleScanner?.stopScan(scanCallback)
        _isScanning.value = false
        addStatus("Scan stopped")
    }

    // ── Connection ───────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to ${gatt.device.name}")
                    _connectionState.value = ConnectionState.CONNECTED
                    _connectedDeviceName.value = gatt.device.name ?: gatt.device.address
                    addStatus("Connected to ${gatt.device.name}")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _connectedDeviceName.value = null
                    motionCharacteristic = null
                    accelCharacteristic = null
                    addStatus("Disconnected")
                    gatt.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addStatus("Service discovery failed")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                addStatus("Motion service not found!")
                disconnect()
                return
            }

            motionCharacteristic = service.getCharacteristic(MOTION_CHAR_UUID)
            if (motionCharacteristic == null) {
                addStatus("Motion characteristic not found!")
                disconnect()
                return
            }

            accelCharacteristic = service.getCharacteristic(ACCEL_CHAR_UUID)
            if (accelCharacteristic != null) {
                addStatus("Ready — motion + accel characteristics found")
            } else {
                addStatus("Ready — motion characteristic found (no accel char — update firmware)")
            }

            // Request higher MTU for 24-byte accel packets
            gatt.requestMtu(128)

            // Enable notifications on status characteristic
            val statusChar = service.getCharacteristic(STATUS_CHAR_UUID)
            if (statusChar != null) {
                gatt.setCharacteristicNotification(statusChar, true)
                val descriptor = statusChar.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == STATUS_CHAR_UUID) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    val text = String(data, Charsets.UTF_8)
                    addStatus("ESP32: $text")
                }
            }
        }
    }

    fun connect(address: String) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        stopScan()

        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        _connectionState.value = ConnectionState.CONNECTING
        addStatus("Connecting to ${device.name ?: address}...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    // ── Data Transmission ────────────────────────────────────────────

    /**
     * Send 6-axis motion data as 12-byte binary payload.
     * Values are uint16 LE, 0-4095 range, center=2047.
     */
    fun sendMotionPacket(channels: IntArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = motionCharacteristic ?: return false
        if (_connectionState.value != ConnectionState.CONNECTED) return false
        if (channels.size != 6) return false

        val payload = ByteArray(12)
        for (i in 0 until 6) {
            val v = channels[i].coerceIn(0, 4095)
            payload[i * 2] = (v and 0xFF).toByte()
            payload[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }

        char.value = payload
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return gatt.writeCharacteristic(char)
    }

    /**
     * Send an ASCII command string (for CONFIG?, VERSION?, etc.)
     */
    fun sendCommand(command: String): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = motionCharacteristic ?: return false
        if (_connectionState.value != ConnectionState.CONNECTED) return false

        val data = "${command}X".toByteArray(Charsets.UTF_8)
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return gatt.writeCharacteristic(char)
    }

    /**
     * Send raw accelerometer + gyroscope data as 24-byte payload on accel characteristic.
     * Data: 6 x float32 LE = [accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z]
     * Units: m/s² (TYPE_ACCELEROMETER, includes gravity) + rad/s (TYPE_GYROSCOPE)
     */
    fun sendAccelPacket(accelX: Float, accelY: Float, accelZ: Float,
                        gyroX: Float, gyroY: Float, gyroZ: Float): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = accelCharacteristic ?: return false
        if (_connectionState.value != ConnectionState.CONNECTED) return false

        val buffer = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(accelX)
        buffer.putFloat(accelY)
        buffer.putFloat(accelZ)
        buffer.putFloat(gyroX)
        buffer.putFloat(gyroY)
        buffer.putFloat(gyroZ)

        char.value = buffer.array()
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return gatt.writeCharacteristic(char)
    }

    /** Returns true if the connected firmware supports the accel characteristic (0xFF03) */
    val hasAccelSupport: Boolean get() = accelCharacteristic != null

    fun destroy() {
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun addStatus(msg: String) {
        val current = _statusMessages.value.toMutableList()
        current.add(0, msg)
        if (current.size > 50) current.removeAt(current.lastIndex)
        _statusMessages.value = current
    }
}
