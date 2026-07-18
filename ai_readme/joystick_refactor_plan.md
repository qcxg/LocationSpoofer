# 搖桿重構計畫（下一階段）

狀態：僅規劃，尚未實作
建立日期：2026-07-13
前置文件：先完整閱讀 `technical_readme.md`，歷史文件不得作為目前架構依據。

## 使用者目標

- 下一階段集中完善「搖桿手動移動」。
- 自動路徑／來回路線不是近期需求；建議移除其 UI 與運行邏輯，減少狀態與時序分支。
- 搖桿必須是獨立功能，不應要求使用者先選兩個路點。
- 本次只留下可直接交給下一個開發任務的設計與執行計畫，不修改現有功能。

## 建議決策

| 功能 | 建議 | 理由 |
|---|---|---|
| 定點模擬 | 保留 | 是目前穩定主鏈與搖桿起點 |
| 搖桿手動移動 | 重寫 | 現況與路線狀態耦合，速度與停止語義不完整 |
| 自動來回路線 | 移除 | 非近期需求，會增加 service、UI、config 與資料庫分支 |
| 「真實道路規劃」 | 移除 | 現況只有 UI／旗標，沒有完整取路 API 運行鏈 |
| 路線收藏 UI | 移除 | 目前同時存在 Room 與 SharedPreferences 兩套資料路徑 |
| `saved_routes` 資料表 | 第一階段保留 | 先避免不必要的 Room migration 風險，第二階段再正式移除 |
| runtime 的 route 欄位 | 暫時保留為空／false | 讓已載入的 hook/config parser 平滑相容一個版本 |

推薦做法是「先切斷路線入口並建立獨立搖桿，再刪除死代碼」。不要在同一個大 patch 中同時改 system hook、RF 覆蓋與 Room schema。

## 目前搖桿／路線的已知問題

以下行號是 2026-07-13 工作樹快照，後續修改後可能位移。

1. 手動搖桿仍走 `startRoutePlanning()`，而該入口要求至少兩個路點；使用者無法直接從目前位置啟動搖桿。見 `MainViewModel.kt:1193-1201`。
2. `JoystickPanel` 的最大速度直接取 `routeSimMode.speedMs`；手動模式沒有真正獨立的速度設定。見 `MapScreen.kt:384-392`。
3. `CUSTOM` 雖有狀態與輸入框，但沒有出現在可選 chip 清單，因此正常 UI 不可達。見 `MapScreen.kt:862-905`。
4. Compose 端每 100ms 呼叫一次 `moveByJoystick()`，ViewModel 又把每次位移固定當作 `0.1s`。卡頓、背景切換或 coroutine 延遲都會讓實際速度失真。見 `MapScreen.kt:964-997`、`MainViewModel.kt:1031-1050`。
5. ViewModel 直接把 manual output 標為 `STILL` 並發布 `speed=0`；座標雖會移動，對外速度／方位時序並不完整。見 `MainViewModel.kt:1380-1400`。
6. 搖桿、路線選點、路線運行、全螢幕地圖與收藏共用 `RoutePlanStage`，造成 UI 狀態互相牽制。
7. 「真實路線」目前只有 `useRealRoute`／`isFetchingRoute` 與說明 UI，沒有完整的抓取、錯誤與運行實作。
8. 路線收藏同時存在 Room DAO 與舊 SharedPreferences 方法，會讓刪除、載入和遷移語義不唯一。
9. `TrajectorySimulator` 同時承擔固定點、路線與歷史抖動算法，職責過大；目前最終定位漂移其實已由 hook 端的完整 sample 時序負責。

## 不可破壞的現有架構

下一個任務無論如何重寫搖桿，都必須保持以下不變式：

- `SpoofingService` 是唯一持續運行、唯一寫入 runtime config／heartbeat 的 owner。
- `MainViewModel` 只能發布 in-memory command、更新 UI 或要求 refresh；不得恢復第二個 runtime file writer。
- 普通定位只有一條 system_server 主鏈：真實 provider abstraction → `LocationProviderManager.processReportedLocation()` canonical fix → framework cache/filter/delivery。
- 不增加 `Location` getter、Parcel、client callback timer 或每 App 定位重採樣 hook。
- system framework 的定位發布節奏維持約 1 Hz；搖桿 UI 可以更流暢，但不得因此建立第二條系統定位鏈。
- 地圖、DAO、runtime RF 仍共用 `EnvironmentCoveragePolicy` exact-cell；禁止鄰格借資料或恢復距離圓覆蓋。
- Wi-Fi／Cell／BLE 開關表示使用者意圖；開啟但無 payload 必須是 `block only`，不可洩漏真實 RF。
- 最終漂移仍在 hook 的完整 location sample 內生成；RF 開啟時不得把最終位置漂到已解析六角格外。
- 正常 stop 必須明確發布 `active=false`；舊 command、start job 或 heartbeat 不得在 stop 後重新啟動。
- 不使用 Android test/mock provider，也不重新加入已刪除的 overlay `FloatingJoystickService`。

