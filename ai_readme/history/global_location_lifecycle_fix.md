# Global Location Hook Lifecycle Fix (Historical)

> **Superseded implementation note (2026-07-10):** this document records earlier diagnosis and design exploration. The `Location.writeToParcel()`/getter strategy described below is not the current location pipeline and must not be reintroduced. The verified path is documented in [`../technical_readme.md`](../technical_readme.md): a single system-server cadence reports through the real provider abstraction, and `LocationProviderManager.processReportedLocation()` returns one canonical fix before framework cache/filter/native delivery.

## Purpose

This document records the requirements, diagnosis, design constraints, and code changes for the location update failure that appeared after the app had been used once and the device had later been rebooted or left idle.

The main symptom was not a UI problem. It was a lifecycle and system-provider contamination problem: after spoofing had been used, Google Maps and other apps could no longer obtain a fresh real location, and the app could also fail to keep updating the spoofed location. Google Maps reported stale location information. Rebooting the device did not immediately fix the situation until the location provider stack was manually repaired.

A later test also exposed a second architectural gap: when only system/GMS packages were scoped, Google Maps could still receive real `Location` objects directly from the Android `gps` provider. This happened because the existing hooks mostly modified `Location` getter methods. Getter methods execute in the consumer process. If the consumer process is not hooked, a real `Location` object delivered through Binder can still leak its original raw fields. The fix is not to add per-app scopes. The correct system-layer fix is to modify outgoing `Location` objects before they are marshalled across Binder.

The goal of this fix is to preserve the original system-level/global spoofing architecture while removing the high-risk pieces that can leave the Android location stack in a broken state.

## User Requirements

The user explicitly required the following behavior:

1. Do not rely on Android Developer Options mock location.
2. Do not enable or depend on the mock location app setting.
3. Do not switch the design to per-app-only spoofing.
4. Keep the original global positioning strategy.
5. Hook the system/GMS/location infrastructure layer so spoofing is global.
6. Do not maintain a manual target-app scope list for Google Maps, Chinese apps, or other consumer apps.
7. While spoofing is active, the real Wi-Fi, cellular, GNSS, BLE, and related environment data must be hidden from the system/GMS location stack.
8. Any app receiving a location from the system layer should receive the spoofed location without being individually hooked.
9. If the spoofing service dies unexpectedly during the same boot session, the hooks must fail closed rather than falling back to real location/environment data.
10. After a real device reboot, stale spoofing state must not persist. The device must return to a clean system-location state.
11. Remove high-risk Test Provider and mock-location side effects.
12. Do not rewrite the entire location logic or replace the existing flow with a new architecture. Only fix the dangerous lifecycle and deadlock/stale-provider areas.

In short:

Active spoofing should be global and strict. Unexpected service death should not leak real location. A real reboot or normal stop should cleanly remove spoofing state and must not break system positioning. Consumer apps should not need to be listed one by one.

## Incident Summary

Observed symptoms:

- On the first use, spoofing appeared to work normally.
- After roughly a day or after reboot/idle cycles, the simulated location only updated once or stopped updating.
- Starting spoofing caused one visible location update, then no further refresh.
- The app UI could still show spoofing as active after reboot, which is not acceptable.
- Google Maps could no longer obtain a real fresh location and showed stale-location status.
- Disabling the module and rebooting did not immediately restore real location until provider cleanup was performed.

The device was later repaired by removing stale test providers and resetting the location stack. After that, system location recovered.

## Diagnosis

### 1. Test Provider contamination

The diagnosis report showed that Android's `gps`, `network`, or `fused` providers had been contaminated by stale Test Provider state. Once a test provider replaces a real provider inside `LocationManagerService`, the real GMS-backed provider may no longer be bound correctly.

Important observation:

- The current source tree no longer contains any `addTestProvider()` or `setTestProviderLocation()` call.
- The current source tree did contain `removeTestProvider()` calls in `SpoofingService`.
- The stale Test Provider state therefore most likely came from an older version, an earlier implementation path, manual shell operations during debugging, or a previous mock-provider flow.

Even though the current code did not add test providers, it still touched that subsystem by calling `removeTestProvider()` on `gps`, `network`, and `fused`. This is risky because:

- It requires mock-location privileges on modern Android.
- It keeps the app coupled to the forbidden mock/test-provider layer.
- It can behave differently across ROMs.
- It encourages future code to restore test-provider logic.
- It makes recovery and failure modes harder to reason about.

