<div align="center">

<h1>LocationSpoofer</h1>

<p>基於 KernelSU + LSPosed 的高保真 Android 系統級虛擬定位與無線環境偽裝模組</p>
<p>High-fidelity Android system-level location spoofing and wireless environment simulation module based on KernelSU + LSPosed</p>

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![KernelSU](https://img.shields.io/badge/Root-KernelSU-orange.svg)](https://kernelsu.org)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-purple.svg)](https://github.com/LSPosed/LSPosed)

[繁體中文](README.md) | [English](README_EN.md)

</div>

---

> **📖 查看 [LocationSpoofer 詳細使用教學](https://docs.google.com/document/d/1fFEz3k7ATdN2dwY1L3RJn1QuzgokIsslNa88-vUPxPk/edit?usp=sharing)**

---

## 📖 專案簡介

在現代 Android 系統的風控環境中，傳統的「模擬位置（Mock Location）」開發者選項早已被各類反作弊 SDK（如高德風控、騰訊安全、網易易盾等）列為高風險特徵。它們不僅能夠輕鬆檢測到 `isFromMockProvider` 標誌，還會透過收集周圍的以下資訊來識別異常：

*   **Wi-Fi BSSID 列表**
*   **行動基站蜂窩小區的蜂窩指紋**
*   **附近 BLE 藍牙信標**
*   甚至透過對定位數據序列進行傅立葉變換（FFT）來識別規律的靜態或線性模擬軌跡。

**LocationSpoofer** 是專為因應此類高強度風控而設計的**系統級虛擬定位與無線電環境克隆方案**。
它基於 **KernelSU / Magisk / APatch** 獲取底層 Root 權限，並利用 **LSPosed (libxposed)** 框架注入目標進程，以極高的物理契合度攔截並偽造所有與定位、網路環境相關的底層 API 響應，確保應用在無法察覺的情況下獲取高度一致的虛假位置指紋。

> [!TIP]
> **🌟 最新版本重要更新**
> 
> 重構並強化了 **Wi-Fi 環境模擬** 與 **行動基站模擬** 的真實性與安全性：
> * **嚴格拒絕生成虛擬 AP**：移除所有隨機生成的 Wi-Fi AP 識別（如 `WIFI_Nearby_*`）與虛擬基站。若定位區域無可信資料，則安全地回傳空資料，確保真實無線電訊號被完全阻斷而不洩露，實現「Fail-Closed」安全機制。
> * **高斯隨機游走抖動邊緣化**：將隨機游走計算移至 Xposed 鉤子層邊緣，解決了先前因服務端重複累積導致的隨機行走無限發散問題。
> * **單調時間戳修復**：引入單調遞增時間戳，解決了因時間倒流導致 Android 系統拒絕位置更新或提示定位訊號弱的問題。

---

## ✨ 核心特性與技術內幕

### 1. 🌐 地圖引擎與自適應座標系翻譯器
* **Google Maps 整合**：專案全面採用 Google Maps & Places SDK 作為地圖基礎，提供流暢的路線規劃與十字準星微調。
* **獨立應用座標系適配**：不同的應用（如微信、學習通、地圖軟體等）對定位介面的期望座標系不同。若直接傳入座標，可能會在特定地圖上產生數百公尺偏移。LocationSpoofer 支援為每個目標 App 單獨指定 `GCJ-02` (火星座標)、`WGS-84` (GPS 原始座標) 或 `BD-09` (百度座標)。
* **零延遲翻譯**：在 Xposed 進行 Hook 時，直接從主進程預先計算好的經緯度數據讀取，規避高頻 Hook 場景下的三角函數開銷。

### 2. 🛰️ 高保真 GPS 物理引擎與隨機游走抖動
真實 GPS 晶片輸出的座標因電離層閃爍、多徑效應及接收機雜訊，天然具有高斯分布的白雜訊。若回傳靜態座標或機械的直線座標，極易被反作弊檢測。
* **Ornstein-Uhlenbeck 隨機過程**：我們引入了高斯隨機游走物理模型來生成自然位置抖動，其狀態方程定義為：
  $$\mathrm{d}X_t = -\alpha X_t \mathrm{d}t + \sigma \mathrm{d}W_t$$
  其中 $\sigma$ 為漂移強度（使用者可在 UI 自訂 1m 至 80m，預設為 30m），$\alpha$ 為均值回歸係數。它既產生了符合物理規律的低頻緩慢漂移，又使漂移量在 3-Sigma 原則下嚴格有界，防止漂移無限發散引起「位置瞬移」告警。
* **步頻橫向抖動（Gait Jitter）**：當處於步行或跑步模式時，引擎自動沿當前移動方向的正交橫向上，根據步頻週期的統計機率施加 `0.15 * N(0,1)` 公尺的高斯橫向位移，完美模擬真實人行時身體左右晃動的步態特徵。
* **海拔高度與精度（GDOP）慢漂移**：精度值（Accuracy）及海拔高度（Altitude）不再是死板的常數，而是在基準值附近進行布朗運動式的緩慢波動，模擬大氣對流層延遲與衛星幾何分布的自然變化。

### 3. 🛡️ 全方位反檢測套件（Stealth & Anti-Detection）
* **深度呼叫棧清洗（Stack Traces Scrubbing）**：動態攔截 `Throwable.getStackTrace` 和 `Thread.getStackTrace`，一旦發現呼叫棧中包含 `de.robv.android.xposed`、`io.github.libxposed`、`lsposed` 等敏感呼叫影格，自動抹除，讓反作弊 SDK 無法在呼叫鏈回溯中發現 Xposed 框架。
* **類別載入隔離**：Hook `Class.forName` 和 `ClassLoader.loadClass`，對於上述敏感類別名稱的查詢直接攔截並拋出 `ClassNotFoundException`。
* **Mock 屬性徹底抹除**：
  * 將 `Location.isFromMockProvider()` 和 `Location.isMock()` 的傳回值永久強制覆寫為 `false`。
  * 針對 Android 12/13+ 系統，透過反射強制將 `Location` 內部私有欄位 `mMock` 和 `mIsFromMockProvider` 重寫為 `false`，並移除 Extra Bundle 中可能殘留的 `mockLocation` 標記。
  * 攔截 `AppOpsManager` 的 `OP_MOCK_LOCATION (58)` 權限查詢，強制傳回 `MODE_IGNORED (1)`，令目標 App 認為系統沒有向任何軟體授權模擬位置。
  * 攔截 `Settings.Secure` 中 `mock_location` 及 `allow_mock_location` 的讀取，傳回 `0`（關閉狀態）。
  * 隱藏 `LocationManager` 中的虛擬 Test Provider，將其一律重命名並偽裝為系統的原生 `gps` 提供者。

### 4. 📶 無線電環境克隆與空間熱力插值引擎
大多數反作弊 SDK 會採集周邊的無線電指紋。如果你定位到台北，但手機掃出來的 Wi-Fi 列表全在你高雄的家裡，瞬間就會被標記異常。
* **實地掃街掃描器（EnvironmentScanner）**：支援在背景運行掃描，自動保存你真實走過的物理世界中的 Wi-Fi 接入點（SSID/BSSID/RSSI/頻率/信道/Wi-Fi標準）、基站小區資訊（GSM, WCDMA, CDMA, LTE, 甚至是 5G NR 的完整蜂窩指紋 MCC/MNC/LAC/CID/TAC/PCI/NCI 及 dbm 訊號強度）以及附近 BLE 藍牙信標。
* **空間反距離加權（IDW）插值**：當虛擬定位在地圖上運動時，系統會在 Room 本地資料庫中檢索 50 公尺範圍內的歷史採集點。使用反距離平方比作為權重對周邊所有訊號強度進行動態差值計算，模擬最真實的訊號過渡。
* **可信 Wi-Fi 來源保障**：僅接受本地實地掃街數據、Room 資料庫記錄以及透過 **WiGLE API** 線上查詢的真實 Wi-Fi 熱點。若該區域無可信資料，Hook 層會回傳空的模擬資料，完全屏蔽真實 Wi-Fi 訊號，防止風控 SDK 讀取到你真實的物理位置。
* **可信基站來源保障**：僅接受實地掃街基站、Room 本地記錄以及透過 **OpenCellID API** 查詢的真實蜂窩小區。若無資料則回傳空/null，拒絕隨機合成虛假基站，防止被基站庫校驗識破。
* **真實品牌 OUI 前綴匹配**：在空白區域產生虛擬 Wi-Fi BSSID 時，系統採用 TP-Link、Huawei、ZTE、Xiaomi、Cisco 等真實主流品牌路由器的合法 MAC 前綴（Organizationally Unique Identifier），拒絕因隨機產生非法 MAC 而被反作弊廠商識破。

### 5. 🛰️ 衛星矩陣與 NMEA 協定產生器
* **GNSS Status 劫持**：Hook 系統的 `GnssStatus` 類別，模擬多達 20+ 顆包括 GPS、北斗、GLONASS 的衛星分布矩陣，注入真實的 PRN 識別、信噪比（CNR）、俯仰角、方位角等，並正確匯報 `usedInFix`（參與定位計算）狀態。
* **NMEA 語句流動態拼裝**：劫持 `OnNmeaMessageListener`。根據目前的模擬位置、航向角、速度和模擬出的衛星，在記憶體中動態組裝符合國家標準的原始 `\$GPGGA`, `\$GPRMC`, `\$GPGSA`, `\$GPGSV` 語句並計算校驗和（Checksum）即時輸出，滿足對底層 NMEA 訊號進行硬核校驗的 App。

### 6. 🔀 智慧路線導航與紅綠燈停候系統
* **真實物理路網擬合**：支援多路點航線設計，透過路網搜尋演算法擬合實際道路輪廓，防止軌跡穿牆或走直線。
* **紅綠燈智慧識別**：路線模擬運行到紅綠燈點時，會**自動停駐 15 秒**再重新加速，完美模擬真實的車輛行車特徵。
* **懸浮窗搖杆**：提供 Compose 懸浮窗虛擬搖杆，可在前台直接手動微調座標。支援根據搖杆拉伸幅度在 0 ~ 10 m/s 之間平滑過渡。

---

## 🏛️ 系統架構

本專案採用 **MVVM** 架構，並利用 Root 權限規避了 Android 11+ 的沙盒可見性隔離，實現零權限跨進程配置傳遞：

```
┌─────────────────────────────────────────────┐
│            LocationSpoofer (宿主 App)       │
│  ┌──────────┐  ┌──────────────────────────┐ │
│  │ Google   │  │    RouteStateMachine     │ │
│  │ Maps SDK │  │    (IDLE/READY/RUN...)   │ │
│  └────┬─────┘  └────────────┬─────────────┘ │
│       │                     │               │
│  ┌────▼─────────────────────▼─────────────┐ │
│  │            ConfigManager                 │ │
│  │  (以臨時檔寫入後 mv，確保配置讀寫的原子性)   │ │
│  └──────────────────┬───────────────────────┘ │
│  ┌──────────────────▼─────────────────────┐ │
│  │           SpoofingService               │ │
│  │       (前台通知服務 & 軌跡計算引擎)       │ │
│  └────────────────────────────────────────┘ │
└─────────────────────┬───────────────────────┘
                      │ (原子寫入配置 JSON)
                      ▼
        ┌───────────────────────────┐
        │ /data/local/tmp/ 配置文件  │
        │ /data/system/    配置文件  │
        └─────────────┬─────────────┘
                      │ (異步讀取配置，緩存至 Volatile 內存)
                      ▼ LSPosed 注入
┌─────────────────────────────────────────────┐
│              目標 App 進程                  │
│  ┌────────────────────────────────────────┐  │
│  │            LocationHooker              │  │
│  │  • Location API / Baidu / Tencent SDK  │  │
│  │  • WiFi & 蜂窩基站 (2G-5G NR) 環境注入    │  │
│  │  • 藍牙 BLE 掃描過濾                    │  │
│  │  • Anti-Mock & Xposed 堆棧清洗反檢測   │  │
│  │  • GnssStatus 衛星 & NMEA 模擬          │  │
│  │  • 診斷面板：支援 ready/block only/off   │  │
│  └────────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

> [!NOTE]
> **關於跨進程通信 (IPC) 的設計決策**：
> 宿主 App 藉由 Root 權限將配置以 JSON 格式原子地寫入 `/data/local/tmp/locationspoofer_config.json`，並賦予 `777` 權限。
> 目標沙盒內的 `LocationHooker` 啟動一個**背景守護線程**，每 1000ms 異步讀取檔案並儲存在 Volatile 記憶體中。主線程的 Hook 方法讀取配置時永遠是 0-IO 延遲，徹底杜絕了因頻繁讀取配置導致的目標 App 畫面丟影格與卡頓。

---

## 📋 環境要求

* **系統版本**：Android 8.0 (API 26) 及以上
* **Root 管理器**：已獲取 Root 權限，強烈推薦使用 [**KernelSU**](https://kernelsu.org) / APatch，亦支援 Magisk。
* **Xposed 框架**：已安裝並啟用 [**LSPosed**](https://github.com/LSPosed/LSPosed)。

---

## 🚀 快速開始

### 1. 編譯與安裝

```bash
# 克隆本倉庫到本地
git clone https://github.com/your-username/LocationSpoofer.git

# 編譯 Debug 版本並直接安裝至設備
./gradlew installDebug
```

### 2. 權限與啟用步驟
1. 打開 **KernelSU / Magisk / APatch** 管理器，授予 LocationSpoofer **Root 權限**。
2. 打開 **LSPosed** 管理器，啟用 LocationSpoofer 模組。
3. 在模組的作用域（Scope）中，**勾選需要進行定位偽裝的目標應用**。
4. **強行停止**勾選的目標 App（或重啟手機）以使其載入 Xposed 鉤子邏輯。

### 3. 使用場景最佳實踐

#### 💻 定點環境欺騙
1. 啟動 LocationSpoofer，在地圖中長按或利用搜尋欄搜尋指定地點。
2. 在下方抽屜中開啟需要偽裝的項目，點擊「啟動模擬」即可接管系統 GPS。
3. **RF 訊號診斷**：在環境資料對話框中，你可以即時查看 Wi-Fi 與基站的模擬狀態（`ready` 代表有可信訊號偽裝，`block only` 代表已成功阻斷真實訊號並回傳空資料，確保安全）。

#### 🕵️‍♂️ 無線電訊號採集（實地掃街）
1. 在物理世界中活動前，進入 LocationSpoofer 「設置」 -> 開啟 **「環境圖譜與掃街」** 採集模式。
2. 採集完成後，可以在「管理本地採集數據」中編輯、以 **JSON 檔案格式匯出** 備份或分享。

---

## 🛠️ 技術棧與依賴庫

* **開發語言**：100% Kotlin
* **主要包名**：`com.shiraka.locatiobprovid`
* **介面框架**：Jetpack Compose & Material Design 3
* **依賴注入**：Koin
* **底層資料庫**：Room Database (SQLite)
* **地圖組件**：Google Maps & Places SDK
* **Xposed 框架**：LSPosed API 93 / libxposed (Service 模式)

---

## ⚠️ 免責聲明

本程序**僅供學習研究、技術交流以及個人合法合規測試（如開發者定位測試、設備相容性調試）使用**。
使用者請勿將本工具用於任何違法違規或違反相關平台服務協議的活動。使用本模組造成的任何帳號封禁、數據丟失、法律糾紛或其他直接/間接損失，均由使用者自行承擔，作者不對此承擔任何責任。

---

## 📜 開源協議

本項目基於 [GNU General Public License v3.0](LICENSE) 協議開源。

```
Copyright (C) 2026 SuseOAA / Shiraka
```
