package com.yushyam.smssense

import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.SmsSenseConfig
import com.yushyam.smssense.data.model.SourceChannel
import com.yushyam.smssense.data.repo.SmsSensePrefs
import com.yushyam.smssense.engine.ForwardRuleEngine
import com.yushyam.smssense.engine.OtpExtractor
import com.yushyam.smssense.engine.SimSlotResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SmsSense 核心過濾白名單、系統雙卡格式、多方推播通道、複選電量通知門檻與安全格式化單元測試
 */
class ForwardRuleEngineTest {

    private val baseConfig = SmsSenseConfig(
        enabled = true,
        deviceAlias = "TestPhone",
        telegramEnabled = true,
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
    fun smsForwardsFullOriginalTextPlusSystemDualSimSlotAndOtpHint() {
        val fullSim = SimSlotResolver.formatSimSummary(
            slotIndex = 0,
            carrierName = "中華電信",
            phoneNumber = "0900000000",
            subId = 1
        )
        assertEquals("SIM1_中華電信_0900000000 (SubId: 1)", fullSim)

        val slotOnlySim = SimSlotResolver.formatSimSummary(
            slotIndex = 1,
            carrierName = "",
            phoneNumber = "",
            subId = -1
        )
        assertEquals("SIM2", slotOnlySim)

        val sms = ForwardEvent(
            channel = SourceChannel.SMS,
            packageName = "sms",
            sourceTitle = "0900111222",
            receiverSimInfo = slotOnlySim,
            content = "您的驗證碼為 583920，請勿告訴他人 <script>test</script>"
        )
        assertTrue(ForwardRuleEngine.shouldForward(baseConfig, sms))

        val html = ForwardRuleEngine.formatTelegramHtml(baseConfig, sms)
        assertTrue(html.contains("🔑 驗證碼：<code>583920</code>"))
        assertTrue(html.contains("來源：<b>0900111222</b>"))
        assertTrue(html.contains("收受：<b>SIM2</b>"))
        assertTrue(html.contains("您的驗證碼為 583920"))
        assertTrue(html.contains("&lt;script&gt;test&lt;/script&gt;"))
        assertTrue(html.contains("[TestPhone]"))

        val plain = ForwardRuleEngine.formatPlainText(baseConfig, sms)
        assertTrue(plain.contains("來源：0900111222"))
        assertTrue(plain.contains("收受：SIM2"))
        assertTrue(plain.contains("🔑 驗證碼：583920"))
    }

    @Test
    fun multiDestinationAllowsLineOrWebhookWithoutTelegram() {
        val webhookOnlyConfig = SmsSenseConfig(
            enabled = true,
            telegramEnabled = false,
            webhookEnabled = true,
            webhookUrl = "https://discord.com/api/webhooks/123/abc"
        )
        val sms = ForwardEvent(SourceChannel.SMS, "sms", "12345", "系統通知")
        assertTrue(webhookOnlyConfig.isWebhookConfigured)
        assertTrue(webhookOnlyConfig.isAnyDestinationConfigured)
        assertTrue(ForwardRuleEngine.shouldForward(webhookOnlyConfig, sms))

        val lineOnlyConfig = SmsSenseConfig(
            enabled = true,
            telegramEnabled = false,
            lineEnabled = true,
            lineChannelAccessToken = "LINE_CHANNEL_ACCESS_TOKEN_1234567890",
            lineTargetId = "U1234567890abcdef"
        )
        assertTrue(lineOnlyConfig.isLineConfigured)
        assertTrue(ForwardRuleEngine.shouldForward(lineOnlyConfig, sms))
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
        val parsed = SmsSensePrefs.parseConfigJson("""{"telegramChatId":"111222","lowBatteryThreshold":25}""")
        assertTrue(parsed.enabled)
        assertTrue(parsed.telegramEnabled)
        assertTrue(parsed.offlineQueueEnabled)
        assertFalse(parsed.lineEnabled)
        assertFalse(parsed.webhookEnabled)
        assertTrue(parsed.smsForwardEnabled)
        assertTrue(parsed.callForwardEnabled)
        assertTrue(parsed.lowBatteryAlertEnabled)
        assertEquals(listOf(25), parsed.lowBatteryThresholds)
        assertEquals("111222", parsed.telegramChatId)
        assertEquals(SmsSenseConfig.DEFAULT_PROMPT_PACKAGES, parsed.whitelistedPromptPackages)
    }

    @Test
    fun incomingCallAndAutoModelFormattingWork() {
        val noAliasConfig = baseConfig.copy(deviceAlias = "")
        val callEvent = ForwardEvent(
            channel = SourceChannel.INCOMING_CALL,
            packageName = "telephony",
            sourceTitle = "0900123456",
            receiverSimInfo = "SIM1",
            content = "收到來電：0900123456"
        )
        assertTrue(ForwardRuleEngine.shouldForward(noAliasConfig, callEvent))
        val html = ForwardRuleEngine.formatTelegramHtml(noAliasConfig, callEvent, deviceModel = "PhoneModel")
        assertTrue(html.contains("[PhoneModel]"))
        assertTrue(html.contains("來電號碼：<b>0900123456</b>"))
        assertTrue(html.contains("收受：<b>SIM1</b>"))
        assertFalse(html.contains("🔑 驗證碼："))
    }

    @Test
    fun multiBatteryThresholdsTriggerSequentiallyAndResetOnCharge() {
        val thresholds = listOf(15, 20, 30)
        // 跌到 29% -> 觸發 30% 門檻
        val (trig1, sent1) = ForwardRuleEngine.evaluateMultiBatteryThresholds(29, false, thresholds, emptySet())
        assertEquals(30, trig1)
        assertEquals(setOf(30), sent1)

        // 跌到 25% -> 30% 已觸發過、尚未達 20% -> 不重複觸發
        val (trig2, sent2) = ForwardRuleEngine.evaluateMultiBatteryThresholds(25, false, thresholds, sent1)
        assertNull(trig2)
        assertEquals(setOf(30), sent2)

        // 跌到 20% -> 觸發 20% 門檻
        val (trig3, sent3) = ForwardRuleEngine.evaluateMultiBatteryThresholds(20, false, thresholds, sent2)
        assertEquals(20, trig3)
        assertEquals(setOf(30, 20), sent3)

        // 跌到 15% -> 觸發 15% 門檻
        val (trig4, sent4) = ForwardRuleEngine.evaluateMultiBatteryThresholds(15, false, thresholds, sent3)
        assertEquals(15, trig4)
        assertEquals(setOf(30, 20, 15), sent4)

        // 插上充電 -> 全部重置
        val (trig5, sent5) = ForwardRuleEngine.evaluateMultiBatteryThresholds(16, true, thresholds, sent4)
        assertNull(trig5)
        assertTrue(sent5.isEmpty())
    }
}
