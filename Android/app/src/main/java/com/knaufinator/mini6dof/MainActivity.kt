package com.knaufinator.mini6dof

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knaufinator.mini6dof.ui.nav.AppNavigation
import com.knaufinator.mini6dof.ui.theme.Mini6DOFTheme
import com.knaufinator.mini6dof.viewmodel.PlatformViewModel

class MainActivity : ComponentActivity() {

    private val permissionsGranted = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted.value = permissions.values.all { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBlePermissions()

        setContent {
            Mini6DOFTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val granted by permissionsGranted
                    if (granted) {
                        val viewModel: PlatformViewModel = viewModel()
                        AppNavigation(viewModel)
                    } else {
                        PermissionRequest {
                            requestBlePermissions()
                        }
                    }
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Check if already granted
        val allGranted = permissions.all {
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            permissionsGranted.value = true
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
private fun PermissionRequest(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Bluetooth Permissions Required",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "This app needs Bluetooth and Location permissions to scan for and connect to your Mini 6DOF platform.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Permissions")
        }
    }
}
