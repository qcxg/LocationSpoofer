# LocationSpoofer

系統級 Android 位置與無線電環境模擬工具，面向需要在受控設備上做定位、地圖、風控相容性與環境資料測試的開發者。

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Root](https://img.shields.io/badge/Root-KernelSU%20%7C%20Magisk%20%7C%20APatch-orange.svg)](https://kernelsu.org)
[![Xposed](https://img.shields.io/badge/Framework-LSPosed-purple.svg)](https://github.com/LSPosed/LSPosed)

[更新記錄](CHANGELOG.md) | [English](README_EN.md)

> 本專案最初源自 [HuangZhuoRui/LocationSpoofer](https://github.com/HuangZhuoRui/LocationSpoofer)，目前已重構為獨立維護分支。當前包名為 `com.shiraka.locatiobprovid`。

## 核心定位

傳統 Android 開發者選項中的 Mock Location 容易被目標 App 或 SDK 通過 `isFromMockProvider`、AppOps、測試 provider、周邊 Wi-Fi／基站指紋等方式識別。LocationSpoofer 用 Root + LSPosed 在 Android 系統框架建立全域定位來源；只有 App 直接讀取 Wi-Fi、基站、GNSS status 或 NMEA 時，才在該 App 進程補充對應環境資料。

本專案遵守兩個重要原則：

- 不把 Android Developer Options mock-location assignment 作為主要機制。
- 不在缺少可信資料時偷偷生成 Wi-Fi AP 或基站身份；沒有可信 RF payload 時返回空資料並阻斷真實資料外洩。

## 主要能力

### 系統級 GNSS 模擬

- `system_server` 以單一 1 Hz 節拍經真實 provider 抽象層上報，並在 framework 驗證後、快取與派發前產生一個完整 WGS-84 fix。
- 經緯度、精度、海拔、運動欄位、wall time 與 elapsed realtime 在同一個樣本一次寫完；一般 App 仍走 Android 原生權限、AppOps、間隔與距離過濾鏈。
- 不攔截或吞掉 `LocationManager` 請求，不改寫 `Location` getter／Parcel，也不建立每個 App 的額外派發計時器。
- GMS 與未加入 module scope 的一般 App 都能從 framework 取得正常定位；GNSS status 與 NMEA 是可選的客戶端補充層。
- 固定點是目前已驗證的主模式。舊路線／搖桿入口仍在程式內，但未納入本輪回歸；下一階段將把搖桿改成獨立控制並考慮移除自動路線，詳見 [搖桿重構計畫](ai_readme/joystick_refactor_plan.md)。

### 抖動與自然漂移

- 最終位置抖動由 `system_server` 的唯一 framework 節拍取樣，服務端只寫入穩定基準座標，避免多層隨機游走反覆累加。
- 抖動半徑可在 `1m` 到 `80m` 之間無級調整，預設 `30m`。
- 慢 / 中 / 快除了使用不同時間尺度，也分別約每 3 / 2 / 1 個 1 Hz framework fix 推進一次漂移樣本；每個 fix 的經緯度仍屬於同一完整樣本。任一 RF 開關啟用時，最終漂移會留在已解析的同一六角格內；全部 RF 關閉時才使用完整設定半徑。

### 座標系適配

- Android framework 的標準 `Location` 固定輸出 `WGS-84`。
- `GCJ-02`、`BD-09` 只用於明確啟用的地圖 SDK 邊界，不得回寫到全域 framework fix。

### Wi-Fi / 基站環境模擬

可信資料來源只包括：

- 實機本地掃描並保存的環境資料。
- Room 資料庫中的歷史採集點。
- WiGLE API 返回的 Wi-Fi AP。
- OpenCellID API 返回的基站資料。

當 active 且對應模擬開關啟用時：

- Wi-Fi hook 可替換 `WifiInfo`、scan results 與 scan callback。
- 基站 hook 可替換 `getAllCellInfo()`、`getCellLocation()`、callback、service state 與 signal strength 等路徑。
- 若沒有可信 payload，hook 返回空或 null 的模擬資料，而不是暴露真實周邊訊號。

### 本地環境資料管理

- 支援實地掃描 Wi-Fi 與基站。
- 支援本地資料查看、編輯、匯入與匯出。
- 首頁顯示最近使用的 7 個位置；收藏列表保留為頂部書籤入口。
- 管理頁會把相同經緯度的環境記錄合併成一條，並在 Wi-Fi / 基站數量後標出「由 WiGLE / OpenCellID 導入」或本地采集來源。
- 採集資料使用 Room/SQLite 保存；只有含可重放 Wi-Fi 或基站關聯的記錄會即時映射到世界固定的 Web Mercator 六角格，六角格外接半徑為 30m。舊 Bluetooth 關聯只為資料庫升級與歷史資料管理保留，不參與掃描或運行期模擬。
- 地圖、開始前能力檢查與運行期 RF 解析共用 `EnvironmentCoveragePolicy` 的完全相同格 ID；`EnvironmentRfResolver`／DAO 只讀取目標所在格，密集采樣不會擴大範圍，跨格也不會借用資料。
- Exact-cell RF 六角格的有效緯度為 `±85.05112878°`；更高緯度目前不保證 RF 隔離或漂移限格，請勿在極區座標啟用 RF 模擬。
- 開始模擬時會先檢查目標六角格的本地 RF 快取；API 補採失敗時可重試，或直接以目前資料啟用。使用者已開啟但該格沒有可信 payload 的 RF 通道會保持啟用並進入 `block only`，不會退回真實 RF。
- 首頁座標算法入口可在設定中暫時顯示或隱藏。
- 本地搜尋與管理頁會優先用 Google Places Nearby Search 取附近 POI 名稱，再用 Geocoding / 系統 Geocoder 後備顯示「某地附近」。
- Wi-Fi payload 對話框提供去重列表：短按展開詳情，長按切換要暴露為 `connectedWifi` 的 AP。
- RF 診斷面板顯示 `ready`、`block only`、`off`、`loading` 等狀態，便於確認目前是模擬、阻斷還是關閉。

### Mock 標記與系統相容

- 清理 `Location.isFromMockProvider()` / `Location.isMock()` 與相關 mock 欄位。
- 隱藏 mock location AppOps 與 Settings 讀取痕跡。
- 不保留 Android test provider 作為運行機制。

## 運行架構

```text
LocationSpoofer App
  |-- Compose UI / MainViewModel: 更新 anchor、控制服務、呈現能力狀態
  |-- EnvironmentScanner / Room: 保存資料與即時 RF-bearing Flow
  |-- EnvironmentCoveragePolicy: 世界固定 30m Web Mercator 六角格
  |-- EnvironmentRfResolver / DAO: 只解析目標所在的完全相同格
  |-- SpoofingService: active 期間唯一持續運行期 writer
  |-- RuntimeConfigWriteCoordinator: 序列化寫入並淘汰過時 revision
  |-- ConfigManager: 啟停邊界配置
  |-- SpooferProvider
        |
        | 單一 moving-anchor 管線（含現有 legacy route / manual）
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
  |-- system_server: authoritative 1 Hz location source
  |-- GMS: ordinary framework consumer
  |-- optional target apps: GNSS / Wi-Fi / Cell supplements
  |-- fail-closed when active config is stale
```

固定點、路線與手動搖桿都只更新 `SpooferProvider` 中的 anchor；`MainViewModel` 不直接寫入運行期檔案，下一個 `SpoofingService` heartbeat 會以同一條管線發布位置及該格 RF payload。啟停邊界與服務 heartbeat 的寫入由 `RuntimeConfigWriteCoordinator` 序列化，較舊 generation 不得覆蓋較新的狀態。

> 路線與目前的 route-coupled 搖桿屬 legacy 功能，保留不代表已完成本輪實機驗證；後續應按重構計畫處理，不要為它另加定位 hook 或第二個 writer。

配置 JSON 使用帶 generation 的 `.tmp` + `mv` 原子寫入，避免 hooked process 讀到半截或過時配置。Hook 端會檢查 heartbeat 與 boot id：同一開機週期中 active 配置失效時 fail-closed；正常停止或重啟後不應復活舊的模擬狀態。

## 環境要求

- Android 8.0+。
- KernelSU、Magisk 或 APatch Root。
- LSPosed / libxposed 可用；全域普通定位需要系統框架與 GMS 作用域。只有要模擬 App 內直接讀取的 Wi-Fi、基站、GNSS status 或 NMEA 時，才把該 App 加入作用域。
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
2. 在 LSPosed 中啟用模組並啟用系統框架／GMS 作用域；需要 RF 或 GNSS/NMEA 補充時再加入相應 App。
3. 修改 module 或 scope 後重啟手機，確保 `system_server` 載入同一版本的唯一定位鏈。
4. 在 LocationSpoofer 中選擇目標點；舊路線模式目前不作為推薦主流程。
5. 根據需要啟用 Wi-Fi、基站與抖動配置。
6. 開始模擬後，在目標 App 中驗證定位與環境資料。

## 資料與安全邊界

- 本工具只應用於你擁有或被授權測試的設備與 App。
- 本工具不保證繞過任何第三方平台規則或風控。
- 缺少可信 RF 資料時，本工具選擇阻斷真實資料，而不是自動造假。
- 使用者需自行承擔違反法律、服務條款或平台規則造成的後果。

## 開源協議

本專案以 [GNU General Public License v3.0](LICENSE) 發布。
