# LocationSpoofer Phase Summary (2026-07-02)

This document records the current phase state of the project based on the recent conversation and the code changes already made in the workspace. It is meant to let the next coding session continue quickly without reconstructing intent from chat history.

## Scope Of This Phase

The work in this phase focused on four threads:

1. Stabilizing global spoofing lifecycle without enabling Developer Options mock location.
2. Keeping real GNSS, Wi-Fi, and cell data blocked while active spoofing is running at the system/GMS layer.
3. Making the UI less opaque by exposing RF simulation readiness and simplifying settings.
4. Removing user-facing GitHub/Telegram/update entry points and expanding Traditional Chinese coverage.

## User Requirements Reconfirmed In This Phase

- Keep global spoofing. Do not switch to per-target mock scopes.
- Do not enable Android developer mock-location assignment.
- Keep system-layer blocking of real GNSS / Wi-Fi / cell / BLE while spoofing is active.
- After stop or reboot, runtime state must fully clear and real location must recover.
- If runtime spoofing data is missing while active, fail closed for RF data instead of leaking real data.
- Do not generate fake Wi-Fi AP identities or coordinate-derived fake cell towers as hidden fallbacks.
- Add in-app visibility so RF chain state is not a black box.

## Major Functional Changes Already Implemented

### 1. Lifecycle and runtime config hardening

- Runtime JSON writes were changed to atomic `.tmp` then `mv` writes for:
  - `/data/local/tmp/locationspoofer_config.json`
  - `/data/system/locationspoofer_config.json`
- This was done to stop hooked processes from reading a temporarily truncated empty file during rewrite.
- Persistent runtime refresh remains foreground-service based and updates every second.

### 2. Stable target anchor vs current output

- The stable spoof target and the current emitted position were separated in provider state.
- The service no longer feeds the previous jittered output back into the stable target anchor.
- This stopped the previous random-walk drift from accumulating farther and farther away over time.

### 3. GNSS refresh path

- `LocationManager` active hooks were added or reinforced so that when spoofing is active:
  - `isLocationEnabled()` can stay logically available,
  - `isProviderEnabled()` for GPS / network / fused can report enabled,
  - `getLastKnownLocation()` can return spoofed location,
  - `requestSingleUpdate()` and `getCurrentLocation()` can be satisfied from the spoof hook path,
  - `requestLocationUpdates()` receives an immediate spoofed update and then periodic updates while active.
- `removeUpdates()` support was added to stop the periodic spoof callbacks cleanly.

### 4. Jitter and drift control

- Jitter radius is user-configurable from `1m` to `80m`, default `30m`, with slow/medium/fast Xposed drift profiles.
- Service-side runtime JSON refresh was changed to write the base route/target position and not apply the final random jitter there.
- The final bounded jitter is applied at the Xposed location-output edge.
- Latitude and longitude jitter reads are short-window cached so both coordinates come from the same sample.

### 5. Wi-Fi simulation cleanup

- Hidden generation of synthetic nearby Wi-Fi entries like `WIFI_Nearby_*` was removed.
- Trusted Wi-Fi sources are now only:
  - local scan records,
  - stored environment records,
  - WiGLE API records.
- If spoofing is active and no trusted Wi-Fi payload exists, hooks return empty spoofed Wi-Fi data instead of real data.

### 6. Cell simulation cleanup

- Hidden coordinate-derived cell fallback logic was removed.
- Trusted cell sources are now only:
  - local scan records,
  - stored environment records,
  - OpenCellID API records.
- If spoofing is active and `cell_json` is empty, hooks return empty or null simulated cell structures rather than exposing real towers.

### 7. PiP / background behavior

- Entering background while spoofing no longer intentionally triggers PiP behavior.
- The app is expected to rely on the foreground service notification and wakelock instead of a small floating/PiP style window.

## UI Changes Already Implemented

### RF diagnostics

- The Wi-Fi / cell status entry now opens a dialog that contains an RF chain diagnostics panel.
- The RF diagnostics panel now collapses when the environment-data list is scrolled so the payload list gets more readable space.
- The panel uses app-side readiness states:
  - `ready`
  - `block only`
  - `off`
  - `loading`
- The intent is:
  - `ready`: trusted payload exists and hooks should feed simulated data.
  - `block only`: spoofing is enabled but the payload is empty; real RF should still be hidden.
  - `off`: that RF class is disabled for the current run.
  - `loading`: payload fetch/rebuild is in progress.

### Settings simplification

- Settings were reorganized into expandable rows instead of a long fully expanded list.
- API keys were merged into a single expandable `API configuration` section containing:
  - Google key
  - WiGLE token
  - OpenCellID token
- App identity and runtime recovery were also moved into their own expandable rows.

### Language support

- A `zh-TW` language option was added.
- Traditional Chinese strings were expanded significantly beyond the initial partial override.
- `ScannerMapScreen` was adjusted so domestic-language behavior uses `startsWith("zh")` rather than matching only `zh`.
- Arabic language support was removed from resources and language selection.