Decision: remove the Test Provider path completely from the app runtime.

### 2. Sticky foreground service resurrection

`SpoofingService` used `START_STICKY`. In addition, when Android restarted the service with a null intent, the service used `SpooferProvider.isActive` to restart spoofing automatically.

This is unsafe for this app's threat model because:

- `SpooferProvider` is in-process/static state and is not a reliable explicit user intent after process/service restoration.
- A service restart can happen without the user actively starting a new spoofing session.
- The UI can show an active spoofing state after reboot or after process resurrection.
- Runtime config heartbeats may continue or be interpreted incorrectly.

Decision: make spoofing an explicit session. The service must not resurrect itself from a null intent.

### 3. Stale config after service death

The Xposed layer reads JSON config from:

- `/data/local/tmp/locationspoofer_config.json`
- `/data/system/locationspoofer_config.json`

The hook layer keeps an in-memory `lastConfig` cache and polls the files. This is necessary for performance, but it creates a stale-state risk:

- If the service dies, the last file may still say `active=true`.
- If the config file cannot be read later, a process may keep using old cached state.
- If the device reboots and the old config file is still present, hooks may interpret old state as current state.

The previous implementation already had a heartbeat TTL concept, but the semantics were too simple: stale config was treated as inactive. For this user requirement, same-boot service death must not leak real data. Therefore, inactive is not always the right fail state.

Decision: split stale active config into two cases:

- Same boot, heartbeat expired: enter fail-closed mode.
- Different boot, stale config: disable spoofing completely.

### 4. Global hook scope

The earlier scope contained a mix of target apps and system packages:

- Target apps such as WeChat, DingTalk, maps, etc.
- System/infrastructure packages such as `android`, `system`, `com.android.phone`, `com.google.android.gms`.

One proposed alternative was to avoid hooking system/GMS/phone and spoof only app-facing APIs. The user rejected this because the intended design is global spoofing at the system/GMS layer.

Decision: keep the global infrastructure hook strategy, remove consumer-app target scopes, and add a system/GMS outbound `Location` serialization hook so consumer apps receive already-spoofed locations.

### 5. Binder outbound Location leakage

After the first lifecycle patch, a test showed Google Maps still receiving real GPS updates. `dumpsys location` showed lines equivalent to:

```text
gps provider delivered location[1] to com.google.android.apps.maps
```

This exposed an important distinction:

- Hooking `Location.getLatitude()` and `Location.getLongitude()` works only in the process that calls those getters.
- If Google Maps is not scoped, its own getter calls are not hooked.
- Hooking only `android`, `system`, or `com.google.android.gms` is not enough if the raw `Location` object is sent unchanged across Binder.

Therefore, a pure system-layer design needs an outbound mutation point.

The chosen point is:

```kotlin
android.location.Location.writeToParcel(Parcel, Int)
```

Reasoning:

- Binder transfers `Location` objects through `Parcelable` serialization.
- The sender process serializes the object before the consumer process receives it.
- If `writeToParcel()` is hooked in `system_server` / Android system process and GMS location provider processes, the raw fields can be changed before any app receives them.
- This allows arbitrary consumer apps to receive spoofed coordinates without being individually scoped.

This is a minimal addition that preserves the existing flow. It does not redesign the provider stack, route simulation, Wi-Fi simulation, cell simulation, GNSS simulation, or file-based config model.

## Design Policy

### Runtime states

The hook layer now effectively distinguishes these states:

1. Inactive
   - `active=false`
   - No spoofing should be applied.
   - After reboot, old config must land here.

2. Active
   - `active=true`
   - `fail_closed=false`
   - Heartbeat is fresh.
   - Location and environment data are spoofed normally.

3. Fail-closed
   - `active=true`
   - `fail_closed=true`
   - Heartbeat expired during the same boot session.
   - The hook layer should not reveal real Wi-Fi/cell/GNSS/BLE data.
   - Environment data is sanitized to empty/null values.
   - Outgoing `Location` objects continue to be rewritten using the last known spoofed coordinates.
   - This prevents an app from suddenly jumping back to real location if the foreground service dies.

### Boot boundary

Each config now carries the current boot ID from:

```text
/proc/sys/kernel/random/boot_id
```

