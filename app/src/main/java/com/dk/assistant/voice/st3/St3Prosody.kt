package com.dk.assistant.voice.st3

/**
 * 20 नवंबर 2026 (मालिक: "लाइव बात में उतार-चढ़ाव, ठहराव नहीं दिखता... बीच में जैसे साँस लेता है, ये सब नहीं दिखता,
 * जैसे Gemini का होता है"): फ़ोन वाली आवाज़ (Supertonic 3) को इंसान जैसा बनाने के छोटे नियम. जड़ें:
 *  1. हर टुकड़े के बाद एक ही ठहराव (वाक्य 140 ms, अल्पविराम 60 ms) - इंसान वाक्य के बाद ~0.3-0.4 s, सवाल के बाद ज़्यादा,
 *     अल्पविराम पर ~0.15 s रुकता है, और हर बार बिल्कुल एक-सा नहीं. अब [pauseMs] (थोड़ा-सा फ़र्क, पर हर text का तय).
 *  2. बीच से कटे टुकड़े ("...और" से पहले) के अंत में मॉडल का नियम "." लगा देता था = हर टुकड़े पर वाक्य-अंत वाली गिरती
 *     आवाज़ + रुकना. अब ऐसे टुकड़े के अंत में "," ([softEnd]) - आवाज़ आगे जाती लगती है.
 *  3. साँस नहीं: मॉडल `<breath>` टैग पहचानता है. लंबे जवाब में ~[BREATH_EVERY] अक्षर बोलने के बाद, नए वाक्य की शुरुआत में
 *     एक हल्की साँस ([Shaper]). छोटे जवाब में कभी नहीं.
 *  4. हर वाक्य एक ही रफ़्तार में: सवाल थोड़ा धीमा, "!" थोड़ा तेज़, बहुत छोटा जवाब ("हाँ, ठीक है") थोड़ा धीमा ([speedMul]).
 * Android import नहीं -> JVM test ([St3ProsodyTest]).
 */
object St3Prosody {
    /** इतने अक्षर बोलने के बाद (नए वाक्य पर) एक साँस */
    const val BREATH_EVERY = 140
    /** इससे छोटे टुकड़े पर साँस नहीं (अटपटा) */
    const val BREATH_MIN_PIECE = 25
    const val BREATH = "<breath>"

    private val END_MARKS = setOf('.', '!', '?', ';', ':', ',', '।', '॥', '…', '"', '\'', ')', '”', '’')
    private val SENTENCE_END = setOf('.', '!', '?', '।', '॥', '…')

    private fun lastMark(t: String): Char? = t.trimEnd().trimEnd(')', '"', '\'', '”', '’').lastOrNull()

    /** टुकड़ा वाक्य के अंत पर ख़त्म हुआ? */
    fun endsSentence(t: String): Boolean = lastMark(t)?.let { it in SENTENCE_END } == true

    /**
     * टुकड़े के बाद ठहराव (ms). [pauseMs] हर text का तय (hash से ±30 ms) - test में हमेशा वही, सुनने में हर बार एक-सा नहीं.
     */
    fun pauseMs(t: String): Int {
        val base = when (lastMark(t)) {
            '?' -> 380
            '!' -> 320
            '…' -> 430
            '।', '.', '॥' -> 300
            ';', ':', '—', '–' -> 200
            ',' -> 150
            null -> 0
            else -> 40
        }
        if (base <= 40) return base
        val jitter = ((t.hashCode() and 0x7fffffff) % 61) - 30
        return base + jitter
    }

    /** बीच से कटा टुकड़ा (कोई विराम-चिह्न नहीं) -> अंत में "," (मॉडल "." न लगाए, आवाज़ आगे जाती लगे) */
    fun softEnd(t: String): String {
        val s = t.trimEnd()
        if (s.isEmpty() || s.last() in END_MARKS || s.endsWith(">")) return s
        return "$s,"
    }

    /** वाक्य की रफ़्तार (Settings वाली × यह). सवाल 0.97, "!" 1.03, बहुत छोटा 0.97, बाकी 1.0 */
    fun speedMul(t: String): Float {
        val s = t.trim()
        return when {
            lastMark(s) == '?' -> 0.97f
            lastMark(s) == '!' -> 1.03f
            s.length in 1..24 -> 0.97f
            else -> 1f
        }
    }

    /** एक जवाब के टुकड़े क्रम से - कब साँस. [reset] = नया जवाब */
    class Shaper {
        private var since = 0
        private var prevEnded = true
        @Synchronized fun reset() { since = 0; prevEnded = true }

        /** टुकड़ा -> बोलने वाला text (साँस + नरम अंत). [breath] = Settings में "साँस" चालू */
        @Synchronized fun shape(t: String, breath: Boolean = true): String {
            val s = t.trim()
            if (s.isEmpty()) return s
            val wantBreath = breath && prevEnded && since >= BREATH_EVERY && s.length >= BREATH_MIN_PIECE && !s.startsWith("<")
            if (wantBreath) since = 0
            since += s.length
            prevEnded = endsSentence(s)
            val body = softEnd(s)
            return if (wantBreath) "$BREATH $body" else body
        }
    }
}
