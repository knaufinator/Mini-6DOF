package com.knaufinator.mini6dof.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.knaufinator.mini6dof.ble.ConnectionState
import com.knaufinator.mini6dof.ui.screens.*
import com.knaufinator.mini6dof.ui.theme.BleConnected
import com.knaufinator.mini6dof.ui.theme.BleDisconnected
import com.knaufinator.mini6dof.viewmodel.ControlMode
import com.knaufinator.mini6dof.viewmodel.PlatformViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Sensors : Screen("sensors", "Sensors", Icons.Default.Sensors)
    object Connect : Screen("connect", "Connect", Icons.Default.Bluetooth)
    object Control : Screen("control", "Control", Icons.Default.Tune)
    object Imu : Screen("imu", "IMU", Icons.Default.PhoneAndroid)
}

val screens = listOf(Screen.Sensors, Screen.Connect, Screen.Control)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(viewModel: PlatformViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val connectionState by viewModel.bleManager.connectionState.collectAsState()
    val controlMode by viewModel.controlMode.collectAsState()
    val packetsSent by viewModel.packetsSent.collectAsState()
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isStreaming = controlMode == ControlMode.RAW_ACCEL && isConnected

    Scaffold(
        topBar = {
            Column {
                // ── Top App Bar ──
                TopAppBar(
                    title = { Text("Mini 6DOF") },
                    actions = {
                        val dotColor = when (connectionState) {
                            ConnectionState.CONNECTED -> BleConnected
                            else -> BleDisconnected
                        }
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = "Connection status",
                            tint = dotColor,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                )
                // ── Persistent Platform Control Bar ──
                PlatformControlBar(
                    isConnected = isConnected,
                    isStreaming = isStreaming,
                    packetsSent = packetsSent,
                    onToggle = { enabled ->
                        if (enabled) {
                            viewModel.setControlMode(ControlMode.RAW_ACCEL)
                        } else {
                            viewModel.setControlMode(ControlMode.MANUAL)
                            viewModel.homeAllAxes()
                        }
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Sensors.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Sensors.route) { SensorScreen(viewModel) }
            composable(Screen.Connect.route) { ConnectionScreen(viewModel) }
            composable(Screen.Control.route) { ControlScreen(viewModel) }
        }
    }
}

@Composable
fun PlatformControlBar(
    isConnected: Boolean,
    isStreaming: Boolean,
    packetsSent: Long,
    onToggle: (Boolean) -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            isStreaming -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "bar_bg"
    )

    Surface(
        tonalElevation = 2.dp,
        color = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: status label + packet count
            Column {
                Text(
                    text = when {
                        isStreaming -> "STREAMING"
                        isConnected -> "READY"
                        else -> "DISCONNECTED"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = when {
                        isStreaming -> MaterialTheme.colorScheme.primary
                        isConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                )
                if (isStreaming) {
                    Text(
                        text = "$packetsSent pkts",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Right: big toggle switch
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isStreaming) "ON" else "OFF",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 8.dp),
                    color = if (isStreaming)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = isStreaming,
                    onCheckedChange = onToggle,
                    enabled = isConnected
                )
            }
        }
    }
}
