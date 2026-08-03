# Phase 1 — Setup and Capture Protocol

## minSdk

Set **`minSdk = 26`** in `build.gradle.kts`. `Notification.getChannelId()`
is called unconditionally in the logger and requires API 26 — anything
lower crashes on the first captured notification. (Icon/smallIcon/
largeIcon only need 23; channel data is the binding constraint.) Not a
concern for the demo itself — any phone you'd actually carry runs well
above this — but it must be declared or a stray old test device will
crash silently.

## Manifest

No storage permission is declared, deliberately. `getExternalFilesDir()`
writes to the app's own directory and has never required
`READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE`, on any Android
version — declaring it would be dead weight per project rule 4.

```xml
<!-- Required on Android 11+ to read Maps' resources, which is how we turn
     an icon id into a readable name like "ic_maneuver_turn_left".
     Without this, resourceName comes back null everywhere. -->
<queries>
    <package android:name="com.google.android.apps.maps" />
    <package android:name="com.google.android.apps.mapslite" />
</queries>

<application ...>

    <service
        android:name=".navigation.notification.NotificationLoggerService"
        android:exported="false"
        android:label="SYNC Navigator capture"
        android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
        <intent-filter>
            <action android:name="android.service.notification.NotificationListenerService" />
        </intent-filter>
    </service>

    <provider
        android:name="androidx.core.content.FileProvider"
        android:authorities="${applicationId}.fileprovider"
        android:exported="false"
        android:grantUriPermissions="true">
        <meta-data
            android:name="android.support.FILE_PROVIDER_PATHS"
            android:resource="@xml/file_paths" />
    </provider>

</application>
```

`res/xml/file_paths.xml`:

```xml
<paths>
    <external-files-path name="captures" path="captures/" />
</paths>
```

No Gradle dependencies beyond Compose + `androidx.core`. JSON uses `org.json`, which is in the Android framework — nothing to add.

---

## Capture protocol — do this today

The quality of Phase 2 depends entirely on how varied this capture set is. A single straight drive gives me four maneuvers and a parser that breaks on the demo route.

1. Install, open the app, tap **Grant access**, enable SYNC Navigator in the listener list.
2. Confirm the screen shows `✓ notification access granted`.
3. Start a Google Maps route. **Leave Maps in the background** — pull down the notification shade so the notification is live.
4. Drive or simulate a route that includes, at minimum:
   - a left and a right turn
   - a roundabout with a numbered exit
   - a slight/bear turn
   - a highway merge or exit if you can get one
   - arrival at the destination
   - **a deliberate wrong turn** so you capture the rerouting state
5. Also capture: what happens when you lose GPS (underground parking works), and what the notification looks like in the last 50 m before arrival.
6. Tap **Export** and send me `notifications.jsonl`.

You do not need the car for any of this. A walk with Maps in walking mode captures most of it; driving mode is better if you can manage it.

---

## What I need back

- `captures/index.jsonl` — one line per unique capture, easiest to diff across a session
- `captures/json/` — the per-capture pretty-printed files
- `captures/images/` — **this matters more than you'd think.** If the maneuver turns out to be image-only, those PNGs and their SHA-1s become the parser's lookup table, and I can't build it without them.
- Your Android version and the Google Maps version from Play Store → Maps → About

---

## What to look for while capturing

Watch the debug screen during the first route and note which of these is true:

| Observation | What it means for Phase 2 |
|---|---|
| `android.title` / `android.text` have real instruction text | Best case. Text parser, straightforward. |
| Extras are null but REMOTEVIEW TEXTS is populated | Expected case. Parse the view tree by `viewId`. Still fine. |
| Texts present, but no maneuver word — only distance and road | Maneuver is image-only. Parser keys on image SHA-1. Workable but needs a full icon set, hence the varied-route requirement. |
| Both empty and ERRORS is populated | Inflation is being blocked. Tell me the error text — there are fallbacks, but I need to see which one failed. |

That last row is the one that would genuinely threaten the approach. Everything above it is tractable.

---

## Rule for this phase

If you find yourself tempted to add a `when (title.contains("left"))` anywhere in this code — stop. That's Phase 2, and it gets written against real data, not against the example payload in the spec.
