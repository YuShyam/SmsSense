package com.yushyam.smssense.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yushyam.smssense.receiver.BatteryAlertReceiver

/**
 * 輕量低電量定期檢查 Worker (30 分鐘一次由系統 WorkManager 批次調度，不常駐行程)
 * 彌補系統 ACTION_BATTERY_LOW 固定在 15% 才觸發的限制，支援自訂 20%/25%/30% 門檻。
 */
class BatteryCheckWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        BatteryAlertReceiver.checkAndAlertBattery(appContext)
        return Result.success()
    }
}