## 推薦的新資料流

```text
Compose Joystick UI
  -> MainViewModel.submitJoystickCommand(...)
  -> SpooferProvider atomic JoystickCommand
  -> SpoofingService 單一 motion loop（唯一運動 owner）
       -> monotonic dt 積分位置／速度／方位
       -> 更新 in-memory current position 供 UI 顯示
       -> exact-cell RF resolver
       -> 每約 1 秒原子寫 config + heartbeat
  -> system_server 既有 1 Hz canonical provider pipeline
  -> Hooker 完整 sample 漂移／時間戳／RF 限格
```

### 建議 command model

```kotlin
data class JoystickCommand(
    val sessionId: Long,
    val x: Float,                 // -1f..1f
    val y: Float,                 // -1f..1f
    val maxSpeedMps: Float,
    val pressed: Boolean,
    val updatedElapsedNanos: Long
)
```

- 用 `SystemClock.elapsedRealtimeNanos()`，不可用 wall clock 計算位移。
- command 以 immutable atomic snapshot 發布；不要讓 UI 與 service 同時修改座標。
- `sessionId` 防止舊畫面、旋轉前 pointer 或延遲 coroutine 覆蓋新控制階段。
- `pressed=false`、pointer cancel、返回鍵、畫面進背景都要立即發布 neutral command。
- service 對 command 設 `300–500ms` TTL；UI／進程失聯後自動歸零，避免無限移動。

### 建議 motion model

- dead zone 建議 `0.10–0.15`，消除中心小抖動。
- dead zone 外重新正規化 intensity，再用曲線控制低速精度，例如 `speed = maxSpeed * intensity^1.6`。
- 用可配置加速度／減速度平滑速度，避免每次拖動立即跳到最大速度。
- 每次用真實 monotonic `dt` 積分，並把單次 `dt` clamp 到合理範圍；service 暫停數秒後不可一次跳出數十米。
- 用球面 destination 計算前進位置，經度經 `normalizeLongitude()` 處理日期變更線。
- 移動時對外發布實際 `speed` 與 course-over-ground `bearing`；停下時 `speed=0`，不要用假方位製造移動。
- RF 模擬開啟時，把可用緯度限制在 `EnvironmentCoveragePolicy` 的 Web Mercator 範圍；目前極區限制不可在新搖桿中被默默忽略。

### 單一 loop，而不是多個 writer

若需要 20 Hz 的搖桿手感，可讓 `SpoofingService` 內只有一個高頻 motion loop：

- 每 `50ms` 左右讀 command 並積分 in-memory 位置；
- 只在同一 loop 中每約 `1s` 發布一次 runtime config／heartbeat；
- RF 僅在 cell／DB revision／refresh revision／同格顯著移動時重算；
- UI 讀 in-memory current position 做平滑 marker，不自行寫 config。

這仍然只有一個 owner、一個排序域。不要建立「UI 100ms writer + service 1s writer」兩條運動時間線。

## 推薦 UI

### 入口

- 首頁把「規劃路線」改成獨立的「搖桿控制」。
- 若 spoofing 尚未啟動：先沿用目前定點開始流程完成 RF 能力解析，再進入搖桿畫面。
- 若 spoofing 已啟動：直接以目前 anchor 進入搖桿，不停止／重啟 service。
- 不要求路點，不顯示路線模式選擇。

### 控制畫面

- 全螢幕地圖 + 單一搖桿，避開 navigation bar／橫豎屏安全區。
- 顯示目前速度、最大速度、方位與 RF 狀態；不要顯示一套與 runtime 不一致的預測值。
- 提供簡單速度 preset（例如步行／騎行／車行）與可選自訂上限；preset 與漂移的慢／中／快是兩組不同概念，名稱不可混用。
- 提供「回到目前位置」、「鏡頭跟隨」和「退出手動控制」。
- 「退出手動控制」只讓 command 歸零並回到固定點；「停止模擬」才執行完整 stop。
- 預設禁止 cruise lock。若未來要做，必須是明確開關，不能因 pointer 遺失而隱式保持速度。

### Compose 實作注意

- thumb 位移可以是 composable-local 高頻 state；對 ViewModel command 做節流／合併，不要讓整個地圖樹每個 pointer event 全量重組。
- `onDragEnd`、`onDragCancel`、`DisposableEffect.onDispose` 與 lifecycle `ON_STOP` 都要發 neutral command。
- UI marker 可在兩個 1 Hz service sample 之間視覺插值，但插值只用於畫面，不能餵回系統定位。

## 移除路徑模擬的安全順序

### Phase 1：切斷入口，不碰資料庫 schema

