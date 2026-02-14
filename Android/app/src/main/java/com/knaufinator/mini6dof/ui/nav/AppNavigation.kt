package com.knaufinator.mini6dof.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.knaufinator.mini6dof.ble.ConnectionState
import com.knaufinator.mini6dof.ui.screens.*
import com.knaufinator.mini6dof.ui.theme.BleConnected
import com.knaufinator.mini6dof.ui.theme.BleDisconnected
import com.knaufinator.mini6dof.viewmodel.PlatformViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Connect : Screen("connect", "Connect", Icons.Default.Bluetooth)
    object Control : Screen("control", "Control", Icons.Default.Tune)
    object Imu : Screen("imu", "IMU", Icons.Default.PhoneAndroid)
}

val screens = listOf(Screen.Connect, Screen.Control, Screen.Imu)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(viewModel: PlatformViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val connectionState by viewModel.bleManager.connectionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mini 6DOF") },
                actions = {
                    // Connection indicator dot in top bar
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
            startDestination = Screen.Connect.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Connect.route) { ConnectionScreen(viewModel) }
            composable(Screen.Control.route) { ControlScreen(viewModel) }
            composable(Screen.Imu.route) { ImuScreen(viewModel) }
        }
    }
}
