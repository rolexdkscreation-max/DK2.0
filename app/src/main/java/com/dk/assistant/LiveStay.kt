package com.dk.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक: "एक बार जुड़ने के बाद ✕ दबाने तक जुड़ा रहे; बीच में कटे तो पीछे से चुपचाप जुड़े, बातचीत वहीं से").
 * लॉग: हर 1011 ("Internal error occurred") से 0.5-1.8s पहले बीच बातचीत में text भेजा गया था; 0-6s में टूटना, 7 बार के बाद हार.
 * यहाँ के नियम छोटे और जाँचने लायक (बिना Android) - GeminiLive इन्हीं से चलता है.
 */
object LiveStay {
    /** "Gemini ने आवाज़ नहीं सुनी" की सीमा: पहला जवाब अक्सर 2-4s में आता है, इसलिए 6s से कम नहीं (पहले 1.8-2.5s) */
    const val NO_REPLY_MS = 6_000L

    /** बीच बातचीत में बचाव वाला text (caption "नहीं सुना", "अटका - दोबारा", "टूटने से पहले की बात", बीच का सवाल) - डिफ़ॉल्ट बंद */
    const val PREF_TEXT_RESCUE = "live_text_rescue"
    fun textRescueOn(c: Context): Boolean = Prefs.bool(c, PREF_TEXT_RESCUE, false)

    /** टूटकर दोबारा जुड़ने का इंतज़ार: 1, 2, 4, 8, 15s (अधिकतम 15) + 0-20% random. कभी हार नहीं मानते (✕ ही रोकता है) */
    private val STEPS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
    const val MAX_BACKOFF_MS = 15_000L
    fun backoffMs(drop: Int, rnd: Double = Math.random()): Long {
        if (drop <= 0) return 250L                      // लंबा चला / goAway = तुरंत (चुपचाप)
        val base = STEPS[(drop - 1).coerceAtMost(STEPS.size - 1)]
        return (base + (base * 0.2 * rnd.coerceIn(0.0, 1.0)).toLong()).coerceAtMost(MAX_BACKOFF_MS + 3_000L)
    }

    /** इतनी बार जल्दी टूटा = यूज़र को एक बार "नेटवर्क कमज़ोर है" (स्क्रीन पर) */
    const val WEAK_NET_TELL_AT = 3

    /** 2s से कम चला और ठीक पहले (2s में) text भेजा था = वही text दोबारा नहीं (वरना टूटने का चक्कर) */
    fun skipResend(lastedMs: Long, textAgoAtDropMs: Long): Boolean = lastedMs < 2_000L && textAgoAtDropMs in 0L..2_000L

    /** goAway का timeLeft ("10s", "9.5s", "" ) -> ms; न समझे तो -1 */
    fun timeLeftMs(s: String?): Long {
        val t = s?.trim().orEmpty().removeSuffix("s")
        return t.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1L
    }

    /**
     * goAway पर पहले से तैयार (standby) connection तभी लें जब उसी ताज़ा बातचीत (handle) से बना हो;
     * तैयार होने के बाद नई बात हुई (handle बदला) और पुराना अभी ज़िंदा = ताज़ा handle से जोड़ो (संदर्भ न छूटे).
     * पुराना मर चुका = तैयार वाला तुरंत (बीच में आवाज़ न कटे, 0 ms).
     */
    fun useParked(parkedHandle: String?, curHandle: String?, oldAlive: Boolean): Boolean =
        parkedHandle == null || parkedHandle == curHandle || !oldAlive

    /** ✕ से बंद = 1000; बाकी हर कटाव (1006/1011/1001/नेटवर्क बदलना) = अपने-आप कटा, चुपचाप फिर */
    fun userClosed(stoppedByUser: Boolean): Boolean = stoppedByUser
    /**
     * नवंबर 2026 (Inspector के बाद "Live नहीं जुड़ रहा"): Google का जाना-माना server bug - gemini-2.5-flash-native-audio-preview-12-2025
     * (और -latest) 27 मई 2026 से बीच बातचीत में 1011 "Internal error" देता है. उसी मॉडल पर बार-बार जुड़ना = वही 1011.
     * 3 मिनट में 2 बार 1011 = यह मॉडल दौड़ से 2 मिनट हटाओ (save नहीं होता), अगला मॉडल (बचा आख़िरी मॉडल कभी नहीं हटता).
     */
    const val MODEL_1011_WINDOW_MS = 3 * 60_000L
    const val MODEL_1011_HITS = 2
    class Model1011 {
        private val hits = HashMap<String, ArrayDeque<Long>>()
        /** इस मॉडल का 1011 दर्ज करो; true = अब यह मॉडल हटाना है */
        @Synchronized fun hit(model: String, now: Long = System.currentTimeMillis()): Boolean {
            val q = hits.getOrPut(model) { ArrayDeque() }
            q.addLast(now)
            while (q.isNotEmpty() && now - q.first() > MODEL_1011_WINDOW_MS) q.removeFirst()
            if (q.size >= MODEL_1011_HITS) { q.clear(); return true }
            return false
        }
        @Synchronized fun count(model: String) = hits[model]?.size ?: 0
    }

    /**
     * अधूरी function declaration (खाली properties / बिना description वाला parameter) native-audio मॉडल पर 1011 का जाना-माना कारण.
     * बिना parameter वाले tool से "parameters" हटाओ; हर property को description दो. मतलब/नाम नहीं बदलता.
     */
    fun cleanTools(tools: JSONArray): JSONArray {
        for (i in 0 until tools.length()) {
            val decls = tools.optJSONObject(i)?.optJSONArray("functionDeclarations") ?: continue
            for (j in 0 until decls.length()) {
                val d = decls.optJSONObject(j) ?: continue
                if (d.optString("description").isBlank()) d.put("description", d.optString("name").replace('_', ' '))
                val p = d.optJSONObject("parameters") ?: continue
                val props = p.optJSONObject("properties")
                if (props == null || props.length() == 0) { d.remove("parameters"); continue }
                fixProps(props)
            }
        }
        return tools
    }
    private fun fixProps(props: JSONObject) {
        for (k in props.keys()) {
            val v = props.optJSONObject(k) ?: continue
            if (v.optString("description").isBlank()) v.put("description", k.replace('_', ' '))
            v.optJSONObject("properties")?.let { fixProps(it) }
            v.optJSONObject("items")?.optJSONObject("properties")?.let { fixProps(it) }
        }
    }
    /**
     * नवंबर 2026 (स्क्रीन पर "Speaking" जबकि Live टूट चुका): Live बंद होते समय stopped=true पहले लगता है, इसलिए आवाज़-इंजन का
     * "बोलना खत्म" नहीं पहुँचता - SPEAKING/THINKING अटका रहता था. Live खत्म = इन्हें IDLE करो (बैकअप आवाज़ खुद SPEAKING लगाती है).
     */
    fun modeAfterEnd(m: Mode): Mode = if (m == Mode.SPEAKING || m == Mode.THINKING) Mode.IDLE else m
    /** बीच में कटा और कुछ नहीं बज रहा = "Speaking" नहीं, "Listening" (जुड़ते ही फिर सुनेगा) */
    fun modeAfterDrop(m: Mode, playing: Boolean): Mode = if (m == Mode.SPEAKING && !playing) Mode.LISTENING else m
}