1. 把首頁「規劃路線」入口替換成「搖桿控制」。
2. 在 `MapScreen` 建立獨立 joystick state，不再用 `RoutePlanStage`／`RouteRunMode` 啟動手動模式。
3. 新增 atomic `JoystickCommand` 與 service-owned motion engine。
4. 搖桿穩定後，移除 `RoutePlanConfigDialog`、路點選取、route library 與 automatic loop UI。
5. service 移除 `calculatePingPongRoutePosition()` 分支與 `parseRoutePoints()`。
6. runtime config 暫時繼續寫 `route_points=[]`、`is_route_mode=false`；舊 parser 仍可安全讀取。
7. `saved_routes` Entity／DAO／table 暫留但不再注入主要 runtime path，避免此階段加入 Room migration 風險。

### Phase 2：刪除死代碼

1. 從 `AppState` 移除 route points/stage/run mode/route speed/real-route/fetching state。
2. 從 `MainViewModel` 移除 route planning、route UI sync、route save/load/delete 方法。
3. 從 `LocationRepository`／`ConfigManager` 簡化 route 參數；若仍保留 config compatibility，集中在 serializer 內寫固定空值。
4. 刪除 `TrajectorySimulator` 的 route-only 算法；固定點若只需要 anchor，可直接輸出 anchor，手動運動放入專用 `JoystickMotionEngine`。
5. 清理 route strings、icons、models 與無引用 imports。
6. 確認 `SettingsManager` 舊 SharedPreferences 路線方法無引用後移除。

### Phase 3：資料庫 migration（可延後）

1. 先確認使用者不需要匯出舊路線。
2. 增加明確 Room migration，移除 `saved_routes` table／Entity／DAO／DI binding。
3. 不使用 destructive migration 只為刪除一個非核心功能。
4. 清掉舊 `saved_routes` SharedPreferences key。

## 建議實作步驟

1. 先為現有 fixed/service/RF chain 補最小回歸測試，凍結已驗證行為。
2. 建立純 Kotlin `JoystickMotionEngine`，先用單元測試完成 dt、dead zone、速度曲線、TTL、經度正規化。
3. 在 `SpooferProvider` 增加 atomic command/session API。
4. 把 service 改成單一 motion owner，保持 config 寫入與 heartbeat 約 1 Hz。
5. 讓 ViewModel 只 submit command；刪除固定 `elapsedSec=0.1` 的座標積分。
6. 建立獨立 joystick screen／overlay，處理 pointer cancel、lifecycle 與安全區。
7. 實機確認搖桿後，再依上述三個 phase 清除 route 功能。
8. 最後才更新 `technical_readme.md`，把 route 從 current pipeline 移除。

## 驗證清單

### 純邏輯測試

- 不同 tick 間隔下，10 秒總距離只由 monotonic 時間與速度決定。
- dead zone 中速度必須為 0；超出後曲線連續且不跳變。
- command TTL 到期、pointer cancel、session 更換後速度歸零。
- service 暫停後的 `dt` clamp 不會造成大跳點。
- 經度可安全跨 `±180°`；無 NaN／Infinity。
- RF 開啟時不走出支援的 Web Mercator 緯度範圍。

### Android／UI 測試

- 不選任何路點即可從目前位置開啟搖桿。
- pointer 按下到 UI marker 有反應目標小於 `100ms`；framework fix 依既有 cadence 在約 `1s` 內更新。
- 穩態 10 秒的實際 framework 位移速度與設定值誤差不超過約 `10%`。
- 放開搖桿後最遲下一個 framework sample 速度為 0，且位置不再持續前進。
- 旋轉、返回、鎖屏、切背景、App 被殺後不會 runaway。
- 退出搖桿後仍保持定點 spoofing；按「停止模擬」才恢復真實位置。
- Wi-Fi／Cell／BLE 開關與 `ready`／`block only`／`off` 語義不變。
- 跨 exact-cell 後 map hex、後端 resolver 與 RF payload 同步切換，沒有鄰格借用。
- system_server 仍只有一條 canonical standard-location hook，約 1 Hz，沒有重複 hook／callback timer。
- stop、重新開始、安裝替換與 reboot 後無 stale command／active resurrection。
- `logcat -b crash` 為空，無 ANR、StackOverflow 或 callback exception。

## 完成標準

- 搖桿是獨立入口，不依賴路點或 route state。
- service 根據真實 monotonic `dt` 擁有全部運動積分。
- 速度與 bearing 對普通 `LocationManager` 消費者可見且互相一致。
- 放手、失焦、背景或進程死亡都會可靠停止移動。
- UI 流暢，但 system/runtime 仍維持單一 writer 與既有 1 Hz 時序鏈。
- 自動路線 UI／runtime 分支已移除，或被明確隔離且不影響搖桿。
- 現有全域定位、漂移、RF exact-cell、停止／重啟行為全部回歸通過。

## 下一個任務的範圍界線

下一個開發任務應先做搖桿，不要順手重寫 `LocationHooker`、coverage geometry、Wi-Fi/Cell/BLE hook 或 API 採集。若搖桿測試暴露核心鏈問題，先提供可重現日誌，再決定是否擴大範圍。
