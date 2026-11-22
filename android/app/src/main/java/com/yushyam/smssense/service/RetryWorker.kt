package com.yushyam.smssense.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 斷網恢復補發 Worker (Network-Constrained Offline Queue Flusher)
 * 僅在有待補發項目且網路恢復時由系統喚醒執行，平時零背景輪詢耗電。
 */
class RetryWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val (_, remaining) = ForwardDispatcher.flushPendingQueueBlocking(appContext)
        if (remaining == 0) Result.success() else Result.retry()
    }
}
