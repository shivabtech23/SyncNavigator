package com.shiv.syncnavigator.sdl

import com.shiv.syncnavigator.navigation.display.DisplayManager
import com.shiv.syncnavigator.navigation.parser.NavigationStepProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.shiv.syncnavigator.R
import com.smartdevicelink.managers.SdlManager
import com.smartdevicelink.managers.SdlManagerListener
import com.smartdevicelink.managers.lifecycle.LifecycleConfigurationUpdate
import com.smartdevicelink.protocol.enums.FunctionID
import com.smartdevicelink.proxy.RPCNotification
import com.smartdevicelink.proxy.RPCResponse
import com.smartdevicelink.proxy.rpc.OnHMIStatus
import com.smartdevicelink.proxy.rpc.Show
import com.smartdevicelink.proxy.rpc.enums.AppHMIType
import com.smartdevicelink.proxy.rpc.enums.HMILevel
import com.smartdevicelink.proxy.rpc.enums.Language
import com.smartdevicelink.proxy.rpc.enums.PredefinedWindows
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener
import com.smartdevicelink.transport.MultiplexTransportConfig
import com.smartdevicelink.util.DebugTool
import com.smartdevicelink.util.SystemInfo
import java.util.Vector

/**
 * SDL PROOF OF CONCEPT — hardware validation track only.
 *
 * Single success criterion: the app appears in SYNC's Mobile Apps menu, and
 * selecting it puts two lines of text on the display.
 *
 * No navigation, no Maps, no parser, no DisplayManager. This service exists to
 * answer one question — can this phone talk to this head unit — and is expected
 * to be thrown away or heavily rewritten once it has.
 */
class SdlService : Service() {

    companion object {
        const val TAG = "SdlPoc"

        /**
         * Arbitrary app ID. Whether SYNC accepts an ID that is not in Ford's
         * policy table is precisely what this spike measures. If registration
         * succeeds but HMI level never leaves NONE and the app never appears in
         * the menu, this string is the reason, not the code below.
         */
        const val APP_ID = "8675309"

        /** Shown in the SYNC Mobile Apps menu. Keep it short — SYNC 1 truncates. */
        const val APP_NAME = "SYNC Navigator"

        const val LINE_1 = "SYNC Navigator"
        const val LINE_2 = "HELLO SHIV"

        private const val CHANNEL_ID = "sdl_poc"
        private const val FOREGROUND_ID = 8675
    }

