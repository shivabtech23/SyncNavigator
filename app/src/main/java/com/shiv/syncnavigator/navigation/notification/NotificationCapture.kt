package com.shiv.syncnavigator.navigation.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * PHASE 1 DATA MODEL — pure observation, zero interpretation.
 *
 * Nothing in this file knows what a maneuver is. If a field name here
 * starts sounding like navigation vocabulary, it's drifted into Phase 2.
 */
data class NotificationCapture(
    val capturedAtMillis: Long,
    val postedAtMillis: Long,
    val whenMillis: Long,

    val packageName: String,
    val notificationKey: String,
    val notificationId: Int,
    val tag: String?,
    val category: String?,
    val channelId: String?,
    val group: String?,
    val sortKey: String?,

    val flags: Int,
    val flagNames: List<String>,
    val priority: Int,
    val priorityName: String,
    val visibility: Int,
    val visibilityName: String,
    val isOngoing: Boolean,
    val isClearable: Boolean,

    val extras: List<TypedEntry>,

    /** One entry per RemoteViews layout that existed. Captured separately. */
    val layouts: List<LayoutCapture>,

    val smallIcon: IconInfo?,
    val largeIcon: IconInfo?,

    val actions: List<ActionInfo>,

    val extractionErrors: List<String>,
) {
    data class TypedEntry(val key: String, val type: String, val value: String?)

    data class ActionInfo(
        val title: String?,
        val hasRemoteInputs: Boolean,
        val icon: IconInfo?,
    )

    data class IconInfo(
        val iconType: String,
        val resId: Int?,
        /** Resolved against the SOURCE app's resources, e.g. Google Maps. */
        val resourceName: String?,
        val image: CapturedImage?,
    )

    data class CapturedImage(
        val sha1: String,
        val widthPx: Int,
        val heightPx: Int,
        val savedFileName: String?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("sha1", sha1)
            put("widthPx", widthPx)
            put("heightPx", heightPx)
            put("savedFileName", savedFileName ?: JSONObject.NULL)

    }}

    /**
     * One inflated RemoteViews layout. contentView and bigContentView are
     * captured independently rather than first-wins, because the collapsed
     * and expanded layouts do not necessarily carry the same fields — and
     * while driving, the shade is usually collapsed.
     */
    data class LayoutCapture(
        val layoutName: String,
        val remoteViewsPackage: String?,
        val remoteViewsLayoutId: Int?,
        val remoteViewsLayoutResourceName: String?,
        val root: ViewNode?,
        val error: String?,
    )

    /**
     * A node in the inflated hierarchy. EVERY view is recorded, not just
     * TextView and ImageView — a custom subclass showing up here is itself
     * a finding worth having.
     */
    data class ViewNode(
        val depth: Int,
        val className: String,
        val viewIdName: String?,
        val visibility: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val contentDescription: String?,

        // TextView-only
        val text: String? = null,
        val textSizePx: Float? = null,
        val textColor: String? = null,
        val maxLines: Int? = null,
        val ellipsize: String? = null,

        // ImageView-only
        val drawableClassName: String? = null,
        val drawableIntrinsicWidth: Int? = null,
        val drawableIntrinsicHeight: Int? = null,
        val scaleType: String? = null,
        val image: CapturedImage? = null,

        val children: List<ViewNode> = emptyList(),
    ) {
        fun flatten(): List<ViewNode> = listOf(this) + children.flatMap { it.flatten() }

        fun toJson(): JSONObject = JSONObject().apply {
            put("depth", depth)
            put("class", className)
            put("viewId", viewIdName ?: JSONObject.NULL)
            put("visibility", visibility)
            put("bounds", "$left,$top,$right,$bottom")
            put("contentDescription", contentDescription ?: JSONObject.NULL)
            text?.let { put("text", it) }
            textSizePx?.let { put("textSizePx", it) }
            textColor?.let { put("textColor", it) }
            maxLines?.let { put("maxLines", it) }
            ellipsize?.let { put("ellipsize", it) }
            drawableClassName?.let { put("drawableClass", it) }
            drawableIntrinsicWidth?.let { put("drawableIntrinsicWidth", it) }
            drawableIntrinsicHeight?.let { put("drawableIntrinsicHeight", it) }
            scaleType?.let { put("scaleType", it) }
            image?.let { put("image", it.toJson()) }
            if (children.isNotEmpty()) {
                put("children", JSONArray().apply { children.forEach { put(it.toJson()) } })
            }
        }
    }

    /**
     * Dedup key. Maps reposts ~2x/second; without this the capture set is
     * thousands of identical records. Includes image hashes so an arrow
     * change with unchanged text still counts as a new event.
     */
    fun contentSignature(): String = buildString {
        append(packageName).append('|')
        extras.forEach { append(it.key).append('=').append(it.value).append(';') }
        layouts.forEach { layout ->
            append(layout.layoutName).append(':')
            layout.root?.flatten()?.forEach { n ->
                n.text?.let { append(it).append(';') }
                n.image?.let { append(it.sha1).append(';') }
                append(n.visibility).append(',')
            }
        }
    }

    fun fileNameStem(): String =
        "capture_${capturedAtMillis}_${notificationId}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("capturedAt", iso(capturedAtMillis))
        put("capturedAtMillis", capturedAtMillis)
        put("postedAtMillis", postedAtMillis)
        put("when", whenMillis)

        put("packageName", packageName)
        put("key", notificationKey)
        put("id", notificationId)
        put("tag", tag ?: JSONObject.NULL)
        put("category", category ?: JSONObject.NULL)
        put("channelId", channelId ?: JSONObject.NULL)
        put("group", group ?: JSONObject.NULL)
        put("sortKey", sortKey ?: JSONObject.NULL)

        put("flags", flags)
        put("flagNames", JSONArray(flagNames))
        put("priority", priority)
        put("priorityName", priorityName)
        put("visibility", visibility)
        put("visibilityName", visibilityName)
        put("ongoing", isOngoing)
        put("clearable", isClearable)

        put("extras", JSONArray().apply {
            extras.forEach {
                put(JSONObject().apply {
                    put("key", it.key)
                    put("type", it.type)
                    put("value", it.value ?: JSONObject.NULL)
                })
            }
        })

        put("layouts", JSONArray().apply {
            layouts.forEach { l ->
                put(JSONObject().apply {
                    put("layoutName", l.layoutName)
                    put("remoteViewsPackage", l.remoteViewsPackage ?: JSONObject.NULL)
                    put("remoteViewsLayoutId", l.remoteViewsLayoutId ?: JSONObject.NULL)
                    put("remoteViewsLayoutRes", l.remoteViewsLayoutResourceName ?: JSONObject.NULL)
                    put("root", l.root?.toJson() ?: JSONObject.NULL)
                    put("error", l.error ?: JSONObject.NULL)
                })
            }
        })

        put("smallIcon", smallIcon?.toJson() ?: JSONObject.NULL)
        put("largeIcon", largeIcon?.toJson() ?: JSONObject.NULL)

        put("actions", JSONArray().apply {
            actions.forEach {
                put(JSONObject().apply {
                    put("title", it.title ?: JSONObject.NULL)
                    put("hasRemoteInputs", it.hasRemoteInputs)
                    put("icon", it.icon?.toJson() ?: JSONObject.NULL)
                })
            }
        })

        put("extractionErrors", JSONArray(extractionErrors))
    }

    private fun IconInfo.toJson() = JSONObject().apply {
        put("iconType", iconType)
        put("resId", resId ?: JSONObject.NULL)
        put("resourceName", resourceName ?: JSONObject.NULL)
        put("image", image?.toJson() ?: JSONObject.NULL)
    }

    private fun CapturedImage.toJson() = JSONObject().apply {
        put("sha1", sha1)
        put("width", widthPx)
        put("height", heightPx)
        put("file", savedFileName ?: JSONObject.NULL)
    }

    companion object {
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }

        fun iso(millis: Long): String = synchronized(ISO) { ISO.format(millis) }
    }
}

/** Process-wide diagnostic buffer for the debug screen. Bounded. */
object CaptureStore {
    private const val MAX_RETAINED = 30

    private val _latest = MutableStateFlow<NotificationCapture?>(null)
    val latest: StateFlow<NotificationCapture?> = _latest.asStateFlow()

    private val _recent = MutableStateFlow<List<NotificationCapture>>(emptyList())
    val recent: StateFlow<List<NotificationCapture>> = _recent.asStateFlow()

    private val _totalSeen = MutableStateFlow(0)
    val totalSeen: StateFlow<Int> = _totalSeen.asStateFlow()

    private val _uniqueCount = MutableStateFlow(0)
    val uniqueCount: StateFlow<Int> = _uniqueCount.asStateFlow()

    fun record(capture: NotificationCapture, isUnique: Boolean) {
        _totalSeen.value += 1
        _latest.value = capture
        if (isUnique) {
            _uniqueCount.value += 1
            _recent.value = (listOf(capture) + _recent.value).take(MAX_RETAINED)
        }
    }

    fun clear() {
        _latest.value = null
        _recent.value = emptyList()
        _totalSeen.value = 0
        _uniqueCount.value = 0
    }
}
