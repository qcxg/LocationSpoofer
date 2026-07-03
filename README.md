# LocationSpoofer

系統級 Android 位置與無線電環境模擬工具，面向需要在受控設備上做定位、地圖、風控相容性與環境資料測試的開發者。

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Root](https://img.shields.io/badge/Root-KernelSU%20%7C%20Magisk%20%7C%20APatch-orange.svg)](https://kernelsu.org)
[![Xposed](https://img.shields.io/badge/Framework-LSPosed-purple.svg)](https://github.com/LSPosed/LSPosed)

> 本專案最初源自 [HuangZhuoRui/LocationSpoofer](https://github.com/HuangZhuoRui/LocationSpoofer)，目前已重構為獨立維護分支。當前包名為 `com.shiraka.locatiobprovid`。

## 核心定位

傳統 Android 開發者選項中的 Mock Location 容易被目標 App 或 SDK 通過 `isFromMockProvider`、AppOps、測試 provider、周邊 Wi-Fi/基站/BLE 指紋等方式識別。LocationSpoofer 的目標是用 Root + LSPosed 在系統與目標進程層接管定位與無線環境讀取，讓測試位置、座標系、GNSS 訊號與 RF 指紋在同一條資料鏈上保持一致。

本專案遵守兩個重要原則：

- 不把 Android Developer Options mock-location assignment 作為主要機制。
- 不在缺少可信資料時偷偷生成 Wi-Fi AP 或基站身份；沒有可信 RF payload 時返回空資料並阻斷真實資料外洩。

## 主要能力

### 系統級 GNSS 模擬

- Hook `Location`、`LocationManager`、GMS/Fused Location 可達路徑、GNSS status、NMEA listener 等定位入口。
- 覆寫經緯度、精度、海拔、速度、方向、時間戳與 elapsed realtime。
- 對同一個 `Location` 物件維持單調且一致的時間戳，避免系統因 non-monotonic location 拒收更新。
- 支援 `requestLocationUpdates()` 的持續主動派發，並在 `removeUpdates()` 或配置失效時停止。
- 支援固定點、路線、循環路線與懸浮搖桿控制。

### 抖動與自然漂移

- 最終位置抖動在 Xposed hook 邊緣完成，服務端只寫入穩定基準座標，避免隨機游走被反覆累加。
- 抖動半徑可在 `1m` 到 `80m` 之間無級調整，預設 `30m`。
- 提供慢 / 中 / 快三種漂移檔位，並快取同一視窗內的經緯度讀取，避免一次位置讀取中緯度與經度來自不同樣本。

### 座標系適配

- 支援為不同目標 App 指定 `GCJ-02`、`WGS-84`、`BD-09` 等座標輸出策略。
- 中國地圖 SDK 與一般國際定位 API 可按 App 單獨配置，減少固定偏移。

### Wi-Fi / 基站 / BLE 環境模擬

可信資料來源只包括：

- 實機本地掃描並保存的環境資料。
- Room 資料庫中的歷史採集點。
- WiGLE API 返回的 Wi-Fi AP。
- OpenCellID API 返回的基站資料。

當 active 且對應模擬開關啟用時：

- Wi-Fi hook 可替換 `WifiInfo`、scan results 與 scan callback。
- 基站 hook 可替換 `getAllCellInfo()`、`getCellLocation()`、callback、service state 與 signal strength 等路徑。
- BLE 掃描資料可按配置替換或阻斷。
- 若沒有可信 payload，hook 返回空或 null 的模擬資料，而不是暴露真實周邊訊號。

### 本地環境資料管理

- 支援實地掃描 Wi-Fi、基站與 BLE。
- 支援本地資料查看、編輯、匯入與匯出。
- 首頁顯示最近使用的 7 個位置；收藏列表保留為頂部書籤入口。
- 管理頁會把相同經緯度的環境記錄合併成一條，並在 Wi-Fi / 基站數量後標出「由 WiGLE / OpenCellID 導入」或本地采集來源。
- 本地搜尋與管理頁會優先用系統地理編碼、再用 Google Geocoding 顯示「某地附近」。
- Wi-Fi payload 對話框提供去重列表：短按展開詳情，長按切換要暴露為 `connectedWifi` 的 AP。
- RF 診斷面板顯示 `ready`、`block only`、`off`、`loading` 等狀態，便於確認目前是模擬、阻斷還是關閉。

### 反檢測處理

- 清理 `Location.isFromMockProvider()` / `Location.isMock()` 與相關 mock 欄位。
- 隱藏 mock location AppOps 與 Settings 讀取痕跡。
- 隔離常見 Xposed / LSPosed 類名查詢。
- 清理呼叫棧中可疑框架影格。
- 不保留 Android test provider 作為運行機制。

## 運行架構

```text
LocationSpoofer App
  |-- Compose UI / ViewModel
  |-- EnvironmentScanner / Room
  |-- SpoofingService
  |-- ConfigManager
  |-- SpooferProvider
        |
        | active runtime config
        v
Config channels
  |-- /data/local/tmp/locationspoofer_config.json
  |-- /data/system/locationspoofer_config.json
  |-- locationspoofer_prefs
  |-- gsm.locsp.* system properties
  |-- exported ContentProvider fallback
        |
        v
LSPosed injected processes
  |-- system / GMS / target apps
  |-- LocationHooker
  |-- GNSS / Wi-Fi / Cell / BLE replacement
  |-- fail-closed when active config is stale
```

配置 JSON 使用 `.tmp` + `mv` 原子寫入，避免 hooked process 讀到半截配置。Hook 端會檢查 heartbeat 與 boot id：同一開機週期中 active 配置失效時 fail-closed；正常停止或重啟後不應復活舊的模擬狀態。

## 環境要求

- Android 8.0+。
- KernelSU、Magisk 或 APatch Root。
- LSPosed / libxposed 可用，並將 LocationSpoofer 與需要測試的目標 App 加入作用域。
- Google Maps API key 用於地圖與搜尋。
- WiGLE / OpenCellID token 可選，用於補充真實 RF payload。

## 編譯

本專案使用 Kotlin、Jetpack Compose、Material 3、Room、Koin、Google Maps / Places SDK 與 LSPosed API。

Windows PowerShell 示例：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

生成 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安裝：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 基本使用

1. 安裝 APK，授予 Root 權限。
2. 在 LSPosed 中啟用模組，將需要測試的 App 加入 scope。
3. 強行停止目標 App，或重啟手機，讓 hook 生效。
4. 在 LocationSpoofer 中選擇目標點或路線。
5. 根據需要啟用 Wi-Fi、基站、BLE 與抖動配置。
6. 開始模擬後，在目標 App 中驗證定位與環境資料。

## 資料與安全邊界

- 本工具只應用於你擁有或被授權測試的設備與 App。
- 本工具不保證繞過任何第三方平台規則或風控。
- 缺少可信 RF 資料時，本工具選擇阻斷真實資料，而不是自動造假。
- 使用者需自行承擔違反法律、服務條款或平台規則造成的後果。

## 開源協議

本專案以 [GNU General Public License v3.0](LICENSE) 發布。
