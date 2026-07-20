# LocationSpoofer Current Simulation Pipeline

Last maintained: 2026-07-20 (repository cleanup, removal of unreachable compatibility hooks, and release documentation)

This document is the current source of truth for how LocationSpoofer simulates GNSS, Wi-Fi, cellular, and related environment signals. It is written for future coding agents and developers so the implementation stays understandable and does not drift into hidden generated telemetry.

## Project Invariants

- The app must not use Android Developer Options mock-location app assignment as the primary spoofing mechanism.
- The app must not leave Android test providers or mock providers registered after stopping, rebooting, or service death.
- Global spoofing is intentional. The hook layer is expected to run in system, GMS, and location infrastructure processes through LSPosed/libxposed scope.
- While spoofing is active, direct reads of real GNSS, Wi-Fi, and cell data should be blocked or replaced only for explicitly enabled simulation channels.
- When no trusted simulated Wi-Fi or cell data exists, return empty simulated data rather than inventing APs or towers.
- A live active snapshot is trusted for at most 30 seconds without a new monotonic runtime frame. After writer death or a normal stop, hooks restore pass-through instead of indefinitely breaking real location/RF behavior.
- Generated environment records must not be stored in the local environment database or displayed as collected/API records.

## Runtime Layers

### Host App

Main package: `com.shiraka.locatiobprovid`

Primary components:

- `MainViewModel.kt`: coordinates UI state, API fetches, database writes, in-memory anchors/options, refresh requests, and service controls. It does not publish runtime config files.
- `EnvironmentScanner.kt`: scans the current real environment for local Wi-Fi and cell data and stores it as user-collected data. It never starts a Bluetooth scan.
- `EnvironmentCoveragePolicy.kt`: owns the world-fixed Web Mercator hex grid and its 30 m circumradius cell IDs.
- `EnvironmentRfResolver.kt`: is the single Room-to-runtime RF resolver; its DAO pre-filter is followed by an exact target-cell comparison.
- `SpoofingService.kt`: owns movement output, refreshes RF for cell/database/request/material within-cell changes, keeps active state fresh, and is the sole continuous active-runtime writer.
- `ConfigManager.kt`: writes lifecycle bootstrap/stop snapshots; it is not a second heartbeat writer.
- `RuntimeConfigWriteCoordinator.kt`: serializes lifecycle and heartbeat commits, rejects stale generations, and advances the committed generation only after a write actually succeeds.
- `RootManager.kt`: owns the service-only persistent root shell used during active simulation; one acknowledged serialized shell replaces a new `su` process per movement tick and is closed on stop, location-off, or service destruction.
- `LocationHooker.kt`: libxposed module injected into scoped processes.

Debug/test apps that directly inspect Wi-Fi, cell, GNSS status, or NMEA must also be in the LSPosed scope. System/GMS scope is enough for many normal location consumers, but tools such as Cellular-Z and GPSTest read several APIs inside their own app process, so their package processes must be hooked too.

### Config Channels

Active spoofing state is written to:

- `/data/local/tmp/locationspoofer_config.json`
- `/data/system/locationspoofer_config.json`
- app SharedPreferences named `locationspoofer_prefs`
- system properties under `gsm.locsp.*`

JSON config files are written through a generation-tagged `.tmp` file followed by `mv` to the final path. Full snapshots are written on static/RF change and as a 60-second fallback, not once per movement tick. `RuntimeConfigWriteCoordinator` serializes in-process commits and advances only after an acknowledged write succeeds. Every root writer also holds `/data/local/tmp/locationspoofer_runtime.lock`, opened as a shell-function redirection so Android toybox `flock` inherits a valid fd. Generation ordering uses decimal-string comparison because Android shell arithmetic is not reliably 64-bit. An admitted old full snapshot therefore cannot finish after a successful stop snapshot and reactivate the runtime.

`gsm.locsp.frame` is the active hot path. It is one bounded property value containing protocol version, active state, the last successful full-snapshot generation, monotonic heartbeat, E7 coordinates, bearing, and speed. A moving run changes only this frame at 1 Hz; a stationary run refreshes it every 10 seconds. The Hook validates the frame and, when its generation matches the cached JSON snapshot, clones/overlays memory without touching disk, ContentProvider, or preferences. A frame generation change is the signal to reload the full JSON snapshot.

