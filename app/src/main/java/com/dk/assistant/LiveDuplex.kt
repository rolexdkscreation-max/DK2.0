package com.dk.assistant

/**
 * नवंबर 2026 (Live जाँच #3): फ़ोन पर चलने वाला Silero VAD (इंसानी आवाज़ की संभावना 0..1) 16kHz पर ठीक 512 samples
 * की खिड़की + पिछले 64 samples (context) माँगता है; DK का माइक 40ms = 640 samples देता है. यह class 640 वाले टुकड़ों को
 * 576 (64 context + 512) वाली खिड़कियों में बाँटती है - बचे samples अगले टुकड़े में.
 * Pure logic -> JVM unit test. ONNX वाला हिस्सा: [com.dk.assistant.voice.SileroVad].
 */
class VadFramer(val window: Int = WINDOW, val context: Int = CONTEXT) {
    companion object { const val WINDOW = 512; const val CONTEXT = 64 }

    private val pending = FloatArray(window * 4)
    private var n = 0
    private val ctx = FloatArray(context)

    fun reset() { n = 0; ctx.fill(0f) }

    /** नए samples डालो; हर पूरी खिड़की पर [onWindow] (context + window = 576 floats, -1..1) */
    fun push(pcm: ShortArray, count: Int, onWindow: (FloatArray) -> Unit) {
        var i = 0
        while (i < count) {
            val take = minOf(window - n, count - i)
            for (k in 0 until take) pending[n + k] = pcm[i + k] / 32768f
            n += take; i += take
            if (n == window) {
                val x = FloatArray(context + window)
                System.arraycopy(ctx, 0, x, 0, context)
                System.arraycopy(pending, 0, x, context, window)
                System.arraycopy(x, x.size - context, ctx, 0, context)
                n = 0
                onWindow(x)
            }
        }
    }
}

/**
 * VAD की संभावना से [TurnDetector] के लिए "असरदार" आवाज़ का स्तर: इंसानी आवाज़ नहीं (पंखा / TV की धुन / खटखट) = स्तर
 * शोर जितना (बात शुरू नहीं / चल रही बात खत्म गिनी जाए); पक्की आवाज़ पर धीमी बात भी शुरू हो सके.
 * prob < 0 = VAD नहीं (model नहीं खुला / सेटिंग बंद) = पुराना RMS जैसा का तैसा.
 */
object VadLevel {
    const val SPEECH_P = 0.5f
    const val NOISE_P = 0.2f

    fun effectiveRms(rms: Double, prob: Float, noise: Double, startThr: Double, inSpeech: Boolean): Double = when {
        prob < 0f -> rms
        // पक्की इंसानी आवाज़, पर धीमी: शुरू होने लायक (दूर वाली [TurnDetector.startFloor] फिर भी लागू)
        prob >= 0.8f && rms > noise * 1.6 -> maxOf(rms, startThr * 1.05)
        prob >= SPEECH_P -> rms
        // बात चल रही, बीच का हल्का हिस्सा (साँस / धीमा शब्द): पूरा न काटो
        inSpeech && prob >= NOISE_P -> rms
        // आवाज़ इंसानी नहीं = शोर
        else -> minOf(rms, noise)
    }
}

/**
 * नवंबर 2026 (Live जाँच #1): DK बोल रहा है और कॉल-mode स्पीकर (पक्का AEC) चालू - तब Silero VAD + DK की गूँज से ऊपर
 * की आवाज़ [CONFIRM_MS] तक = यूज़र सच में बोल रहा, caption के शब्दों का इंतज़ार किए बिना तुरंत रुको (full-duplex).
 * बचाव: रोका पर [WORDS_WAIT_MS] में कोई शब्द (caption / Live का लिखा) नहीं आया = गलत रोका. [MAX_MISSES] बार = इस
 * session में यह तरीका बंद, पुराना caption वाला रास्ता.
 */
class DuplexBarge {
    companion object {
        const val SPEECH_P = 0.6f
        const val CONFIRM_MS = 200L
        const val WORDS_WAIT_MS = 2_500L
        const val MAX_MISSES = 2
    }

    var speechMs = 0L; private set
    var confirms = 0; private set
    var misses = 0; private set
    var disabled = false; private set
    private var pendingAt = 0L

    fun reset() { speechMs = 0L }
    fun resetSession() { reset(); confirms = 0; misses = 0; disabled = false; pendingAt = 0L }

    /** DK बोलते समय (शक वाली हालत) हर chunk: true = अभी रोको */
    fun feed(prob: Float, overEcho: Boolean, chunkMs: Long): Boolean {
        if (disabled || prob < 0f) { speechMs = 0L; return false }
        speechMs = when {
            prob >= SPEECH_P && overEcho -> speechMs + chunkMs
            prob < 0.3f -> 0L
            else -> speechMs
        }
        return speechMs >= CONFIRM_MS
    }

    fun onConfirm(now: Long) { confirms++; pendingAt = now; speechMs = 0L }
    fun onWords() { pendingAt = 0L }

    /** हर chunk. true = अभी-अभी बंद हुआ (log के लिए) */
    fun tick(now: Long): Boolean {
        if (pendingAt == 0L || now - pendingAt < WORDS_WAIT_MS) return false
        pendingAt = 0L; misses++
        if (misses >= MAX_MISSES && !disabled) { disabled = true; return true }
        return false
    }
}

/**
 * नवंबर 2026 (Live जाँच #4): "बात खत्म" का समझदार फ़ैसला caption (अलग connection) के लिखे पर टिका था - कमज़ोर नेट पर
 * caption पीछे / टूटा = DK अटकता (या 0.7s+ रुकता). अब caption न चले तो Live का अपना लिखा (inputTranscription, उसी
 * connection पर, कोई नया socket नहीं) इस्तेमाल हो.
 * Pure logic -> JVM unit test.
 */
object HeardPick {
    /** Live का लिखा इतना पुराना हो तो इस बात का नहीं */
    const val LIVE_FRESH_MS = 4_000L

    data class Heard(val text: String, val at: Long, val on: Boolean, val src: String)

    fun pick(
        capOn: Boolean, capText: String, capAt: Long,
        fallbackOn: Boolean, liveText: String, liveAt: Long, speechStartAt: Long, now: Long,
    ): Heard = when {
        capOn -> Heard(capText, capAt, true, "caption")
        fallbackOn && liveText.isNotBlank() && liveAt >= speechStartAt && now - liveAt <= LIVE_FRESH_MS ->
            Heard(liveText, liveAt, true, "live")
        else -> Heard(capText, capAt, false, "none")
    }
}
