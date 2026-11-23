package com.yushyam.smssense.data.model

/**
 * 轉發來源列舉 (Capture Source Channel)
 */
enum class SourceChannel(val labelZh: String, val badge: String) {
    SMS("簡訊轉發", "📩 簡訊"),
    NOTIFICATION("應用通知", "🔔 通知"),
    FULLSCREEN_NOTIFICATION("全畫面通知", "⚡ 全畫面通知"),
    LOGIN_PROMPT("登入彈窗", "🔐 登入彈窗"),
    INCOMING_CALL("來電通知", "📞 來電"),
    LOW_BATTERY("電量通知", "🔋 電量通知")
}

/**
 * 推播目標通道列舉 (Forward Target Destination)
 */
enum class TargetDestination(val label: String) {
    TELEGRAM("Telegram"),
    LINE("LINE"),
    WEBHOOK("Webhook")
}

/**
 * 外觀深淺色主題模式 (App Theme Mode)
 */
enum class ThemeMode(val settingValue: String, val labelZh: String) {
    SYSTEM("system", "跟隨系統"),
    LIGHT("light", "淺色"),
    DARK("dark", "深色");

    fun next(): ThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }

    companion object {
        fun fromSetting(raw: String?): ThemeMode = when (raw?.lowercase()) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> SYSTEM
        }
    }
}

/**
 * 使用者設定與白名單規則 (Configuration & Whitelists)
 */
data class SmsSenseConfig(
    val enabled: Boolean = true,
    val deviceAlias: String = "",
    val offlineQueueEnabled: Boolean = true,
    val themeMode: String = ThemeMode.SYSTEM.settingValue,
    // Telegram Bot
    val telegramEnabled: Boolean = true,
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    // LINE Messaging API
    val lineEnabled: Boolean = false,
    val lineChannelAccessToken: String = "",
    val lineTargetId: String = "",
    // 通用 Webhook
    val webhookEnabled: Boolean = false,
    val webhookUrl: String = "",
    val webhookSecretHeader: String = "",
    // 簡訊轉發
    val smsForwardEnabled: Boolean = true,
    val appendOtpHint: Boolean = true,
    // 應用通知（白名單制）
    val notificationForwardEnabled: Boolean = true,
    val whitelistedNotifPackages: List<String> = emptyList(),
    // 登入彈窗（白名單制）
    val promptForwardEnabled: Boolean = true,
    val whitelistedPromptPackages: List<String> = DEFAULT_PROMPT_PACKAGES,
    // 來電通知
    val callForwardEnabled: Boolean = true,
    // 電量通知（支援複選多個百分比門檻，例如同時勾選 30%、20%、15%）
    val lowBatteryAlertEnabled: Boolean = true,
    val lowBatteryThresholds: List<Int> = DEFAULT_BATTERY_THRESHOLDS
) {
    val isTelegramConfigured: Boolean
        get() = telegramEnabled && telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()

    val isLineConfigured: Boolean
        get() = lineEnabled && lineChannelAccessToken.isNotBlank() && lineTargetId.isNotBlank()

    val isWebhookConfigured: Boolean
        get() = webhookEnabled && (webhookUrl.startsWith("https://") || webhookUrl.startsWith("http://"))

    val isAnyDestinationConfigured: Boolean
        get() = isTelegramConfigured || isLineConfigured || isWebhookConfigured

    fun activeDestinationLabels(): List<String> {
        val list = mutableListOf<String>()
        if (isTelegramConfigured) list.add("Telegram")
        if (isLineConfigured) list.add("LINE")
        if (isWebhookConfigured) list.add("Webhook")
        return list
    }

    companion object {
        val DEFAULT_BATTERY_THRESHOLDS = listOf(20)
        val AVAILABLE_BATTERY_THRESHOLDS = listOf(15, 20, 25, 30)

        val DEFAULT_PROMPT_PACKAGES = listOf(
            "com.google.android.gms",
            "com.azure.authenticator",
            "com.google.android.apps.authenticator2"
        )
    }
}

/**
 * 單筆攔截事件 (Captured Forward Event)
 */
data class ForwardEvent(
    val channel: SourceChannel,
    val packageName: String,
    val sourceTitle: String,
    val content: String,
    val receiverSimInfo: String = "",
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
    val formattedPlainText: String = "",
    val targetDestinations: List<String> = listOf(TargetDestination.TELEGRAM.name),
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
 * 已安裝應用資訊
 */
data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)