`LocationHooker.readConfig()` reads only the expected path for its process: UID 1000 uses `/data/system`, other hooked clients use `/data/local/tmp`. It never probes both paths, and after the first permission denial that process permanently disables disk reads and uses the exported ContentProvider instead. XSharedPreferences is not queried by the runtime path. Rich Wi-Fi/cell JSON is never carried in legacy properties. The SystemProperties change callback is global, so it performs only compact property reads and equality checks; unrelated property changes do not trigger JSON parsing or I/O.

An inactive lifecycle write first publishes the monotonic `gsm.locsp.stop_generation` under a separate short-held lock, then publishes the inactive compact frame before it waits for the full runtime-file lock. Hook consumers reject every frame/JSON/property snapshot whose generation is missing or is less than or equal to that fence. Every active full-snapshot and position-frame writer re-checks both the fence and current snapshot generation after acquiring the runtime lock; a superseded service retires without publishing another tombstone. A later legitimate start does not clear the fence; its first successful full snapshot naturally has a newer generation. This keeps stop fail-open even if an orphaned old writer outlives the normal root-command timeout, while stale/equal stop replays cannot turn off a newer run.

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
- `heartbeat_uptime_ms`
- `config_generation`
- `gsm.locsp.stop_generation` (property-only lifecycle fence)
- `runtime_lifecycle_token`
- `boot_id`

Routine success and failure logs in hot hook/scanner paths use fixed-cardinality keys and at least one-minute throttling; repeated ContentProvider, Wi-Fi transport, cell-construction, NMEA, and scan failures should no longer dominate logcat during stable operation.

Collected environment records remain in Room/SQLite. The active Room flow/projection loads only rows with replayable Wi-Fi or cell relations; historical Bluetooth tables and management/import compatibility remain in the schema but are not materialized by runtime simulation. Map rendering, UI capability checks, and runtime resolution all use `EnvironmentCoveragePolicy`: a world-fixed Web Mercator hex grid with a 30 m circumradius. `EnvironmentRfResolver` queries a conservative DAO bound and then requires the stored row and target to have the exact same cell ID. Sampling density cannot enlarge coverage, and no distance-based fallback may borrow RF data across an edge.

Fixed-point, route, and manual-joystick movement share one output path. `MainViewModel` updates in-memory anchors/options and requests refreshes, but never writes runtime files. `SpoofingService` is the sole continuous writer; it re-resolves RF after an exact-cell change, a Room invalidation, an explicit refresh request, or material movement within a cell before publishing the next heartbeat/config. Do not restore a second active-runtime writer in `MainViewModel`.

The start-spoofing dialog uses content height up to a screen-bounded maximum, then scrolls. Collapsed sections are removed from layout instead of reserving blank space or animating platform-window remeasurement. It stays visible after confirmation as a compact progress surface. Local exact-cell RF cache hits enable immediately; API misses identify WiGLE/OpenCellID as separate sources and offer either retry or "use current state" startup. User-enabled RF channels remain enabled even when their exact-cell payload is empty, in which case diagnostics show `block only` and hooks hide real RF data.

`show_home_coordinate_algorithm` controls only the home UI entry. Per-app coordinate mappings are persisted and published for compatibility, but the disabled legacy map-SDK hooks do not currently apply them to the canonical framework fix.

The hook layer checks compact-frame monotonic heartbeat and boot ID. A missing/stale active heartbeat becomes inactive pass-through after 30 seconds; a stale or mismatched boot config must not revive spoofing after reboot.

`SpooferProvider` exposes only the last heartbeat actually published by the host writer; a ContentProvider query must never renew liveness. Explicit stop clears the provider heartbeat/RF snapshot and publishes the compact inactive frame plus legacy `active=false` before filesystem work, so normal platform behavior resumes even if a later chmod/file write fails.

Start/API work is guarded by both a UI operation generation and a repository/service lifecycle token. Provider publication is serialized by a lifecycle mutex; stale START/STOP commands are rejected; first active state is not reported ready until the service has committed its first full snapshot. Config commits use a separate monotonic write generation and re-check `SpooferProvider.isActive`, so an older in-flight start, rollback, or frame cannot reactivate runtime after stop.

