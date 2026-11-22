package com.yushyam.smssense.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * 系統雙卡 SIM 卡槽與門號解析器 (System Dual-SIM Slot & Number Resolver)
 *
 * 直接讀取系統 SubscriptionManager 設定：
 * - 若系統有電信商與門號，組裝如 `SIM1_中華電信_0900000000 (SubId: 1)` 或 `SIM1_中華電信`
 * - 若未設定或無門號，直接顯示 `SIM1` 或 `SIM2`
 */
object SimSlotResolver {

    private val SUB_ID_KEYS = listOf(
        "subscription",
        "android.telephony.extra.SUBSCRIPTION_INDEX",
        "sub_id",
        "subId",
        "simId",
        "phone_id"
    )

    private val SLOT_KEYS = listOf(
        "slot",
        "simSlot",
        "phone",
        "android.telephony.extra.SLOT_INDEX",
        "slot_id"
    )

    @SuppressLint("MissingPermission")
    fun resolveSimDescription(context: Context, intent: Intent): String {
        val subId = extractIntExtra(intent, SUB_ID_KEYS, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val rawSlot = extractIntExtra(intent, SLOT_KEYS, -1)

        val hasPhoneStatePerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        var resolvedSlotIndex = rawSlot
        var resolvedSubId = subId
        var carrier = ""
        var phoneNumber = ""

        if (hasPhoneStatePerm) {
            try {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                if (sm != null) {
                    val subInfo: SubscriptionInfo? = when {
                        subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID ->
                            sm.getActiveSubscriptionInfo(subId)
                        rawSlot >= 0 ->
                            sm.getActiveSubscriptionInfoForSimSlotIndex(rawSlot)
                        else -> {
                            val list = sm.activeSubscriptionInfoList
                            if (list != null && list.size == 1) list.first() else null
                        }
                    }

                    if (subInfo != null) {
                        resolvedSlotIndex = subInfo.simSlotIndex
                        resolvedSubId = subInfo.subscriptionId
                        carrier = (subInfo.displayName ?: subInfo.carrierName)?.toString()?.trim().orEmpty()
                        phoneNumber = readSimPhoneNumber(context, sm, subInfo)
                    }
                }
            } catch (_: Exception) {
                // fallback
            }
        }

        return formatSimSummary(
            slotIndex = resolvedSlotIndex,
            carrierName = carrier,
            phoneNumber = phoneNumber,
            subId = resolvedSubId
        )
    }

    internal fun formatSimSummary(
        slotIndex: Int,
        carrierName: String,
        phoneNumber: String,
        subId: Int
    ): String {
        val slotLabel = if (slotIndex >= 0) "SIM${slotIndex + 1}" else ""
        val cleanCarrier = carrierName.trim()
        val cleanNumber = phoneNumber.trim()

        val parts = mutableListOf<String>()
        if (slotLabel.isNotBlank()) {
            parts.add(slotLabel)
        }
        // 避免系統 displayName 本身就是 "SIM 1" 或 "SIM1" 時重複疊加
        if (cleanCarrier.isNotBlank() && !cleanCarrier.replace(" ", "").equals(slotLabel, ignoreCase = true)) {
            parts.add(cleanCarrier)
        }
        if (cleanNumber.isNotBlank()) {
            parts.add(cleanNumber)
        }

        val mainLabel = parts.joinToString("_")
        val hasValidSubId = subId >= 0 && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID

        return when {
            mainLabel.isNotBlank() && hasValidSubId -> "$mainLabel (SubId: $subId)"
            mainLabel.isNotBlank() -> mainLabel
            hasValidSubId -> "SubId: $subId"
            else -> ""
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readSimPhoneNumber(
        context: Context,
        sm: SubscriptionManager,
        subInfo: SubscriptionInfo
    ): String {
        val hasNumberPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_NUMBERS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!hasNumberPerm) return ""

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val num33 = sm.getPhoneNumber(subInfo.subscriptionId).trim()
                if (num33.isNotBlank()) return num33
            }
        } catch (_: Exception) {
            // fallback
        }

        return try {
            subInfo.number?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    @Suppress("DEPRECATION")
    private fun extractIntExtra(intent: Intent, keys: List<String>, defaultVal: Int): Int {
        val extras = intent.extras ?: return defaultVal
        for (key in keys) {
            if (!extras.containsKey(key)) continue
            when (val raw = extras.get(key)) {
                is Int -> if (raw != defaultVal) return raw
                is Long -> if (raw.toInt() != defaultVal) return raw.toInt()
                is String -> raw.toIntOrNull()?.let { if (it != defaultVal) return it }
            }
        }
        return defaultVal
    }
}
