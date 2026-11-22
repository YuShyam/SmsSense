package com.yushyam.smssense.service

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.SourceChannel
import com.yushyam.smssense.data.repo.SmsSensePrefs
import com.yushyam.smssense.engine.ForwardRuleEngine

/**
 * 軌道 3：嚴格唯讀前景登入確認彈窗感知服務 (Read-Only Login Prompt Detector)
 *
 * 專門補齊 NotificationListenerService 無法捕捉之「不經通知欄、直接彈前景」的確認視窗
 * （如 Google 登入提示、Microsoft Authenticator、銀行 App 驗證彈窗）。
 *
 * 安全紅線：本服務僅執行唯讀視窗文字蒐集與單向 Telegram 提醒，絕不執行任何點擊或手勢。
 */
class PromptAccessibilityService : AccessibilityService() {

    private val maxCollectedNodes = 40
    private val maxTextLength = 600

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) return

        // 前置快速過濾：僅處理白名單內之套件，避免在日常滑動一般 App 時耗電
        val prefs = SmsSensePrefs(this)
        val config = prefs.loadConfig()
        if (!config.enabled || !config.promptForwardEnabled) return
        val inWhitelist = config.whitelistedPromptPackages.any { it.equals(pkg, ignoreCase = true) }
        if (!inWhitelist) return

        val className = event.className?.toString()
        val collected = LinkedHashSet<String>()

        event.text.forEach { cs ->
            val s = cs?.toString()?.trim().orEmpty()
            if (s.isNotEmpty()) collected.add(s)
        }

        // 嘗試唯讀收集當前視窗可見文字（Android 14+ 若標記為高敏畫面可能回傳空，屬系統正常保護）
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (root != null) {
            collectVisibleText(root, collected, depth = 0)
        }

        val combinedText = collected.joinToString("\n").take(maxTextLength)
        val isLikelyPrompt = ForwardRuleEngine.looksLikeLoginPrompt(combinedText, className)

        // 若系統遮蔽高敏畫面導致無文字，但套件為已知驗證器/GMS 登入 Activity，仍發出視窗彈出提醒
        val isKnownAuthActivity = className.orEmpty().lowercase().let { cls ->
            cls.contains("auth") || cls.contains("prompt") || cls.contains("signin") || cls.contains("verify")
        }
        if (!isLikelyPrompt && !isKnownAuthActivity) return

        val appLabel = resolveAppLabel(pkg)
        val finalBody = if (combinedText.isNotBlank()) {
            combinedText
        } else {
            "偵測到 $appLabel 彈出全畫面驗證或確認視窗（系統已啟用隱私遮蔽，請回到原裝置確認）。"
        }

        ForwardDispatcher.dispatch(
            context = this,
            event = ForwardEvent(
                channel = SourceChannel.LOGIN_PROMPT,
                packageName = pkg,
                sourceTitle = appLabel,
                content = finalBody,
                timestampMs = System.currentTimeMillis()
            )
        )
    }

    override fun onInterrupt() {
        // 唯讀監聽無狀態需釋放
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo?,
        out: MutableSet<String>,
        depth: Int
    ) {
        if (node == null || depth > 8 || out.size >= maxCollectedNodes) return
        val txt = node.text?.toString()?.trim().orEmpty()
        if (txt.isNotEmpty() && txt.length <= 200) {
            out.add(txt)
        }
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc.isNotEmpty() && desc.length <= 120 && txt.isEmpty()) {
            out.add(desc)
        }
        for (i in 0 until node.childCount) {
            if (out.size >= maxCollectedNodes) break
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            if (child != null) {
                collectVisibleText(child, out, depth + 1)
            }
        }
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: Exception) {
            packageName
        }
    }
}
