package com.knaufinator.mini6dof.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knaufinator.mini6dof.sensor.FusionMode
import com.knaufinator.mini6dof.ui.composables.CarVisualization
import com.knaufinator.mini6dof.ui.theme.*
import com.knaufinator.mini6dof.viewmodel.PlatformViewModel

@Composable
fun SensorScreen(viewModel: PlatformViewModel) {
    val imuData by viewModel.imuManager.imuData.collectAsState()
    val imuActive by viewModel.imuManager.isActive.collectAsState()

    // Auto-start sensors when this screen is shown
    DisposableEffect(Unit) {
        viewModel.imuManager.start()
        onDispose { /* keep running — ViewModel owns lifecycle */ }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── 3D Car Visualization ───────────────────────────────────────
        CarVisualization(
            rollDeg = imuData.roll,
            pitchDeg = imuData.pitch,
            yawDeg = imuData.yaw,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // ── Orientation readout ────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Orientation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BigAngleValue("Roll",  imuData.roll,  AxisRoll)
                    BigAngleValue("Pitch", imuData.pitch, AxisPitch)
                    BigAngleValue("Yaw",   imuData.yaw,   AxisYaw)
                }
            }
        }

        // ── Raw Accelerometer ──────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Accelerometer  (m/s²)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                AxisRow("X", imuData.accelX, AxisSurge)
                AxisRow("Y", imuData.accelY, AxisSway)
                AxisRow("Z", imuData.accelZ, AxisHeave)
            }
        }

        // ── Raw Gyroscope ──────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Gyroscope  (rad/s)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                AxisRow("X", imuData.gyroX, AxisRoll)
                AxisRow("Y", imuData.gyroY, AxisPitch)
                AxisRow("Z", imuData.gyroZ, AxisYaw)
            }
        }

        // ── Status bar ─────────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Sensor badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SensorChip("Accel", viewModel.imuManager.hasAccelerometer)
                    SensorChip("Gyro", viewModel.imuManager.hasGyroscope)
                    SensorChip("RotVec", viewModel.imuManager.hasRotationVector)
                }

                // Rate + count + fusion mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Rate: %.0f Hz".format(imuData.sampleRateHz),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (imuData.sampleRateHz >= 80f) BleConnected
                                    else if (imuData.sampleRateHz >= 40f) BleScanning
                                    else MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Samples: ${imuData.sampleCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val fusionLabel = when (imuData.fusionMode) {
                        FusionMode.ROTATION_VECTOR -> "HW Fusion"
                        FusionMode.COMPLEMENTARY   -> "Comp. Filter"
                        FusionMode.NONE            -> "Accel Only"
                    }
                    val fusionColor = when (imuData.fusionMode) {
                        FusionMode.ROTATION_VECTOR -> BleConnected
                        FusionMode.COMPLEMENTARY   -> BleScanning
                        FusionMode.NONE            -> BleDisconnected
                    }
                    SurfaceChip(fusionLabel, fusionColor)
                }

                // Reset zero button
                OutlinedButton(
                    onClick = { viewModel.imuManager.resetReference() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset Zero Position")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── Composable helpers ─────────────────────────────────────────────────

@Composable
private fun BigAngleValue(label: String, value: Float, color: Color) {
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
private fun AxisRow(axis: String, value: Float, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                axis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(20.dp)
            )
        }
        Text(
            "%+8.3f".format(value),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun SensorChip(name: String, available: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
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
private fun SurfaceChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
        contentColor = color
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
