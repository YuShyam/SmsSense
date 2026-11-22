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

    private val FORBIDDEN_PACKAGES = setOf(
        "com.yushyam.smssense",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "tw.nekomimi.nekogram",
        "xyz.nextalone.nagram",
        "org.thunderdog.challegram"
    )

    private val LOGIN_PROMPT_SIGNALS = listOf(
        "嘗試登入", "正在嘗試登入", "是，是我本人", "是你本人嗎", "核准登入", "同意登入",
        "確認登入", "登入要求", "登入驗證", "授權登入", "雙重驗證", "兩步驟驗證",
        "trying to sign in", "yes, it's me", "approve sign-in", "approve request",
        "verify it's you", "2-step verification", "enter the number", "tap the number"
    )

    private val recentFingerprints = LinkedHashMap<String, Long>()

    fun shouldForward(config: SmsSenseConfig, event: ForwardEvent): Boolean {
        if (!config.enabled) return false
        if (!config.isAnyDestinationConfigured) return false
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
     * 多段電量通知門檻判定（支援複選如 30%、20%、15%）：
     * - 充電中：清空所有已發送門檻紀錄
     * - 電量回升 >= 該門檻 + 10%：解除該門檻已發送紀錄
     * - 未充電且電量 <= 某個尚未發送之門檻：觸發當前跨越之最低門檻，並將所有 >= 當前電量之門檻標記為已發送
     *
     * @return Pair(觸發之門檻百分比 或 null, 更新後之已發送門檻集合)
     */
    fun evaluateMultiBatteryThresholds(
        levelPercent: Int,
        isCharging: Boolean,
        configuredThresholds: List<Int>,
        alreadySentThresholds: Set<Int>
    ): Pair<Int?, Set<Int>> {
        if (levelPercent !in 0..100 || configuredThresholds.isEmpty()) {
            return null to alreadySentThresholds
        }
        if (isCharging) {
            return null to emptySet()
        }

        val cleanedThresholds = configuredThresholds.distinct().sorted()
        // 僅保留尚未回充至 +10% 以上的已觸發門檻
        val activeSent = alreadySentThresholds.filter { t ->
            levelPercent < (t + 10).coerceAtMost(95)
        }.toMutableSet()

        // 找出目前電量已跌破（level <= t）且尚未發送過的門檻
        val newlyReached = cleanedThresholds.filter { t ->
            levelPercent <= t && t !in activeSent
        }

        if (newlyReached.isEmpty()) {
            return null to activeSent
        }

        // 取本次跌破的最低門檻發送一次通知，並把所有已跌破的門檻一併記入已發送集合避免連發
        val triggeredThreshold = newlyReached.minOrNull()
        activeSent.addAll(newlyReached)
        return triggeredThreshold to activeSent
    }

    fun looksLikeLoginPrompt(visibleText: String, className: String? = null): Boolean {
        val combined = "${visibleText.lowercase()} ${className.orEmpty().lowercase()}"
        if (LOGIN_PROMPT_SIGNALS.any { combined.contains(it.lowercase()) }) return true
        if (combined.contains("authprompt") || combined.contains("loginapproval") || combined.contains("signin")) {
            return true
        }
        return false
    }

    @Synchronized
    fun isDuplicate(event: ForwardEvent, nowMs: Long = System.currentTimeMillis()): Boolean {
        val iterator = recentFingerprints.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value > DEDUP_WINDOW_MS) {
                iterator.remove()
            }
        }

        val normalizedBody = event.content.trim().replace(Regex("\\s+"), " ")
        val key = if (event.channel == SourceChannel.SMS || event.channel == SourceChannel.NOTIFICATION) {
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

    fun formatTelegramHtml(
        config: SmsSenseConfig,
        event: ForwardEvent,
        isDelayedRetry: Boolean = false,
        deviceModel: String = ""
    ): String {
        val sb = StringBuilder()
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.timestampMs))
        val tagText = resolveDeviceTag(config, deviceModel)
        val aliasPrefix = if (tagText.isNotBlank()) "[${escapeHtml(tagText)}] " else ""

        sb.append("<b>").append(aliasPrefix).append(escapeHtml(event.channel.badge)).append("</b>")
        if (isDelayedRetry) {
            sb.append(" <i>(斷網補發)</i>")
        }
        sb.append("\n")

        if (event.sourceTitle.isNotBlank()) {
            val sourceLabel = if (event.channel == SourceChannel.INCOMING_CALL) "來電號碼" else "來源"
            sb.append(sourceLabel).append("：<b>").append(escapeHtml(event.sourceTitle)).append("</b>\n")
        }

        if (event.receiverSimInfo.isNotBlank()) {
            sb.append("收受：<b>").append(escapeHtml(event.receiverSimInfo)).append("</b>\n")
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

    fun formatPlainText(
        config: SmsSenseConfig,
        event: ForwardEvent,
        isDelayedRetry: Boolean = false,
        deviceModel: String = ""
    ): String {
        val sb = StringBuilder()
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.timestampMs))
        val tagText = resolveDeviceTag(config, deviceModel)
        val aliasPrefix = if (tagText.isNotBlank()) "[$tagText] " else ""

        sb.append(aliasPrefix).append(event.channel.badge)
        if (isDelayedRetry) {
            sb.append(" (斷網補發)")
        }
        sb.append("\n")

        if (event.sourceTitle.isNotBlank()) {
            val sourceLabel = if (event.channel == SourceChannel.INCOMING_CALL) "來電號碼" else "來源"
            sb.append(sourceLabel).append("：").append(event.sourceTitle).append("\n")
        }

        if (event.receiverSimInfo.isNotBlank()) {
            sb.append("收受：").append(event.receiverSimInfo).append("\n")
        }

        if (config.appendOtpHint &&
            event.channel != SourceChannel.LOGIN_PROMPT &&
            event.channel != SourceChannel.INCOMING_CALL &&
            event.channel != SourceChannel.LOW_BATTERY
        ) {
            val otp = OtpExtractor.extractHint(event.content)
            if (!otp.isNullOrBlank()) {
                sb.append("🔑 驗證碼：").append(otp).append("\n")
            }
        }

        sb.append("──────────\n")
        sb.append(event.content.trim().ifBlank { "（系統遮蔽畫面內容，請於原裝置確認）" })

        if (event.actionLabels.isNotEmpty()) {
            val buttons = event.actionLabels.joinToString(" / ") { "[$it]" }
            sb.append("\n選項按鈕：").append(buttons)
        }

        sb.append("\n時間：").append(timeStr)
        return sb.toString()
    }

    private fun resolveDeviceTag(config: SmsSenseConfig, deviceModel: String): String {
        return when {
            config.deviceAlias.isNotBlank() && deviceModel.isNotBlank() &&
                !config.deviceAlias.contains(deviceModel, ignoreCase = true) -> "${config.deviceAlias} ($deviceModel)"
            config.deviceAlias.isNotBlank() -> config.deviceAlias
            deviceModel.isNotBlank() -> deviceModel
            else -> ""
        }
    }

    fun escapeHtml(raw: String): String {
        return buildString(raw.length + 16) {
            for (ch in raw) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(ch)
                }
            }
        }
    }
}
