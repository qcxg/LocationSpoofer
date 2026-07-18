# LocationSpoofer AI Notes

This folder is the maintained AI-facing technical memory for the project.

## Current Source Of Truth

- `technical_readme.md` is the current source of truth for the GNSS, Wi-Fi, cellular, Bluetooth, config, lifecycle, and hook paths.

## Active Planning Documents

- `joystick_refactor_plan.md` is the handoff plan for replacing the route-coupled manual control with an independent, service-owned joystick. It is a plan, not current behavior.

## Historical Records — Do Not Implement

- `history/global_location_lifecycle_fix.md` preserves an earlier lifecycle investigation. Its `writeToParcel()`/getter proposal is superseded.
- `history/phase_summary_2026-07-02.md` is a superseded phase snapshot.
- `history/diagnosis_report.md` records the old test-provider contamination incident. Its recovery commands and code recommendations are not current architecture.

Use historical files only to understand incidents. Resolve every current implementation question against `technical_readme.md` and the code.

## Maintenance Rules

- Keep `technical_readme.md` updated whenever spoofing data flow or lifecycle behavior changes.
- Ordinary location has one authoritative system path: real provider abstraction -> framework pre-filter canonical fix -> native delivery. Do not add `Location` getter/Parcel/transport resampling or client callback timers.
- Never swallow `LocationManager` requests. Unscoped apps must receive normal framework locations without a consumer-app scope list.
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

Clean debug build command:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat clean :app:assembleDebug --console=plain
```

Install command:

```powershell
& 'C:\Users\shira\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r 'C:\Users\shira\Documents\antigravity\LocationSpoofer\app\build\outputs\apk\debug\app-debug.apk'
```

Default test device:

- `47050DLAQ001LE`
