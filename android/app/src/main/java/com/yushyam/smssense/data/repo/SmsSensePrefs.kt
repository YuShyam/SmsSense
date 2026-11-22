package com.yushyam.smssense.data.repo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yushyam.smssense.data.model.ForwardLogItem
import com.yushyam.smssense.data.model.PendingForwardItem
import com.yushyam.smssense.data.model.SmsSenseConfig

/**
 * 防禦性設定、歷史紀錄與離線佇列儲存庫 (Schema-Safe Preferences Repository)
 *
 * 所有反序列化一律使用 JsonParser 逐欄解析，確保跨版本新增欄位時零 NullPointerException。
 */
class SmsSensePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadConfig(): SmsSenseConfig = parseConfigJson(prefs.getString(KEY_CONFIG, null))

    fun saveConfig(config: SmsSenseConfig) {
        val clean = config.copy(
            deviceAlias = config.deviceAlias.trim(),
            telegramBotToken = config.telegramBotToken.trim(),
            telegramChatId = config.telegramChatId.trim(),
            whitelistedNotifPackages = config.whitelistedNotifPackages.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            whitelistedPromptPackages = config.whitelistedPromptPackages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        )
        prefs.edit().putString(KEY_CONFIG, gson.toJson(clean)).apply()
    }

    fun loadLogs(): List<ForwardLogItem> = parseLogsJson(prefs.getString(KEY_LOGS, null))

    fun appendLog(item: ForwardLogItem) {
        val list = loadLogs().toMutableList()
        list.add(0, item)
        prefs.edit().putString(KEY_LOGS, gson.toJson(list.take(MAX_LOGS))).apply()
    }

    fun clearLogs() {
        prefs.edit().remove(KEY_LOGS).apply()
    }

    fun isLowBatteryAlertSent(): Boolean = prefs.getBoolean(KEY_LOW_BATT_SENT, false)

    fun setLowBatteryAlertSent(sent: Boolean) {
        prefs.edit().putBoolean(KEY_LOW_BATT_SENT, sent).apply()
    }

    @Synchronized
    fun loadPendingQueue(): List<PendingForwardItem> = parsePendingJson(prefs.getString(KEY_PENDING, null))

    @Synchronized
    fun enqueuePending(item: PendingForwardItem) {
        val list = loadPendingQueue().toMutableList()
        list.add(item)
        prefs.edit().putString(KEY_PENDING, gson.toJson(list.takeLast(MAX_PENDING))).apply()
    }

    @Synchronized
    fun savePendingQueue(items: List<PendingForwardItem>) {
        if (items.isEmpty()) {
            prefs.edit().remove(KEY_PENDING).apply()
        } else {
            prefs.edit().putString(KEY_PENDING, gson.toJson(items.takeLast(MAX_PENDING))).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "smssense_prefs"
        private const val KEY_CONFIG = "config_json"
        private const val KEY_LOGS = "logs_json"
        private const val KEY_PENDING = "pending_queue_json"
        private const val KEY_LOW_BATT_SENT = "low_battery_alert_sent"
        private const val MAX_LOGS = 50
        private const val MAX_PENDING = 30

        fun parseConfigJson(json: String?): SmsSenseConfig {
            if (json.isNullOrBlank()) return SmsSenseConfig()
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return SmsSenseConfig()
                val obj = root.asJsonObject
                SmsSenseConfig(
                    enabled = obj.optBoolean("enabled", true),
                    deviceAlias = obj.optString("deviceAlias", ""),
                    telegramBotToken = obj.optString("telegramBotToken", ""),
                    telegramChatId = obj.optString("telegramChatId", ""),
                    smsForwardEnabled = obj.optBoolean("smsForwardEnabled", true),
                    appendOtpHint = obj.optBoolean("appendOtpHint", true),
                    notificationForwardEnabled = obj.optBoolean("notificationForwardEnabled", true),
                    whitelistedNotifPackages = obj.optStringList("whitelistedNotifPackages", emptyList()),
                    promptForwardEnabled = obj.optBoolean("promptForwardEnabled", true),
                    whitelistedPromptPackages = obj.optStringList(
                        "whitelistedPromptPackages",
                        SmsSenseConfig.DEFAULT_PROMPT_PACKAGES
                    ),
                    callForwardEnabled = obj.optBoolean("callForwardEnabled", true),
                    lowBatteryAlertEnabled = obj.optBoolean("lowBatteryAlertEnabled", true),
                    lowBatteryThreshold = obj.optInt("lowBatteryThreshold", 20).coerceIn(5, 50)
                )
            } catch (_: Exception) {
                SmsSenseConfig()
            }
        }

        fun parseLogsJson(json: String?): List<ForwardLogItem> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonArray) return emptyList()
                root.asJsonArray.mapNotNull { elem ->
                    if (!elem.isJsonObject) return@mapNotNull null
                    val o = elem.asJsonObject
                    ForwardLogItem(
                        timestampMs = o.optLong("timestampMs", 0L),
                        channelLabel = o.optString("channelLabel", ""),
                        sourceTitle = o.optString("sourceTitle", ""),
                        preview = o.optString("preview", ""),
                        delivered = o.optBoolean("delivered", false),
                        statusDetail = o.optString("statusDetail", "")
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parsePendingJson(json: String?): List<PendingForwardItem> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonArray) return emptyList()
                root.asJsonArray.mapNotNull { elem ->
                    if (!elem.isJsonObject) return@mapNotNull null
                    val o = elem.asJsonObject
                    val html = o.optString("formattedHtml", "")
                    if (html.isBlank()) return@mapNotNull null
                    PendingForwardItem(
                        id = o.optString("id", ""),
                        channelLabel = o.optString("channelLabel", ""),
                        sourceTitle = o.optString("sourceTitle", ""),
                        formattedHtml = html,
                        createdAtMs = o.optLong("createdAtMs", 0L),
                        attempts = o.optInt("attempts", 0)
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun JsonObject.optString(key: String, default: String): String {
            val e = get(key) ?: return default
            return if (e.isJsonNull || !e.isJsonPrimitive) default else e.asString
        }

        private fun JsonObject.optBoolean(key: String, default: Boolean): Boolean {
            val e = get(key) ?: return default
            return try {
                if (e.isJsonNull || !e.isJsonPrimitive) default else e.asBoolean
            } catch (_: Exception) {
                default
            }
        }

        private fun JsonObject.optLong(key: String, default: Long): Long {
            val e = get(key) ?: return default
            return try {
                if (e.isJsonNull || !e.isJsonPrimitive) default else e.asLong
            } catch (_: Exception) {
                default
            }
        }

        private fun JsonObject.optInt(key: String, default: Int): Int {
            val e = get(key) ?: return default
            return try {
                if (e.isJsonNull || !e.isJsonPrimitive) default else e.asInt
            } catch (_: Exception) {
                default
            }
        }

        private fun JsonObject.optStringList(key: String, default: List<String>): List<String> {
            val e = get(key) ?: return default
            if (e.isJsonNull || !e.isJsonArray) return default
            return e.asJsonArray.mapNotNull { item ->
                if (item.isJsonNull || !item.isJsonPrimitive) null else item.asString
            }
        }
    }
}