    private var sdlManager: SdlManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var navJob: Job? = null
    private var lastLines: DisplayManager.Lines? = null
    private var inHmiFull = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SdlService.onCreate")
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "SdlService.onStartCommand")
        startSdl()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "SdlService.onDestroy — disposing SdlManager")
        navJob?.cancel()
        navJob = null
        scope.cancel()
        sdlManager?.dispose()
        sdlManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The service must reach the foreground almost immediately or Android kills
     * it. This notification is scaffolding, not a feature.
     */
    private fun startAsForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SDL connection", NotificationManager.IMPORTANCE_LOW)
            )
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SYNC Navigator")
                .setContentText("SDL proof of concept running")
                .setSmallIcon(R.drawable.ic_launcher)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            startForeground(FOREGROUND_ID, builder.build())
        } catch (t: Throwable) {
            // Android 14+ is strict about foreground service types. If this
            // throws, the service will likely be killed — and knowing that is
            // more useful than crashing here.
            Log.e(TAG, "Unable to enter foreground: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun startSdl() {
        if (sdlManager != null) {
            Log.d(TAG, "SdlManager already running — ignoring duplicate start")
            return
        }

        // Turns on the library's own verbose logging. This is what actually
        // satisfies "log every RPC sent and received" — it dumps the protocol
        // handshake, the RegisterAppInterface response (including the head
        // unit's display capabilities) and every subsequent message to logcat.
        DebugTool.enableDebugTool()

        Log.i(TAG, "Building SdlManager appId=$APP_ID appName=$APP_NAME")

        // FLAG_MULTI_SECURITY_OFF: SYNC 1 predates SDL's security layer.
        // Requiring security here would fail the handshake outright.
        val transport = MultiplexTransportConfig(
            this,
            APP_ID,
            MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF,
        )

        val listener = object : SdlManagerListener {
            override fun onStart() {
                Log.i(TAG, "SdlManagerListener.onStart — session established, registered with SYNC")
                sdlManager?.let { registerNotificationLogging(it) }
            }

            override fun onDestroy() {
                Log.w(TAG, "SdlManagerListener.onDestroy — session ended, stopping service")
                stopSelf()
            }

            override fun onError(info: String?, e: Exception?) {
                Log.e(TAG, "SdlManagerListener.onError info=$info", e)
            }

            override fun managerShouldUpdateLifecycle(
                language: Language?,
                hmiLanguage: Language?,
            ): LifecycleConfigurationUpdate? {
                Log.i(TAG, "managerShouldUpdateLifecycle language=$language hmiLanguage=$hmiLanguage")
                return null
            }

            override fun onSystemInfoReceived(systemInfo: SystemInfo?): Boolean {
                Log.i(
                    TAG,
                    "onSystemInfoReceived make=${systemInfo?.vehicleType?.make} " +
                        "model=${systemInfo?.vehicleType?.model} " +
                        "systemSoftware=${systemInfo?.systemSoftwareVersion} " +
                        "systemHardware=${systemInfo?.systemHardwareVersion}",
                )
                // MUST be true. Android Studio auto-generates this as `false`,
                // which silently prevents the app from ever connecting.
                return true
            }
        }

        val manager = SdlManager.Builder(this, APP_ID, APP_NAME, listener).apply {
            // Vector, not listOf(). The builder's signature is Vector<AppHMIType>;
            // a Kotlin List does not satisfy it. Vector also satisfies a List
            // parameter, so this is correct either way.
            //
            // DEFAULT, not NAVIGATION. SYNC 1-era AppLink was built for media
            // apps; NAVIGATION risks rejection for reasons unrelated to this code.
            // Prove Show() works first, argue about app type afterwards.
            setAppTypes(Vector(listOf(AppHMIType.DEFAULT)))
            setTransportType(transport)
        }.build()

        sdlManager = manager


        Log.i(TAG, "SdlManager.start() — waiting for SYNC")
        manager.start()
    }

    /**
     * Logs the notifications that explain *why* a connection did or did not work.
     * OnHMIStatus is the important one: it is the difference between "SYNC saw
     * us" and "SYNC let the user open us".
     */
    private fun registerNotificationLogging(manager: SdlManager) {
        manager.addOnRPCNotificationListener(
            FunctionID.ON_HMI_STATUS,
            object : OnRPCNotificationListener() {
                override fun onNotified(notification: RPCNotification?) {
                    val status = notification as? OnHMIStatus ?: return

                    // Status can arrive for widget windows as well as the main
                    // one. Only the default window is the display we care about.
                    val windowId = status.windowID
                    if (windowId != null && windowId != PredefinedWindows.DEFAULT_WINDOW.value) {
                        Log.d(TAG, "OnHMIStatus for non-default window $windowId — ignoring")
                        return
                    }

                    Log.i(
                        TAG,
                        "OnHMIStatus hmiLevel=${status.hmiLevel} " +
                            "audioStreamingState=${status.audioStreamingState} " +
                            "systemContext=${status.systemContext} " +
                            "firstRun=${status.firstRun}",
                    )
                    inHmiFull = status.hmiLevel == HMILevel.HMI_FULL
                    if (inHmiFull) startNavigationUpdates()
                }
            },
        )

        manager.addOnRPCNotificationListener(
            FunctionID.ON_APP_INTERFACE_UNREGISTERED,
            object : OnRPCNotificationListener() {
                override fun onNotified(notification: RPCNotification?) {
                    Log.w(TAG, "OnAppInterfaceUnregistered: $notification")
                }
            },
        )

        manager.addOnRPCNotificationListener(
            FunctionID.ON_PERMISSIONS_CHANGE,
            object : OnRPCNotificationListener() {
                // If the app registers but cannot do anything, the policy table
                // is the reason and this is where it shows up.
                override fun onNotified(notification: RPCNotification?) {
                    Log.i(TAG, "OnPermissionsChange: $notification")
                }
            },
        )

        manager.addOnRPCNotificationListener(
            FunctionID.ON_DRIVER_DISTRACTION,
            object : OnRPCNotificationListener() {
                override fun onNotified(notification: RPCNotification?) {
                    Log.d(TAG, "OnDriverDistraction: $notification")
                }
            },
        )
    }

    /**
     * Raw Show rather than the ScreenManager, deliberately.
     *
     * ScreenManager negotiates templates and text-field counts, which is the
     * right choice against a known head unit. Against a 2016 SYNC 1 it adds a
     * layer that can fail for its own reasons. Show with two main fields is the
     * smallest thing that can possibly work, and its response tells us exactly
     * what SYNC thought of it.
     */
    /** Starts streaming navigation to the display. Called on HMI_FULL. */
    private fun startNavigationUpdates() {
        if (navJob != null) return
        Log.i(TAG, "HMI_FULL — starting navigation updates")

        navJob = scope.launch {
            NavigationStepProvider.steps.collect { step ->
                if (!inHmiFull) return@collect

                // Hold previous guidance through reroute and redaction rather
                // than blanking the screen.
                if (DisplayManager.shouldHold(step) && lastLines != null) return@collect

                val lines = DisplayManager.format(step)
                if (lines == lastLines) return@collect   // nothing changed
                lastLines = lines

                Log.i(TAG, "Show: '${lines.line1}' / '${lines.line2}'")
                sendShow(lines)
            }
        }
    }

    private fun sendShow(lines: DisplayManager.Lines) {
        val show = Show().apply {
            mainField1 = lines.line1
            mainField2 = lines.line2
            mainField3 = lines.line3 ?: ""
            mainField4 = lines.line4 ?: ""
        }

        show.onRPCResponseListener = object : OnRPCResponseListener() {
            override fun onResponse(correlationId: Int, response: RPCResponse?) {
                if (response?.success != true) {
                    Log.w(
                        TAG,
                        "Show FAILED corrId=$correlationId " +
                                "result=${response?.resultCode} info=${response?.info}",
                    )
                }
            }
        }

        sdlManager?.sendRPC(show)
    }}