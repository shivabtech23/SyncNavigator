package com.shiv.syncnavigator.navigation.notification

import android.app.Notification
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * PHASE 1 — DISCOVERY ONLY.
 *
 * Captures Google Maps notifications verbatim. Does not parse, interpret,
 * or guess. Its only job is to find out what Maps actually emits on this
 * device and Android version before a line of parser exists.
 *
 * Testable today on the phone alone. No car, no SDL, no Ford.
 */
class NotificationLoggerService : NotificationListenerService() {

    companion object {
        const val TAG = "SyncNavCapture"

        val WATCHED_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "com.google.android.apps.mapslite",
        )

        const val ROOT_DIR = "captures"
        const val JSON_SUBDIR = "json"
        const val IMAGE_SUBDIR = "images"
        const val INDEX_FILE = "index.jsonl"

        const val MAX_JSON_FILES = 2000
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lastSignature: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected. Watching $WATCHED_PACKAGES")
        Log.i(TAG, "Dump root: ${rootDir()?.absolutePath}")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED_PACKAGES) return

        // RemoteViews inflation requires the main thread.
        main.post {
            val capture = try {
                build(sbn)
            } catch (t: Throwable) {
                Log.e(TAG, "capture failed", t)
                return@post
            }

            val sig = capture.contentSignature()
            val unique = sig != lastSignature
            lastSignature = sig

            CaptureStore.record(capture, unique)

            // Every posted notification is counted; only distinct content is
            // written. Maps reposts ~2x/sec — logging all of it would bury
            // the signal and fill the disk.
            if (unique) {
                logcat(capture)
                io.execute { persist(capture) }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED_PACKAGES) return
        Log.i(TAG, "REMOVED key=${sbn.key}")
        io.execute {
            appendIndex(
                JSONObject().apply {
                    put("event", "removed")
                    put("key", sbn.key)
                    put("capturedAt", NotificationCapture.iso(System.currentTimeMillis()))
                }
            )
        }
    }

    // Notification.priority is deprecated in favour of channel importance
    // (API 26+), but the raw value is still worth capturing for Phase 1 —
    // we're recording what Maps sends, not what's currently idiomatic.
    @Suppress("DEPRECATION")
    private fun build(sbn: StatusBarNotification): NotificationCapture {
        val n = sbn.notification
        val errors = mutableListOf<String>()
        val imageDir = rootDir()?.let { File(it, IMAGE_SUBDIR) }

        val inspector = ViewTreeInspector(this, sbn.packageName, imageDir)
        val layouts = inspector.inspectAll(n, errors)

        return NotificationCapture(
            capturedAtMillis = System.currentTimeMillis(),
            postedAtMillis = sbn.postTime,
            whenMillis = n.`when`,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            notificationId = sbn.id,
            tag = sbn.tag,
            category = n.category,
            channelId = n.channelId,
            group = n.group,
            sortKey = n.sortKey,
            flags = n.flags,
            flagNames = flagNames(n.flags),
            priority = n.priority,
            priorityName = priorityName(n.priority),
            visibility = n.visibility,
            visibilityName = visibilityName(n.visibility),
            isOngoing = sbn.isOngoing,
            isClearable = sbn.isClearable,
            extras = flattenExtras(n.extras, errors),
            layouts = layouts,
            smallIcon = iconInfo(n.smallIcon, inspector, errors),
            largeIcon = iconInfo(n.getLargeIcon(), inspector, errors),
            actions = n.actions?.map { a ->
                NotificationCapture.ActionInfo(
                    title = a.title?.toString(),
                    hasRemoteInputs = a.remoteInputs?.isNotEmpty() == true,
                    icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        iconInfo(a.getIcon(), inspector, errors) else null,
                )
            } ?: emptyList(),
            extractionErrors = errors,
        )
    }

    private fun iconInfo(
        icon: Icon?,
        inspector: ViewTreeInspector,
        errors: MutableList<String>,
    ): NotificationCapture.IconInfo? {
        if (icon == null) return null
        return try {
            val type = iconTypeName(icon)
            val resId = if (type == "RESOURCE") runCatching { icon.resId }.getOrNull() else null
            val bmp = icon.loadDrawable(this)?.let { inspector.drawableToBitmap(it) }
            NotificationCapture.IconInfo(
                iconType = type,
                resId = resId,
                resourceName = resId?.let { inspector.resourceName(it) },
                image = bmp?.let { inspector.saveImage(it) },
            )
        } catch (t: Throwable) {
            errors += "icon read failed: ${t.message}"
            null
        }
    }

    private fun iconTypeName(icon: Icon): String = try {
        when (icon.type) {
            Icon.TYPE_BITMAP -> "BITMAP"
            Icon.TYPE_RESOURCE -> "RESOURCE"
            Icon.TYPE_DATA -> "DATA"
            Icon.TYPE_URI -> "URI"
            else -> "OTHER(${icon.type})"
        }
    } catch (_: Throwable) {
        "UNKNOWN"
    }

    /**
     * Dumps every key, including undocumented ones. Do not filter to the
     * android.* keys you expect — the useful data is often under keys
     * nobody has documented.
     */
    private fun flattenExtras(
        extras: Bundle?,
        errors: MutableList<String>,
        prefix: String = "",
    ): List<NotificationCapture.TypedEntry> {
        if (extras == null) return emptyList()
        val out = mutableListOf<NotificationCapture.TypedEntry>()
        for (key in extras.keySet()) {
            val full = prefix + key
            try {
                @Suppress("DEPRECATION")
                when (val v = extras.get(key)) {
                    null -> out += NotificationCapture.TypedEntry(full, "null", null)
                    is Bundle -> out += flattenExtras(v, errors, "$full.")
                    is CharSequence ->
                        out += NotificationCapture.TypedEntry(full, "CharSequence", v.toString())
                    is Array<*> -> out += NotificationCapture.TypedEntry(
                        full,
                        "array<${v.firstOrNull()?.javaClass?.simpleName ?: "?"}>[${v.size}]",
                        v.joinToString(" | ") { it?.toString() ?: "null" },
                    )
                    is Icon -> out += NotificationCapture.TypedEntry(
                        full, "Icon", "type=${iconTypeName(v)}"
                    )
                    else -> out += NotificationCapture.TypedEntry(
                        full, v.javaClass.simpleName, v.toString()
                    )
                }
            } catch (t: Throwable) {
                errors += "extras[$full] failed: ${t.message}"
            }
        }
        return out
    }

    private fun flagNames(flags: Int): List<String> = buildList {
        fun bit(mask: Int, name: String) { if (flags and mask != 0) add(name) }
        bit(Notification.FLAG_ONGOING_EVENT, "ONGOING_EVENT")
        bit(Notification.FLAG_NO_CLEAR, "NO_CLEAR")
        bit(Notification.FLAG_FOREGROUND_SERVICE, "FOREGROUND_SERVICE")
        bit(Notification.FLAG_AUTO_CANCEL, "AUTO_CANCEL")
        bit(Notification.FLAG_GROUP_SUMMARY, "GROUP_SUMMARY")
        bit(Notification.FLAG_LOCAL_ONLY, "LOCAL_ONLY")
        bit(Notification.FLAG_ONLY_ALERT_ONCE, "ONLY_ALERT_ONCE")
        bit(Notification.FLAG_INSISTENT, "INSISTENT")
    }

    @Suppress("DEPRECATION")
    private fun priorityName(p: Int) = when (p) {
        Notification.PRIORITY_MIN -> "MIN"
        Notification.PRIORITY_LOW -> "LOW"
        Notification.PRIORITY_DEFAULT -> "DEFAULT"
        Notification.PRIORITY_HIGH -> "HIGH"
        Notification.PRIORITY_MAX -> "MAX"
        else -> "UNKNOWN($p)"
    }

    private fun visibilityName(v: Int) = when (v) {
        Notification.VISIBILITY_PUBLIC -> "PUBLIC"
        Notification.VISIBILITY_PRIVATE -> "PRIVATE"
        Notification.VISIBILITY_SECRET -> "SECRET"
        else -> "UNKNOWN($v)"
    }

    // ── output ────────────────────────────────────────────────────────

    private fun logcat(c: NotificationCapture) {
        Log.i(TAG, "════ ${NotificationCapture.iso(c.capturedAtMillis)} id=${c.notificationId}")
        Log.i(TAG, "cat=${c.category} chan=${c.channelId} prio=${c.priorityName} " +
            "vis=${c.visibilityName} flags=${c.flagNames}")
        Log.i(TAG, "smallIcon=${c.smallIcon?.resourceName ?: c.smallIcon?.iconType}")
        Log.i(TAG, "EXTRAS (${c.extras.size}):")
        c.extras.forEach { Log.i(TAG, "   ${it.key} [${it.type}] = ${it.value}") }
        c.layouts.forEach { l ->
            Log.i(TAG, "LAYOUT ${l.layoutName} res=${l.remoteViewsLayoutResourceName} " +
                "err=${l.error}")
            l.root?.flatten()?.forEach { n ->
                val indent = "  ".repeat(n.depth + 1)
                val detail = when {
                    n.text != null -> "\"${n.text}\""
                    n.image != null -> "img ${n.drawableClassName} ${n.image.sha1.take(10)}"
                    else -> ""
                }
                Log.i(TAG, "$indent${n.className.substringAfterLast('.')} " +
                    "#${n.viewIdName} ${n.visibility} $detail")
            }
        }
        if (c.extractionErrors.isNotEmpty()) Log.w(TAG, "ERRORS: ${c.extractionErrors}")
    }

    private fun rootDir(): File? = try {
        File(getExternalFilesDir(null), ROOT_DIR).apply { if (!exists()) mkdirs() }
    } catch (t: Throwable) {
        Log.e(TAG, "no dump dir", t); null
    }

    private fun persist(c: NotificationCapture) {
        val root = rootDir() ?: return
        val jsonDir = File(root, JSON_SUBDIR).apply { if (!exists()) mkdirs() }

        if ((jsonDir.list()?.size ?: 0) >= MAX_JSON_FILES) {
            Log.w(TAG, "capture file cap reached; not writing")
            return
        }

        try {
            File(jsonDir, "${c.fileNameStem()}.json")
                .writeText(c.toJson().toString(2))
        } catch (t: Throwable) {
            Log.e(TAG, "write failed", t)
        }

        // One-line index alongside the per-capture files: much easier to
        // scan and diff across a whole session than opening 400 files.
        appendIndex(c.toJson())
    }

    private fun appendIndex(json: JSONObject) {
        val root = rootDir() ?: return
        try {
            File(root, INDEX_FILE).appendText(json.toString() + "\n")
        } catch (t: Throwable) {
            Log.e(TAG, "index append failed", t)
        }
    }
}
