package com.yushyam.smssense.data.model

/**
 * 轉發來源軌道列舉 (Capture Source Channel)
 */
enum class SourceChannel(val labelZh: String, val badge: String) {
    SMS("實體簡訊", "📩 簡訊"),
    NOTIFICATION("白名單通知", "🔔 通知"),
    FULLSCREEN_NOTIFICATION("全畫面通知", "⚡ 全畫面通知"),
    LOGIN_PROMPT("登入確認彈窗", "🔐 登入彈窗"),
    INCOMING_CALL("來電通知", "📞 來電"),
    LOW_BATTERY("低電量警報", "🔋 低電量")
}

/**
 * 使用者配置與嚴格白名單規則 (Configuration & Whitelists)
 */
data class SmsSenseConfig(
    val enabled: Boolean = true,
    val deviceAlias: String = "",
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    // 軌道 1：簡訊預設「全文全部轉發」，絕不因過濾漏接
    val smsForwardEnabled: Boolean = true,
    // 輔助功能：僅在頂部額外附加一行可點擊複製的驗證碼提示，永遠保留完整原文
    val appendOtpHint: Boolean = true,
    // 軌道 2：App 通知（嚴格白名單制）
    val notificationForwardEnabled: Boolean = true,
    val whitelistedNotifPackages: List<String> = emptyList(),
    // 軌道 3：前景全畫面登入確認彈窗（嚴格白名單制 + 唯讀感知）
    val promptForwardEnabled: Boolean = true,
    val whitelistedPromptPackages: List<String> = DEFAULT_PROMPT_PACKAGES,
    // 軌道 4：來電通知轉發（接班備用機來電提醒）
    val callForwardEnabled: Boolean = true,
    // 軌道 5：備用機低電量警報（預設低於 20% 提醒，充回門檻 + 10% 重置防洗版）
    val lowBatteryAlertEnabled: Boolean = true,
    val lowBatteryThreshold: Int = 20
) {
    val isTelegramConfigured: Boolean
        get() = telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()

    companion object {
        // 常見登入確認彈窗預設白名單（使用者可隨時增刪）
        val DEFAULT_PROMPT_PACKAGES = listOf(
            "com.google.android.gms",
            "com.azure.authenticator",
            "com.google.android.apps.authenticator2"
        )
    }
}

/**
 * 單筆待評估之攔截事件 (Captured Forward Event)
 */
data class ForwardEvent(
    val channel: SourceChannel,
    val packageName: String,
    val sourceTitle: String,
    val content: String,
    val actionLabels: List<String> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * 離線待補發項目 (Offline Retry Queue Item)
 */
data class PendingForwardItem(
    val id: String,
    val channelLabel: String,
    val sourceTitle: String,
    val formattedHtml: String,
    val createdAtMs: Long,
    val attempts: Int = 0
)

/**
 * 歷史轉發紀錄 (Forward Log Item)
 */
data class ForwardLogItem(
    val timestampMs: Long,
    val channelLabel: String,
    val sourceTitle: String,
    val preview: String,
    val delivered: Boolean,
    val statusDetail: String
)

/**
 * 手機已安裝應用資訊（供白名單搜尋勾選使用）
 */
data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)
