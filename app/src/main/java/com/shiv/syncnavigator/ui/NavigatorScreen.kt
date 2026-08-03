package com.shiv.syncnavigator.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.platform.LocalContext
import com.shiv.syncnavigator.navigation.display.DisplayManager
import com.shiv.syncnavigator.navigation.model.NavigationStep
import com.shiv.syncnavigator.navigation.parser.NavigationStepProvider

/**
 * The app's only user-facing screen.
 *
 * Deliberately shows what the *car* is showing rather than what the phone
 * captured — the raw notification dump was a Phase 1 diagnostic and is no
 * longer the point. Two jobs: set a destination, and confirm at a glance that
 * the head unit is receiving guidance.
 */
@Composable
fun NavigatorScreen() {
    val context = LocalContext.current
    var destination by remember { mutableStateOf("") }

    val step by NavigationStepProvider.steps.collectAsState(
        initial = NavigationStep.NotNavigating(System.currentTimeMillis())
    )
    val lines = remember(step) { DisplayManager.format(step) }
    val listening = remember(step) { isListenerEnabled(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "SYNC Navigator",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )

        // Mirrors the head unit, monospaced so it reads as a device readout
        // rather than app chrome.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E3A4A)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    lines.line1.ifBlank { "—" },
                    color = Color(0xFF7FE3FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    lines.line2.ifBlank { " " },
                    color = Color(0xFF7FE3FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                )
            }
        }

        Text(
            "Where to?",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )

        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Enter a destination") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { startNavigation(context, destination) }),
        )

        Button(
            onClick = { startNavigation(context, destination) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = destination.isNotBlank(),
        ) {
            Text("Start navigation", fontSize = 16.sp)
        }
        OutlinedButton(
            onClick = { openMaps(context) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Open Google Maps", fontSize = 16.sp)
        }

        Spacer(Modifier.weight(1f))

        // Only surfaced when broken. Notification access is silently revoked on
        // reinstall, and without it nothing reaches the car — so it is worth a
        // prompt, but not worth permanent screen space.
        if (!listening) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF4A2B0E)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Notification access is off. Guidance cannot reach the car.",
                        color = Color(0xFFFFD9A0),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                    )
                    Button(onClick = { openListenerSettings(context) }) {
                        Text("Grant access")
                    }
                }
            }
        }
    }
}

/** Hands the destination to Google Maps; the notification listener does the rest. */
private fun startNavigation(context: Context, destination: String) {
    if (destination.isBlank()) return
    val uri = Uri.parse("google.navigation:q=${Uri.encode(destination.trim())}")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.google.android.apps.maps")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            // Maps missing or the package filter blocked it: let the system pick.
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
}
/**
 * Opens Maps without a destination — for picking somewhere from history,
 * saved places or the map itself. Guidance still reaches the car either way,
 * since the listener does not care how navigation was started.
 */
private fun openMaps(context: Context) {
    val intent = context.packageManager
        .getLaunchIntentForPackage("com.google.android.apps.maps")
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
private fun openListenerSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun isListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)