If a hooked process reads a config whose `boot_id` does not match the current device boot ID, that config is treated as stale from a previous boot and is disabled instead of entering fail-closed mode.

This is the key distinction:

- Service died during this boot: fail closed.
- Device rebooted: clean slate.

### No Android mock-location path

The app should not:

- Request `ACCESS_MOCK_LOCATION`.
- Enable mock-location appops.
- Add test providers.
- Set test provider locations.
- Remove test providers during normal runtime.
- Use mock provider cleanup as part of normal start/stop.

Any emergency recovery of stale test providers must remain an explicit manual diagnostic action, not part of normal app behavior.

### System-layer outbound location policy

All ordinary location consumers are intentionally left out of `scope.list`.

Instead, the scoped infrastructure processes are responsible for:

1. Blocking or replacing real Wi-Fi, cell, BLE, GNSS, and connectivity environment signals.
2. Feeding virtual environment data from the app's config.
3. Rewriting outgoing `Location` objects before Binder delivery.

The key invariant is:

```text
No consumer app should need a dedicated Xposed scope entry in order to receive spoofed coordinates.
```

This is stricter and cleaner than the previous target-app model.

## Implemented Changes

### 1. Manifest mock permission removed

File:

```text
app/src/main/AndroidManifest.xml
```

Removed:

```xml
<uses-permission android:name="android.permission.ACCESS_MOCK_LOCATION" />
```

Reason:

The app must not advertise or rely on Android mock-location permission. Developer Options mock app is an obvious detection signal and is explicitly forbidden by the user.

### 2. SpoofingService no longer touches Test Providers

File:

```text
app/src/main/java/com/shiraka/locatiobprovid/service/SpoofingService.kt
```

Removed the `LocationManager` dependency from the service.

Removed:

```kotlin
clearLegacyTestProviders()
removeTestProvider(LocationManager.GPS_PROVIDER)
removeTestProvider(LocationManager.NETWORK_PROVIDER)
removeTestProvider("fused")
```

Reason:

The service must never manipulate Android test providers during normal operation. This avoids re-contaminating `gps`, `network`, or `fused` providers.

### 3. SpoofingService no longer auto-resurrects

File:

```text
app/src/main/java/com/shiraka/locatiobprovid/service/SpoofingService.kt
```

Changed service return behavior:

```kotlin
return START_NOT_STICKY
```

Changed null-intent behavior:

```kotlin
if (intent == null) {
    stopSelf()
    return START_NOT_STICKY
}
```

Reason:

Spoofing must be an explicit user-started session. Android must not silently resurrect the spoofing service after process death or resource pressure.

### 4. Config now records boot ID

Files:

```text
app/src/main/java/com/shiraka/locatiobprovid/utils/ConfigManager.kt
app/src/main/java/com/shiraka/locatiobprovid/service/SpoofingService.kt
```

Added fields written into config:

```json
{
  "boot_id": "...",
  "fail_closed": false
}
```

Reason:

The hook layer needs to know whether a stale active config belongs to the current boot or a previous boot.

### 5. Config normalization now supports fail-closed mode

File:

```text
app/src/main/java/com/shiraka/locatiobprovid/xposed/LocationHooker.kt
```

New behavior:

- If `active=true` and the config boot ID differs from the current boot ID:
  - Set `active=false`.
  - Set `fail_closed=false`.
- If `active=true`, boot ID matches, and heartbeat is older than the TTL:
  - Keep `active=true`.
  - Set `fail_closed=true`.
  - Replace Wi-Fi JSON with a disconnected/empty state.
  - Replace cell JSON with an empty array.
  - Replace Bluetooth JSON with an empty array.
  - Set satellite count to 0.

Reason:

This preserves the user's requested same-boot fail-closed behavior while guaranteeing reboot cleanup.

### 6. Config read failure refreshes cached stale config

File:

```text
app/src/main/java/com/shiraka/locatiobprovid/xposed/LocationHooker.kt
```

When file reads fail, the hook layer now normalizes any existing `lastConfig` cache. This prevents a cached `active=true` state from living forever without heartbeat checks.

Reason:

The in-memory cache is necessary for low-latency hooks, but it must not bypass lifecycle rules.

### 7. Normal app start/stop no longer revokes mock appops

Files:

```text
app/src/main/java/com/shiraka/locatiobprovid/data/repository/LocationRepository.kt
app/src/main/java/com/shiraka/locatiobprovid/utils/RootManager.kt
```

