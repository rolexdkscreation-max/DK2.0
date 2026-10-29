package com.dk.assistant

/**
 * DK लाइव के छोटे-छोटे फ़ैसले (v3.39) - Android पर निर्भर नहीं, पूरी तरह unit test होते हैं.
 *
 *  - SentTrace      : टूटने से पहले DK ने आख़िरी क्या भेजा (सिर्फ़ किस्म + आकार, आपकी बात का text नहीं)
 *  - Drop1007       : 1007 "invalid argument" पर - टूटी बातचीत से दोबारा नहीं, बार-बार हो तो Google Search बंद
 *  - StreamEndGate  : "बात खत्म" (audioStreamEnd) एक बात पर एक ही बार, और सिर्फ़ असली बोलने पर
 *  - DuckWatch      : पीछे की आवाज़ (गाना/वीडियो) कब वापस तेज़ हो
 *  - CaptionRotate  : caption का जुड़ाव टूटने से पहले चुपचाप नया
 *  - BtPick         : Bluetooth headset का माइक मिला? (AudioDeviceInfo की किस्में)
 */

/** टूटने से पहले भेजे आख़िरी संदेश - लॉग के लिए. आवाज़ के लगातार टुकड़े एक लाइन में गिने जाते हैं */
class SentTrace(private val keep: Int = 6, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private data class Item(val kind: String, var count: Int, var bytes: Int, var at: Long)
    private val items = ArrayDeque<Item>()

    companion object {
        private val ID = Regex("\"id\"\\s*:\\s*\"([^\"]{1,40})\"")
        private val NAME = Regex("\"name\"\\s*:\\s*\"([^\"]{1,40})\"")

        /** संदेश की किस्म - text/आवाज़ की सामग्री कभी नहीं */
        fun kind(msg: String): String {
            val head = msg.take(200)
            return when {
                head.startsWith("{\"setup\"") -> "setup"
                head.contains("\"toolResponse\"") -> "toolResponse(" +
                    listOfNotNull(NAME.find(msg)?.groupValues?.get(1), ID.find(msg)?.groupValues?.get(1)?.let { "id=$it" }).joinToString(" ") + ")"
                head.contains("\"audioStreamEnd\"") -> "audioStreamEnd"
                head.contains("\"audio\"") -> "audio"
                head.contains("\"video\"") -> "video"
                head.contains("\"realtimeInput\"") && head.contains("\"text\"") -> "text"
                head.contains("\"clientContent\"") -> "clientContent"
                else -> "other"
            }
        }
    }

    @Synchronized fun reset() = items.clear()

    @Synchronized fun sent(msg: String) {
        val k = kind(msg)
        val now = clock()
        val last = items.lastOrNull()
        if (last != null && last.kind == k && (k == "audio" || k == "video")) { last.count++; last.bytes += msg.length; last.at = now; return }
        items.addLast(Item(k, 1, msg.length, now))
        while (items.size > keep) items.removeFirst()
    }

    /** "setup 3KB (5.1s पहले) • audio×25 (0.0s पहले) • text 40B (0.2s पहले)" */
    @Synchronized fun summary(): String {
        if (items.isEmpty()) return "कुछ नहीं भेजा"
        val now = clock()
        return items.joinToString(" • ") { i ->
            val size = if (i.bytes >= 1024) "${i.bytes / 1024}KB" else "${i.bytes}B"
            val ago = "%.1f".format((now - i.at) / 1000.0)
            (if (i.count > 1) "${i.kind}×${i.count}" else i.kind) + " $size (${ago}s पहले)"
        }
    }
}

/**
 * 1007 = सर्वर को DK का कोई संदेश गलत लगा. टूटी बातचीत (resume handle) से दोबारा जुड़ने पर वही गड़बड़ फिर आती है -
 * इसलिए: नई बातचीत; वही बात दोबारा सिर्फ़ एक बार; 2 मिनट में 2 बार हो तो Google Search बंद (जाना-माना 1007 कारण).
 */
class Drop1007(private val windowMs: Long = 120_000L) {
    data class Action(val freshSession: Boolean, val disableSearch: Boolean, val count: Int)
    private val hits = ArrayDeque<Long>()

