package com.knaufinator.mini6dof.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knaufinator.mini6dof.ble.ConnectionState
import com.knaufinator.mini6dof.ui.theme.*
import com.knaufinator.mini6dof.viewmodel.ControlMode
import com.knaufinator.mini6dof.viewmodel.PlatformViewModel

private val allAxisColors = arrayOf(AxisSurge, AxisSway, AxisHeave, AxisRoll, AxisPitch, AxisYaw)
private val allAxisNames = arrayOf("Surge", "Sway", "Heave", "Roll", "Pitch", "Yaw")

@Composable
fun ControlScreen(viewModel: PlatformViewModel) {
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val axisValues by viewModel.axisValues.collectAsState()
    val sendRate by viewModel.sendRateHz.collectAsState()
    val controlMode by viewModel.controlMode.collectAsState()
    val axisScale by viewModel.axisScale.collectAsState()
    val axisInvert by viewModel.axisInvert.collectAsState()
    val axisEnabled by viewModel.axisEnabled.collectAsState()
    val maxAngle by viewModel.imuMaxAngle.collectAsState()
    val liveValues by viewModel.liveSentValues.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isStreaming = controlMode == ControlMode.RAW_ACCEL

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Send Rate + Zero ─────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Rate:", fontSize = 12.sp)
                listOf(25, 50, 100).forEach { hz ->
                    FilterChip(
                        selected = sendRate == hz,
                        onClick = { viewModel.setSendRate(hz) },
                        label = { Text("${hz}Hz", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
            FilledTonalButton(
                onClick = { viewModel.resetImuReference() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Zero", fontSize = 11.sp)
            }
        }

        // ── Max Angle (rotation clamp) ───────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Max", modifier = Modifier.width(30.dp), fontSize = 11.sp)
            Slider(
                value = maxAngle,
                onValueChange = { viewModel.setImuMaxAngle(it) },
                valueRange = 1f..6f,
                steps = 9,
                modifier = Modifier.weight(1f)
            )
            Text(
                "%.1f\u00B0".format(maxAngle),
                modifier = Modifier.width(34.dp),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            )
        }

        // ── Rotation Axes (Roll, Pitch, Yaw) ─────────────────────────
        Text("Rotation", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        (3..5).forEach { i ->
            AxisControlRow(
                name = allAxisNames[i],
                color = allAxisColors[i],
                enabled = axisEnabled[i],
                scale = axisScale[i],
                inverted = axisInvert[i],
                liveValue = liveValues[i],
                unit = "\u00B0",
                onEnabledChange = { viewModel.setAxisEnabled(i, it) },
                onScaleChange = { viewModel.setAxisScale(i, it) },
                onInvertChange = { viewModel.setAxisInvert(i, it) }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

        // ── Translation Axes (Surge, Sway, Heave) ────────────────────
        Text("Translation", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        (0..2).forEach { i ->
            AxisControlRow(
                name = allAxisNames[i],
                color = allAxisColors[i],
                enabled = axisEnabled[i],
                scale = axisScale[i],
                inverted = axisInvert[i],
                liveValue = liveValues[i],
                unit = "m/s\u00B2",
                onEnabledChange = { viewModel.setAxisEnabled(i, it) },
                onScaleChange = { viewModel.setAxisScale(i, it) },
                onInvertChange = { viewModel.setAxisInvert(i, it) }
            )
        }

        if (!isConnected) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Text(
                    "Connect to a device first",
                    modifier = Modifier.padding(12.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // ── Manual Axis Sliders (only when not streaming) ────────────
        if (!isStreaming) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Manual Sliders", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                FilledTonalButton(
                    onClick = { viewModel.homeAllAxes() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Home", fontSize = 11.sp)
                }
            }
            PlatformViewModel.AXIS_NAMES.forEachIndexed { index, name ->
                AxisSlider(
                    name = name,
                    value = axisValues[index],
                    color = allAxisColors[index],
                    enabled = isConnected,
                    onValueChange = { viewModel.setAxisValue(index, it) },
                    onValueChangeFinished = {
                        if (kotlin.math.abs(axisValues[index]) < 3f) {
                            viewModel.setAxisValue(index, 0f)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AxisControlRow(
    name: String,
    color: Color,
    enabled: Boolean,
    scale: Float,
    inverted: Boolean,
    liveValue: Float,
    unit: String,
    onEnabledChange: (Boolean) -> Unit,
    onScaleChange: (Float) -> Unit,
    onInvertChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Enable checkbox
        Checkbox(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.size(28.dp)
        )

        // Axis name
        Text(
            name.take(3),
            modifier = Modifier.width(30.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) color else color.copy(alpha = 0.4f)
        )

        // Scale slider
        Slider(
            value = scale,
            onValueChange = onScaleChange,
            valueRange = 0.01f..2.0f,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                disabledThumbColor = color.copy(alpha = 0.3f),
                disabledActiveTrackColor = color.copy(alpha = 0.2f)
            )
        )

        // Scale value
        Text(
            "%.2f".format(scale),
            modifier = Modifier.width(32.dp),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )

        // Invert
        Checkbox(
            checked = inverted,
            onCheckedChange = onInvertChange,
            enabled = enabled,
            modifier = Modifier.size(24.dp)
        )

        // Live value
        Text(
            "%+.1f".format(liveValue),
            modifier = Modifier.width(40.dp),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            color = if (enabled) color else color.copy(alpha = 0.3f)
        )
    }
}

@Composable
private fun AxisSlider(
    name: String,
    value: Float,
    color: Color,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = "%+.0f%%".format(value),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (value != 0f) color else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = -100f..100f,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = color,
                    activeTrackColor = color,
                    inactiveTrackColor = color.copy(alpha = 0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
