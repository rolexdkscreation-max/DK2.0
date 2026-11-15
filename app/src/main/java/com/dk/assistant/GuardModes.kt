package com.dk.assistant

import android.content.Context

/**
 * 15 नवंबर 2026 (मालिक, मास्टर प्रॉम्प्ट "God Mode / Stealth Mode"): टच मोड ([Guard]) के तीन रूप.
 *  - NORMAL  = पहले जैसा (सायरन + चेतावनी स्क्रीन + 3 फ़ोटो). डिफ़ॉल्ट.
 *  - GOD     = सबसे सख़्त: तेज़ फ़्लैश-स्ट्रोब, ज़्यादा फ़ोटो (10, हर 1.5 s), भरोसेमंद नंबर हो तो लोकेशन SMS हमेशा,
 *              और अलार्म बजते समय सिर्फ़ PIN से फ़ोन खोलना काफ़ी नहीं - मालिक का चेहरा या गुप्त शब्द चाहिए
 *              (दोनों में से कुछ भी सेट न हो तो PIN से ही बंद - मालिक कभी फँसे नहीं).
 *  - STEALTH = चुपचाप: न सायरन, न फ़्लैश, न कंपन, न बोलना - काली स्क्रीन, चुपके से फ़ोटो + लोकेशन SMS + रिकॉर्ड.
 * बोलकर: "गॉड मोड ऑन करो" / "स्टेल्थ मोड ऑन करो" = वह रूप चुनकर टच मोड चालू; "गॉड/स्टेल्थ मोड बंद करो" = वापस NORMAL.
 * Pure हिस्से JVM test में.
 */
object GuardModes {
    const val PREF = "guard_mode"
    const val TYPE = "guard_mode"

    enum class Mode(val id: String, val hi: String, val sub: String) {
        NORMAL("normal", "नॉर्मल", "सायरन + चेतावनी स्क्रीन + 3 फ़ोटो (पहले जैसा)"),
        GOD("god", "गॉड मोड", "सबसे सख़्त: तेज़ फ़्लैश, 10 फ़ोटो, लोकेशन SMS, PIN से नहीं — चेहरा / गुप्त शब्द से ही बंद"),
        STEALTH("stealth", "स्टेल्थ मोड", "चुपचाप: कोई आवाज़/फ़्लैश नहीं, काली स्क्रीन, चुपके से फ़ोटो + लोकेशन SMS");
        companion object { fun of(id: String?): Mode = values().firstOrNull { it.id == id } ?: NORMAL }
    }

    fun mode(c: Context): Mode = Mode.of(Prefs.str(c, PREF, Mode.NORMAL.id))
    fun set(c: Context, m: Mode) { Prefs.edit(c) { putString(PREF, m.id) }; DKLog.d("टच मोड का रूप: ${m.hi}") }

    // ------------------------------------------------------------------ pure नियम

    fun loud(m: Mode) = m != Mode.STEALTH
    fun photoMax(m: Mode) = when (m) { Mode.NORMAL -> 3; Mode.GOD -> 10; Mode.STEALTH -> 6 }
    fun photoGapMs(m: Mode) = when (m) { Mode.NORMAL -> 2_500L; Mode.GOD -> 1_500L; Mode.STEALTH -> 2_000L }
    fun strobeMs(m: Mode) = if (m == Mode.GOD) 120L else 250L
    /** GOD: नंबर ठीक हो तो SMS हमेशा (Settings का SMS स्विच बंद हो तब भी) */
    fun forceSms(m: Mode) = m == Mode.GOD

    /**
     * फ़ोन PIN / फ़िंगरप्रिंट से खुला - टच मोड बंद हो? GOD + अलार्म बज रहा + (चेहरा या गुप्त शब्द सेट) = नहीं.
     * बाकी हर हाल में हाँ (पहले जैसा).
     */
    fun pinStops(m: Mode, alarming: Boolean, faceReady: Boolean, hasCode: Boolean): Boolean =
        !(m == Mode.GOD && alarming && (faceReady || hasCode))

    // ------------------------------------------------------------------ बोलकर (pure)

    private val GOD = listOf("गॉड", "गोड", "गाड", "गॉड्", "god", "गॉड मोड")
    private val STEALTH = listOf("स्टेल्थ", "स्टील्थ", "स्टेल्त", "स्टेल्ट", "stealth", "steal th", "चुपके वाला", "साइलेंट गार्ड", "silent guard")
    private val MODE = listOf("मोड", "मोड़", "मॉड", "mode", "वाला")
    private val ON = listOf("ऑन", "आन", " on", "चालू", "शुरू", "लगा", "करो", "कर दो", "कीजिए", "activate", "एक्टिव", "स्टार्ट", "start")
    private val OFF = listOf("ऑफ", " off", "बंद", "हटा")
    private val NOT = listOf(" मत ", "नहीं", " ना ", "don't", " nahi", "क्या ", "कैसे", "क्यों")

    sealed class Cmd { data class On(val m: Mode, val minutes: Int = 0) : Cmd(); object Normal : Cmd() }

    fun parse(raw: String): Cmd? {
        val t = " " + raw.lowercase().trim().replace(Regex("[,।!?.]"), " ").replace(Regex("\\s+"), " ") + " "
        if (t.length > 80 || NOT.any { t.contains(it) }) return null
        if (MODE.none { t.contains(it) }) return null
        val m = when {
            GOD.any { t.contains(" $it") } -> Mode.GOD
            STEALTH.any { t.contains(it) } -> Mode.STEALTH
            else -> return null
        }
        if (OFF.any { t.contains(it) }) return Cmd.Normal
        if (ON.none { t.contains(it) }) return null
        return Cmd.On(m, GuardRules.minutes(raw))
    }

    fun quick(cmd: Cmd) = QuickCommands.Quick(
        org.json.JSONObject().put("type", TYPE).put("mode", (cmd as? Cmd.On)?.m?.id ?: Mode.NORMAL.id)
            .put("minutes", (cmd as? Cmd.On)?.minutes ?: 0), "")
}
