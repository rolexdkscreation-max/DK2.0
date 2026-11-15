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
    const val WATCH = "plus_watchman"
    val TYPES = setOf(SOS, DRIVING, WATCH)

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
        // 15 नवंबर 2026: DK वॉचमैन - "वॉचमैन चालू करो" / "कौन आया था" (रिपोर्ट = पहले Boss का चेहरा)
        watch(t, has(*arrayOf("बंद", "रोको", "stop", " off")))?.let { return it }
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

    internal fun watch(t: String, off: Boolean): QuickCommands.Quick? {
        val w = listOf("वॉचमैन", "वाचमैन", "watchman", "चौकीदार").any { t.contains(it) }
        val who = listOf("कौन आया था", "कौन कौन आया", "कौन-कौन आया", "कौन-कौन आए", "कौन कौन आए", "मेरे पीछे कौन").any { t.contains(it) }
        if (!w && !who) return null
        val report = who || listOf("रिपोर्ट", "report", "बताओ", "क्या हुआ", "कौन आया").any { t.contains(it) }
        val clear = listOf("मिटा", "डिलीट", "delete", "clear", "साफ").any { t.contains(it) }
        return when {
            report -> QuickCommands.Quick(JSONObject().put("type", WATCH).put("do", "report"), "पहले चेहरा जाँच — रिपोर्ट सिर्फ़ Boss के लिए")
            clear -> QuickCommands.Quick(JSONObject().put("type", WATCH).put("do", "clear"), "पहले Boss का चेहरा जाँच")
            off -> QuickCommands.Quick(JSONObject().put("type", WATCH).put("do", "stop"), "वॉचमैन सिर्फ़ Boss के चेहरे से बंद होगा")
            else -> QuickCommands.Quick(JSONObject().put("type", WATCH).put("do", "start"), "DK वॉचमैन चालू")
        }
    }

    private fun watchRun(c: Context, what: String): String {
        if (!FeatureFlags.on(c, FeatureFlags.FACE_WATCHMAN)) return "DK वॉचमैन बंद है — Settings → चेहरा पहचान → \"DK वॉचमैन\" चालू करें"
        return when (what) {
            "start" -> com.dk.assistant.WatchmanActivity.start(c)
            "stop" -> { com.dk.assistant.BossCheckActivity.open(c, com.dk.assistant.BossCheckActivity.MODE_STOP); "OK" }
            "clear" -> { com.dk.assistant.BossCheckActivity.open(c, com.dk.assistant.BossCheckActivity.MODE_CLEAR); "OK" }
            else -> { com.dk.assistant.BossCheckActivity.open(c, com.dk.assistant.BossCheckActivity.MODE_REPORT); "OK" }
        }
    }

    fun run(c: Context, a: JSONObject): String = when (a.optString("type")) {
        WATCH -> watchRun(c, a.optString("do", "start"))
        SOS -> if (a.optBoolean("on", true)) Sos.start(c) else Sos.stop()
        DRIVING -> DrivingMode.set(c, a.optBoolean("on", true))
        else -> "ERROR: unknown"
    }
}
