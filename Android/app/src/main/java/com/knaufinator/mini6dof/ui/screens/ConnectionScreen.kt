package com.knaufinator.mini6dof.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knaufinator.mini6dof.ble.ConnectionState
import com.knaufinator.mini6dof.ble.ScannedDevice
import com.knaufinator.mini6dof.ui.theme.*
import com.knaufinator.mini6dof.viewmodel.PlatformViewModel

@Composable
fun ConnectionScreen(viewModel: PlatformViewModel) {
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val isScanning by viewModel.bleManager.isScanning.collectAsState()
    val scannedDevices by viewModel.bleManager.scannedDevices.collectAsState()
    val connectedName by viewModel.bleManager.connectedDeviceName.collectAsState()
    val statusMessages by viewModel.bleManager.statusMessages.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Connection Status Card ───────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (connectionState) {
                    ConnectionState.CONNECTED -> BleConnected.copy(alpha = 0.1f)
                    ConnectionState.CONNECTING -> BleScanning.copy(alpha = 0.1f)
                    ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when (connectionState) {
                                ConnectionState.CONNECTED -> BleConnected
                                ConnectionState.CONNECTING -> BleScanning
                                ConnectionState.DISCONNECTED -> BleDisconnected
                            }
                        )
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (connectionState) {
                            ConnectionState.CONNECTED -> "Connected"
                            ConnectionState.CONNECTING -> "Connecting..."
                            ConnectionState.DISCONNECTED -> "Disconnected"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (connectedName != null) {
                        Text(
                            text = connectedName!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (connectionState == ConnectionState.CONNECTED) {
                    FilledTonalButton(onClick = { viewModel.bleManager.disconnect() }) {
                        Text("Disconnect")
                    }
                }
            }
        }

        // ── Scan Controls ────────────────────────────────────────────
        if (connectionState == ConnectionState.DISCONNECTED) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (isScanning) viewModel.bleManager.stopScan()
                        else viewModel.bleManager.startScan()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Scan")
                    } else {
                        Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan for Devices")
                    }
                }
            }

            // ── Device List ──────────────────────────────────────────
            if (scannedDevices.isNotEmpty()) {
                Text(
                    text = "Discovered Devices",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scannedDevices) { device ->
                    DeviceCard(device = device) {
                        viewModel.bleManager.connect(device.address)
                    }
                }

                if (scannedDevices.isEmpty() && isScanning) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Searching for Mini6DOF...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (scannedDevices.isEmpty() && !isScanning) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Tap Scan to find nearby devices",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(0.3f))
        }

        // ── Status Log ───────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (connectionState == ConnectionState.DISCONNECTED) 0.4f else 1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Status Log",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(statusMessages) { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: ScannedDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // RSSI indicator
            val rssiColor = when {
                device.rssi > -50 -> BleConnected
                device.rssi > -70 -> BleScanning
                else -> BleDisconnected
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.SignalCellularAlt,
                    contentDescription = "Signal strength",
                    modifier = Modifier.size(20.dp),
                    tint = rssiColor
                )
                Text(
                    text = "${device.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
