# 更新記錄

本文件只保留對使用者有意義的版本變更。內部試驗、重複提交與已被取代的實作不列入發布說明。

## [1.4.3] - 2026-07-19

### 穩定性與功耗

- 未開始模擬、定位總開關關閉或運行期狀態失效時，定位與 RF hook 會恢復系統原始行為。
- 活躍位置改用精簡的單一 runtime frame 更新，降低重複讀檔、Root shell 與系統屬性處理開銷。
- 強化啟動、停止、服務銷毀、APK 替換與重開機的 generation 排序，避免舊任務重新啟用模擬。
- 熱路徑日誌採固定鍵與限頻，減少 logcat 刷屏。

### 定位與 GNSS

- 修正 GPS provider 收到 non-monotonic location 的問題，維持 wall time 與 elapsed realtime 一致遞增。
- 允許在系統定位總開關關閉時預先啟動；此時暫停輸出，開啟定位後自動恢復。
- 標準位置統一走 Android framework 的單一 WGS-84 管線，保留原生權限、AppOps、間隔與距離過濾。
- 改善 GNSS 衛星狀態、used-in-fix 與 NMEA 補充資料的一致性。

### Wi-Fi 與基站

- 模擬未啟用時不再改寫 Wi-Fi、基站或通用連線狀態。
- 移除 Bluetooth 掃描與 hook；舊資料只保留資料庫和匯入／匯出相容性。
- 修正多 SIM／多基站資料被截斷及 LTE RSRP、RSRQ、SINR 正規化問題。
- 沒有可信 Wi-Fi／基站 payload 時使用明確的 `block only` 行為，不生成座標推導的 AP 或基地台。

### 發布

- 版本：`1.4.3`（`versionCode 14300`）。
- GitHub Actions 同時產出已簽名 APK、AAB 與 SHA-256 校驗檔。

### 安裝注意

- 更新含 hook 程式碼的版本後，需要重新啟動目標進程或裝置，已注入的 LSPosed/libxposed 程式碼才會更新。
