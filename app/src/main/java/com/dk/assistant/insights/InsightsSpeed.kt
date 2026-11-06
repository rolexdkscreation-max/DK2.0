package com.dk.assistant.insights

import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक: "रिव्यू में बहुत टाइम लगता है - फटाफट करे").
 *
 * समय कहाँ जाता था (जड़):
 *  1. ScreenCollector: हर टैप/स्क्रॉल के बाद तय 1.1-1.8 सेकंड का इंतज़ार, ऐप खुलने पर 2.2 सेकंड - स्क्रीन पहले बदल जाए तब भी.
 *  2. InsightsAgent.gemini(): हर कदम पर Gemini 3 Flash की पूरी "सोच" (thinking) + हर कदम स्क्रीनशॉट; पहला मॉडल limit पर हो
 *     तो हर कदम फिर उसी से शुरू (हर बार एक बेकार कॉल); एक अटकी कॉल 90 सेकंड तक.
 *  3. सारे आँकड़े मिलने के बाद भी AI के done=true कहने तक कदम चलते रहते थे (36 कदम तक).
 *  4. ट्रेंड की इंटरनेट खोज (45 सेकंड तक) सबसे आख़िर में, अकेले - बाकी काम के साथ नहीं.
 *
 * यह हिस्सा pure (JVM test): कब स्क्रीनशॉट, कब बस, Gemini की तेज़ सेटिंग, किस मॉडल से पहले.
 */
object InsightsSpeed {
    /** हर प्लेटफ़ॉर्म पर ज़्यादा से ज़्यादा AI कदम (पहले 36) */
    const val MAX_STEPS = 32
    /** टैप के बाद स्क्रीन बदलने का इंतज़ार: हर इतने ms पर देखो, ज़्यादा से ज़्यादा इतना */
    const val POLL_MS = 150L
    const val SETTLE_MAX_MS = 1_300L
    /** बदलने के बाद थोड़ा रुको - नई स्क्रीन के आँकड़े भर जाएँ */
    const val SETTLE_AFTER_MS = 280L
    /** ऐप खुलने के बाद (पहले 2.2 सेकंड) */
    const val OPEN_SETTLE_MS = 2_000L
    /** एक कदम की AI कॉल ज़्यादा से ज़्यादा (पहले 90 सेकंड) */
    const val STEP_CALL_S = 25L
    /** ट्रेंड खोज: बाकी काम के साथ चलती है; रिपोर्ट से पहले इतना ही और इंतज़ार */
    const val TREND_WAIT_MS = 20_000L

    /** स्क्रीन पर लिखे नंबर कम हों (चार्ट / तस्वीर वाला डैशबोर्ड) तभी स्क्रीनशॉट - वरना text ही काफ़ी (तेज़) */
    fun wantShot(prompt: String): Boolean {
        val i = prompt.indexOf("CURRENT SCREEN")
        if (i < 0) return true
        val end = prompt.indexOf("\nA screenshot", i).let { if (it < 0) prompt.length else it }
        val lines = prompt.substring(i, end).lines().drop(1).filter { it.startsWith("[") }
        if (lines.size < 4) return true
        // "[12]" (id) और "@y=40%" वाला हिस्सा नंबर नहीं गिना जाता
        val numeric = lines.count { l -> Regex("\\d").containsMatchIn(l.substringAfter("] ").substringBefore(" @y=")) }
        return numeric < 4 || prompt.contains("WARNING: the screen has not changed")
    }

    /** जो चाहिए था सब मिल गया - AI के "done" का इंतज़ार किए बिना बस */
    fun enough(hasOverview: Boolean, items: Int, listTarget: Int, detailed: Int, detailTarget: Int, statusChecked: Boolean): Boolean =
        hasOverview && items >= listTarget && detailed >= detailTarget && statusChecked

    /** Gemini 3+ पर सोच कम (कदम = minimal, रिपोर्ट = low) - जवाब कई गुना जल्दी. दूसरे मॉडल = null */
    fun thinking(model: String, report: Boolean): JSONObject? {
        val m = model.lowercase().removePrefix("models/")
        if (m.startsWith("gemini-2.5")) return JSONObject().put("thinkingBudget", if (report) 1024 else 0)
        val major = m.removePrefix("gemini-").takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        if (!m.startsWith("gemini-") || major < 3) return null
        return JSONObject().put("thinkingLevel", if (report) "low" else "minimal")
    }

    /** पिछली बार जो मॉडल चला, वही पहले (limit वाले पर हर कदम बेकार कॉल नहीं) */
    fun order(models: List<String>, lastOk: String?): List<String> =
        if (lastOk != null && lastOk in models) listOf(lastOk) + models.filter { it != lastOk } else models
}
