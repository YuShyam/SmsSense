# SmsSense

一個將 Android 手機上的簡訊、來電通知、電量通知、白名單 App 通知，以及全畫面登入確認彈窗單向轉發到 **Telegram / LINE Messaging API / Webhook** 的輕量工具。

## 主要功能

- **簡訊轉發**：完整合併長簡訊並以原文轉發，自動讀取系統雙卡 SIM 卡槽與電信商資訊（若未設定則顯示 `SIM1` / `SIM2`），並支援驗證碼置頂複製。
- **來電通知**：來電響鈴時即時推播來電號碼、收受 SIM 卡槽與發生時間。
- **多方推播通道**：支援 **Telegram Bot**、**LINE Messaging API** 與 **Webhook（相容 Discord / Slack / n8n / 自建 API）**，可單選或多軌同時推播。
- **斷網補發**：無網路或發送失敗時，自動將原始發生時間與收受卡槽存入本機佇列，待網路恢復後由 WorkManager 自動補送並標註 `(斷網補發)`。
- **電量通知**：支援複選多個電量百分比門檻（如 30%、20%、15%），電量依序跌破時發送提醒，插上充電或充回門檻 + 10% 自動重置。
- **應用通知與登入彈窗白名單**：只監聽主動勾選的 App，針對直接跳出前景的登入確認視窗（如 Google 提示、Microsoft Authenticator）發送提醒。

## 權限說明

所有資料只會直接送到你自己設定的推播通道，不經過任何第三方中繼伺服器。

- **簡訊權限（`RECEIVE_SMS`）**：讀取實體簡訊。
- **電話與通話紀錄權限（`READ_PHONE_STATE`、`READ_CALL_LOG`、`READ_PHONE_NUMBERS`）**：讀取來電號碼與系統雙卡 SIM1 / SIM2 卡槽資訊。
- **通知存取權限（Notification Access）**：讀取白名單 App 的通知。
- **無障礙服務（視窗偵測，選配）**：僅用於偵測白名單 App 跳出前景登入彈窗時發出提醒，不具備點擊或輸入操作權限。

## 專案目錄

- `android/`：Android 原生專案（Kotlin / Jetpack Compose）
- `docs/ARCHITECTURE.md`：架構筆記
- `.github/workflows/build-apk.yml`：GitHub Actions 單元測試與 APK 建置

## 授權

[MIT License](LICENSE)
