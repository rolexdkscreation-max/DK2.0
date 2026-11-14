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

    /**
     * नवंबर 2026 (Smart turn): Roman Hinglish + English के अधूरे आख़िरी शब्द. पहले सिर्फ़ देवनागरी पहचानी जाती थी -
     * "kal meeting hai to…" / "set an alarm for…" / "मेरे फ़ोन की…" पर बात 0.5s में कट जाती थी.
     */
    private val CONTINUES_MORE = Regex("(?i)(\\s|^)(का|की|के|में|से|ने|तक|वाला|वाली|वाले|यानी|aur|to|toh|ki|ke|ka|ko|kyunki|kyuki|kyonki|lekin|par|pe|phir|fir|matlab|jaise|agar|jab|ya|yaani|suno|dekho|ek baat|ye batao|yeh batao|batao ki|umm*|hmm*|uh+|um+|if|when|then|like|for|about|of|with|the|a|an|my|your|from|into|is|are|was|were|will|can|could|should|would|to|and then)\\s*[,.…]*$")
    /** "अम्म / उह" जैसे सोचने वाले शब्द */
    private val FILLER = Regex("(?i)(\\s|^)(अम्म+|उम्म+|हम्म+|उह+|अ+|ए+)\\s*[,.…]*$")

    fun continues(caption: String): Boolean {
        if (caption.isBlank()) return false
        val t = caption.trim()
        return CONTINUES.containsMatchIn(t) || CONTINUES_MORE.containsMatchIn(t) || FILLER.containsMatchIn(t)
    }

    /** v3.152: caption का आख़िरी text आपकी आख़िरी आवाज़ से इतना पीछे नहीं - यानी वह आपकी पूरी बात है */
    const val CAPTION_STALE_MS = 450L
    fun captionFresh(textAt: Long, lastVoiceAt: Long): Boolean = textAt > 0 && (lastVoiceAt <= 0 || textAt >= lastVoiceAt - CAPTION_STALE_MS)

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
    private val NOT_DONE = setOf("को", "जो", "वो", "सो", "दो", "दोनों", "तो", "to", "toh", "ko", "jo", "wo", "woh", "so", "do", "dono")
    /** Roman Hinglish: "karo / batao / kholiye / hai / tha / chahiye / kya" */
    private val DONE_LATIN = Regex("(?i)^[a-z]*(o|iye|ie|ao|hai|hain|tha|thi|ho|hoga|hogi|chahiye|kya|kaise|kahan|kab|kyun|kyon|please|plz|theek|thik|haan|nahi|nahin|bas|dena|lena|karna|batana|dikhana|sunana)$")
    private val IMPERATIVE_LATIN = Regex("(?i)^[a-z]+(o|iye|ie|dena|lena|karna|dikhana|batana|sunana)$")
    /** "Can you…" / "What is…" - दो शब्द का अंग्रेज़ी सवाल-शुरू पूरा नहीं */
    private val EN_OPENER = setOf("can", "could", "will", "would", "do", "does", "did", "is", "are", "should", "what", "how", "where", "why", "when", "who", "which", "tell", "please", "let")
    private val DONE_EN = Regex("(?i)\\b(it|this|that|now|please|me|you|him|her|them|up|down|on|off|done|thanks|thank you|yes|no|okay|ok)$")

    /**
     * नवंबर 2026 (DKI-70D747 / DKI-87912D): "Map में सागर नर्सरी search करो ... और वहाँ navigation start करो" -
     * "करो" पर 0.36s चुप्पी में बात कट जाती थी (एक हुक्म 3 टुकड़े). लंबे हुक्म में लोग "और ..." जोड़ते हैं, इसलिए
     * हुक्म वाले शब्द (ओ / िए / दो) पर जल्दी काटना सिर्फ़ छोटी बात (<= [EARLY_CMD_MAX_WORDS] शब्द) में. सवाल वैसे ही तेज़.
     */
    const val EARLY_CMD_MAX_WORDS = 3
    private val IMPERATIVE = Regex("(ओ|ो|िए|िये|ीजिए|देना|लेना|करना|दिखाना|सुनाना|बताना)$")

    fun earlyOk(caption: String): Boolean {
        if (!complete(caption)) return false
        val t = caption.trim()
        if (t.endsWith("?")) return true
        val words = t.trimEnd('.', ',', '।', '…', ' ').split(Regex("\\s+")).filter { it.isNotBlank() }
        val last = words.last()
        return !(IMPERATIVE.containsMatchIn(last) || IMPERATIVE_LATIN.matches(last)) || words.size <= EARLY_CMD_MAX_WORDS
    }

    /** 14 नवंबर 2026: एक ही शब्द और वह खुद पूरा जवाब नहीं ("हाँ / नहीं / ठीक / ok / धन्यवाद" जैसे नहीं) = बात का टुकड़ा */
    private val ONE_WORD_DONE = Regex("(?i)^(हाँ|हां|नहीं|नही|ना|जी|ठीक|अच्छा|ओके|धन्यवाद|शुक्रिया|बस|रुको|चुप|हेलो|हैलो|नमस्ते|yes|no|ok|okay|haan|nahi|stop|thanks|hello|hi|hey)[?.!।]*$")
    fun fragment(caption: String): Boolean {
        val words = caption.trim().trimEnd('.', ',', '।', '…', ' ').split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size == 1 && !caption.trim().endsWith("?") && !ONE_WORD_DONE.matches(words[0])
    }

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
        if (last in NOT_DONE || last.lowercase() in NOT_DONE) return false
        if (words.size <= 2 && words.first().lowercase() in EN_OPENER) return false
        return DONE_HI.containsMatchIn(last) || DONE_EN.containsMatchIn(t) || DONE_LATIN.matches(last)
    }

    /**
     * नवंबर 2026 (Smart turn - adaptive चुप्पी): एक तय 500ms की जगह बात के हिसाब से फ़ोन कितनी चुप्पी पर "खत्म" माने.
     * Gemini docs का Hybrid VAD: फ़ोन का audioStreamEnd = तुरंत फ़ैसला; server का silenceDurationMs सिर्फ़ बचाव.
     *  - वाक्य साफ़ पूरा (caption ताज़ा) = [EARLY_END_MS] (लंबी बात में [base])
     *  - "और / to / for / की / umm" पर रुके = [CONT_END_MS] (सोचने का समय - बात न कटे)
     *  - caption अभी आख़िरी शब्द तक नहीं पहुँचा = [CAPTION_WAIT_MS] तक रुको (पूरा/अधूरा पता चले)
     *  - बाक़ी = [base], आपकी आदत के ठहराव (typicalPauseMs) से थोड़ा ज़्यादा, [MAX_NEUTRAL_END_MS] तक
     */
    const val CONT_END_MS = 800
    const val CAPTION_WAIT_MS = 700
    /** caption आपकी आख़िरी आवाज़ के बाद बदला और इतनी देर से वैसा ही = आख़िरी शब्द लिखे जा चुके */
    const val CAPTION_SETTLE_MS = 250
    const val MAX_NEUTRAL_END_MS = 800

    /**
     * पीछे का शोर (पंखा / TV की बातें) बात खत्म नहीं होने देता: बोलने जितनी तेज़ आवाज़ [NOISE_END_MS] से नहीं और
     * caption में [NOISE_CAPTION_QUIET_MS] से कोई नया शब्द नहीं = आप चुप हैं, बाक़ी शोर है -> "बात खत्म".
     * (धीमे बोल रहे हों तो caption बदलता रहता है - बात नहीं कटती)
     */
    const val NOISE_END_MS = 1_000
    const val NOISE_CAPTION_QUIET_MS = 600L
    fun noiseTail(now: Long, lastTalkAt: Long, captionAt: Long, needMs: Int, captionOn: Boolean): Boolean =
        captionOn && lastTalkAt > 0 && captionAt > 0 && now - lastTalkAt >= maxOf(NOISE_END_MS, needMs + 200) &&
            now - captionAt >= NOISE_CAPTION_QUIET_MS

    /**
     * @param lastTalkAt आख़िरी बार बोलने जितनी तेज़ आवाज़ ([TurnDetector.lastTalkAt]) - शोर का झोंका नहीं
     * @return इस पल "बात खत्म" के लिए कितनी चुप्पी (ms)
     */
    fun adaptiveEndMs(caption: String, captionAt: Long, lastTalkAt: Long, speechMs: Int, typicalPauseMs: Int,
                      captionOn: Boolean, base: Int = 500, now: Long = 0L): Int {
        val long = speechMs >= LONG_SPEECH_MS
        val learned = if (typicalPauseMs > 0) (typicalPauseMs * 1.25 + 60).toInt() else 0
        val neutral = maxOf(if (long) LONG_END_SILENCE_MS else base, learned).coerceAtMost(MAX_NEUTRAL_END_MS)
        if (!captionOn || caption.isBlank()) return neutral
        if (continues(caption)) return CONT_END_MS
        // caption आपकी आख़िरी आवाज़ के बाद बदला = आख़िरी शब्द (लगभग) लिखे जा चुके
        val heard = captionAt > 0 && captionAt >= lastTalkAt
        if (heard && complete(caption)) return if (long) base.coerceAtMost(neutral) else if (earlyOk(caption)) EARLY_END_MS else base
        // 14 नवंबर 2026 (मालिक: "DK पहले पूरा सुने, आधी बात पर जवाब न दे"): सिर्फ़ एक अधूरा शब्द ("मेरा" / "वो") = आगे बोलेंगे
        if (heard && fragment(caption)) return CONT_END_MS
        if (heard && now - captionAt >= CAPTION_SETTLE_MS) return neutral
        // पूरा नहीं लगता / caption पीछे: Live caption आवाज़ से ~0.4-0.6s पीछे चलता है - "…और" लिखा जाए उतना रुको
        return maxOf(neutral, CAPTION_WAIT_MS)
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
