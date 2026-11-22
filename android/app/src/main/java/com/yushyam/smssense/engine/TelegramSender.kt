package com.yushyam.smssense.engine

import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Telegram Bot API HTTPS 單向推播器 (Lightweight HTTPS Telegram Sender)
 *
 * 使用標準系統 HttpsURLConnection（嚴格啟用系統憑證驗證），
 * 並以 Gson 序列化請求主體，避免 JSON 拼接注入。
 */
object TelegramSender {

    private val gson = Gson()
    private val TOKEN_REGEX = Regex("^[0-9]{5,15}:[A-Za-z0-9_-]{20,}$")
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 10000

    fun isValidBotToken(token: String): Boolean = TOKEN_REGEX.matches(token.trim())

    fun sendHtmlMessage(
        botToken: String,
        chatId: String,
        htmlText: String
    ): Pair<Boolean, String> {
        val cleanToken = botToken.trim()
        val cleanChatId = chatId.trim()
        if (!isValidBotToken(cleanToken)) {
            return false to "Bot Token 格式不正確"
        }
        if (cleanChatId.isEmpty()) {
            return false to "尚未設定 Chat ID"
        }

        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://api.telegram.org/bot$cleanToken/sendMessage")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val payload = mapOf(
                "chat_id" to cleanChatId,
                "text" to htmlText.take(4000),
                "parse_mode" to "HTML",
                "disable_web_page_preview" to true
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
                true to "已送達 Telegram (HTTP $code)"
            } else {
                false to "Telegram API 回傳錯誤 (HTTP $code): ${body.take(120)}"
            }
        } catch (e: Exception) {
            false to "連線失敗：${e.message ?: e.javaClass.simpleName}"
        } finally {
            conn?.disconnect()
        }
    }
}
