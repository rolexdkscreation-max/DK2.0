package com.dk.assistant

import android.content.Context
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): Settings → AI दिमाग में "सोचने का स्तर" (Thinking level) - Auto / Low / Medium / High.
 * हर हिस्से (DK चैट, कोडिंग एजेंट, DK कंप्यूटर) का अपना; "एक key से सब" चालू हो तो उसका एक ही स्तर सब पर.
 * AUTO = पहले जैसा (हर जगह का पुराना तय स्तर) - इसलिए पुराने users का व्यवहार बिल्कुल नहीं बदलता.
 * यहाँ का सारा logic pure Kotlin है (JUnit से जाँचा जाता है), Context सिर्फ़ setting पढ़ने/लिखने में.
 */
object ThinkLevel {
    const val AUTO = "auto"
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    val ALL = listOf(AUTO, LOW, MEDIUM, HIGH)

    /** "एक key से सब" का अपना स्तर (सारे हिस्सों पर) */
    const val ONE = "one"

    /** Setting का नाम: think_level_chat / think_level_coder / think_level_pc / think_level_one */
    fun pref(scope: String) = "think_level_$scope"

    fun norm(v: String?): String = v?.trim()?.lowercase()?.takeIf { it in ALL } ?: AUTO

    fun get(c: Context, scope: String): String {
        val s = if (Prefs.oneKeyOn(c)) ONE else scope
        return norm(Prefs.str(c, pref(s), AUTO))
    }

    fun set(c: Context, scope: String, level: String) {
        Prefs.edit(c) { putString(pref(scope), norm(level)) }
    }

    /** Settings में दिखने वाला नाम + छोटा विवरण (तस्वीर जैसा: "Low — Quick and efficient") */
    fun title(level: String) = when (norm(level)) {
        LOW -> "Low"; MEDIUM -> "Medium"; HIGH -> "High"; else -> "Auto"
    }

    fun desc(level: String) = when (norm(level)) {
        LOW -> "जल्दी और किफ़ायती (Quick and efficient)"
        MEDIUM -> "संतुलित गहराई (Balanced depth)"
        HIGH -> "सबसे गहरी सोच, थोड़ा धीमा (Extra thorough)"
        else -> "DK खुद तय करे — पहले जैसा, सबसे तेज़"
    }

    /**
     * Gemini का thinkingConfig. null = AUTO (कुछ नहीं बदलो, पुराना तरीका).
     * Gemini 3+ = thinkingLevel (low/medium/high); Gemini 2.5 = thinkingBudget (tokens).
     * Gemma / पुराने मॉडल thinking नहीं समझते = null.
     */
    fun geminiConfig(model: String, level: String): JSONObject? {
        val l = norm(level)
        if (l == AUTO) return null
        val m = model.lowercase().removePrefix("models/")
        if (!m.startsWith("gemini-")) return null
        if (m.startsWith("gemini-2.5")) {
            val budget = when (l) { LOW -> 1024; MEDIUM -> 8192; else -> 24576 }
            return JSONObject().put("thinkingBudget", budget)
        }
        val major = m.removePrefix("gemini-").takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        if (major < 3) return null
        return JSONObject().put("thinkingLevel", l)
    }

    /** सोचने के tokens भी maxOutputTokens में गिने जाते हैं - ऊँचे स्तर पर जवाब कट न जाए, इसलिए इतने extra */
    fun extraTokens(level: String) = when (norm(level)) { LOW -> 1024; MEDIUM -> 4096; HIGH -> 8192; else -> 0 }

    /**
     * OpenAI-जैसी API (Groq, OpenRouter, Z.ai, NVIDIA) के body में सोचने का स्तर.
     * Z.ai (GLM-4.7 / 5.x अपने-आप सोचते हैं = धीमा): AUTO / Low = "thinking": disabled (तुरंत जवाब), Medium / High = enabled.
     * AUTO = पुराना नियम (सिर्फ़ gpt-oss पर [autoEffort]). OpenRouter = "reasoning": {"effort"} (जो मॉडल न सोचे वह अनदेखा करता है).
     * बाकी = "reasoning_effort" सिर्फ़ उन मॉडलों पर जो इसे समझते हैं (नहीं तो 400 आता है).
     * लौटाता है: कुछ जोड़ा या नहीं.
     */
    fun applyOpenAi(body: JSONObject, baseUrl: String, model: String, level: String, autoEffort: String?): Boolean {
        val l = norm(level)
        val m = model.lowercase()
        if (baseUrl.contains("api.z.ai") || baseUrl.contains("bigmodel.cn")) {
            body.put("thinking", JSONObject().put("type", if (l == MEDIUM || l == HIGH) "enabled" else "disabled")); return true
        }
        if (l == AUTO) {
            if (autoEffort != null && m.contains("gpt-oss")) { body.put("reasoning_effort", autoEffort); return true }
            return false
        }
        if (baseUrl.contains("openrouter.ai")) { body.put("reasoning", JSONObject().put("effort", l)); return true }
        if (supportsEffort(m)) { body.put("reasoning_effort", l); return true }
        return false
    }

    /** कौन-से मॉडल "reasoning_effort" (low/medium/high) लेते हैं */
    fun supportsEffort(model: String): Boolean {
        val m = model.lowercase().substringAfterLast('/')
        return m.contains("gpt-oss") || Regex("^o[1-9]").containsMatchIn(m) || m.startsWith("gpt-5") ||
            m.contains("grok-3-mini") || m.contains("grok-4") || m.contains("magistral") || m.contains("reasoner")
    }

    /** API ने सोचने वाला हिस्सा नहीं समझा (400) - उसी मॉडल से उसके बिना दोबारा. Key की गलती नहीं */
    fun rejected(code: Int, msg: String): Boolean =
        code == 400 && (msg.contains("reasoning", true) || msg.contains("think", true)) && !msg.contains("API key", true)

    /** body से सोचने वाला हिस्सा हटाओ (दोबारा भेजने से पहले) */
    fun strip(body: JSONObject) { body.remove("reasoning_effort"); body.remove("reasoning"); body.remove("thinking") }
}
