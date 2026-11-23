package com.yushyam.smssense.data.repo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yushyam.smssense.data.model.ForwardLogItem
import com.yushyam.smssense.data.model.PendingForwardItem
import com.yushyam.smssense.data.model.SmsSenseConfig
import com.yushyam.smssense.data.model.TargetDestination

/**
 * 防禦性設定、歷史紀錄與離線佇列儲存庫 (Schema-Safe Preferences Repository)
 */
class SmsSensePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadConfig(): SmsSenseConfig = parseConfigJson(prefs.getString(KEY_CONFIG, null))

    fun saveConfig(config: SmsSenseConfig) {
        val cleanThresholds = config.lowBatteryThresholds
            .filter { it in 5..90 }
            .distinct()
            .sorted()
            .ifEmpty { SmsSenseConfig.DEFAULT_BATTERY_THRESHOLDS }

        val clean = config.copy(
            deviceAlias = config.deviceAlias.trim(),
            telegramBotToken = config.telegramBotToken.trim(),
            telegramChatId = config.telegramChatId.trim(),
            lineChannelAccessToken = config.lineChannelAccessToken.trim(),
            lineTargetId = config.lineTargetId.trim(),
            webhookUrl = config.webhookUrl.trim(),
            webhookSecretHeader = config.webhookSecretHeader.trim(),
            whitelistedNotifPackages = config.whitelistedNotifPackages.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            whitelistedPromptPackages = config.whitelistedPromptPackages.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            lowBatteryThresholds = cleanThresholds
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

    fun loadSentBatteryThresholds(): Set<Int> {
        val raw = prefs.getString(KEY_SENT_BATT_THRESHOLDS, null).orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    fun saveSentBatteryThresholds(thresholds: Set<Int>) {
        if (thresholds.isEmpty()) {
            prefs.edit().remove(KEY_SENT_BATT_THRESHOLDS).apply()
        } else {
            prefs.edit().putString(KEY_SENT_BATT_THRESHOLDS, thresholds.sorted().joinToString(",")).apply()
        }
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

    @Synchronized
    fun clearPendingQueue() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    companion object {
        private const val PREFS_NAME = "smssense_prefs"
        private const val KEY_CONFIG = "config_json"
        private const val KEY_LOGS = "logs_json"
        private const val KEY_PENDING = "pending_queue_json"
        private const val KEY_SENT_BATT_THRESHOLDS = "sent_battery_thresholds"
        private const val MAX_LOGS = 50
        private const val MAX_PENDING = 50

        fun parseConfigJson(json: String?): SmsSenseConfig {
            if (json.isNullOrBlank()) return SmsSenseConfig()
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return SmsSenseConfig()
                val obj = root.asJsonObject

                val multiThresholds = obj.optIntList("lowBatteryThresholds")
                val resolvedThresholds = if (multiThresholds.isNotEmpty()) {
                    multiThresholds.map { it.coerceIn(5, 90) }.distinct().sorted()
                } else if (obj.has("lowBatteryThreshold")) {
                    listOf(obj.optInt("lowBatteryThreshold", 20).coerceIn(5, 90))
                } else {
                    SmsSenseConfig.DEFAULT_BATTERY_THRESHOLDS
                }

                SmsSenseConfig(
                    enabled = obj.optBoolean("enabled", true),
                    deviceAlias = obj.optString("deviceAlias", ""),
                    offlineQueueEnabled = obj.optBoolean("offlineQueueEnabled", true),
                    themeMode = obj.optString("themeMode", "system").ifBlank { "system" },
                    telegramEnabled = obj.optBoolean("telegramEnabled", true),
                    telegramBotToken = obj.optString("telegramBotToken", ""),
                    telegramChatId = obj.optString("telegramChatId", ""),
                    lineEnabled = obj.optBoolean("lineEnabled", false),
                    lineChannelAccessToken = obj.optString("lineChannelAccessToken", ""),
                    lineTargetId = obj.optString("lineTargetId", ""),
                    webhookEnabled = obj.optBoolean("webhookEnabled", false),
                    webhookUrl = obj.optString("webhookUrl", ""),
                    webhookSecretHeader = obj.optString("webhookSecretHeader", ""),
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
                    lowBatteryThresholds = resolvedThresholds
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
                    val plain = o.optString("formattedPlainText", html.replace(Regex("<[^>]+>"), ""))
                    val targets = o.optStringList(
                        "targetDestinations",
                        listOf(TargetDestination.TELEGRAM.name)
                    )
                    PendingForwardItem(
                        id = o.optString("id", ""),
                        channelLabel = o.optString("channelLabel", ""),
                        sourceTitle = o.optString("sourceTitle", ""),
                        formattedHtml = html,
                        formattedPlainText = plain,
                        targetDestinations = targets,
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

        private fun JsonObject.optIntList(key: String): List<Int> {
            val e = get(key) ?: return emptyList()
            if (e.isJsonNull || !e.isJsonArray) return emptyList()
            return e.asJsonArray.mapNotNull { item ->
                try {
                    if (item.isJsonNull || !item.isJsonPrimitive) null else item.asInt
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
