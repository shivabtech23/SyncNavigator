package com.shiv.syncnavigator.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.shiv.syncnavigator.navigation.notification.CaptureStore
import com.shiv.syncnavigator.navigation.notification.NotificationCapture
import com.shiv.syncnavigator.navigation.notification.NotificationLoggerService
import java.io.File

/**
 * Phase 1 debug screen. Deliberately plain — it exists so you can tell,
 * from the driver's seat of a parked car, whether capture is working.
 * It is not part of the demo video.
 */
@Composable
fun CaptureDebugScreen() {
    val context = LocalContext.current
    val latest by CaptureStore.latest.collectAsState()
    val total by CaptureStore.totalSeen.collectAsState()
    val unique by CaptureStore.uniqueCount.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Notification Discovery", style = MaterialTheme.typography.titleLarge)
            Mono("posted $total   unique $unique")
            Mono(
                if (isListenerEnabled(context)) "✓ notification access granted"
                else "✗ NOT granted — tap Grant access"
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openListenerSettings(context) }) { Text("Grant access") }
                OutlinedButton(onClick = { exportAll(context) }) { Text("Export") }
                OutlinedButton(onClick = { CaptureStore.clear() }) { Text("Clear") }
            }
        }

        item { HorizontalDivider() }

        val c = latest
        if (c == null) {
            item { Text("Nothing captured yet.\n\nStart a route in Google Maps, then return here.") }
        } else {
            item { RawSection(c) }
            item { ImagesSection(c) }
            c.layouts.forEach { layout -> item { LayoutSection(layout) } }
            if (c.extractionErrors.isNotEmpty()) item { ErrorsSection(c) }
        }
    }
}

@Composable
private fun RawSection(c: NotificationCapture) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            SectionTitle("RAW NOTIFICATION")
            Mono("time      ${NotificationCapture.iso(c.capturedAtMillis)}")
            Mono("package   ${c.packageName}")
            Mono("id/tag    ${c.notificationId} / ${c.tag}")
            Mono("category  ${c.category}")
            Mono("channel   ${c.channelId}")
            Mono("priority  ${c.priorityName} (${c.priority})")
            Mono("visible   ${c.visibilityName}")
            Mono("flags     ${c.flagNames.joinToString(",")}")
            Mono("ongoing   ${c.isOngoing}   clearable ${c.isClearable}")
            Mono("smallIcon ${c.smallIcon?.resourceName ?: c.smallIcon?.iconType}")
            Mono("largeIcon ${c.largeIcon?.resourceName ?: c.largeIcon?.iconType}")

            SectionTitle("EXTRAS (${c.extras.size})")
            if (c.extras.isEmpty()) Mono("  (empty)")
            c.extras.forEach { Mono("  ${it.key} [${it.type}] = ${it.value}") }

            if (c.actions.isNotEmpty()) {
                SectionTitle("ACTIONS (${c.actions.size})")
                c.actions.forEach { Mono("  \"${it.title}\" ${it.icon?.resourceName ?: ""}") }
            }
        }
    }
}

/**
 * Renders the actual bitmaps. If the maneuver turns out to be image-only,
 * this is the screen where you'll see the arrow change as you approach a
 * junction — and that observation is what unblocks the parser.
 */
@Composable
private fun ImagesSection(c: NotificationCapture) {
    val context = LocalContext.current
    val files = remember(c.capturedAtMillis) {
        val all = buildList {
            c.layouts.forEach { l -> l.root?.flatten()?.forEach { it.image?.let(::add) } }
            c.smallIcon?.image?.let(::add)
            c.largeIcon?.image?.let(::add)
        }
        all.distinctBy { it.sha1 }
    }
    if (files.isEmpty()) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            SectionTitle("CAPTURED IMAGES (${files.size})")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files.size) { i ->
                    val img = files[i]
                    Column {
                        val bmp = remember(img.sha1) { loadBitmap(context, img.savedFileName) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(56.dp).background(Color(0xFF333333)),
                            )
                        }
                        Mono(img.sha1.take(8))
                        Mono("${img.widthPx}x${img.heightPx}")
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutSection(l: NotificationCapture.LayoutCapture) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            SectionTitle("VIEW TREE — ${l.layoutName}")
            Mono("rvPackage ${l.remoteViewsPackage}")
            Mono("layoutRes ${l.remoteViewsLayoutResourceName ?: l.remoteViewsLayoutId}")
            if (l.error != null) {
                Mono("ERROR ${l.error}")
                return@Column
            }
            val nodes = l.root?.flatten().orEmpty()
            Mono("${nodes.size} views")
            nodes.forEach { n ->
                val indent = "  ".repeat(n.depth)
                val simple = n.className.substringAfterLast('.')
                val detail = when {
                    n.text != null -> "  \"${n.text}\""
                    n.image != null -> "  [img ${n.image.sha1.take(8)}]"
                    else -> ""
                }
                Mono("$indent$simple #${n.viewIdName ?: "-"} ${n.visibility}$detail")
            }
        }
    }
}

@Composable
private fun ErrorsSection(c: NotificationCapture) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            SectionTitle("EXTRACTION ERRORS")
            c.extractionErrors.forEach { Mono("  $it") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
}

private fun loadBitmap(context: Context, fileName: String?) = try {
    fileName?.let {
        val f = File(
            File(
                File(context.getExternalFilesDir(null), NotificationLoggerService.ROOT_DIR),
                NotificationLoggerService.IMAGE_SUBDIR,
            ),
            it,
        )
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }
} catch (_: Throwable) {
    null
}

private fun openListenerSettings(context: Context) {
    // Notification access cannot be requested at runtime — Settings only.
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun isListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    ) ?: return false
    val me = ComponentName(context, NotificationLoggerService::class.java)
    return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
}

private fun exportAll(context: Context) {
    val root = File(context.getExternalFilesDir(null), NotificationLoggerService.ROOT_DIR)
    val index = File(root, NotificationLoggerService.INDEX_FILE)
    if (!index.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", index)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Export capture index",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
