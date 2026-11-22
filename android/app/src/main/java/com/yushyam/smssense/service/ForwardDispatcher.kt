package com.yushyam.smssense.service

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.ForwardLogItem
import com.yushyam.smssense.data.model.PendingForwardItem
import com.yushyam.smssense.data.repo.SmsSensePrefs
import com.yushyam.smssense.engine.ForwardRuleEngine
import com.yushyam.smssense.engine.TelegramSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 統一事件轉發調度與離線補發管理器 (Unified Forward Dispatcher)
 */
object ForwardDispatcher {

    const val ACTION_LOGS_UPDATED = "com.yushyam.smssense.ACTION_LOGS_UPDATED"
    private const val RETRY_WORK_NAME = "smssense_retry_queue"
    private const val BATTERY_WORK_NAME = "smssense_battery_guard"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dispatch(context: Context, event: ForwardEvent) {
        val appContext = context.applicationContext
        val prefs = SmsSensePrefs(appContext)
        val config = prefs.loadConfig()

        if (!ForwardRuleEngine.shouldForward(config, event)) return
        if (ForwardRuleEngine.isDuplicate(event)) return

        val model = Build.MODEL.orEmpty()
        val html = ForwardRuleEngine.formatTelegramHtml(
            config = config,
            event = event,
            isDelayedRetry = false,
            deviceModel = model
        )

        scope.launch {
            val (ok, detail) = TelegramSender.sendHtmlMessage(
                botToken = config.telegramBotToken,
                chatId = config.telegramChatId,
                htmlText = html
            )

            if (ok) {
                prefs.appendLog(
                    ForwardLogItem(
                        timestampMs = event.timestampMs,
                        channelLabel = event.channel.labelZh,
                        sourceTitle = event.sourceTitle.ifBlank { event.packageName },
                        preview = event.content.trim().take(120),
                        delivered = true,
                        statusDetail = detail
                    )
                )
                // 寄送成功代表目前網路正常，順便消化離線佇列
                flushPendingQueueBlocking(appContext)
            } else {
                // 斷網或暫時失敗：加入離線重試佇列，並排程待網路恢復後自動補發
                val delayedHtml = ForwardRuleEngine.formatTelegramHtml(
                    config = config,
                    event = event,
                    isDelayedRetry = true,
                    deviceModel = model
                )
                prefs.enqueuePending(
                    PendingForwardItem(
                        id = UUID.randomUUID().toString(),
                        channelLabel = event.channel.labelZh,
                        sourceTitle = event.sourceTitle.ifBlank { event.packageName },
                        formattedHtml = delayedHtml,
                        createdAtMs = event.timestampMs,
                        attempts = 1
                    )
                )
                prefs.appendLog(
                    ForwardLogItem(
                        timestampMs = event.timestampMs,
                        channelLabel = event.channel.labelZh,
                        sourceTitle = event.sourceTitle.ifBlank { event.packageName },
                        preview = event.content.trim().take(120),
                        delivered = false,
                        statusDetail = "已排入離線補發（$detail）"
                    )
                )
                scheduleRetryWorker(appContext)
            }
            notifyUi(appContext)
        }
    }

    fun flushPendingQueueBlocking(context: Context): Pair<Int, Int> {
        val appContext = context.applicationContext
        val prefs = SmsSensePrefs(appContext)
        val config = prefs.loadConfig()
        val queue = prefs.loadPendingQueue()
        if (queue.isEmpty() || !config.isTelegramConfigured) return 0 to queue.size

        var sentCount = 0
        val remaining = mutableListOf<PendingForwardItem>()

        for (item in queue) {
            val (ok, detail) = TelegramSender.sendHtmlMessage(
                botToken = config.telegramBotToken,
                chatId = config.telegramChatId,
                htmlText = item.formattedHtml
            )
            if (ok) {
                sentCount++
                prefs.appendLog(
                    ForwardLogItem(
                        timestampMs = System.currentTimeMillis(),
                        channelLabel = "${item.channelLabel} (補發)",
                        sourceTitle = item.sourceTitle,
                        preview = "原始時間訊息已成功補送",
                        delivered = true,
                        statusDetail = detail
                    )
                )
            } else if (item.attempts < 5) {
                remaining.add(item.copy(attempts = item.attempts + 1))
            }
        }

        prefs.savePendingQueue(remaining)
        notifyUi(appContext)
        return sentCount to remaining.size
    }

    fun scheduleRetryWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RetryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(RETRY_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun syncBatteryMonitor(context: Context) {
        val appContext = context.applicationContext
        val config = SmsSensePrefs(appContext).loadConfig()
        val wm = WorkManager.getInstance(appContext)
        if (config.enabled && config.lowBatteryAlertEnabled) {
            val req = PeriodicWorkRequestBuilder<BatteryCheckWorker>(30, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(
                BATTERY_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        } else {
            wm.cancelUniqueWork(BATTERY_WORK_NAME)
        }
    }

    fun notifyUi(context: Context) {
        context.applicationContext.sendBroadcast(
            Intent(ACTION_LOGS_UPDATED).setPackage(context.packageName)
        )
    }
}
