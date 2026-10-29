package com.dk.assistant

/**
 * v3.5+: DK लाइव में बीच में बोलना - मालिक का नियम:
 *  - DK बोल रहा है और यूज़र "रुको / बस / चुप / stop" बोले = तुरंत रुको (पुराना जवाब बंद).
 *  - कुछ और बोले (अगला सवाल) = जवाब मत काटो. सवाल याद रखो, जवाब पूरा होते ही उसी का जवाब दो.
 *
 * Android import नहीं -> JVM test.
 */
object LiveStop {
    /** ये अकेले भी आएँ तो रोकना (छोटी बात में कहीं भी) */
    private val STRONG = listOf(
        "रुको", "रुकिए", "रुकिये", "रुक जाओ", "रुक जा", "ठहरो", "चुप", "चुप हो जाओ", "चुप रहो", "स्टॉप", "बंद करो", "मत बोलो",
        "बोलना बंद", "stop", "ruko", "ruk jao", "ruk ja", "chup", "band karo", "shut up", "be quiet",
        "رکو", "رک جاؤ", "رکیے", "ٹھہرو", "چپ", "بند کرو", "مت بولو",
    )
    /** ये सिर्फ़ तब जब पूरी बात बस इन्हीं (और भराव) की हो - "बस का टाइम" (bus) जैसे सवाल न रुकें */
    private val WEAK = setOf("बस", "रहने", "छोड़ो", "छोडो", "wait", "bas", "rehne", "chhodo", "بس", "رہنے", "چھوڑو", "hold")
    private val FILLER = setOf(
        "अरे", "ओके", "ठीक", "है", "डीके", "dk", "यार", "प्लीज़", "प्लीज", "please", "अभी", "एक", "मिनट", "सेकंड", "करो", "दो", "जाओ",
        "नहीं", "हो", "भी", "बस", "ok", "okay", "arey", "are", "yaar", "abhi", "ek", "minute", "second", "karo", "do", "jao", "nahi", "ho",
        "اوکے", "ٹھیک", "ہے", "یار", "ابھی", "ایک", "منٹ", "کرو", "دو", "جاؤ", "نہیں",
    )
    private val NOT_STOP = listOf("मत रुको", "रुको मत", "रुकना मत", "mat ruko", "ruko mat", "don't stop", "dont stop", "do not stop",
        "never stop", "मत रुकना", "مت رکو", "رکو مت")
    private val SPLIT = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    fun words(t: String): List<String> = t.lowercase().split(SPLIT).filter { it.isNotBlank() }

    /** NOT_STOP भी उसी तरह तोड़ो जैसे यूज़र की बात - "don't" -> "don t" (पहले मेल नहीं खाता था) */
    private val NOT_STOP_W = NOT_STOP.map { words(it).joinToString(" ") }

    /** यूज़र ने DK को रोका? */
    fun isStop(t: String): Boolean {
        val w = words(t)
        if (w.isEmpty()) return false
        val joined = " " + w.joinToString(" ") + " "
        if (NOT_STOP_W.any { joined.contains(" $it ") }) return false     // "मत रुको, बोलते रहो"
        if (w.size <= 6 && STRONG.any { joined.contains(" $it ") }) return true
        // लंबी बात भी "रुको ..." से शुरू हो तो रोकना
        if (STRONG.any { joined.startsWith(" $it ") }) return true
        if (w.any { it in WEAK } && w.size <= 5 && w.all { it in WEAK || it in FILLER || STRONG.contains(it) }) return true
        return false
    }
}

/** DK के बोलते समय पूछे सवाल - जवाब पूरा होते ही एक-एक करके (एक साथ जोड़कर) Gemini को */
class AskQueue(private val max: Int = 3) {
    private val items = ArrayList<String>()

    @Synchronized fun add(t: String): Boolean {
        val s = t.trim().trimEnd('…').trim()
        if (s.isEmpty()) return false
        // वही बात दोबारा (caption ने दो बार लिखा) = एक ही
        if (items.any { it.equals(s, ignoreCase = true) || it.contains(s, ignoreCase = true) }) return false
        items.removeAll { s.contains(it, ignoreCase = true) }
        items.add(s)
        while (items.size > max) items.removeAt(0)
        return true
    }

    @Synchronized fun isEmpty() = items.isEmpty()
    @Synchronized fun size() = items.size
    @Synchronized fun clear() = items.clear()

    /** सब सवाल एक बार में (एक से ज़्यादा = "और" से जोड़कर), कतार खाली */
    @Synchronized fun drain(): String? {
        if (items.isEmpty()) return null
        val t = if (items.size == 1) items[0] else items.joinToString(" । और ")
        items.clear()
        return t
    }

    companion object {
        /** सवाल भेजने लायक समय? DK चुप, कुछ बन नहीं रहा, कोई tool नहीं चल रहा, यूज़र अभी नहीं बोल रहा */
        fun readyToSend(playing: Boolean, generating: Boolean, toolsRunning: Boolean, userSpeaking: Boolean,
                        holding: Boolean, quietMs: Long, minQuietMs: Long = 450): Boolean =
            !playing && !generating && !toolsRunning && !userSpeaking && !holding && quietMs >= minQuietMs
    }
}
