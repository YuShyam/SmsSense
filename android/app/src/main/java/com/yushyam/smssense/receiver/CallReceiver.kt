package com.yushyam.smssense.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.SourceChannel
import com.yushyam.smssense.engine.SimSlotResolver
import com.yushyam.smssense.service.ForwardDispatcher

/**
 * 來電通知接收器 (Incoming Call Ringing Receiver)
 * 當手機有來電響鈴時，立即將來電號碼、系統收受門號 (SIM1/SIM2) 與發生時間推播至啟用通道。
 */
class CallReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.trim().orEmpty()
        if (number.isEmpty() && !intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) {
            return
        }

        val receiverSim = SimSlotResolver.resolveSimDescription(context, intent)
        val displayNumber = number.ifBlank { "未知號碼（或未授予通話紀錄權限）" }

        ForwardDispatcher.dispatch(
            context = context,
            event = ForwardEvent(
                channel = SourceChannel.INCOMING_CALL,
                packageName = "telephony",
                sourceTitle = displayNumber,
                content = "收到來電：$displayNumber",
                receiverSimInfo = receiverSim,
                timestampMs = System.currentTimeMillis()
            )
        )
    }
}
