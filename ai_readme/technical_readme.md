# LocationSpoofer Current Simulation Pipeline

Last maintained: 2026-07-02

This document is the current source of truth for how LocationSpoofer simulates GNSS, Wi-Fi, cellular, and related environment signals. It is written for future coding agents and developers so the implementation stays understandable and does not drift into hidden generated telemetry.

## Project Invariants

- The app must not use Android Developer Options mock-location app assignment as the primary spoofing mechanism.
- The app must not leave Android test providers or mock providers registered after stopping, rebooting, or service death.
- Global spoofing is intentional. The hook layer is expected to run in system, GMS, and location infrastructure processes through LSPosed/libxposed scope.
- While spoofing is active, direct reads of real GNSS, Wi-Fi, cell, and BLE data should be blocked or replaced where this module hooks them.
- When no trusted simulated Wi-Fi or cell data exists, return empty simulated data rather than inventing APs or towers.
- When the service dies in the same boot, hooks should fail closed by hiding real environment data. After a normal stop or reboot, real location must recover.
- Generated environment records must not be stored in the local environment database or displayed as collected/API records.

## Runtime Layers

### Host App

Main package: `com.shiraka.locatiobprovid`

Primary components:

- `MainViewModel.kt`: coordinates UI state, local environment lookup, API fetches, and database writes.
- `EnvironmentScanner.kt`: scans the current real environment for local Wi-Fi, cell, and Bluetooth data and stores it as user-collected data.
- `SpoofingService.kt`: foreground service that keeps active spoofing state fresh, updates in-memory provider state, and repeatedly persists runtime config.
- `ConfigManager.kt`: writes active runtime config into multiple channels readable by hooked processes.
- `LocationHooker.kt`: libxposed module injected into scoped processes.

### Config Channels

Active spoofing state is written to:

- `/data/local/tmp/locationspoofer_config.json`
- `/data/system/locationspoofer_config.json`
- app SharedPreferences named `locationspoofer_prefs`
- system properties under `gsm.locsp.*`

JSON config files are written through a `.tmp` file followed by `mv` to the final path. This is intentional: hooked system/GMS processes poll these files every second, and non-atomic rewrites can expose a truncated empty JSON file that makes the hook temporarily lose active state.

`LocationHooker.readConfig()` prefers JSON config, then tries ContentProvider, XSharedPreferences, and system properties as fallbacks. The JSON config carries the rich data fields; system properties are only a minimal fallback for active coordinates, heartbeat, and basic MCC/MNC.

Important config fields:

- `active`
- `fail_closed`
- `lat`, `lng`, `altitude`
- `sim_mode`, `sim_bearing`
- `wifi_json`
- `cell_json`
- `bluetooth_json`
- `mock_wifi`, `mock_cell`, `mock_bluetooth`
- `satellite_count`
- `jitter_radius_meters`
- `heartbeat_at`
- `boot_id`

The hook layer checks heartbeat and boot ID. A stale active config in the same boot becomes fail-closed. A stale or mismatched boot config must not revive spoofing after reboot.

## GNSS Simulation

### Data Source

GNSS coordinates come from the selected target coordinate or route/joystick simulation in the host app. The location path is not derived from Wi-Fi or cell data.

### Hook Path

`LocationHooker.kt` hooks:

- `android.location.Location` getters and parcel serialization.
- `LocationManager` listener/callback paths.
- Fused/GMS location result objects where reachable.
- `GnssStatus` and legacy `GpsStatus`.
- NMEA listener paths.

### Behavior

- Coordinates are replaced with the active spoofed latitude/longitude.
- While fixed spoofing is active, dragging the home map and leaving it idle updates the active spoof target and runtime config instead of only changing the UI fields.
- Accuracy, altitude, bearing, speed, elapsed realtime, and timestamps are normalized for active spoofing.
- The foreground service keeps the route/target base coordinate fresh once per second. It does not apply final random jitter before writing runtime JSON.
- When slight jitter is enabled, the Xposed location hook applies one bounded jitter pass using `jitter_radius_meters` as the hard maximum radius. The UI exposes 1m to 80m, with slow/medium/fast drift profiles and 30m as the default.
- Xposed latitude/longitude reads are cached by speed profile so paired latitude/longitude calls use the same jitter sample instead of advancing the random walk twice.
- `LocationManager.requestLocationUpdates()` is actively fulfilled by the hook while spoofing is active. The hook sends an immediate spoofed location and then continues sending fresh locations every second until `removeUpdates()` or inactive config stops the dispatcher.
- `LocationManager.getCurrentLocation()` and `requestSingleUpdate()` remain single-shot.
- NMEA `RMC`, `GGA`, and `GLL` sentences are rewritten and checksums recalculated.
- Hardware satellite visibility is suppressed or replaced through GNSS status hooks.

