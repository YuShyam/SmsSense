package com.yushyam.smssense.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.SourceChannel
import com.yushyam.smssense.service.ForwardDispatcher

/**
 * 軌道 4：來電通知接收器 (Incoming Call Ringing Receiver)
 * 當備用機有來電響鈴時，立即將來電號碼與時間單向推播至 Telegram。
 */
class CallReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        // Android 9+ 需同時具備 READ_CALL_LOG 才能拿到真實來電號碼；第二次廣播會夾帶號碼
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.trim().orEmpty()
        if (number.isEmpty() && !intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) {
            return
        }

        val displayNumber = number.ifBlank { "未知號碼（或未授予通話紀錄權限）" }
        ForwardDispatcher.dispatch(
            context = context,
            event = ForwardEvent(
                channel = SourceChannel.INCOMING_CALL,
                packageName = "telephony",
                sourceTitle = displayNumber,
                content = "備用機收到來電：$displayNumber",
                timestampMs = System.currentTimeMillis()
            )
        )
    }
}
