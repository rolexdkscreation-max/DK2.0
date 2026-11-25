package com.dk.assistant.translate

import com.dk.assistant.QuickCommands
import org.json.JSONObject

/**
 * 25 नवंबर 2026 (मालिक: "डीके ट्रांसलेट को चालू करो बोलूँ तो DK Live चालू करेगा, उसके बाद DK Live का माइक बंद
 * और ट्रांसलेटर का माइक चालू - एक बार में एक ही"): बोलकर DK ट्रांसलेटर चालू / बंद.
 *
 * सिर्फ़ साफ़ आदेश: "ट्रांसलेटर चालू करो", "DK ट्रांसलेट को चालू करो", "दुभाषिया बंद", "translator mode on".
 * "इसे अंग्रेज़ी में ट्रांसलेट करो" (किसी वाक्य का अनुवाद) = यह नहीं - वह पहले जैसा DK का अपना जवाब.
 * Android import नहीं -> JVM test.
 */
object TrCommands {
    const val TYPE = "dk_translator"

    private val NAME = listOf("ट्रांसलेटर", "ट्रान्सलेटर", "translator", "दुभाषिया", "दुभाषिए", "इंटरप्रेटर", "interpreter", "अनुवादक")
    private val LOOSE = listOf("ट्रांसलेट", "ट्रान्सलेट", "ट्रांसलेशन", "translate", "translation", "अनुवाद")
    private val ON = listOf("चालू", "ऑन", " on", "शुरू", "स्टार्ट", "start", "खोल", "open", "चला", "activate", "लगा")
    private val OFF = listOf("बंद", "ऑफ", " off", "रोको", "रोक दो", "stop", "हटा", "close", "बाहर", "exit")
    /** किसी वाक्य / भाषा में अनुवाद की माँग - मोड नहीं */
    private val SENTENCE = listOf(" में ", " into ", " to english", " to hindi", "मतलब", "meaning", "इसका", "इसे", "यह वाक्य", "लिखो", "बताओ")

    fun parse(t0: String): QuickCommands.Quick? {
        val t = " " + t0.lowercase().trim().trim('.', '।', '!', '?') + " "
        if (t.trim().split(Regex("\\s+")).size > 8) return null
        val named = NAME.any { t.contains(it) }
        val loose = !named && LOOSE.any { t.contains(it) }
        if (!named && !loose) return null
        val on = ON.any { t.contains(it) }
        val off = OFF.any { t.contains(it) }
        if (!on && !off) return null
        if (loose && SENTENCE.any { t.contains(it) }) return null
        return if (off) QuickCommands.Quick(JSONObject().put("type", TYPE).put("on", false), "DK ट्रांसलेटर बंद")
        else QuickCommands.Quick(JSONObject().put("type", TYPE).put("on", true), "DK ट्रांसलेटर चालू")
    }
}
