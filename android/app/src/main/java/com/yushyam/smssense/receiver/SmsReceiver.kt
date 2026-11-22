package com.yushyam.smssense.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.SourceChannel
import com.yushyam.smssense.service.ForwardDispatcher

/**
 * 軌道 1：實體簡訊接收器 (SMS BroadcastReceiver)
 * 自動將超長簡訊之多段 PDU 合併為完整原文，確保絕不截斷驗證資訊。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // 依發件號碼分組合併多段長簡訊
        val grouped = LinkedHashMap<String, StringBuilder>()
        var timestamp = System.currentTimeMillis()

        for (msg in messages) {
            val sender = msg.displayOriginatingAddress?.trim().orEmpty().ifBlank { "未知號碼" }
            val body = msg.messageBody.orEmpty()
            grouped.getOrPut(sender) { StringBuilder() }.append(body)
            if (msg.timestampMillis > 0L) {
                timestamp = msg.timestampMillis
            }
        }

        for ((sender, bodyBuilder) in grouped) {
            val fullText = bodyBuilder.toString().trim()
            if (fullText.isEmpty()) continue
            ForwardDispatcher.dispatch(
                context = context,
                event = ForwardEvent(
                    channel = SourceChannel.SMS,
                    packageName = "sms",
                    sourceTitle = sender,
                    content = fullText,
                    timestampMs = timestamp
                )
            )
        }
    }
}
