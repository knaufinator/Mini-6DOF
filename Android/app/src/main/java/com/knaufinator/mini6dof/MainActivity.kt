package com.knaufinator.mini6dof

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knaufinator.mini6dof.ui.nav.AppNavigation
import com.knaufinator.mini6dof.ui.theme.Mini6DOFTheme
import com.knaufinator.mini6dof.viewmodel.PlatformViewModel

class MainActivity : ComponentActivity() {

    private val blePermissionsGranted = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        blePermissionsGranted.value = permissions.values.all { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request BLE permissions eagerly — but don't block the app on them.
        // Sensor screen works with zero permissions; BLE features need these.
        requestBlePermissions()

        setContent {
            Mini6DOFTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: PlatformViewModel = viewModel()
                    AppNavigation(viewModel)
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

        val allGranted = permissions.all {
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            blePermissionsGranted.value = true
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