    @Synchronized fun hit(now: Long, searchOn: Boolean): Action {
        hits.addLast(now)
        while (hits.isNotEmpty() && now - hits.first() > windowMs) hits.removeFirst()
        return Action(freshSession = true, disableSearch = searchOn && hits.size >= 2, count = hits.size)
    }

    @Synchronized fun reset() = hits.clear()
}

/**
 * "बात खत्म" (audioStreamEnd) सिर्फ़ एक बार - फिर से तभी जब नई असली आवाज़ जाए.
 * बहुत छोटी आवाज़ (खटखट/खाँसी) पर और बिना caption शब्दों के नहीं - सर्वर का अपना VAD संभालेगा.
 */
class StreamEndGate(private val minSpeechMs: Int = 350) {
    @Volatile var ended = false
        private set

    /** असली आवाज़ भेजी - अगली "बात खत्म" फिर से भेज सकते हैं */
    fun onReal() { ended = false }

    /** फ़ोन VAD ने "बोलना खत्म" पकड़ा */
    fun onSpeechEnd(speechMs: Int, hasWords: Boolean): Boolean {
        if (ended) return false
        if (speechMs < minSpeechMs && !hasWords) return false
        ended = true
        return true
    }

    /** 1s की चुप्पी के बाद stream बंद करते समय */
    fun onSilenceEnd(): Boolean { if (ended) return false; ended = true; return true }

    fun reset() { ended = false }
}

/** पीछे की आवाज़ (गाना/वीडियो) धीमी है - कब वापस तेज़ करें */
object DuckWatch {
    const val QUIET_MS = 3_000L

    /**
     * @param quietSince आख़िरी बार यूज़र बोला या DK बोला (ms)
     * यूज़र नहीं बोल रहा, DK नहीं बोल रहा, जवाब नहीं बन रहा, और 3 सेकंड से सब शांत = वापस तेज़
     */
    fun shouldRelease(ducked: Boolean, userSpeaking: Boolean, dkPlaying: Boolean, generating: Boolean, quietSince: Long, now: Long): Boolean =
        ducked && !userSpeaking && !dkPlaying && !generating && quietSince > 0 && now - quietSince >= QUIET_MS
}

/** caption का जुड़ाव ~61 सेकंड पर सर्वर काटता है - उससे पहले चुपचाप नया जोड़ो */
object CaptionRotate {
    const val AT_MS = 50_000L
    const val GIVE_UP_MS = 58_000L

    /** यूज़र बोल रहा हो (आख़िरी असली आवाज़ 1.2s के अंदर) तो थोड़ा रुको; 58s तक रुक चुके तो बदल ही दो */
    fun now(ageMs: Long, lastRealAudioAgoMs: Long): Boolean =
        ageMs >= AT_MS && (lastRealAudioAgoMs > 1_200L || ageMs >= GIVE_UP_MS)
}

/** Bluetooth headset के माइक की किस्में (android.media.AudioDeviceInfo के नंबर) */
object BtPick {
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLE_HEADSET = 26

    /** बात करने के डिवाइसों में से Bluetooth वाला (पहले पुराना SCO headset, फिर नया BLE) - index, या -1 */
    fun pick(types: List<Int>): Int {
        val sco = types.indexOf(TYPE_BLUETOOTH_SCO)
        return if (sco >= 0) sco else types.indexOf(TYPE_BLE_HEADSET)
    }

    /**
     * v3.6: "कॉल वाला रास्ता" सच में headset पर बना? (फ़ोन ने हाँ कहा पर SCO नहीं जुड़ा = आवाज़ ऊपर वाले
     * छोटे कॉल-स्पीकर/earpiece से, धीमी - owner की शिकायत). type = अभी का बात वाला डिवाइस (null = कोई नहीं)
     */
    fun routedToHeadset(type: Int?): Boolean = type == TYPE_BLUETOOTH_SCO || type == TYPE_BLE_HEADSET

    /** SDK < 31: SCO की ख़बर (AudioManager.SCO_AUDIO_STATE_CONNECTED = 1) */
    fun scoConnected(state: Int): Boolean = state == 1

    /** कितनी बार जाँचें (ms, जुड़ने के बाद): पहली बार में न बने तो एक बार फिर कोशिश, फिर फ़ोन के speaker पर */
    val VERIFY_AT_MS = longArrayOf(1500L, 4000L)
}
