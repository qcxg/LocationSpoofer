<div align="center">

<h1>LocationSpoofer</h1>

<p>High-fidelity Android system-level location spoofing and wireless environment simulation module based on KernelSU + LSPosed</p>

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![KernelSU](https://img.shields.io/badge/Root-KernelSU-orange.svg)](https://kernelsu.org)
[![Framework](https://img.shields.io/badge/Framework-LSPosed-purple.svg)](https://github.com/LSPosed/LSPosed)

[繁體中文](README.md) | [English](README_EN.md)

</div>

---

### 🌱 Origin and Evolution

This project originally originated from the open-source repository [SuseOAA/LocationSpoofer](https://github.com/SuseOAA/LocationSpoofer). Following continuous feature evolution and codebase refactoring (including package renaming, map engine streamlining, and hardening of wireless environment simulation rules, along with the removal of unstable experimental features like traffic light waits and magnetic vector simulation), **it has now evolved into a fully independent development branch**.

---

## 📖 Introduction

In modern Android risk control environments, the standard developer options feature "Mock Location" has long been flagged as a high-risk indicator by major anti-cheat SDKs (such as AMap risk control, Tencent Security, NetEase EasyShield, etc.). These SDKs do not merely verify the standard `isFromMockProvider` API flag; they also gather environmental signals such as:

*   **Surrounding Wi-Fi BSSID lists**
*   **Cellular tower cell IDs (cellular fingerprints)**
*   **Local BLE beacons**
*   Furthermore, they perform Fast Fourier Transform (FFT) analysis on location coordinate sequences to detect artificial static coordinates or deterministic linear trajectory patterns.

**LocationSpoofer** is a **system-level virtual positioning and radio environment cloning solution** designed specifically to counter these deep anti-cheating mechanisms. 
By leveraging **KernelSU / Magisk / APatch** for root privileges and the **LSPosed (libxposed)** framework to inject hook routines into targeted processes, LocationSpoofer intercepts and fakes all positioning and wireless networking API responses with high physical fidelity. This ensures the target apps receive highly consistent location fingerprints without detecting any virtualization.

---

## ✨ Core Features & Technical Deep Dive

### 1. 🌐 Map Engine & Adaptive Coordinate Translator
* **Google Maps & Places SDK**: The map interface is fully powered by Google Maps, ensuring smooth multi-point path snapping and accurate crosshair placements.
* **Per-App Coordinate System Adapter**: Different applications expect different coordinate systems. Sending GCJ-02 coordinates to Baidu Maps or WGS-84 to others creates static offsets of 300-500 meters. LocationSpoofer lets you specify `GCJ-02` (Mars Coordinates), `WGS-84` (Standard GPS Coordinates), or `BD-09` (Baidu Coordinates) on a per-app basis.
* **Zero-Latency Calculations**: Coordinate translation is computed on-the-fly inside the Xposed hooks by fetching pre-calculated values from the host app, bypassing expensive trigonometric calls in high-frequency callback contexts.

### 2. 🛰️ High-Fidelity GPS Physics Engine with Monotonic Timestamps
Raw GPS receivers output coordinates that naturally contain Gaussian white noise due to ionospheric/tropospheric delays and clock offsets. Static or straight-line mock sequences are easily identified by anti-cheat spectral analyzers.
* **Decoupled Jitter Math**: The system supports custom location jitter (radius from 1m to 80m, defaulting to 30m, offering slow/medium/fast drift profiles). Jitter calculations are shifted to the hook output edge with a short caching window, resolving the previous infinite random-walk accumulation drift.
* **Monotonic Timestamp Generator**: To prevent Android from rejecting mock locations due to non-monotonic clock intervals (often reported as "weak GPS signal"), the hook layer features a monotonic timestamp generator ensuring output locations increment naturally on the timeline.
* **Altitude & Accuracy (GDOP) Drift**: Horizontal accuracy (Accuracy) and vertical elevation (Altitude) fluctuate slowly in a Brownian motion pattern to simulate tropospheric delay changes and changes in satellite geometries.

### 3. 🛡️ Stealth & Anti-Detection Suite
* **Deep Call Stack Cleaning**: Intercepts `Throwable.getStackTrace` and `Thread.getStackTrace` to scan stack frames. Any calling frames referencing `de.robv.android.xposed`, `io.github.libxposed`, or `lsposed` are dynamically expunged to prevent SDK trace detections.
* **Classloader Isolation**: Hooks `Class.forName` and `ClassLoader.loadClass` to throw a `ClassNotFoundException` whenever an application attempts to probe for Xposed classes.
* **Mock Flag Eraser**:
  * Forces `Location.isFromMockProvider()` and `Location.isMock()` to always return `false`.
  * Reflectively overwrites the private internal fields `mMock` and `mIsFromMockProvider` in the `Location` class to `false` (Android 12/13+ compatibility), while wiping custom mock values from the location Extras Bundle.
  * Intercepts `AppOpsManager`'s `OP_MOCK_LOCATION (58)` operation checks and forces a return value of `MODE_IGNORED (1)`.
  * Hooks secure settings queries (e.g. `mock_location`, `allow_mock_location`) in `Settings.Secure` to return `0` (disabled status).
  * Replaces test/mock providers in `LocationManager` and forces them to report as native `gps` signals.

### 4. 📶 Radio Environment Cloning & "Fail-Closed" Security
Anti-cheat engines compare your GPS coordinates against the Wi-Fi scan results reported by your device.
* **Zero Synthetic Fake Data**: Removed all generated Wi-Fi AP identities (e.g. `WIFI_Nearby_*`) and fake cell towers. If no trusted environmental data is present for the coordinates, the module securely returns empty sets, blocking the real environment without leaking fake patterns.
* **Fail-Closed Security Mechanism**:
  * **Wi-Fi Simulation**: Only accepts local scans, stored local DB records, or live queries from the **WiGLE API**. If no trusted data exists, the hook returns empty arrays, successfully hiding physical networks.
  * **Cellular Simulation**: Uses local sweeps and **OpenCellID API** cell records. Returns null or empty cellular structures when empty, refusing to synthesize fake towers.
* **On-Site Environment Scanner**: Background sweep utility collects and logs Wi-Fi networks (SSID/BSSID/RSSI/frequency/channel/WiFi standard), Cell Towers (GSM, WCDMA, CDMA, LTE, and 5G NR configurations containing MCC/MNC/LAC/CID/TAC/PCI/NCI and signal dbm), and BLE Bluetooth beacons.
* **Spatial Inverse Distance Weighting (IDW) Interpolation**: During virtual movements, the Xposed module searches the local Room SQLite DB for physical records within a 50-meter radius of the target coordinates. It computes weights based on the inverse square distance to interpolate nearby Wi-Fi RSSI, Cell signal strength dbm, and BLE Bluetooth RSSI.
* **Brand OUI Prefix Matching**: When generating fake scans in non-recorded zones, the generator assigns real MAC prefixes (OUI) belonging to mainstream network manufacturers (e.g. TP-Link, Huawei, ZTE, Xiaomi, Cisco, Netgear) instead of random MAC addresses.

### 5. 🛰️ Satellite Sky Matrix & NMEA Protocol Generator
* **GNSS Status Hijacking**: Hooks `GnssStatus` to simulate a fully populated constellation of 20+ active satellites (GPS, BeiDou, GLONASS) detailing unique PRN IDs, signal-to-noise ratios (CNR/SNR), elevations, azimuths, and Used-In-Fix status flags.
* **Dynamic NMEA Naming & Calculations**: Intercepts `OnNmeaMessageListener` and constructs matching raw NMEA-0183 sentences (such as `\$GPGGA`, `\$GPRMC`, `\$GPGSA`, `\$GPGSV`) in memory based on current coordinates, velocities, and bearing inputs, calculating the proper Checksum to bypass deep hardware queries.

### 6. 🚗 Route & Joystick Simulation
* **Route Path Snapping**: Fits custom multi-point routes to physical roads using routing APIs, preventing straight-line navigation. Select travel speed class (Walking, Running, Cycling, Driving, or custom speed value).
* **Compose Floating Joystick**: A float window containing an interactive joystick overlay to fine-tune coords on-the-fly, leveraging smooth bearing transitions and steering damping adjustments.

---

## 🏛️ System Architecture

This project is built on the **MVVM** architecture, implementing a custom Root-privileged IPC mechanism to bypass sandboxing restrictions and package visibility limits on Android 11+:

```
┌─────────────────────────────────────────────┐
│          LocationSpoofer (Host App)         │
│  ┌──────────┐  ┌──────────────────────────┐ │
│  │ Google   │  │    RouteStateMachine     │ │
│  │ Maps SDK │  │    (IDLE/READY/RUN...)   │ │
│  └────┬─────┘  └────────────┬─────────────┘ │
│       │                     │               │
│  ┌────▼─────────────────────▼─────────────┐ │
│  │            ConfigManager                 │ │
│  │   (Writes serialized config using temporary│ │
│  │    files to guarantee write atomicity)   │ │
│  └──────────────────┬───────────────────────┘ │
│  ┌──────────────────▼─────────────────────┐ │
│  │           SpoofingService               │ │
│  │       (Foreground Notification & Engine) │ │
│  └────────────────────────────────────────┘ │
└─────────────────────┬───────────────────────┘
                      │ (Atomic Config JSON Write)
                      ▼
        ┌───────────────────────────┐
        │ /data/local/tmp/ Config   │
        │ /data/system/    Config   │
        └─────────────┬─────────────┘
                      │ (Reads Config, Daemon Thread 1000ms Cache)
                      ▼ LSPosed Injection
┌─────────────────────────────────────────────┐
│              Target App Process             │
│  ┌────────────────────────────────────────┐  │
│  │            LocationHooker              │  │
│  │  • Location API / Baidu / Tencent SDK  │  │
│  │  • WiFi & Cellular (2G-5G NR) Injection│  │
│  │  • Bluetooth BLE Scan Filtering        │  │
│  │  • Anti-Mock & Xposed Stack Cleaning   │  │
│  │  • GnssStatus Satellite & NMEA Mocking │  │
│  │  • Diagnostics: ready / block only / off│  │
│  └────────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

> [!NOTE]
> **IPC Design Decisions**:
> Sandboxed app processes cannot query a custom `ContentProvider` on Android 11+ due to package visibility rules and SELinux isolation.
> To address this, the host app uses root shell permissions to atomically write configurations to `/data/local/tmp/locationspoofer_config.json`, changing permissions to `777`.
> The sandboxed module's `LocationHooker` launches a **background daemon thread** that polls the file every 1000ms and updates a volatile in-memory cache. The main thread hooks fetch settings from memory with 0-IO latency, completely preventing UI drop-frames.

---

## 📋 Requirements

* **OS Version**: Android 8.0 (API 26) or higher.
* **Root Manager**: Root access is required (recommended: [**KernelSU**](https://kernelsu.org) / APatch / Magisk).
* **Xposed Hook framework**: Installed and enabled [**LSPosed**](https://github.com/LSPosed/LSPosed).

---

## 🚀 Quick Start

### 1. Build & Install

```bash
# Clone the repository
git clone https://github.com/your-username/LocationSpoofer.git

# Build and install the Debug APK directly to your device
./gradlew installDebug
```

### 2. Module Activation
1. Launch **KernelSU / Magisk / APatch** and grant Root permissions to LocationSpoofer.
2. Open **LSPosed Manager**, enable **LocationSpoofer**.
3. Under the module's scope, **check the target apps** you wish to spoof.
4. **Force stop** target apps or reboot your phone to apply the hooks.

### 3. Usage Best Practices

#### 💻 Fixed Point Mocking
1. Launch LocationSpoofer, tap/drag the crosshair on the map, or use the search bar to find target locations.
2. In the bottom drawer, enable spoofing options: **Mock Wi-Fi**, **Mock Cell Tower**, **Mock Bluetooth**, and **Enable Jitter**.
3. Tap "Start Simulation" to take over standard system GPS feeds.
4. **RF Diagnostics**: Open the environment-data dialog to inspect spoofing states (`ready` indicates consistent simulation payload, `block only` indicates real RF channels are blocked with empty mock structures for safety).

#### 🕵️‍♂️ Signal Scanning & Spatial Replay
1. Before walking outdoors, open Settings -> toggle **"Environment Map & Street Scan"** scanning mode.
2. The scanner will run in the background, logging physical Wi-Fi/Cell/Bluetooth signals into local Room tables.
3. You can review collected points in the management page, edit tags, or **export as a JSON file** to share with others.

---

## 🛠️ Tech Stack

* **Language**: 100% Kotlin
* **Package Identifier**: `com.shiraka.locatiobprovid`
* **UI**: Jetpack Compose & Material Design 3
* **Dependency Injection**: Koin
* **Local Storage**: Room Database (SQLite)
* **Map SDK**: Google Maps & Places SDK
* **Xposed Hooking**: LSPosed API 93 / libxposed (Service mode)

---

## ⚠️ Disclaimer

This program is intended **solely for educational, academic, and developer testing purposes** (such as debugging coordinate-dependent apps).
Do not use this tool for any illegal activities or violations of third-party agreements. The author is not responsible for any banned accounts, data losses, legal issues, or other direct/indirect damages arising from the use of this software.

---

## 📜 License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

```
Copyright (C) 2026 SuseOAA / Shiraka
```
