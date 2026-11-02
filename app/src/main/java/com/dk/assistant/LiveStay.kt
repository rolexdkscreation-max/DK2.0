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
     * नवंबर 2026 (DK Inspector DKI-406DD9 / DKI-7A418A, 02 Nov 09:00-09:06): 6 मिनट में 6 बार 1011 - ऐप तीनों 2.5 native-audio
     * मॉडलों (12-2025 -> 09-2025 -> -latest) पर घूमता रहा, 3.8-live पर 6 मिनट बाद पहुँचा. Google forum: -latest / 12-2025 पर
     * 27 मई 2026 से 80-90% turn 1011; मालिक के फ़ोन पर 09-2025 भी टूटा.
     *  - [known1011]: 2.5 native-audio = पहले 1011 पर ही हटाओ (2 बार का इंतज़ार नहीं)
     *  - [Broken1011]: जिस मॉडल पर 1011 आया वह [BROKEN_1011_MS] तक सबसे पीछे (हटता नहीं - आख़िरी बचा मॉडल हमेशा रहता है)
     *  - [safeOrder]: टूटे सबसे पीछे; मालिक का चुना मॉडल पहले रहता है जब तक वह सच में न टूटे
     */
    const val BROKEN_1011_MS = 6 * 3600_000L
    fun known1011(m: String) = m.startsWith("gemini-2.5-flash-native-audio")

    class Broken1011 {
        private val until = HashMap<String, Long>()
        @Synchronized fun mark(m: String, now: Long = System.currentTimeMillis()) { until[m] = now + BROKEN_1011_MS }
        @Synchronized fun isBroken(m: String, now: Long = System.currentTimeMillis()) = (until[m] ?: 0L) > now
        @Synchronized fun encode(now: Long = System.currentTimeMillis()) =
            until.filter { it.value > now }.entries.joinToString(";") { it.key + "=" + it.value }
        companion object {
            fun decode(s: String?): Broken1011 = Broken1011().also { b ->
                s.orEmpty().split(';').forEach { p ->
                    val i = p.lastIndexOf('='); if (i > 0) p.substring(i + 1).toLongOrNull()?.let { b.until[p.substring(0, i)] = it }
                }
            }
        }
    }

    /** क्रम वैसा ही, बस 1011 से टूटे मॉडल सबसे पीछे (कोई मॉडल हटता नहीं) */
    fun safeOrder(models: List<String>, broken: (String) -> Boolean): List<String> {
        val (bad, ok) = models.partition(broken)
        return ok + bad
    }

    /** मालिक ने कोई मॉडल नहीं चुना तो: 2.5 native-audio (Google की तरफ़ 1011) बाकी Live मॉडलों के बाद - सिर्फ़ बैकअप */
    fun demoteKnown1011(models: List<String>): List<String> {
        val (old, rest) = models.partition { known1011(it) }
        return if (rest.isEmpty()) models else rest + old
    }

    /** पुरानी बातचीत (handle) से जुड़ते ही 1011 = वह handle ज़हरीला (Google forum) - उसी से फिर जुड़ना = फिर 1011, नई बातचीत */
    const val RESUME_POISON_MS = 8_000L
    fun resumePoisoned(code: Int, ranMs: Long, resumed: Boolean) = code == 1011 && resumed && ranMs in 0 until RESUME_POISON_MS

    /** माइक गैप के समय की हालत - Inspector / Coding Agent को वजह पक्की करने का सबूत */
    fun gapContext(connected: Boolean, upSince: Long, now: Long, mode: String, playing: Boolean): String =
        (if (!connected || upSince <= 0) "Live जुड़ा नहीं था (दोबारा जुड़ रहा था)" else "Live जुड़ा था (${(now - upSince) / 1000}s से)") +
            " • हाल: $mode" + (if (playing) " • DK बोल रहा था" else "")

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
