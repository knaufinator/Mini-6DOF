package com.knaufinator.mini6dof.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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
import com.knaufinator.mini6dof.viewmodel.PlatformViewModel

private val axisColors = arrayOf(AxisSurge, AxisSway, AxisHeave, AxisRoll, AxisPitch, AxisYaw)

@Composable
fun ControlScreen(viewModel: PlatformViewModel) {
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val axisValues by viewModel.axisValues.collectAsState()
    val packetsSent by viewModel.packetsSent.collectAsState()
    val sendRate by viewModel.sendRateHz.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Header with Home + Stats ─────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Axis Control",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isConnected) {
                    Text(
                        "${packetsSent} pkts",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = { viewModel.homeAllAxes() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Home", fontSize = 13.sp)
                }
            }
        }

        // ── Send Rate ────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Rate:", style = MaterialTheme.typography.labelMedium)
            listOf(10, 25, 50).forEach { hz ->
                FilterChip(
                    selected = sendRate == hz,
                    onClick = { viewModel.setSendRate(hz) },
                    label = { Text("${hz}Hz", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        if (!isConnected) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Text(
                    "Connect to a device first",
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // ── 6 Axis Sliders ───────────────────────────────────────────
        PlatformViewModel.AXIS_NAMES.forEachIndexed { index, name ->
            AxisSlider(
                name = name,
                value = axisValues[index],
                color = axisColors[index],
                enabled = isConnected,
                onValueChange = { viewModel.setAxisValue(index, it) },
                onValueChangeFinished = { /* snap to zero on release if close */
                    if (kotlin.math.abs(axisValues[index]) < 3f) {
                        viewModel.setAxisValue(index, 0f)
                    }
                }
            )
        }
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
