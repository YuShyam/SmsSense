package com.yushyam.smssense.engine

import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.SmsSenseConfig
import com.yushyam.smssense.data.model.SourceChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轉發規則、防死循環、去重與安全格式化引擎 (Pure Forward Rule & Formatting Engine)
 */
object ForwardRuleEngine {

    private const val DEDUP_WINDOW_MS = 5000L

    // 強制硬編碼排除所有 Telegram 客戶端與自身套件，徹底杜絕轉發無窮死循環
    private val FORBIDDEN_PACKAGES = setOf(
        "com.yushyam.smssense",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "tw.nekomimi.nekogram",
        "xyz.nextalone.nagram",
        "org.thunderdog.challegram"
    )

    // 前景視窗彈出時，用於判別是否屬於「登入確認／雙重驗證彈窗」之關鍵語詞
    private val LOGIN_PROMPT_SIGNALS = listOf(
        "嘗試登入", "正在嘗試登入", "是，是我本人", "是你本人嗎", "核准登入", "同意登入",
        "確認登入", "登入要求", "登入驗證", "授權登入", "雙重驗證", "兩步驟驗證",
        "trying to sign in", "yes, it's me", "approve sign-in", "approve request",
        "verify it's you", "2-step verification", "enter the number", "tap the number"
    )

    private val recentFingerprints = LinkedHashMap<String, Long>()

    /**
     * 判斷單一事件是否允許轉發（嚴格遵守白名單與防循環規則）
     */
    fun shouldForward(config: SmsSenseConfig, event: ForwardEvent): Boolean {
        if (!config.enabled) return false
        if (!config.isTelegramConfigured) return false
        if (event.content.isBlank() && event.sourceTitle.isBlank()) return false

        val pkg = event.packageName.trim().lowercase()
        if (pkg in FORBIDDEN_PACKAGES) return false

        return when (event.channel) {
            SourceChannel.SMS -> config.smsForwardEnabled
            SourceChannel.NOTIFICATION, SourceChannel.FULLSCREEN_NOTIFICATION -> {
                config.notificationForwardEnabled &&
                    config.whitelistedNotifPackages.any { it.equals(pkg, ignoreCase = true) }
            }
            SourceChannel.LOGIN_PROMPT -> {
                config.promptForwardEnabled &&
                    config.whitelistedPromptPackages.any { it.equals(pkg, ignoreCase = true) }
            }
            SourceChannel.INCOMING_CALL -> config.callForwardEnabled
            SourceChannel.LOW_BATTERY -> config.lowBatteryAlertEnabled
        }
    }

    /**
     * 低電量警報遲滯判定（防洗版 Hysteresis）：
     * - 電量 <= 門檻 且未充電 且尚未發送過 ⮕ 觸發警報 (shouldAlert = true, nextSentState = true)
     * - 正在充電 或 電量 >= 門檻 + 10% ⮕ 重置旗標 (shouldAlert = false, nextSentState = false)
     */
    fun evaluateLowBatteryTrigger(
        levelPercent: Int,
        isCharging: Boolean,
        thresholdPercent: Int,
        alreadySent: Boolean
    ): Pair<Boolean, Boolean> {
        if (levelPercent !in 0..100) return false to alreadySent
        if (isCharging || levelPercent >= (thresholdPercent + 10).coerceAtMost(95)) {
            return false to false
        }
        if (levelPercent <= thresholdPercent && !alreadySent) {
            return true to true
        }
        return false to alreadySent
    }

    /**
     * 判斷前景視窗文字是否疑似登入確認／同意彈窗
     */
    fun looksLikeLoginPrompt(visibleText: String, className: String? = null): Boolean {
        val combined = "${visibleText.lowercase()} ${className.orEmpty().lowercase()}"
        if (LOGIN_PROMPT_SIGNALS.any { combined.contains(it.lowercase()) }) return true
        if (combined.contains("authprompt") || combined.contains("loginapproval") || combined.contains("signin")) {
            return true
        }
        return false
    }

    /**
     * 5 秒視窗去重：避免實體簡訊與簡訊 App 通知同時觸發造成 Telegram 重複推送
     */
    @Synchronized
    fun isDuplicate(event: ForwardEvent, nowMs: Long = System.currentTimeMillis()): Boolean {
        // 清理過期指紋
        val iter = recentFingerprints.entries.iterator()
        while (iter.hasNext()) {
            if (nowMs - iter.next().value > DEDUP_WINDOW_MS) {
                iter.remove()
            }
        }

        val normalizedBody = event.content.trim().replace(Regex("\\s+"), " ")
        val key = if (event.channel == SourceChannel.SMS || event.channel == SourceChannel.NOTIFICATION) {
            // 簡訊與通知共享內容指紋，有效消除雙軌重複
            "msg:${normalizedBody.lowercase()}"
        } else {
            "${event.channel.name}:${event.packageName.lowercase()}:${normalizedBody.lowercase()}"
        }

        val prevTs = recentFingerprints[key]
        if (prevTs != null && nowMs - prevTs <= DEDUP_WINDOW_MS) {
            return true
        }
        recentFingerprints[key] = nowMs
        return false
    }

    @Synchronized
    internal fun resetDedupForTest() {
        recentFingerprints.clear()
    }

    /**
     * 組合送往 Telegram Bot API (parse_mode=HTML) 之安全格式化訊息
     * 永遠保留完整原文；僅於啟用輔助提示且語境明確時於頂部加註可單擊複製之 OTP。
     */
    fun formatTelegramHtml(
        config: SmsSenseConfig,
        event: ForwardEvent,
        isDelayedRetry: Boolean = false,
        deviceModel: String = ""
    ): String {
        val sb = StringBuilder()
        val timeStr = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(event.timestampMs))
        val tagText = when {
            config.deviceAlias.isNotBlank() && deviceModel.isNotBlank() &&
                !config.deviceAlias.contains(deviceModel, ignoreCase = true) -> "${config.deviceAlias} ($deviceModel)"
            config.deviceAlias.isNotBlank() -> config.deviceAlias
            deviceModel.isNotBlank() -> deviceModel
            else -> ""
        }
        val aliasPrefix = if (tagText.isNotBlank()) "[${escapeHtml(tagText)}] " else ""

        sb.append("<b>").append(aliasPrefix).append(escapeHtml(event.channel.badge)).append("</b>")
        if (isDelayedRetry) {
            sb.append(" <i>(斷網補發)</i>")
        }
        sb.append("\n")

        if (event.sourceTitle.isNotBlank()) {
            sb.append("來源：<b>").append(escapeHtml(event.sourceTitle)).append("</b>\n")
        }

        if (config.appendOtpHint &&
            event.channel != SourceChannel.LOGIN_PROMPT &&
            event.channel != SourceChannel.INCOMING_CALL &&
            event.channel != SourceChannel.LOW_BATTERY
        ) {
            val otp = OtpExtractor.extractHint(event.content)
            if (!otp.isNullOrBlank()) {
                sb.append("🔑 驗證碼：<code>").append(escapeHtml(otp)).append("</code>\n")
            }
        }

        sb.append("──────────\n")
        sb.append(escapeHtml(event.content.trim().ifBlank { "（系統遮蔽畫面內容，請於原裝置確認）" }))

        if (event.actionLabels.isNotEmpty()) {
            val buttons = event.actionLabels.joinToString(" / ") { "[${escapeHtml(it)}]" }
            sb.append("\n選項按鈕：").append(buttons)
        }

        sb.append("\n<i>時間：").append(timeStr).append("</i>")
        return sb.toString()
    }

    fun escapeHtml(raw: String): String {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
