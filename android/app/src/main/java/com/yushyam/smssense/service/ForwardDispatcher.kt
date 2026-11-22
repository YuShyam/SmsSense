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
import com.yushyam.smssense.data.model.SmsSenseConfig
import com.yushyam.smssense.data.model.TargetDestination
import com.yushyam.smssense.data.repo.SmsSensePrefs
import com.yushyam.smssense.engine.ForwardRuleEngine
import com.yushyam.smssense.engine.LineSender
import com.yushyam.smssense.engine.TelegramSender
import com.yushyam.smssense.engine.WebhookSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 核心多通道轉發協調與離線佇列派發器 (Multi-Channel Forward Dispatcher & Offline Retry Coordinator)
 */
object ForwardDispatcher {

    const val ACTION_LOGS_UPDATED = "com.yushyam.smssense.ACTION_LOGS_UPDATED"
    private const val RETRY_WORK_NAME = "smssense_offline_retry_work"
    private const val BATTERY_WORK_NAME = "smssense_battery_monitor_work"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dispatch(context: Context, event: ForwardEvent) {
        val appContext = context.applicationContext
        val prefs = SmsSensePrefs(appContext)
        val config = prefs.loadConfig()

        if (!ForwardRuleEngine.shouldForward(config, event)) return
        if (ForwardRuleEngine.isDuplicate(event, event.timestampMs)) return

        val model = Build.MODEL.orEmpty()
        val html = ForwardRuleEngine.formatTelegramHtml(
            config = config,
            event = event,
            isDelayedRetry = false,
            deviceModel = model
        )
        val plain = ForwardRuleEngine.formatPlainText(
            config = config,
            event = event,
            isDelayedRetry = false,
            deviceModel = model
        )

        scope.launch {
            val activeTargets = mutableListOf<TargetDestination>()
            if (config.isTelegramConfigured) activeTargets.add(TargetDestination.TELEGRAM)
            if (config.isLineConfigured) activeTargets.add(TargetDestination.LINE)
            if (config.isWebhookConfigured) activeTargets.add(TargetDestination.WEBHOOK)

            val results = sendToTargets(config, activeTargets, html, plain)
            val failedTargets = results.filter { !it.second }.map { it.first.name }
            val allSucceeded = failedTargets.isEmpty()
            val summaryDetail = results.joinToString(" | ") { "${it.first.label}: ${it.third}" }

            val logTitle = buildString {
                append(event.sourceTitle.ifBlank { event.packageName })
                if (event.receiverSimInfo.isNotBlank()) {
                    append(" ➔ ").append(event.receiverSimInfo)
                }
            }

            if (allSucceeded) {
                prefs.appendLog(
                    ForwardLogItem(
                        timestampMs = event.timestampMs,
                        channelLabel = event.channel.labelZh,
                        sourceTitle = logTitle,
                        preview = event.content.trim().take(120),
                        delivered = true,
                        statusDetail = summaryDetail
                    )
                )
                flushPendingQueueBlocking(appContext)
            } else {
                if (config.offlineQueueEnabled) {
                    val delayedHtml = ForwardRuleEngine.formatTelegramHtml(
                        config = config,
                        event = event,
                        isDelayedRetry = true,
                        deviceModel = model
                    )
                    val delayedPlain = ForwardRuleEngine.formatPlainText(
                        config = config,
                        event = event,
                        isDelayedRetry = true,
                        deviceModel = model
                    )
                    prefs.enqueuePending(
                        PendingForwardItem(
                            id = UUID.randomUUID().toString(),
                            channelLabel = event.channel.labelZh,
                            sourceTitle = logTitle,
                            formattedHtml = delayedHtml,
                            formattedPlainText = delayedPlain,
                            targetDestinations = failedTargets,
                            createdAtMs = event.timestampMs,
                            attempts = 1
                        )
                    )
                    scheduleRetryWorker(appContext)
                }

                val statusMsg = if (config.offlineQueueEnabled) {
                    "已排入離線補發（$summaryDetail）"
                } else {
                    "發送失敗，離線佇列已關閉（$summaryDetail）"
                }

                prefs.appendLog(
                    ForwardLogItem(
                        timestampMs = event.timestampMs,
                        channelLabel = event.channel.labelZh,
                        sourceTitle = logTitle,
                        preview = event.content.trim().take(120),
                        delivered = false,
                        statusDetail = statusMsg
                    )
                )
            }
            notifyUi(appContext)
        }
    }

    fun sendToTargets(
        config: SmsSenseConfig,
        targets: List<TargetDestination>,
        htmlText: String,
        plainText: String
    ): List<Triple<TargetDestination, Boolean, String>> {
        val results = mutableListOf<Triple<TargetDestination, Boolean, String>>()
        for (target in targets) {
            when (target) {
                TargetDestination.TELEGRAM -> {
                    if (config.isTelegramConfigured) {
                        val (ok, msg) = TelegramSender.sendHtmlMessage(
                            botToken = config.telegramBotToken,
                            chatId = config.telegramChatId,
                            htmlText = htmlText
                        )
                        results.add(Triple(TargetDestination.TELEGRAM, ok, msg))
                    }
                }
                TargetDestination.LINE -> {
                    if (config.isLineConfigured) {
                        val (ok, msg) = LineSender.sendPushMessage(
                            channelAccessToken = config.lineChannelAccessToken,
                            targetId = config.lineTargetId,
                            plainText = plainText
                        )
                        results.add(Triple(TargetDestination.LINE, ok, msg))
                    }
                }
                TargetDestination.WEBHOOK -> {
                    if (config.isWebhookConfigured) {
                        val (ok, msg) = WebhookSender.sendWebhook(
                            webhookUrl = config.webhookUrl,
                            secretHeader = config.webhookSecretHeader,
                            plainText = plainText,
                            htmlText = htmlText
                        )
                        results.add(Triple(TargetDestination.WEBHOOK, ok, msg))
                    }
                }
            }
        }
        return results
    }

    fun flushPendingQueueBlocking(context: Context): Pair<Int, Int> {
        val appContext = context.applicationContext
        val prefs = SmsSensePrefs(appContext)
        val config = prefs.loadConfig()
        if (!config.enabled || !config.isAnyDestinationConfigured) {
            return 0 to prefs.loadPendingQueue().size
        }

        val queue = prefs.loadPendingQueue()
        if (queue.isEmpty()) return 0 to 0

        val remaining = mutableListOf<PendingForwardItem>()
        var sentCount = 0

        for (item in queue) {
            val targets = item.targetDestinations.mapNotNull { name ->
                TargetDestination.entries.find { it.name == name }
            }.ifEmpty { listOf(TargetDestination.TELEGRAM) }

            val plain = item.formattedPlainText.ifBlank {
                item.formattedHtml.replace(Regex("<[^>]+>"), "")
            }
            val results = sendToTargets(config, targets, item.formattedHtml, plain)
            val stillFailed = results.filter { !it.second }.map { it.first.name }
            val detail = results.joinToString(" | ") { "${it.first.label}: ${it.third}" }

            if (stillFailed.isEmpty()) {
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
            } else if (item.attempts < 8) {
                remaining.add(
                    item.copy(
                        targetDestinations = stillFailed,
                        attempts = item.attempts + 1
                    )
                )
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
