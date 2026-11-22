package com.yushyam.smssense.engine

import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LINE Messaging API 單向推播器 (LINE Push Message Sender)
 */
object LineSender {

    private val gson = Gson()
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 10000

    fun sendPushMessage(
        channelAccessToken: String,
        targetId: String,
        plainText: String
    ): Pair<Boolean, String> {
        val cleanToken = channelAccessToken.trim()
        val cleanTarget = targetId.trim()
        if (cleanToken.length < 20) {
            return false to "LINE Channel Access Token 格式不正確"
        }
        if (cleanTarget.isEmpty()) {
            return false to "尚未設定 LINE Target ID"
        }

        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://api.line.me/v2/bot/message/push")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Authorization", "Bearer $cleanToken")
            }

            val payload = mapOf(
                "to" to cleanTarget,
                "messages" to listOf(
                    mapOf(
                        "type" to "text",
                        "text" to plainText.take(4500)
                    )
                )
            )
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
                true to "已送達 LINE (HTTP $code)"
            } else {
                false to "LINE API 錯誤 (HTTP $code): ${body.take(120)}"
            }
        } catch (e: Exception) {
            false to "LINE 連線失敗：${e.message ?: e.javaClass.simpleName}"
        } finally {
            conn?.disconnect()
        }
    }
}