## GNSS Simulation

### Data Source

GNSS coordinates come from the selected target coordinate or the host app's legacy route/joystick path. Fixed-point behavior is the currently verified primary mode; route-coupled manual control is scheduled for replacement in `joystick_refactor_plan.md`. Standard Android location is independent of Wi-Fi/cell payload availability. Wi-Fi and cell hooks are optional RF supplements; they never gate whether a normal `LocationManager` consumer can obtain a fix.

The host/UI coordinate is stored as GCJ-02 where applicable. The canonical Android framework fix is always WGS-84 (`wgs84_lat`, `wgs84_lng`). GCJ-02 and BD-09 conversion belongs only at an explicitly enabled map-SDK boundary, never in `system_server`.

### Authoritative Framework Path

There is exactly one owner for ordinary location cadence: the module instance loaded by `onSystemServerStarting`.

```text
SpoofingService fixed / legacy route / manual output point
  -> system_server 1 Hz cadence
  -> current real AbstractLocationProvider.reportLocation(LocationResult)
  -> LocationProviderManager.processReportedLocation() validates provider input
  -> processReportedLocation after-hook returns one new canonical WGS-84 fix
  -> framework last-location cache
  -> interval / minDistance filtering
  -> permission, AppOps and coarse-location handling
  -> native listener / PendingIntent / Binder transport
  -> scoped and completely unscoped ordinary LocationManager consumers
```

The cadence calls the current real provider abstraction for `gps`, `network`, and `fused`. It does not create, register, remove, or disguise an Android test/mock provider. Passive location is updated by the framework itself.

`LocationProviderManager.processReportedLocation()` is the only standard-location rewrite point on the verified Android 16 path. Each provider report returns a newly built singleton `LocationResult`; one complete sample is written before framework caching and delivery filtering. The downstream transport, `Location.writeToParcel()`, and public `Location` getters must remain untouched.

This placement is deliberate:

- Late transport/Parcel rewriting makes the point accepted by `minUpdateDistance` differ from the point delivered to the app.
- Getter rewriting in `system_server` can recurse through `Location.equals()` and corrupt framework logic.
- Rewriting after coarse-location handling can overwrite Android's privacy reduction.
- Per-client callback timers do not populate framework provider state and cannot provide global location.

Do not reintroduce a `Map<Location, ...>` sample cache. Android 16 `Location.equals()` reads motion/location getters, and an equality-keyed `WeakHashMap<Location, ...>` previously caused a verified recursive `StackOverflowError` in `system_server`. There is no cross-object cache in the current path: a fresh sample is created once per provider report and then carried as ordinary raw fields.

### Behavior

- `LocationManager.requestLocationUpdates()`, `requestSingleUpdate()`, `getCurrentLocation()`, and `removeUpdates()` always proceed to the real framework. No client hook fulfils or swallows them.
- Provider name stays equal to its manager (`gps`, `network`, or `fused`); RF payload availability does not rename a provider.
- A complete fix includes paired latitude/longitude, horizontal accuracy, optional altitude plus vertical accuracy, wall time, elapsed realtime, and motion fields. MSL altitude from an old real fix is removed. Speed/bearing and their accuracies are either set together for moving simulation or removed together for stationary simulation.
- Wall time is current; elapsed realtime is non-decreasing and never manufactured in the future. Drift cadence uses `SystemClock.elapsedRealtime()`, not wall clock, so network time corrections cannot freeze jitter.
- The location master switch remains authoritative for delivery, not lifecycle admission. Simulation may be armed while the switch is off; the foreground service stays asleep with no active runtime frame/RF output, then writes its first full snapshot automatically when the switch is enabled. Turning it off during a run pauses output and turning it back on resumes with a fresh full snapshot.
- While fixed spoofing is active, dragging the home map and leaving it idle updates the shared anchor; the next service heartbeat publishes it instead of `MainViewModel` writing runtime config directly.
- The foreground service is the single moving-output and active-runtime writer for fixed, route, and manual modes. It keeps moving coordinates fresh through the compact frame at 1 Hz, refreshes a stationary frame every 10 seconds, and rewrites full runtime JSON only on content/RF changes or the 60-second safety fallback. It does not apply final random jitter before publication.
- Jitter is sampled only on the authoritative framework cadence. `jitter_radius_meters` is the requested roaming envelope, not only a distant clamp. Slow/medium/fast use different OU time scales and hold a coordinate sample for about 2.8 / 1.5 / 0.8 seconds, producing approximately 3 / 2 / 1-fix advancement at 1 Hz while timestamps remain fresh. When any RF switch is enabled, the final OU sample is bounded to the anchor's exact `EnvironmentCoveragePolicy` cell before GCJ-02 -> WGS-84 conversion; this prevents a delivered coordinate from crossing into a cell whose RF payload was not resolved. With all RF switches off, the full configured drift envelope remains available. The location fields are written together, so paired latitude/longitude can never belong to different samples.
- `onSystemServerStarting` installs the framework hook groups once per PID. Later `onPackageReady` callbacks in that PID are ignored by the process installation guard.
- GMS receives ordinary framework reports through its normal registrations. There is no GMS `LocationResult` getter replacement and no extra GMS callback timer.