Removed calls to:

```kotlin
rootManager.revokeMockLocation()
```

Removed helper that executed:

```text
appops set com.shiraka.locatiobprovid android:mock_location deny
```

Reason:

Even denying mock location is still touching the mock-location appops path during normal operation. The user wants this path avoided entirely.

### 8. Xposed scope narrowed to infrastructure packages

File:

```text
app/src/main/resources/META-INF/xposed/scope.list
```

Current scope:

```text
android
system
com.android.phone
com.google.android.gms
com.android.location.fused
com.android.networkstack
```

Reason:

This preserves the user's requested global positioning strategy by targeting the system/GMS/location infrastructure layer instead of a list of individual apps.

The following package types are intentionally not included:

- Google Maps as a consumer app.
- WeChat, DingTalk, AMap, Baidu Map, Tencent Map, Meituan, Chaoxing, or other China-specific target apps.
- Any ordinary third-party location consumer.

Those apps should receive already-mutated `Location` data from the system/GMS layer.

### 9. Outbound Location Parcelable hook added

File:

```text
app/src/main/java/com/shiraka/locatiobprovid/xposed/LocationHooker.kt
```

Added a hook for:

```kotlin
android.location.Location.writeToParcel(Parcel, Int)
```

Before the object is written to a Parcel, the hook:

- Reads current spoofing config.
- Confirms `active=true`.
- Applies the spoofed latitude and longitude directly onto the `Location` object.
- Refreshes location time and elapsed realtime.
- Rewrites accuracy with the existing jitter logic.
- Optionally rewrites altitude when configured.
- Sets provider to `gps`.
- Clears mock flags such as `mMock` and `mIsFromMockProvider`.
- Removes mock markers from extras.
- Adds a satellite-count extra.

Why this matters:

The previous getter-only hook could not protect an unscoped consumer process. `writeToParcel()` is executed on the sender side during Binder transfer, so the consumer app receives a modified `Location` object even if that app is not scoped.

This closes the leak observed in Google Maps without returning to a manual target-app scope list.

## What Was Intentionally Not Changed

The following were intentionally left intact:

- The file-based config IPC model.
- The Xposed/libxposed hook architecture.
- The existing coordinate conversion logic.
- The existing Wi-Fi, cell, BLE, GNSS, NMEA, and anti-detection hooks.
- The original global spoofing concept.
- The existing UI flow.
- The existing trajectory and jitter implementation.
- The system/GMS/global hook strategy.

This fix is deliberately a lifecycle and risk-containment patch, not a full rewrite.

## Important Semantics After This Patch

### Normal start

1. User starts spoofing.
2. App writes config with `active=true`, current `boot_id`, and fresh `heartbeat_at`.
3. Foreground service starts and refreshes runtime config every second.
4. Xposed hooks read the config and spoof location/environment data in system/GMS/location infrastructure processes.
5. Outgoing `Location` objects are rewritten before Binder delivery to consumers.

### Normal stop

1. User stops spoofing.
2. App writes config with `active=false`.
3. Foreground service stops.
4. Hooks stop spoofing.

### Service dies unexpectedly without reboot

1. Last config may still contain `active=true`.
2. Heartbeat eventually becomes stale.
3. Hook layer sees same boot ID and enters fail-closed mode.
4. Real Wi-Fi/cell/BLE/GNSS data is not exposed through the spoofing hooks.
5. Outgoing location still uses last spoofed coordinates instead of real coordinates.

### Device reboots

1. Old config may still exist on disk.
2. Hook layer compares config `boot_id` against current boot ID.
3. Mismatch disables spoofing instead of fail-closing.
4. System location should be able to recover cleanly.

## Build and Install Verification

Commands run after the patch:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

Both completed successfully.

The debug APK was installed with:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Install result:

```text
Success
```

Warnings observed:

- Android Gradle Plugin 8.4.0 warns that it was tested only up to compileSdk 34 while this project uses compileSdk 36.
- Kotlin reported existing non-fatal warnings in `LocationHooker.kt` and `SpoofingService.kt`.

No build-blocking errors remained.

## Manual Verification Required

Because this patch changes Xposed scope and system-process hook lifecycle behavior, manual runtime testing should be done after reboot.

Recommended test sequence:

