package com.knaufinator.mini6dof.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knaufinator.mini6dof.ble.ConnectionState
import com.knaufinator.mini6dof.ui.theme.*
import com.knaufinator.mini6dof.viewmodel.ControlMode
import com.knaufinator.mini6dof.viewmodel.PlatformViewModel

@Composable
fun ImuScreen(viewModel: PlatformViewModel) {
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val controlMode by viewModel.controlMode.collectAsState()
    val imuData by viewModel.imuManager.imuData.collectAsState()
    val imuActive by viewModel.imuManager.isActive.collectAsState()
    val sensitivity by viewModel.imuSensitivity.collectAsState()
    val maxAngle by viewModel.imuMaxAngle.collectAsState()
    val packetsSent by viewModel.packetsSent.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isImuMode = controlMode == ControlMode.IMU

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ───────────────────────────────────────────────────
        Text(
            "Phone IMU Control",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Tilt your phone to control the platform. " +
            "Roll and pitch map directly to platform orientation. " +
            "Gyroscope yaw rate controls platform yaw.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Enable/Disable Card ──────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isImuMode) BleConnected.copy(alpha = 0.1f)
                    else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "IMU Streaming",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isImuMode) "Active — streaming to platform"
                        else "Tap to enable phone motion control",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isImuMode,
                    onCheckedChange = {
                        viewModel.setControlMode(
                            if (it) ControlMode.IMU else ControlMode.MANUAL
                        )
                    },
                    enabled = isConnected
                )
            }
        }

        if (!isConnected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Text(
                    "Connect to a device first",
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // ── Sensor Availability ──────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SensorBadge("Accelerometer", viewModel.imuManager.hasAccelerometer)
            SensorBadge("Gyroscope", viewModel.imuManager.hasGyroscope)
        }

        // ── Live Orientation ─────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Live Orientation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OrientationValue("Roll", imuData.roll, AxisRoll)
                    OrientationValue("Pitch", imuData.pitch, AxisPitch)
                    OrientationValue("Yaw", imuData.yaw, AxisYaw)
                }
            }
        }

        // ── Raw Sensor Data ──────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Raw Sensors",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                SensorRow("Accel", imuData.accelX, imuData.accelY, imuData.accelZ, "m/s²")
                SensorRow("Gyro", imuData.gyroX, imuData.gyroY, imuData.gyroZ, "rad/s")
            }
        }

        // ── Settings ─────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // Sensitivity
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sensitivity", modifier = Modifier.width(100.dp), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = sensitivity,
                        onValueChange = { viewModel.setImuSensitivity(it) },
                        valueRange = 0.1f..5.0f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "%.1fx".format(sensitivity),
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }

                // Max angle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Max Tilt", modifier = Modifier.width(100.dp), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = maxAngle,
                        onValueChange = { viewModel.setImuMaxAngle(it) },
                        valueRange = 5f..90f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "%.0f°".format(maxAngle),
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }

                // Reset reference
                OutlinedButton(
                    onClick = { viewModel.resetImuReference() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset Zero Position")
                }
            }
        }

        // ── Packet Stats ─────────────────────────────────────────────
        if (isImuMode && isConnected) {
            Text(
                "Packets sent: $packetsSent",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SensorBadge(name: String, available: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (available) BleConnected else BleDisconnected)
        )
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OrientationValue(label: String, value: Float, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "%+.1f°".format(value),
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun SensorRow(label: String, x: Float, y: Float, z: Float, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(50.dp)
        )
        Text(
            "X:%+6.2f  Y:%+6.2f  Z:%+6.2f  %s".format(x, y, z, unit),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}