### Satellite and NMEA Supplements

- `GnssStatus`, legacy `GpsStatus`, and NMEA remain supplemental client-facing simulations; they are not the source or gate for ordinary LocationManager positions.
- GNSS status satellite count follows `satellite_count`; satellite azimuth/elevation/CN0 are generated from the active WGS-84 target coordinate, current time, and simplified GPS/GLONASS/BDS orbit geometry, then lightly jittered for stable realism.
- The generated GNSS status marks a realistic subset of the configured satellites as `usedInFix` so tools do not see only visible-but-unused satellites.
- GNSS status is exposed through public `GnssStatus` getter hooks (`getSatelliteCount`, `getSvid`, `getCn0DbHz`, elevation/azimuth, carrier/baseband CN0, and fix flags). Do not mutate `GnssStatus` private parallel arrays in callbacks; Android version field drift can make count and array lengths inconsistent and crash consumers such as Google Maps.
- NMEA `RMC`, `GGA`, `GLL`, `VTG`, `GSA`, and `GSV` sentences are rewritten and checksums recalculated. `GSA`/`GSV` use the same cached simulated satellite set as `GnssStatus`.
- When simulation is inactive or the Android location master switch is off, NMEA listener registration is passed through byte-for-byte and no Proxy or injector timer is created. A listener registered while inactive remains a real listener until its client registers again after simulation starts.
- If a listener Proxy was created during an active run and remains registered after stop, its normal NMEA callback path becomes a direct real-sentence pass-through: it does not poll config, rewrite the sentence, copy callback arguments, or use reflection.
- In non-infrastructure scoped client processes, NMEA listeners also receive an active injected burst shortly after registration and then once per second, so satellite views do not need to wait for slow hardware NMEA sentences before showing the simulated constellation. System/GMS infrastructure processes keep passive rewriting but do not receive extra active NMEA timer events.
- `usedInFix` satellite selection is weighted by signal quality but not strictly the first sorted satellites; a rotating subset of lower-list satellites can participate in the fix.
- Hardware satellite visibility is suppressed or replaced through GNSS status and NMEA hooks.
- AMap, Baidu, and Tencent SDK-specific deep hooks are disabled by default. The stable path is Android framework + system/GMS/fused output. Re-enabling per-SDK branches requires an explicit compatibility decision because those branches can conflict with the global provider chain and reintroduce app-specific instability.

### Restart and Reboot Semantics

- Installing a new APK does not reload already-injected LSPosed/libxposed code inside running target processes. A target process restart or device reboot is required before the newly installed hook code is authoritative.
- A release `MY_PACKAGE_REPLACED` sets `moduleRestartRequired`; repository starts are rejected until the next `BOOT_COMPLETED`. Debug replacement remains startable for host/service-only iteration, but a tester must still reboot explicitly whenever hook bytecode changed. This prevents production host writers from running against old resident hook code without blocking the normal debug loop.
- Active runtime configs include `boot_id`; after reboot, previous-boot active configs are intentionally disabled instead of resurrected. Spoofing must be started again so the foreground service writes a fresh same-boot heartbeat/config.
- `RuntimeCleanupReceiver` marks runtime config inactive on `MY_PACKAGE_REPLACED` and `BOOT_COMPLETED`. This prevents an old `active=true` file with no foreground service heartbeat from leaving system/GMS/client hooks stuck in fail-closed before the user opens the app again.

