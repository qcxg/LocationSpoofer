# LocationSpoofer AI Notes

This folder is the maintained AI-facing technical memory for the project.

## Current Documents

- `technical_readme.md` is the current source of truth for the GNSS, Wi-Fi, cellular, Bluetooth, config, lifecycle, and hook paths.
- `global_location_lifecycle_fix.md` is a historical deep dive for the lifecycle failure that previously kept spoofing active after reboot or service death.
- `diagnosis_report.md` is a historical diagnosis record for the device state corruption incident.

## Maintenance Rules

- Keep `technical_readme.md` updated whenever spoofing data flow or lifecycle behavior changes.
- Do not reintroduce generated Wi-Fi APs or coordinate-derived cell towers as hidden fallbacks.
- If a fallback is needed for Android object construction, document it as object-shape normalization, not as collected/API data.
- Remove transient status notes once their content is merged into the current technical document.
- After every successful debug build, install the APK automatically with `adb install -r`, then record only the current status unless a data-flow behavior changed.

## Local Build And Deploy Environment

Use these paths directly after context compaction. Do not rediscover them unless they stop working.

- Java 21 runtime for Gradle:
  `C:\Program Files\Android\Android Studio\jbr`
- ADB executable:
  `C:\Users\shira\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Debug APK output:
  `C:\Users\shira\Documents\antigravity\LocationSpoofer\app\build\outputs\apk\debug\app-debug.apk`

Build command:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

Install command:

```powershell
& 'C:\Users\shira\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r 'C:\Users\shira\Documents\antigravity\LocationSpoofer\app\build\outputs\apk\debug\app-debug.apk'
```

Known connected device from the latest successful install:

- `47050DLAQ001LE`

Codex sandbox note: running `adb.exe` and the Android Studio JBR may require escalated execution even though the files are readable. The latest successful debug build and install completed with these exact paths on 2026-07-02.
