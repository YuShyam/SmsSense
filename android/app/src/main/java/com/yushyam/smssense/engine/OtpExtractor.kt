package com.yushyam.smssense.engine

/**
 * 語境感知 OTP 驗證碼輔助提取器 (Context-Aware Auxiliary OTP Extractor)
 *
 * 設計原則：
 * 1. 僅作為「頂部單擊複製提示」，永遠搭配完整原文轉發，不取代原文。
 * 2. 必須同時命中驗證碼語境關鍵字（如「驗證碼」「動態密碼」「OTP」「code」），
 *    且排除金額（元 / NT$ / $）與卡號末四碼等干擾數字，將誤判機率降至最低。
 */
object OtpExtractor {

    private val CONTEXT_KEYWORDS = listOf(
        "驗證碼", "验证码", "動態密碼", "动态密码", "交易密碼", "認證碼", "校验码",
        "一次性密碼", "登入碼", "安全碼", "otp", "passcode", "verification",
        "verify", "security code", "auth code", "login code", "pin code", "code"
    )

    // 優先匹配明確緊鄰於關鍵字之後的 4~8 位數字（例：驗證碼：482910 或 code is 123456）
    private val DIRECT_AFTER_KEYWORD = Regex(
        "(?:驗證碼|验证码|動態密碼|动态密码|認證碼|交易密碼|安全碼|otp|passcode|code)(?:\\s*(?:為|为|是|is|:|：|\\[|【|「)?\\s*)(\\d{4,8})\\b",
        RegexOption.IGNORE_CASE
    )

    // 次選匹配獨立的 4~8 位數字（排除前後緊貼貨幣符號或「末」「元」「號」）
    private val STANDALONE_DIGITS = Regex("(?<![\\d$￥¥元末])\\b(\\d{4,8})\\b(?![\\d元塊块])")

    fun extractHint(text: String): String? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        val hasContext = CONTEXT_KEYWORDS.any { lower.contains(it) }
        if (!hasContext) return null

        DIRECT_AFTER_KEYWORD.find(text)?.groupValues?.getOrNull(1)?.let { direct ->
            if (!isLikelyYearOrNoise(direct)) return direct
        }

        // 篩選候選數字：偏好 6 位數（最常見 OTP 長度），次選 4~8 位非年份數字
        val candidates = STANDALONE_DIGITS.findAll(text)
            .map { it.groupValues[1] }
            .filterNot { isLikelyYearOrNoise(it) }
            .toList()

        return candidates.firstOrNull { it.length == 6 } ?: candidates.firstOrNull()
    }

    private fun isLikelyYearOrNoise(digits: String): Boolean {
        if (digits.length == 4 && (digits.startsWith("20") || digits.startsWith("19"))) return true
        return false
    }
}