### Current Risk Notes

- GNSS object compatibility depends on Android version. Prefer public getter hooks and avoid private field mutation.
- The provider cadence and `processReportedLocation` method are verified on Pixel Android 16/API 36. Other framework versions must be checked against their real provider-manager method order before moving the rewrite point.
- The current stable path does not rewrite magnetometer data or force `Location` bearing APIs in fixed spoofing mode. Compass calibration, magnetic-field accuracy, and app-side compass behavior remain handled by the system/physical sensor stack. Route, joystick, and moving simulation modes can still expose course-over-ground through `Location` speed/bearing.
- Legacy route/manual UI was not part of the 2026-07-13 regression pass. Its known coupling and planned removal/rewrite are documented in `joystick_refactor_plan.md`; that work must preserve the same service writer and framework hook chain.

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

`EnvironmentRfResolver` builds the payload only from stored records in the target's exact `EnvironmentCoveragePolicy` cell. `locationToJson()` may interpolate RSSI among those same-cell records but may not include a record from another cell.

### API Wi-Fi Import

`fetchWifiFromWigleSync()` queries WiGLE using WGS-84 coordinates converted from the app coordinate. It stores WiGLE APs as environment data when returned.

On start spoofing, WiGLE is queried only when the exact target cell has no trusted local Wi-Fi payload; once imported, later runs resolve the Room/SQLite row through the same cell policy.

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
- `wifi_connection_mode=FIXED` keeps the first/connected AP, while `RANDOM` chooses deterministically when a stable session timestamp is available,
- the environment-data dialog keeps one de-duplicated Wi-Fi list; short press expands details and long press switches `connectedWifi`,
- otherwise another AP from the same exact-cell payload may be used as the visible connection anchor for compatibility,
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

If `cell_json` is empty while active and `mock_cell=true`, the hook returns empty or null cell structures. This keeps real base stations blocked without creating hidden fake towers.

### Local Cell Storage

`saveEnvironmentData()` stores normalized cell identity and signal fields:

- radio type,
- MCC/MNC,
- TAC/CI for LTE/NR,
- LAC/CID/PSC for GSM/WCDMA,
- NR NCI when available,
- CDMA fields when available,
- dBm and registered state.

`locationToJson()` interpolates signal strength only among the resolved exact-cell records and emits `cell_json`.

OpenCellID follows the same one-time supplement rule as Wi-Fi: the API is queried only when the exact target cell has no trusted local cell payload, then the service resolves the cached row through that same cell before publishing `cell_json` to Telephony hooks.

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
- RSRP/RSRQ/SINR are normalized into realistic ranges and can apply slow bounded signal jitter through `signal_jitter_enabled` and `signal_jitter_level`.
- `getCellLocation` returns `null` if no valid first cell exists.
- listeners/callbacks receive simulated cell events only when valid data exists.
- `listen()` and callback registration always proceed with the caller's original flags. Delivery-time hooks re-check active/location/channel state, so registration-before-start works and inactive/location-off/channel-off never changes normal telephony behavior.
- `requestCellInfoUpdate()` avoids a real modem refresh only while Cell simulation is active and always completes the accepted callback contract, including a stop race.
- Registration-failed, barring-info, physical-channel, voice-network-type, CellInfo, CellLocation, ServiceState, and SignalStrength paths are covered. Empty or incomplete payloads return neutral/empty results rather than leaking a real identity or inventing LTE/`-70 dBm` defaults.

### App-Side Diagnostics Standard

The app uses the same RF chain diagnostic state labels for cellular:

- `ready`: spoofing is enabled and `cell_json` contains trusted tower records. Telephony APIs and callbacks should return simulated cells.
- `block only`: spoofing is enabled but `cell_json` is empty. Real towers are still hidden; hooks should return empty or null cell structures.
- `off`: cellular spoofing is disabled for this run.

