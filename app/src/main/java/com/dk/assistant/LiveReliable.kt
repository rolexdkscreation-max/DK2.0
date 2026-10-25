package com.dk.assistant

import java.util.Calendar
import java.util.TimeZone

/**
 * AI की limit (429) को पहचानना।
 * OpenRouter का "free-models-per-day" पूरे खाते की रोज़ की limit है - उसी key के बाकी फ्री मॉडल भी 429 देंगे,
 * इसलिए उन्हें आज़माना बेकार है और key को अगले दिन (UTC आधी रात = भारत में सुबह 5:30) तक रोकना है।
 */
object RateLimit {
    private val DAILY = Regex("(?i)(per[-_ ]?day|daily|requests? per day|PerDay|free-models-per-day|quota exceeded for metric.*day)")

    /** पूरी key/खाते की रोज़ की limit खत्म */
    fun daily(e: Throwable?): Boolean {
        val x = e as? AiException ?: return false
        return x.code == 429 && DAILY.containsMatchIn(x.message.orEmpty())
    }

    /** limit पूरी key की है (मॉडल की नहीं) - उसी key के दूसरे मॉडल मत आज़माओ */
    fun accountWide(e: Throwable?): Boolean = daily(e)

    /** अगली UTC आधी रात (ms) */
    fun nextUtcMidnight(now: Long): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** बार-बार 429: 1 → 2 → 4 → 8 → 15 मिनट (हर बार उसी मॉडल को मारते रहने से बचो) */
    fun backoffMs(strikes: Int, retryAfterSec: Int = 0): Long {
        val base = 60_000L shl (strikes.coerceIn(1, 5) - 1)
        return maxOf(minOf(base, 15 * 60_000L), retryAfterSec * 1000L)
    }

    /** "सुबह 5:30 बजे" जैसा - फ़ोन के समय से */
    fun clock(at: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz); c.timeInMillis = at
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        val part = when (h) { in 4..11 -> "सुबह"; in 12..15 -> "दोपहर"; in 16..19 -> "शाम"; else -> "रात" }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$part $h12:${m.toString().padStart(2, '0')} बजे"
    }
}

/** router: "चलो ठीक है", "अच्छा डीके" जैसे भराव वाले हिस्से - इनमें कोई काम नहीं */
object Filler {
    private val WORDS = setOf(
        "चलो", "चल", "ठीक", "है", "हैं", "अच्छा", "अच्छे", "ओके", "ok", "okay", "हाँ", "हां", "हा", "हम्म", "हम", "अरे", "सुनो", "सुन",
        "यार", "तो", "अब", "जी", "भाई", "बॉस", "boss", "डीके", "dk", "डी", "के", "बढ़िया", "बढिया", "वाह", "हेलो", "hello",
        "hi", "हाय", "ज़रा", "जरा", "प्लीज़", "प्लीज", "please", "थैंक्यू", "thanks", "धन्यवाद", "शुक्रिया", "fine", "good",
    )

