package com.dk.assistant.plus

import android.content.Context
import com.dk.assistant.QuickCommands
import org.json.JSONObject

/**
 * 15 नवंबर 2026: DK AI Plus की बोलकर चलने वाली कमांड (बिना AI, तुरंत):
 *  - "DK, activate SOS" / "SOS चालू करो" / "एसओएस भेजो"  -> इमरजेंसी SOS     ("SOS बंद" / "SOS रोको" -> रोकना)
 *  - "DK, turn on driving mode" / "ड्राइविंग मोड चालू"     -> ड्राइविंग मोड     ("... बंद" -> बंद)
 *  - "Touch Guard चालू" अंग्रेज़ी में भी -> पुराना टच मोड ([com.dk.assistant.GuardRules], वही 12 सेकंड वाला)
 * [parse] pure (JVM test - [PlusCommandsTest]); [run] Accessibility executor से.
 */
object PlusCommands {
    const val SOS = "plus_sos"
    const val DRIVING = "plus_driving"
    val TYPES = setOf(SOS, DRIVING)

    private fun a(type: String, on: Boolean) = JSONObject().put("type", type).put("on", on)

    fun parse(t0: String): QuickCommands.Quick? {
        val t = " " + t0.lowercase().trim() + " "
        fun has(vararg w: String) = w.any { t.contains(it) }
        val off = has("बंद", "रोको", "रोक दो", "stop", " off", "cancel", "कैंसल", "deactivate")
        val words = t.trim().split(Regex("\\s+")).size
        if (words > 9) return null
        if (has(" sos ", "एसओएस", "एस ओ एस", "s.o.s", "इमरजेंसी अलर्ट", "emergency alert")) {
            if (off) return QuickCommands.Quick(a(SOS, false), "SOS रोक रही हूँ")
            // 15 नवंबर 2026 (ऑडिट): पहले " sos " शब्द ही "चालू" माना जाता था और "on" शब्द के बीच भी मिलता था
            // ("phone") - "SOS क्या होता है" कहते ही 5 लोगों को SMS + कॉल चले जाते. अब सिर्फ़ साफ़ आदेश पर.
            return if (sosStart(t)) QuickCommands.Quick(a(SOS, true), "") else null
        }
        if (has("driving mode", "ड्राइविंग मोड", "ड्राइव मोड", "drive mode", "गाड़ी मोड", "गाड़ी चला रहा")) {
            return if (off) QuickCommands.Quick(a(DRIVING, false), "ड्राइविंग मोड बंद")
            else QuickCommands.Quick(a(DRIVING, true), "ड्राइविंग मोड चालू")
        }
        return null
    }

    /** SOS सवाल/जानकारी/सेटिंग की बात हो तो कभी चालू नहीं */
    private val SOS_INFO = setOf("क्या", "kya", "what", "मतलब", "matlab", "meaning", "कैसे", "kaise", "how", "बताओ", "batao",
        "explain", "समझाओ", "नंबर", "number", "सेटिंग", "सेटिंग्स", "setting", "settings", "डालो", "जोड़ो", "add", "क्यों", "why")
    /** पूरे शब्द (टुकड़े नहीं) - "on" अब "phone" में नहीं मिलता */
    private val SOS_GO = setOf("activate", "चालू", "भेजो", "भेजें", "भेज", "send", "शुरू", "start", "on", "ऑन", "करो", "करें", "trigger",
        "मदद", "help", "बचाओ", "bachao")
    private val SOS_WORD = setOf("sos", "एसओएस", "s.o.s", "dk", "डीके", "please", "प्लीज़", "प्लीज")

    /** सिर्फ़ साफ़ आदेश: "SOS भेजो", "activate SOS", "इमरजेंसी अलर्ट चालू", या अकेला "SOS" / "SOS SOS" / "SOS मदद" */
    internal fun sosStart(t0: String): Boolean {
        val tok = t0.lowercase().split(Regex("[\\s,!?।]+")).map { it.trim('.', '\'', '"') }.filter { it.isNotEmpty() }
        if (tok.isEmpty() || tok.any { it in SOS_INFO }) return false
        if (tok.any { it in SOS_GO }) return true
        if (t0.contains("कर दो")) return true
        // अकेला "SOS" (असली इमरजेंसी में लोग यही चिल्लाते हैं) - सिर्फ़ SOS/DK जैसे शब्द, और कुछ नहीं
        return tok.all { it in SOS_WORD || it == "एस" || it == "ओ" }
    }

    fun run(c: Context, a: JSONObject): String = when (a.optString("type")) {
        SOS -> if (a.optBoolean("on", true)) Sos.start(c) else Sos.stop()
        DRIVING -> DrivingMode.set(c, a.optBoolean("on", true))
        else -> "ERROR: unknown"
    }
}
