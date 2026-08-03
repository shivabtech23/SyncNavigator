package com.shiv.syncnavigator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.shiv.syncnavigator.ui.NavigatorScreen

/**
 * PHASE 1 HOST — no logic of its own.
 *
 * Its entire job is to put CaptureDebugScreen on screen. Capture itself is
 * done by NotificationLoggerService, which is bound by the system and runs
 * whether or not this Activity is open. Closing the app does not stop
 * capture; revoking notification access does.
 *
 * Uses MaterialTheme defaults rather than a generated Theme.kt/Color.kt/
 * Type.kt trio — the debug screen only reads typography, and three files of
 * unused colour tokens would be dead weight.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavigatorScreen()
                }
            }
        }
    }

    /**
     * SDL PROOF OF CONCEPT — hardware validation track.
     *
     * BLUETOOTH_CONNECT is a runtime permission on API 31+. Without it the SDL
     * router service cannot open a socket, and the failure is completely silent:
     * the app builds, installs, runs, and simply never appears in SYNC's Mobile
     * Apps menu. Asking on launch is the cheapest way to rule that out.
     *
     * Phase 1 notification capture does not need this and is unaffected.
     */
    private fun requestPermissionsIfNeeded() {
            val permissions = mutableListOf<String>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (permissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    permissions.toTypedArray(),
                    REQ_BLUETOOTH,
                )
            }
        }


    private companion object {
        const val REQ_BLUETOOTH = 1001
    }
}
