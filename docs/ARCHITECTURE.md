# SmsSense 系統架構筆記

## 1. 三軌單向擷取管線

```mermaid
flowchart LR
    S1["軌道 1: 實體簡訊<br/>SmsReceiver (SMS_RECEIVED)"] --> ENG["ForwardRuleEngine<br/>白名單 / 防死循環 / 5 秒去重 / 輔助 OTP"]
    S2["軌道 2: 白名單通知 & 全畫面通知<br/>NotificationSenseService"] --> ENG
    S3["軌道 3: 前景登入彈窗 (唯讀)<br/>PromptAccessibilityService"] --> ENG
    ENG --> DISP["ForwardDispatcher<br/>Telegram Bot API (HTML)"]
    DISP -- 成功 --> LOG["本地歷史紀錄 (上限 50 筆)"]
    DISP -- 斷網/失敗 --> Q["離線待發佇列 + WorkManager<br/>(恢復連線自動補送)"]
```

## 2. 關鍵設計原則

1. **原文優先**：簡訊合併完整多段 PDU 後原文轉發；僅在命中驗證碼語境時於頂端多加一行 `<code>` 方便單擊複製，杜絕截錯造成困擾。
2. **嚴格白名單與防死循環**：App 通知與登入彈窗偵測一律採白名單制，且引擎層硬編碼排除所有 Telegram 客戶端與 `SmsSense` 自身，避免轉發形成無窮迴圈。
3. **純唯讀無障礙感知**：`PromptAccessibilityService` 僅宣告監聽 `typeWindowStateChanged`，不申請手勢執行權限，守住零遠端點擊的資安底線。
4. **事件驅動省電**：三條擷取軌皆由 Android 系統事件喚醒；僅在發送失敗時交由 `WorkManager` 等待網路恢復後補發，平時零定時輪詢。