As with Wi-Fi, this is a local readiness check; hook delivery is verified through logcat.

## Bluetooth Removal And Historical Compatibility

- The app requests no Bluetooth permissions, starts no BLE scan, exposes no start-time Bluetooth switch, and installs no Bluetooth Xposed hook.
- Runtime payloads keep only compatibility tombstones: `bluetooth_json=[]` and `mock_bluetooth=false`. No runtime consumer reads or simulates them.
- Existing Room Bluetooth entities/relations and data-management import/export views remain so upgrading does not require a destructive schema migration and users can still inspect historical records.
- Runtime coverage SQL and `EnvironmentRfResolver` projections exclude Bluetooth-only rows and do not materialize Bluetooth relations.

## Connectivity Layer

The unused generic `ConnectivityManager`, `NetworkCapabilities`, `NetworkInfo`, and `NetworkInterface` hook helper has been removed. Wi-Fi and cellular simulation own only their explicit API surfaces; inactive mode and disabled RF channels do not rewrite generic connectivity state.

## Compass And Magnetic Field

The previous experimental magnetic-field hook is removed from the stable hook chain. Compass calibration and magnetic-field behavior are left to the physical sensor stack. Fixed spoofing does not force `Location.getBearing()` because that makes stationary targets appear to have an artificial near-zero course; moving modes expose course-over-ground from the simulated trajectory.

## Data Quality Rules

- Do not generate AP BSSIDs.
- Do not generate cell tower identities from coordinates.
- Do not silently convert “no data” into plausible-looking data.
- Do not store fallback object-construction defaults as collected/API data.
- Prefer local collected data first because it is auditable by the user.
- Remote API imports must be labeled as `由 WiGLE 導入` / `由 OpenCellID 導入`, not reused as place names.
- The app UI groups environment records with the same coordinate into one visible location and shows RF source labels beside the Wi-Fi/cell counts.
- Nearby place names resolve through Google Places Nearby Search first, then Google Geocoding and Android `Geocoder`, with `LocationSpoofer_Debug` logs for success and failure.
- Recent locations are separate from saved favorites: the home screen shows the latest seven used locations, while the top bookmark button opens the saved favorites list.
- Recent locations must be written only after the current coordinate's Wi-Fi/cell payload has been resolved; loading a saved/recent point re-evaluates local RF data for that coordinate.
- If Android object construction needs missing optional fields, use harmless structural defaults and keep identity fields from trusted data.

## Known Gaps

- Exact-cell RF coverage uses Web Mercator and is defined only for latitude `-85.05112878°..85.05112878°`. The coordinate UI still accepts the wider Android range; polar coordinates outside the grid do not guarantee exact-cell RF isolation or same-cell jitter constraint and should not be used with RF simulation.
- Phone/network-stack processes are deliberately not given client RF hooks. System UID and client hooks use their one known-readable JSON path; minimal properties are exceptional fallback only.
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
- Is the target package itself being hooked? For RF/GNSS test tools, logcat should include `Hooking package: <target package>` after the target app starts.
- Is `active=true` and heartbeat fresh?
- How many APs are present in `wifi_json.nearbyWifi`?
- How many cells are present in `cell_json`?
- Does `getAllCellInfo` log a non-zero fake cell count?
- Does `getScanResults returning fake AP count=N` log a non-zero fake AP count?
- Did an unexpected writer death exceed the 30-second compact-frame TTL and restore pass-through? A normal stop should publish the inactive frame immediately rather than waiting for TTL.

## File Map

- `app/src/main/java/com/shiraka/locatiobprovid/xposed/LocationHooker.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/provider/SpooferProvider.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/service/SpoofingService.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/data/repository/LocationRepository.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/receiver/RuntimeCleanupReceiver.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/ConfigManager.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/RuntimeConfigWriteCoordinator.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/EnvironmentCoveragePolicy.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/EnvironmentRfResolver.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/MapCoverageHelper.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/data/db/EnvironmentDao.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/EnvironmentScanner.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/WigleClient.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/utils/OpenCellIdClient.kt`
- `app/src/main/java/com/shiraka/locatiobprovid/viewmodel/MainViewModel.kt`
