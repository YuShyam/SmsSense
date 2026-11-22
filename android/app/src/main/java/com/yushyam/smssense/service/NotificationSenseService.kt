package com.yushyam.smssense.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.SourceChannel
import com.yushyam.smssense.service.ForwardDispatcher

/**
 * 軌道 2：白名單 App 通知與全畫面通知 (fullScreenIntent) 監聽服務
 *
 * 嚴格遵守白名單過濾；若偵測到系統通知帶有 fullScreenIntent 或互動確認按鈕，
 * 會自動標記為「全畫面通知」並附上按鈕文案單向送至 Telegram。
 */
class NotificationSenseService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val notification = sbn.notification ?: return

        // 忽略常駐群組摘要與進度條類背景通知，減少雜訊
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 && notification.fullScreenIntent == null) return

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val normalText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        val bodyParts = listOf(bigText.ifEmpty { normalText }, subText).filter { it.isNotBlank() }
        val content = bodyParts.joinToString("\n")
        if (title.isBlank() && content.isBlank()) return

        val actionLabels = notification.actions
            ?.mapNotNull { it?.title?.toString()?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val appLabel = resolveAppLabel(sbn.packageName)
        val sourceHeader = if (title.isNotBlank() && !title.equals(appLabel, ignoreCase = true)) {
            "$appLabel · $title"
        } else {
            appLabel
        }

        val isFullScreen = notification.fullScreenIntent != null
        ForwardDispatcher.dispatch(
            context = this,
            event = ForwardEvent(
                channel = if (isFullScreen) SourceChannel.FULLSCREEN_NOTIFICATION else SourceChannel.NOTIFICATION,
                packageName = sbn.packageName,
                sourceTitle = sourceHeader,
                content = content.ifBlank { title },
                actionLabels = actionLabels,
                timestampMs = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        )
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
