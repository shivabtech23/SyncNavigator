package com.shiv.syncnavigator.sdl

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.smartdevicelink.transport.SdlBroadcastReceiver
import com.smartdevicelink.transport.SdlRouterService

/**
 * SDL PROOF OF CONCEPT — hardware validation track only.
 *
 * Entry point for the whole SDL stack. When the phone's Bluetooth connects to
 * SYNC, the library routes an intent here and we start [SdlService].
 *
 * Nothing else in the app triggers SDL. If this receiver never fires, no amount
 * of correctness in [SdlService] matters — which is why every branch logs.
 */
class SdlReceiver : SdlBroadcastReceiver() {

    companion object {
        const val TAG = "SdlPoc"
    }

    /**
     * Called by the library once it has decided SDL is available on this
     * connection. This is the first genuinely encouraging log line to look for.
     */
    override fun onSdlEnabled(context: Context?, intent: Intent?) {
        Log.i(TAG, "onSdlEnabled — SDL connection detected, starting SdlService")
        if (context == null || intent == null) {
            Log.e(TAG, "onSdlEnabled with null context/intent — cannot start service")
            return
        }
        intent.setClass(context, SdlService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            // Android 12+ can refuse background foreground-service starts.
            // Bluetooth-connection broadcasts are normally exempt, so if this
            // fires the exemption did not apply and that is the finding.
            Log.e(TAG, "startForegroundService REFUSED: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    /**
     * Tells the library which router service belongs to this app. Returning the
     * wrong class here is a silent failure — the app builds, connects to nothing.
     */
    override fun defineLocalSdlRouterClass(): Class<out SdlRouterService> =
        com.shiv.syncnavigator.sdl.SdlRouterService::class.java

    /**
     * super.onReceive() is mandatory — it drives the library's own connection
     * state machine. Omitting it is a well-known way to get a stack that never
     * starts, so the log line goes after the super call, not instead of it.
     */
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "Receiver BEFORE super: ${intent?.action}")
        super.onReceive(context, intent)
        Log.d(TAG, "Receiver AFTER super: ${intent?.action}")
    }
}