### Social / update entry points

- User-facing GitHub and Telegram buttons on the main screen were removed.
- Settings update entry was removed.
- Auto-check/update UI was removed from the main user flow.
- Related code and resources were cleaned up in the workspace:
  - `UpdateViewModel.kt`
  - `GithubRelease.kt`
  - `UpdateManager.kt`
  - `ic_github.xml`
  - `ic_telegram.xml`

### Active map and Wi-Fi controls

- During active fixed spoofing, dragging the home map and leaving it stable updates the active spoof location and shows `位置已更新`; the Wi-Fi payload list can now select which trusted AP is exposed as `connectedWifi`.
- The search panel is a focusable top-level floating overlay; the Wi-Fi payload list is de-duplicated, short press expands details, and long press switches the connected AP.

## Current Technical Understanding Of The Data Path

### Host app write path

1. The app selects a target point or route.
2. The app gathers environment payload from:
   - local collection database,
   - WiGLE for Wi-Fi,
   - OpenCellID for cell.
3. The host app stores active state in `SpooferProvider`.
4. `ConfigManager.saveConfig()` and `SpoofingService.writeRuntimeConfig()` write active state to:
   - `/data/local/tmp/locationspoofer_config.json`
   - `/data/system/locationspoofer_config.json`
   - app SharedPreferences
   - `gsm.locsp.*` system properties

### Hook-side read path

1. `LocationHooker.readConfig()` polls and loads config into memory.
2. Hooked system/GMS/location processes consume that config.
3. Hook layers replace or suppress:
   - GNSS / `Location` / `LocationManager`
   - Wi-Fi APIs and scan results
   - cell APIs, callbacks, and related network identity metadata
   - selected connectivity state APIs

## Log-Based Findings From This Phase

### Confirmed working signals

Recent logcat showed repeated signs that spoofed RF payload is actually being built:

- `fakeWifiInfo build result` repeatedly appeared in hooked processes.
- The Wi-Fi payload included coherent values like SSID, BSSID, RSSI, frequency, and link speed.
- Cell logs showed `mockCell=true cellJsonCount=20`.
- ContentProvider fallback was also loading active config successfully in at least one system process.

This suggests Wi-Fi and cell spoof payloads are not simply absent at runtime.

### Important instability signal

Recent logcat also repeatedly showed:

- `LocationManagerService: non-monotonic location received from gps provider`

This is important because Android can downgrade or reject updates when location timestamps or elapsed realtime move backward or are otherwise inconsistent.

A mitigation was implemented in workspace code:

- spoofed `Location.time`
- spoofed `Location.elapsedRealtimeNanos`
- spoofed `Location.getElapsedRealtimeMillis()`

were changed to use a shared monotonic timestamp generator so new spoofed `Location` objects should never go backward relative to prior emitted ones.

This change was made because the intermittent Maps confidence / weak signal behavior may be caused by the system distrusting the location stream even when Wi-Fi/cell payload exists.

## Compass / Weak Signal Interpretation

Current understanding is:

- Compass accuracy is not directly rewritten in the stable path.
- The project intentionally does not currently rewrite magnetometer data.
- Therefore compass quality can still vary with physical sensor calibration and motion history.

If Google Maps still says to connect Wi-Fi or mobile data while spoofing is active, there are two likely explanations:

1. RF spoof payload exists but the overall location stream is being downgraded because of timestamp monotonicity or related system validation.
2. Some network-location-facing API path still expects additional coherence beyond the currently spoofed Wi-Fi / cell identity objects.

The first explanation became more plausible after seeing the repeated `non-monotonic location` logs.

## Build / Install Status

### Last confirmed successful build

The latest confirmed `assembleDebug` run completed successfully on 2026-07-02 with no Kotlin or AGP warnings.

### Last confirmed successful install

The latest debug APK was installed successfully on device `47050DLAQ001LE` with `adb install -r` on 2026-07-02.

### Current workspace status at the end of this phase summary

The workspace and installed debug APK are aligned for the latest build/install pass.

## Open Items For The Next Phase

1. Re-check logcat for `non-monotonic location received from gps provider` after restarting the relevant hooked processes or rebooting.
2. Verify whether Google Maps still reports weak signal / asks for Wi-Fi or mobile data after the timestamp fix is active.
3. If weak-signal prompts remain, inspect whether additional network-location API surfaces still need coherence work.

## Files Most Relevant To Continue From Here

- `app/src/main/java/com/shiraka/locatiobprovid/xposed/LocationHooker.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/service/SpoofingService.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/ConfigManager.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/viewmodel/MainViewModel.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/ui/screen/SpoofingScreen.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/ui/screen/SettingsScreen.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/ui/screen/LanguageSelectionScreen.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/src/main/res/values-zh-rTW/strings.xml`
