package com.yushyam.smssense

import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.SmsSenseConfig
import com.yushyam.smssense.data.model.SourceChannel
import com.yushyam.smssense.data.repo.SmsSensePrefs
import com.yushyam.smssense.engine.ForwardRuleEngine
import com.yushyam.smssense.engine.OtpExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SmsSense 核心過濾白名單、語境 OTP 提取、防死循環與安全格式化單元測試
 */
class ForwardRuleEngineTest {

    private val baseConfig = SmsSenseConfig(
        enabled = true,
        deviceAlias = "備用機 S22",
        telegramBotToken = "123456:TEST_TOKEN",
        telegramChatId = "987654321",
        smsForwardEnabled = true,
        appendOtpHint = true,
        notificationForwardEnabled = true,
        whitelistedNotifPackages = listOf("com.cathaybk.mymobibank", "com.azure.authenticator"),
        promptForwardEnabled = true,
        whitelistedPromptPackages = listOf("com.google.android.gms", "com.azure.authenticator")
    )

    @Before
    fun setUp() {
        ForwardRuleEngine.resetDedupForTest()
    }

    @Test
    fun otpExtractorPicksCodeAndIgnoresCardDigitsOrAmount() {
        val bankSms = "【國泰世華】您末四碼 8821 刷卡消費 3500 元，動態密碼：649201，請於 5 分鐘內輸入。"
        assertEquals("649201", OtpExtractor.extractHint(bankSms))

        val noContextSms = "您的信用卡尾號 4321 將於 2026 年 11 月扣款 12500 元。"
        assertNull(OtpExtractor.extractHint(noContextSms))
    }

    @Test
    fun smsForwardsFullOriginalTextPlusOptionalOtpHint() {
        val sms = ForwardEvent(
            channel = SourceChannel.SMS,
            packageName = "sms",
            sourceTitle = "0912345678",
            content = "您的驗證碼為 583920，請勿告訴他人 <script>test</script>"
        )
        assertTrue(ForwardRuleEngine.shouldForward(baseConfig, sms))

        val html = ForwardRuleEngine.formatTelegramHtml(baseConfig, sms)
        assertTrue(html.contains("🔑 驗證碼：<code>583920</code>"))
        assertTrue(html.contains("您的驗證碼為 583920"))
        assertTrue(html.contains("&lt;script&gt;test&lt;/script&gt;"))
        assertTrue(html.contains("[備用機 S22]"))
    }

    @Test
    fun notificationsStrictlyFollowWhitelist() {
        val allowed = ForwardEvent(
            channel = SourceChannel.FULLSCREEN_NOTIFICATION,
            packageName = "com.azure.authenticator",
            sourceTitle = "Microsoft Authenticator",
            content = "核准登入要求",
            actionLabels = listOf("核准", "拒絕")
        )
        val blocked = ForwardEvent(
            channel = SourceChannel.NOTIFICATION,
            packageName = "com.shopee.tw",
            sourceTitle = "蝦皮購物",
            content = "限時特賣開跑！"
        )
        assertTrue(ForwardRuleEngine.shouldForward(baseConfig, allowed))
        assertFalse(ForwardRuleEngine.shouldForward(baseConfig, blocked))
    }

    @Test
    fun telegramAndSelfPackagesAreAlwaysBlockedAgainstLoops() {
        val badConfig = baseConfig.copy(
            whitelistedNotifPackages = listOf("org.telegram.messenger", "com.yushyam.smssense")
        )
        val tgEvent = ForwardEvent(SourceChannel.NOTIFICATION, "org.telegram.messenger", "Telegram", "New message")
        val selfEvent = ForwardEvent(SourceChannel.NOTIFICATION, "com.yushyam.smssense", "SmsSense", "Status")
        assertFalse(ForwardRuleEngine.shouldForward(badConfig, tgEvent))
        assertFalse(ForwardRuleEngine.shouldForward(badConfig, selfEvent))
    }

    @Test
    fun dedupPreventsDoubleForwardBetweenSmsAndMessagingAppNotif() {
        val body = "Your login verification code is 774129"
        val smsEvent = ForwardEvent(SourceChannel.SMS, "sms", "+886900000000", body)
        val notifEvent = ForwardEvent(SourceChannel.NOTIFICATION, "com.google.android.apps.messaging", "訊息", body)

        assertFalse(ForwardRuleEngine.isDuplicate(smsEvent, nowMs = 1000L))
        assertTrue(ForwardRuleEngine.isDuplicate(notifEvent, nowMs = 3000L))
        // 超過 5 秒視窗後允許再次發送
        assertFalse(ForwardRuleEngine.isDuplicate(smsEvent, nowMs = 8000L))
    }

    @Test
    fun loginPromptDetectionRecognizesGoogleAndAuthenticatorPrompts() {
        assertTrue(ForwardRuleEngine.looksLikeLoginPrompt("有人正在嘗試登入你的 Google 帳戶 是，是我本人 42"))
        assertTrue(ForwardRuleEngine.looksLikeLoginPrompt("Approve sign-in request? Enter the number shown"))
        assertFalse(ForwardRuleEngine.looksLikeLoginPrompt("系統設定 顯示與亮度 電池"))
    }

    @Test
    fun partialLegacyConfigParsesSafelyWithDefaults() {
        val parsed = SmsSensePrefs.parseConfigJson("""{"telegramChatId":"111222"}""")
        assertTrue(parsed.enabled)
        assertTrue(parsed.smsForwardEnabled)
        assertTrue(parsed.callForwardEnabled)
        assertTrue(parsed.lowBatteryAlertEnabled)
        assertEquals(20, parsed.lowBatteryThreshold)
        assertEquals("111222", parsed.telegramChatId)
        assertEquals(SmsSenseConfig.DEFAULT_PROMPT_PACKAGES, parsed.whitelistedPromptPackages)
    }

    @Test
    fun incomingCallAndAutoModelFormattingWork() {
        val noAliasConfig = baseConfig.copy(deviceAlias = "")
        val callEvent = ForwardEvent(
            channel = SourceChannel.INCOMING_CALL,
            packageName = "telephony",
            sourceTitle = "0988123456",
            content = "備用機收到來電：0988123456"
        )
        assertTrue(ForwardRuleEngine.shouldForward(noAliasConfig, callEvent))
        val html = ForwardRuleEngine.formatTelegramHtml(noAliasConfig, callEvent, deviceModel = "SM-N960F")
        assertTrue(html.contains("[SM-N960F]"))
        assertTrue(html.contains("0988123456"))
        assertFalse(html.contains("🔑 驗證碼："))
    }

    @Test
    fun lowBatteryHysteresisPreventsRepeatedSpam() {
        // 掉到 20% 未充電 -> 觸發一次
        assertEquals(true to true, ForwardRuleEngine.evaluateLowBatteryTrigger(20, false, 20, false))
        // 掉到 19% 已發送過 -> 不重複發
        assertEquals(false to true, ForwardRuleEngine.evaluateLowBatteryTrigger(19, false, 20, true))
        // 充到 25% 未達門檻+10% 且未充電 -> 維持已發送狀態
        assertEquals(false to true, ForwardRuleEngine.evaluateLowBatteryTrigger(25, false, 20, true))
        // 插上充電中 -> 立刻重置旗標
        assertEquals(false to false, ForwardRuleEngine.evaluateLowBatteryTrigger(22, true, 20, true))
        // 充回 30% (門檻+10%) -> 重置旗標
        assertEquals(false to false, ForwardRuleEngine.evaluateLowBatteryTrigger(30, false, 20, true))
    }
}
