package com.yushyam.smssense.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.SourceChannel
import com.yushyam.smssense.data.repo.SmsSensePrefs
import com.yushyam.smssense.engine.ForwardRuleEngine
import com.yushyam.smssense.service.ForwardDispatcher

/**
 * 系統電量通知事件與開機排程恢復接收器 (Battery Notification & Boot Receiver)
 */
class BatteryAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                ForwardDispatcher.syncBatteryMonitor(context)
            }
            Intent.ACTION_BATTERY_OKAY -> {
                SmsSensePrefs(context).saveSentBatteryThresholds(emptySet())
            }
            Intent.ACTION_BATTERY_LOW -> {
                checkAndAlertBattery(context)
            }
        }
    }

    companion object {
        fun readBatteryStatus(context: Context): Pair<Int, Boolean> {
            val sticky = context.applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return -1 to false

            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val percent = if (level >= 0) (level * 100) / scale else -1
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return percent to isCharging
        }

        fun checkAndAlertBattery(context: Context) {
            val prefs = SmsSensePrefs(context)
            val config = prefs.loadConfig()
            if (!config.enabled || !config.lowBatteryAlertEnabled) return

            val (percent, isCharging) = readBatteryStatus(context)
            val alreadySent = prefs.loadSentBatteryThresholds()
            val (triggeredThreshold, nextSent) = ForwardRuleEngine.evaluateMultiBatteryThresholds(
                levelPercent = percent,
                isCharging = isCharging,
                configuredThresholds = config.lowBatteryThresholds,
                alreadySentThresholds = alreadySent
            )

            if (nextSent != alreadySent) {
                prefs.saveSentBatteryThresholds(nextSent)
            }

            if (triggeredThreshold != null) {
                ForwardDispatcher.dispatch(
                    context = context,
                    event = ForwardEvent(
                        channel = SourceChannel.LOW_BATTERY,
                        packageName = "battery",
                        sourceTitle = "電量 $percent%",
                        content = "目前電量剩餘 $percent%（已達通知門檻 $triggeredThreshold%），請記得充電。",
                        timestampMs = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}
