package com.shiv.syncnavigator.navigation.notification

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Inflates each RemoteViews layout and records the ENTIRE hierarchy.
 *
 * WHY: Google Maps builds its navigation notification with a custom
 * RemoteViews. Community reports consistently show android.title and
 * android.text arriving null, with the real content only inside the
 * custom view. Reading extras alone makes the approach look impossible
 * when it isn't.
 *
 * Reflection on RemoteViews.mActions is the other known technique and is
 * deliberately avoided: mActions sits on the hidden-API blocklist and
 * will fail on modern Android. Inflating is slower but uses public API.
 *
 * MUST run on the main thread — this inflates real views.
 */
class ViewTreeInspector(
    private val context: Context,
    private val sourcePackage: String,
    private val imageDir: File?,
) {
    /** Resources of the notifying app, for turning ids into readable names. */
    private val sourceResources: Resources? = try {
        context.packageManager.getResourcesForApplication(sourcePackage)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Throwable) {
        null
    }

    /**
     * Captures contentView, bigContentView and headsUpContentView
     * independently. Not first-wins: the collapsed and expanded layouts
     * may carry different fields, and while driving the shade is usually
     * collapsed, so the collapsed layout is the one that has to work.
     */
    fun inspectAll(
        notification: Notification,
        errors: MutableList<String>,
    ): List<NotificationCapture.LayoutCapture> {
        val out = mutableListOf<NotificationCapture.LayoutCapture>()

        val sources: List<Pair<String, RemoteViews?>> = listOf(
            "contentView" to notification.contentView,
            "bigContentView" to notification.bigContentView,
            "headsUpContentView" to notification.headsUpContentView,
        )

        for ((name, rv) in sources) {
            if (rv == null) continue
            out += inspectOne(name, rv)
        }

        // If the app supplied no custom RemoteViews at all, rebuild the
        // system-decorated view so we still see the rendered tree.
        if (out.none { it.root != null }) {
            try {
                val builder = Notification.Builder.recoverBuilder(context, notification)
                listOf(
                    "recovered.contentView" to builder.createContentView(),
                    "recovered.bigContentView" to builder.createBigContentView(),
                ).forEach { (name, rv) ->
                    if (rv != null) out += inspectOne(name, rv)
                }
            } catch (t: Throwable) {
                errors += "recoverBuilder failed: ${t.javaClass.simpleName}: ${t.message}"
            }
        }

        return out
    }

    private fun inspectOne(
        layoutName: String,
        rv: RemoteViews,
    ): NotificationCapture.LayoutCapture {
        val layoutId = try { rv.layoutId } catch (_: Throwable) { null }
        val rvPackage = try { rv.getPackage() } catch (_: Throwable) { null }

        return try {
            val host = FrameLayout(context)
            val view = rv.apply(context, host)
            host.addView(view)
            measureAndLayout(host)

            NotificationCapture.LayoutCapture(
                layoutName = layoutName,
                remoteViewsPackage = rvPackage,
                remoteViewsLayoutId = layoutId,
                remoteViewsLayoutResourceName = layoutId?.let { resourceName(it) },
                root = node(view, 0),
                error = null,
            )
        } catch (t: Throwable) {
            NotificationCapture.LayoutCapture(
                layoutName = layoutName,
                remoteViewsPackage = rvPackage,
                remoteViewsLayoutId = layoutId,
                remoteViewsLayoutResourceName = layoutId?.let { resourceName(it) },
                root = null,
                error = "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    private fun measureAndLayout(host: View) {
        val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        host.measure(w, h)
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
    }

    /**
     * Records every view, whatever its type. A custom subclass appearing
     * in this tree is itself a finding — don't filter it out.
     */
    private fun node(view: View, depth: Int): NotificationCapture.ViewNode {
        var text: String? = null
        var textSize: Float? = null
        var textColor: String? = null
        var maxLines: Int? = null
        var ellipsize: String? = null

        var drawableClass: String? = null
        var dw: Int? = null
        var dh: Int? = null
        var scaleType: String? = null
        var image: NotificationCapture.CapturedImage? = null

        if (view is TextView) {
            // Capture even blank text — a field that exists but is empty
            // is different information from a field that isn't there.
            text = view.text?.toString()
            textSize = view.textSize
            textColor = "#%08X".format(view.currentTextColor)
            maxLines = view.maxLines
            ellipsize = view.ellipsize?.name
        }

        if (view is ImageView) {
            scaleType = view.scaleType?.name
            val d: Drawable? = view.drawable
            if (d != null) {
                drawableClass = d.javaClass.name
                dw = d.intrinsicWidth
                dh = d.intrinsicHeight
                image = try {
                    drawableToBitmap(d)?.let { saveImage(it) }
                } catch (_: Throwable) {
                    null
                }
            }
        }

        val children = if (view is ViewGroup) {
            (0 until view.childCount).map { node(view.getChildAt(it), depth + 1) }
        } else emptyList()

        return NotificationCapture.ViewNode(
            depth = depth,
            className = view.javaClass.name,
            viewIdName = idName(view),
            visibility = visibilityName(view.visibility),
            left = view.left,
            top = view.top,
            right = view.right,
            bottom = view.bottom,
            contentDescription = view.contentDescription?.toString(),
            text = text,
            textSizePx = textSize,
            textColor = textColor,
            maxLines = maxLines,
            ellipsize = ellipsize,
            drawableClassName = drawableClass,
            drawableIntrinsicWidth = dw,
            drawableIntrinsicHeight = dh,
            scaleType = scaleType,
            image = image,
            children = children,
        )
    }

    private fun idName(view: View): String? = try {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    } catch (_: Throwable) {
        null
    }

    /**
     * Resolve an id against the notifying app's resources. If the maneuver
     * arrow comes back as something like "ic_maneuver_turn_left", that
     * name is a far better parser key than a pixel hash — it survives
     * icon restyling across Maps releases.
     *
     * Requires a <queries> entry for the package on Android 11+.
     */
    fun resourceName(resId: Int): String? = try {
        sourceResources?.getResourceName(resId)
    } catch (_: Throwable) {
        null
    }

    private fun visibilityName(v: Int) = when (v) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> "UNKNOWN($v)"
    }

    fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 64
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 64
        if (w > 1024 || h > 1024) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(bmp))
        return bmp
    }

    /** Content-addressed by pixel hash, so identical icons store once. */
    fun saveImage(bitmap: Bitmap): NotificationCapture.CapturedImage {
        val sha = sha1(bitmap)
        var fileName: String? = null
        if (imageDir != null) {
            try {
                if (!imageDir.exists()) imageDir.mkdirs()
                val f = File(imageDir, "$sha.png")
                if (!f.exists()) {
                    FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                fileName = f.name
            } catch (_: Throwable) {
            }
        }
        return NotificationCapture.CapturedImage(sha, bitmap.width, bitmap.height, fileName)
    }

    private fun sha1(bitmap: Bitmap): String {
        val safe = if (bitmap.config == null)
            bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val buf = java.nio.ByteBuffer.allocate(safe.byteCount)
        safe.copyPixelsToBuffer(buf)
        return MessageDigest.getInstance("SHA-1").digest(buf.array())
            .joinToString("") { "%02x".format(it) }
    }
}