### Current Risk Notes

- GNSS object compatibility depends on Android version and hidden field names.
- The current stable path does not rewrite magnetometer data. Compass calibration and accuracy should remain handled by the system/physical sensor stack.

## Wi-Fi Simulation

### Trusted Wi-Fi Sources

Wi-Fi simulation accepts only:

- Real local scans from `EnvironmentScanner.scanWifi()`.
- Previously stored local environment records from Room.
- WiGLE API records returned by `WigleClient.fetchWifiData()`.

`WigleClient.kt` now returns an empty JSON array when:

- the token is blank,
- the request fails,
- the body is missing,
- the API returns no results,
- a result lacks a BSSID/netid.

It no longer generates random SSIDs or vendor-looking BSSIDs.

### Local Wi-Fi Storage

`saveEnvironmentData()` stores:

- connected Wi-Fi as `LocationConnectedWifi`,
- nearby scan results as `WifiDevice` plus `LocationWifi`,
- RSSI and frequency/channel/capability metadata when available.

Local lookup in `locationToJson()` interpolates RSSI from nearby stored records around the target coordinate.

### API Wi-Fi Import

`fetchWifiFromWigleSync()` queries WiGLE using WGS-84 coordinates converted from the app coordinate. It stores WiGLE APs as environment data when returned.

Important distinction:

- WiGLE supplies BSSID/SSID identity.
- The app still normalizes Android object fields such as RSSI, frequency, capabilities, network id, and Wi-Fi standard so hooked Android APIs can return structurally valid `WifiInfo` and `ScanResult` objects.
- This normalization is not treated as collected RF truth.

### Hook Path

`LocationHooker.kt` hooks Wi-Fi APIs including:

- `WifiManager.getConnectionInfo`
- `WifiManager.getScanResults`
- scan listener/callback result paths
- related Wi-Fi scan result structures where available

When active and `mock_wifi=true`:

- connection info is built from `connectedWifi` when available,
- the environment-data dialog keeps one de-duplicated Wi-Fi list; short press expands details and long press switches `connectedWifi`,
- otherwise a nearby API/local AP may be used as the visible connection anchor for compatibility,
- scan results are built from `nearbyWifi`,
- if no simulated APs exist, hooks return empty Wi-Fi data rather than exposing the real scan list,
- the hook must not generate coordinate-derived Wi-Fi APs such as `WIFI_Nearby_*`.

### App-Side Diagnostics Standard

The app exposes RF chain diagnostics in the environment-data dialog. The state labels mean:

- `ready`: spoofing is enabled and trusted payload exists. Wi-Fi hooks should return simulated `WifiInfo`, scan results, and scan callbacks.
- `block only`: spoofing is enabled but trusted payload is empty. Real Wi-Fi is still hidden; hooks should return empty simulated scan data.
- `off`: Wi-Fi spoofing is disabled for this run.
- `loading`: the app is fetching or rebuilding Wi-Fi payload.

This is an app-side readiness check. Full confirmation that a target process consumed the hook still comes from logcat hook logs.

## Cellular Simulation

### Trusted Cell Sources

Cell simulation accepts only:

- Real local scans from `EnvironmentScanner.scanCell()`.
- Previously stored local environment records from Room.
- OpenCellID API records returned by `OpenCellIdClient.fetchCellData()`.

`OpenCellIdClient.kt` already returns an empty JSON array when:

- the token is blank,
- no cells are found,
- returned cells cannot be normalized,
- the request fails.

### Removed Synthetic Paths

The following paths are intentionally removed or disabled:

- coordinate-derived deterministic LTE fallback towers,
- synthetic LTE primary cell prepended to non-LTE OpenCellID results,
- generated LAC/CID fallback for `getCellLocation()` when `cell_json` is empty.

If `cell_json` is empty while active, the hook returns empty or null cell structures. This keeps real base stations blocked without creating hidden fake towers.

### Local Cell Storage

`saveEnvironmentData()` stores normalized cell identity and signal fields:

- radio type,
- MCC/MNC,
- TAC/CI for LTE/NR,
- LAC/CID/PSC for GSM/WCDMA,
- NR NCI when available,
- CDMA fields when available,
- dBm and registered state.

