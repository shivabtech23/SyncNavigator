# Build notes — Phase 1 scaffolding

## Before anything else: the Gradle wrapper

`gradle/wrapper/gradle-wrapper.properties` is included, but
**`gradle-wrapper.jar`, `gradlew` and `gradlew.bat` are not** — the jar is a
binary and cannot be generated as text.

Pick one:

- **Open the project folder in Android Studio.** It detects the missing
  wrapper and offers to generate it on first sync. This is the easy path.
- Or, with Gradle installed locally: `gradle wrapper --gradle-version 8.9`

## Then

1. Open in Android Studio, let it sync.
2. If sync fails on the Android Gradle Plugin version, adjust it in the root
   `build.gradle.kts`. AGP must match your Studio version. This is the most
   common first-run failure and is unrelated to project code.
3. Build → Run on the phone.
4. Tap **Grant access**, enable SYNC Navigator in the listener list.
5. Confirm the screen shows `✓ notification access granted`.
6. Follow the capture protocol in `PHASE1_SETUP.md`.

## If the screen still says "NOT granted" after returning from Settings

`isListenerEnabled()` is read during composition and is not reactive, so the
line can be stale after you come back from the Settings screen. Background
and foreground the app to force recomposition. It also self-corrects as soon
as the first notification is captured.

This is existing Phase 1 behaviour and has been left untouched.

## If nothing captures at all

Check in this order:

1. Is SYNC Navigator actually ticked in the notification-access list?
   Some OEM skins (Xiaomi, Oppo, Vivo, Samsung) silently revoke it on reboot
   or when battery optimisation kicks in. Also whitelist the app from
   battery optimisation.
2. Is Google Maps actually navigating, with the notification visible in the
   shade?
3. `adb logcat -s SyncNavCapture` — if `Listener connected` never appears,
   the service is not being bound and the problem is registration, not
   capture.
4. Only if `Listener connected` never appears: try flipping
   `android:exported` to `true` on the service in `AndroidManifest.xml`.
   See the note in the handover message about this.