    private fun words(t: String) = t.lowercase().replace(Regex("[,.!?।\"'-]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    /** पूरा हिस्सा सिर्फ़ भराव है (कोई काम/सवाल नहीं) */
    fun isFiller(clause: String): Boolean { val w = words(clause); return w.isNotEmpty() && w.all { it in WORDS } }

    /** शुरू के भराव वाले शब्द हटाओ ("चलो ठीक है होम स्क्रीन पे आओ" → "होम स्क्रीन पे आओ"); सब भराव हो तो जैसा था */
    fun stripLead(t: String): String {
        val parts = t.trim().split(Regex("\\s+"))
        var i = 0
        while (i < parts.size && parts[i].lowercase().trim(',', '.', '!', '?', '।') in WORDS) i++
        if (i == 0 || i >= parts.size) return t.trim()
        return parts.drop(i).joinToString(" ").trimStart(',', ' ', '.')
    }
}

/** बोलना कब "खत्म" मानें */
object TurnEnd {
    /** वाक्य इन शब्दों पर रुका = आप अभी आगे बोलेंगे (Gemini को "बात खत्म" मत बोलो, उसका VAD तय करे) */
    private val CONTINUES = Regex("(?i)(\\s|^)(और|तो|कि|लेकिन|पर|फिर|मतलब|जैसे|क्योंकि|या|अगर|जब|ये बताओ|यह बताओ|बताओ कि|सुनो|देखो|एक बात|and|so|but|because|or)\\s*[,.]*$")

    fun continues(caption: String): Boolean = caption.isNotBlank() && CONTINUES.containsMatchIn(caption.trim())

    /** लंबी बात (2.5 सेकंड से ज़्यादा) में बीच का ठहराव लंबा होता है - तब 0.55 नहीं, 0.8 सेकंड चुप्पी पर "खत्म" */
    const val LONG_SPEECH_MS = 2_500
    const val LONG_END_SILENCE_MS = 800

    /**
     * v3.66 (तेज़ी): वाक्य साफ़ पूरा लगा (आखिरी शब्द "करो / बताओ / है / था / चाहिए / ?" जैसा) तो 0.55 की जगह
     * 0.36 सेकंड चुप्पी पर ही "बात खत्म" - जवाब ~0.2 सेकंड पहले. आप फिर भी आगे बोलें तो Gemini पुराना जवाब
     * रोककर नई बात सुनता है (START_OF_ACTIVITY_INTERRUPTS) - बात कटती नहीं.
     */
    const val EARLY_END_MS = 360
    /** इतने से कम शब्द = पता नहीं पूरा है या नहीं (जल्दी मत काटो) */
    const val EARLY_MIN_WORDS = 2
    private val DONE_HI = Regex("(ओ|ो|िए|िये|ीजिए|है|हैं|हूँ|हूं|था|थी|थे|गा|गी|गे|चाहिए|देना|लेना|करना|बताना|सुनाना|दिखाना|क्या|कैसे|कहाँ|कब|क्यों|प्लीज़|प्लीज|धन्यवाद|शुक्रिया|ठीक|हाँ|नहीं)$")
    /** "ओ" पर खत्म पर अधूरे (को / जो / वो / सो) */
    private val NOT_DONE = setOf("को", "जो", "वो", "सो", "दो", "दोनों", "तो")
    private val DONE_EN = Regex("(?i)\\b(it|this|that|now|please|me|you|him|her|them|up|down|on|off|done|thanks|thank you|yes|no|okay|ok)$")

    /** caption का text पूरा वाक्य लगता है (आखिरी शब्द से) - "और / तो / कि" वाले continues() पहले ही रोकता है */
    fun complete(caption: String): Boolean {
        val t = caption.trim().trimEnd('.', ',', '।', '…', ' ')
        if (t.isEmpty() || continues(caption)) return false
        if (caption.trim().endsWith("?")) return true
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < EARLY_MIN_WORDS) return false
        val last = words.last()
        // बीच में कटा शब्द (caption अभी लिख रहा है) - जैसे "बता" -> पूरा नहीं मानते: कम से कम 2 अक्षर
        if (last.length < 2) return false
        if (last in NOT_DONE) return false
        return DONE_HI.containsMatchIn(last) || DONE_EN.containsMatchIn(t)
    }
}

/** वही पहचानी बात router में बार-बार न जाए */
class RouteDedupe(private val windowMs: Long = 15_000L) {
    private var last = ""; private var at = 0L
    @Synchronized fun fresh(text: String, now: Long): Boolean {
        val k = text.trim().lowercase().replace(Regex("[,.!?।\\s]+"), " ").trim()
        if (k.isEmpty()) return false
        if (k == last && now - at < windowMs) return false
        last = k; at = now; return true
    }
    @Synchronized fun reset() { last = ""; at = 0L }
}

/** फ़ोन पर किए काम की पक्की जाँच - बिना सबूत "हो गया" नहीं */
object ActionCheck {
    private val DELETE_WORDS = Regex("(?i)(डिलीट|delete|हटा|मिटा|unsend)")
    private val MSG_WORDS = Regex("(?i)(मैसेज|मेसेज|message|msg|चैट|whatsapp|व्हाट्सएप|व्हाट्सऐप|संदेश)")

    /** यह मैसेज डिलीट करने का काम है */
    fun isDeleteMessage(goal: String): Boolean = DELETE_WORDS.containsMatchIn(goal) && MSG_WORDS.containsMatchIn(goal)

    private val DELETED = listOf(
        "you deleted this message", "this message was deleted", "आपने यह मैसेज डिलीट किया", "आपने यह संदेश हटा दिया",
        "यह संदेश हटा दिया गया", "यह मैसेज डिलीट कर दिया गया", "message deleted", "deleted for everyone", "आपने यह मैसेज हटाया",
    )

    /** स्क्रीन पर "मैसेज डिलीट हुआ" दिख रहा है */
    fun deleteConfirmed(screen: String): Boolean { val s = screen.lowercase(); return DELETED.any { s.contains(it.lowercase()) } }
}
