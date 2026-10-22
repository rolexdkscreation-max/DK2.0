package com.dk.assistant

import android.content.Context
import org.json.JSONObject

/**
 * तीनों फ़ीचर के लिए डिफ़ॉल्ट मॉडल, ऑटो-फ़ॉलबैक और कोटा स्टेट मैनेजमेंट:
 * - liveVoice: Gemini Live API से वॉइस-टू-वॉइस बातचीत
 * - textChat: टेक्स्ट चैट (वॉइस से बिल्कुल अलग)
 * - codingAgent: ऐप का कोडिंग एजेंट
 */
object ModelConfig {
    private const val PREFS_NAME = "dk_model_config_state"

    // Feature keys
    const val FEATURE_LIVE = "liveVoice"
    const val FEATURE_CHAT = "textChat"
    const val FEATURE_CODER = "codingAgent"

    // Primary & Fallback models
    // liveVoice: primary = gemini-3.8-live, fallback = gemini-3.1-flash-live-preview
    // textChat: primary = gemini-2.5-flash, fallback = gemini-2.0-flash
    // codingAgent: primary = gemini-2.5-flash, fallback = gemini-2.5-flash-lite
    fun primaryModel(feature: String): String = when (feature) {
        FEATURE_LIVE -> "gemini-3.8-live"
        FEATURE_CHAT -> "gemini-2.5-flash"
        FEATURE_CODER -> "gemini-2.5-flash"
        else -> "gemini-2.5-flash"
    }

    fun fallbackModel(feature: String): String = when (feature) {
        FEATURE_LIVE -> "gemini-3.1-flash-live-preview"
        FEATURE_CHAT -> "gemini-2.0-flash"
        FEATURE_CODER -> "gemini-2.5-flash-lite"
        else -> "gemini-2.0-flash"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * जांच करो कि क्या primaryModel पर वापस जाने का समय हो गया है।
     * RPM limit ~1 मिनट में रीसेट होती है, RPD limit Pacific Time आधी रात को रीसेट होती है।
     */
    fun getActiveModel(context: Context, feature: String): String {
        val p = prefs(context)
        val fallbackSince = p.getLong("${feature}_fallback_since", 0L)
        val retryPrimaryAt = p.getLong("${feature}_retry_primary_at", 0L)
        val now = System.currentTimeMillis()

        if (fallbackSince > 0L && now >= retryPrimaryAt) {
            // समय हो गया, primary पर वापस जाने की कोशिश करो
            DKLog.d("ModelConfig [$feature]: Primary model quota cooling period ended. Switching back to primary: ${primaryModel(feature)}")
            return primaryModel(feature)
        }

        val isFallbackActive = p.getBoolean("${feature}_is_fallback", false)
        return if (isFallbackActive) fallbackModel(feature) else primaryModel(feature)
    }

    /**
     * जब 429 या quota exceeded आए, तो fallback पर स्विच करो और टाइमस्टैम्प सेट करो।
     */
    fun recordRateLimit(context: Context, feature: String) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        // 65 सेकंड बाद primary पर दोबारा कोशिश करेंगे (RPM limit clear होने के बाद)
        val retryAt = now + 65_000L

        p.edit()
            .putBoolean("${feature}_is_fallback", true)
            .putLong("${feature}_fallback_since", now)
            .putLong("${feature}_retry_primary_at", retryAt)
            .apply()

        DKLog.d("ModelConfig [$feature]: Rate limit (429) hit. Switched to fallback: ${fallbackModel(feature)}. Will retry primary at $retryAt")
    }

    /**
     * अगर रिक्वेस्ट सफलतापूर्वक पूरी हो जाए, तो स्टेट साफ़ करो ताकि अगली बार primary इस्तेमाल हो सके।
     */
    fun recordSuccess(context: Context, feature: String, usedModel: String) {
        val p = prefs(context)
        val primary = primaryModel(feature)
        if (usedModel == primary) {
            val wasFallback = p.getBoolean("${feature}_is_fallback", false)
            if (wasFallback) {
                DKLog.d("ModelConfig [$feature]: Primary model is working again! Resetting fallback state.")
            }
            p.edit()
                .putBoolean("${feature}_is_fallback", false)
                .putLong("${feature}_fallback_since", 0L)
                .putLong("${feature}_retry_primary_at", 0L)
                .apply()
        }
    }

    /**
     * हिंदी संदेश जब दोनों मॉडल (primary और fallback) फ़ेल हो जाएं।
     */
    fun quotaExhaustedMessage(feature: String): String = when (feature) {
        FEATURE_LIVE -> "लाइव वॉइस फ़ीचर कुछ देर के लिए उपलब्ध नहीं है (कोटा समाप्त)।"
        FEATURE_CHAT -> "टेक्स्ट चैट फ़ीचर कुछ देर के लिए उपलब्ध नहीं है (कोटा समाप्त)।"
        FEATURE_CODER -> "कोडिंग एजेंट फ़ीचर कुछ देर के लिए उपलब्ध नहीं है (कोटा समाप्त)।"
        else -> "यह फ़ीचर कुछ देर के लिए उपलब्ध नहीं है।"
    }
}