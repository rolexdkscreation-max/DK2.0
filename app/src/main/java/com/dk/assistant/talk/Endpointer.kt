package com.dk.assistant.talk

/**
 * 18 नवंबर 2026 (मालिक: "मेरे रुकने के बाद करीब 700-800 ms की चुप्पी = बोलना खत्म, यह समय सेटिंग में बदल सके"):
 * हल्का, एक ही नियम वाला VAD. हर 40 ms के टुकड़े का "बोली-अंक" (0..1) - Silero की संभावना, या वह न हो तो आवाज़ की ताकत
 * ([rmsScore]). Android import नहीं -> JVM test ([EndpointerTest]).
 *
 *  - शुरू: लगातार [startMs] बोली (एक टुकड़े की चूक माफ़) -> [Ev.START]
 *  - खत्म: शुरू होने के बाद [silenceMs] लगातार चुप्पी -> [Ev.END] (बोली [minSpeechMs] से कम थी = [Ev.DISCARD], खाँसी/खटका)
 *  - बहुत लंबी बात ([maxMs]) -> [Ev.END] (Whisper 30 s तक ही देखता है)
 *  - [strict] (DK बोल रहा है): ऊँची सीमा और लंबा शुरू - DK की अपनी गूँज से बीच में न रुके
 */
class Endpointer(
    var silenceMs: Long = TalkPrefs.SILENCE_DEF,
    val chunkMs: Long = 40,
    val startMs: Long = 200,
    val strictStartMs: Long = 320,
    val minSpeechMs: Long = 300,
    val maxMs: Long = 28_000,
) {
    enum class Ev { NONE, START, END, DISCARD }

    var started = false; private set
    private var run = 0L          // लगातार बोली (शुरू होने से पहले)
    private var gap = 0           // शुरू से पहले माफ़ की गई चूक
    private var quiet = 0L        // शुरू होने के बाद लगातार चुप्पी
    private var voiced = 0L       // शुरू होने के बाद कुल बोली
    private var total = 0L        // शुरू होने के बाद कुल समय
    var strict = false

    fun reset() { started = false; run = 0; gap = 0; quiet = 0; voiced = 0; total = 0 }

    /** बोली का कुल समय (शुरू से) - छोटा = शायद शोर */
    val voicedMs: Long get() = voiced + (if (started) 0 else run)

    fun feed(score: Float): Ev {
        val on = if (strict) STRICT_ON else ON
        if (!started) {
            if (score >= on) { run += chunkMs; gap = 0 }
            else if (run > 0 && gap == 0 && score >= OFF) { gap = 1 }     // एक हल्की चूक माफ़
            else { run = 0; gap = 0 }
            if (run >= (if (strict) strictStartMs else startMs)) {
                started = true; voiced = run; total = run; quiet = 0; run = 0
                return Ev.START
            }
            return Ev.NONE
        }
        total += chunkMs
        // बात शुरू होने के बाद नीचे वाली सीमा (शब्दों के बीच की हल्की आवाज़ भी बात ही)
        if (score >= OFF) { voiced += chunkMs; quiet = 0 } else quiet += chunkMs
        if (quiet >= silenceMs || total >= maxMs) {
            val ok = voiced >= minSpeechMs
            reset()
            return if (ok) Ev.END else Ev.DISCARD
        }
        return Ev.NONE
    }

    companion object {
        const val ON = 0.5f
        const val STRICT_ON = 0.8f
        const val OFF = 0.35f

        /**
         * Silero न खुले तो: आवाज़ की ताकत (RMS, 16-bit) -> अंक. [noise] = कमरे का आम शोर (धीरे-धीरे नापा हुआ).
         * शोर के 3 गुना या 900 से ऊपर = पक्की बोली (1.0), 2 गुना या 500 = हल्की (0.4).
         */
        fun rmsScore(rms: Double, noise: Double): Float = when {
            rms >= maxOf(900.0, noise * 3) -> 1f
            rms >= maxOf(500.0, noise * 2) -> 0.4f
            else -> 0f
        }

        /** कमरे का शोर: सिर्फ़ चुप्पी वाले टुकड़ों से, धीरे-धीरे (बोली से नहीं बढ़ता) */
        fun nextNoise(noise: Double, rms: Double, speech: Boolean): Double =
            if (speech) noise else (noise * 0.95 + rms * 0.05).coerceIn(50.0, 4000.0)

        fun rms(pcm: ShortArray, n: Int): Double {
            if (n <= 0) return 0.0
            var s = 0.0
            for (i in 0 until n) { val v = pcm[i].toDouble(); s += v * v }
            return Math.sqrt(s / n)
        }
    }
}
