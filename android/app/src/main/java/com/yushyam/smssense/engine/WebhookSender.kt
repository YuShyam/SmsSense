package com.yushyam.smssense.engine

import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通用 Webhook JSON 單向推播器 (Generic / Discord / Slack / Custom Webhook Sender)
 */
object WebhookSender {

    private val gson = Gson()
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 10000

    fun sendWebhook(
        webhookUrl: String,
        secretHeader: String,
        plainText: String,
        htmlText: String = ""
    ): Pair<Boolean, String> {
        val cleanUrl = webhookUrl.trim()
        if (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("http://")) {
            return false to "Webhook URL 必須以 https:// 或 http:// 開頭"
        }

        var conn: HttpURLConnection? = null
        return try {
            val url = URL(cleanUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "SmsSense-Android/0.3")
                val secret = secretHeader.trim()
                if (secret.isNotEmpty()) {
                    setRequestProperty("X-Webhook-Secret", secret)
                    if (secret.startsWith("Bearer ", ignoreCase = true)) {
                        setRequestProperty("Authorization", secret)
                    }
                }
            }

            val payload = linkedMapOf<String, Any>(
                "text" to plainText.take(4000),
                "content" to plainText.take(2000),
                "source" to "SmsSense"
            )
            if (htmlText.isNotBlank()) {
                payload["html"] = htmlText.take(4000)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(gson.toJson(payload))
                writer.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
            }.orEmpty()

            if (code in 200..299) {
                true to "已送達 Webhook (HTTP $code)"
            } else {
                false to "Webhook 錯誤 (HTTP $code): ${body.take(120)}"
            }
        } catch (e: Exception) {
            false to "Webhook 連線失敗：${e.message ?: e.javaClass.simpleName}"
        } finally {
            conn?.disconnect()
        }
    }
}