1. Reboot the device.
2. Do not start spoofing yet.
3. Open Google Maps and verify real location works.
4. Start LocationSpoofer.
5. Start spoofing.
6. Verify Google Maps or another consumer receives spoofed/global location.
7. Leave spoofing active for several minutes and verify the location continues refreshing.
8. Check logcat for:

```text
Location.writeToParcel outbound hook installed
```

9. Check `dumpsys location`. It may still show that the `gps` provider delivered a location to a consumer app, but the consumer should see spoofed coordinates because the `Location` object is rewritten before parceling.
10. Stop spoofing normally.
11. Verify real location can recover.
12. Reboot again.
13. Verify spoofing does not remain active after reboot.

## Recovery Notes

If the device ever again shows stale Maps location or cannot obtain real location, the first suspect is stale Test Provider state from an older build or manual debugging session.

Manual emergency cleanup command sequence:

```powershell
adb shell appops set 2000 android:mock_location allow
adb shell cmd location providers remove-test-provider gps
adb shell cmd location providers remove-test-provider network
adb shell cmd location providers remove-test-provider fused
adb shell appops set 2000 android:mock_location default
adb shell appops set com.shiraka.locatiobprovid android:mock_location deny
adb reboot
```

This should remain a manual diagnostic recovery path only. The app should not execute this during normal operation.

Optional root cleanup for stale config files:

```powershell
adb shell su -c "rm -f /data/local/tmp/locationspoofer_config.json /data/system/locationspoofer_config.json"
adb reboot
```

## Remaining Risks

1. Hooking system/GMS/phone remains inherently risky.
   - This is required by the user's global spoofing design.
   - Lifecycle protection reduces but does not eliminate the blast radius.

2. Fail-closed mode can intentionally block real environment data.
   - This is by design during same-boot service failure.
   - It should end after reboot because of boot ID invalidation.

3. `/data/local/tmp` config permissions remain broad.
   - This is part of the existing architecture.
   - It is not addressed by this patch.

4. The exported `SpooferProvider` remains a security issue.
   - It is deprecated by the file IPC flow but still exported.
   - This patch did not change it because the user requested only the high-risk lifecycle and stuck-provider fix.

5. The current hook layer still uses many broad API hooks.
   - The patch did not redesign hook granularity.
   - Future work should add better package/process policy inside `LocationHooker`.

6. `Location.writeToParcel()` depends on normal Android Parcelable delivery.
   - This should cover ordinary framework `Location` delivery.
   - If a provider sends proprietary location payloads through non-`Location` objects, additional system-layer hooks may be needed.
   - Google Play Services fused APIs may also wrap locations in `LocationResult` objects, but those wrappers still contain Android `Location` instances in normal API paths.

## Future Hardening Ideas

These should be considered later, separately from this minimal repair:

1. Add a clear package/process policy matrix inside `LocationHooker`.
2. Separate "location output spoofing" hooks from "environment input shielding" hooks.
3. Add explicit logs for outbound location rewriting:
   - package/process name
   - provider
   - fail-closed status
   - heartbeat age
   - whether `writeToParcel()` was reached
4. Add explicit logs for state transitions:
   - inactive
   - active
   - fail-closed
   - previous-boot config ignored
5. Add a debug screen showing:
   - current boot ID
   - config boot ID
   - heartbeat age
   - fail-closed status
   - config source path
6. Remove or protect the exported `SpooferProvider`.
7. Replace world-writable config with a safer root-readable or binder-based channel if feasible.
8. Add a manual "repair location stack" tool that only displays commands and asks the user to execute them, rather than silently touching mock/test-provider APIs.
9. Investigate direct hooks into `LocationProviderManager` / `LocationManagerService` delivery internals for even earlier mutation, but only after confirming class names and method signatures on the target Android version.

## Final Design Summary

This patch keeps the original global/system-layer spoofing approach, but removes the dangerous mock/test-provider coupling and makes spoofing lifecycle explicit.

The resulting policy is:

- No Developer Options mock location.
- No Android Test Provider runtime.
- No sticky service resurrection.
- Global infrastructure hooks remain.
- No manual consumer-app target scope list.
- Outgoing `Location` objects are rewritten in system/GMS scoped processes before Binder delivery.
- Same-boot service failure fails closed.
- Reboot clears stale spoofing state.
- System provider contamination risk is reduced without rewriting the original spoofing design.