`locationToJson()` interpolates signal strength from nearby stored records and emits `cell_json`.

### Hook Path

`LocationHooker.kt` hooks:

- `TelephonyManager.getAllCellInfo`
- `TelephonyManager.getCellLocation`
- `TelephonyManager.getNeighboringCellInfo`
- `TelephonyManager.listen`
- `TelephonyManager.requestCellInfoUpdate`
- `TelephonyManager.registerTelephonyCallback`
- service state and signal strength paths

When active and `mock_cell=true`:

- `getAllCellInfo` returns `CellInfo*` objects built only from `cell_json`.
- `getCellLocation` returns `null` if no valid first cell exists.
- listeners/callbacks receive simulated cell events only when valid data exists.
- real cell listener flags are removed so raw base station updates are not forwarded.

### App-Side Diagnostics Standard

The app uses the same RF chain diagnostic state labels for cellular:

- `ready`: spoofing is enabled and `cell_json` contains trusted tower records. Telephony APIs and callbacks should return simulated cells.
- `block only`: spoofing is enabled but `cell_json` is empty. Real towers are still hidden; hooks should return empty or null cell structures.
- `off`: cellular spoofing is disabled for this run.

As with Wi-Fi, this is a local readiness check; hook delivery is verified through logcat.

## Bluetooth LE Simulation

### Data Source

Bluetooth simulation accepts:

- local BLE scan records from `EnvironmentScanner`,
- local Room records around the selected target coordinate.

No remote BLE API path is currently maintained.

### Hook Path

`LocationHooker.kt` hooks BLE scanner result callbacks where available and replaces or suppresses BLE scan data while active.

If no simulated BLE data exists, hooks should return empty BLE data instead of exposing real nearby BLE devices.

## Connectivity Layer

`LocationHooker.kt` includes connectivity hooks for parts of:

- `ConnectivityManager`
- `NetworkCapabilities`
- `NetworkInfo`
- network interface metadata

These hooks are used to keep the visible network state consistent with Wi-Fi/cellular spoofing. They do not create trusted Wi-Fi or cell records by themselves.

## Compass And Magnetic Field

The previous experimental magnetic-field hook used `android.hardware.GeomagneticField` to calculate an expected field strength for the spoofed coordinate and rescale physical magnetometer vectors.

That path is now removed from the stable hook chain.

Reason:

- Android compass accuracy is strongly tied to physical sensor calibration, bias estimation, and motion history.
- Rescaling the vector magnitude by coordinate can make the data less self-consistent.
- It is hard for the user to verify and control.

Future compass work should be treated as a separate experiment, behind an explicit setting, with logging and a clear rollback path.

## Data Quality Rules

- Do not generate AP BSSIDs.
- Do not generate cell tower identities from coordinates.
- Do not silently convert “no data” into plausible-looking data.
- Do not store fallback object-construction defaults as collected/API data.
- Prefer local collected data first because it is auditable by the user.
- Remote API imports must be labeled in metadata.
- If Android object construction needs missing optional fields, use harmless structural defaults and keep identity fields from trusted data.

## Known Gaps

- Some radio-domain processes may not be able to read rich JSON config and may fall back to minimal properties.
- If no local/API Wi-Fi or cell data exists for a spoofed coordinate, Maps may still report weak location signal because the module will correctly hide real RF data and return empty simulated RF data.
- OpenCellID coverage varies by country and coordinate. Empty results should be expected in some places.
- WiGLE data may contain APs without enough Android-specific fields; the app fills object-shape fields for API compatibility.
- GNSS status and NMEA coverage depends on Android version internals.

## Debugging Checklist

Local build/install paths are recorded in `ai_readme/README.md`; use them directly after context compaction.

Use logcat filters:

- `LocationSpoofer_Xposed`
- `OpenCellID`
- `LocationSpoofer_Debug`

Questions to answer from logs:

- Did `readConfig()` load JSON or only property fallback?
- Is `active=true` and heartbeat fresh?
- How many APs are present in `wifi_json.nearbyWifi`?
- How many cells are present in `cell_json`?
- Does `getAllCellInfo` log a non-zero fake cell count?
- Does `getScanResults` log a non-zero fake AP count?
- Is fail-closed active because the foreground service stopped?

## File Map

- `app/src/main/java/com/shiraka/locatiobprovid/xposed/LocationHooker.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/service/SpoofingService.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/ConfigManager.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/EnvironmentScanner.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/WigleClient.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/OpenCellIdClient.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/viewmodel/MainViewModel.kt`
