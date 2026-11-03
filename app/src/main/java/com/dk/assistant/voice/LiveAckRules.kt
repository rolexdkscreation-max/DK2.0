package com.dk.assistant.voice

/**
 * मालिक (नवंबर 2026): "power बटन दबाते ही DK बस एक शब्द बोले - Yes Boss / जी बोलिए - फिर Live जुड़ने वाली आवाज़ वैसी ही रहे".
 *
 * जड़: "Yes Boss" की आवाज़ सिर्फ़ Gemini TTS (Cloudflare Worker) से बनती थी. Worker का पता डिफ़ॉल्ट खाली = आवाज़ कभी नहीं बनती
 * = हर बार सिर्फ़ छोटी "टिंग". अब DK की अपनी Gemini Live आवाज़ (वही key, वही voice) से एक छोटे अलग connection पर एक बार
 * बनाकर फ़ोन में रखते हैं ([LiveAckMaker]); फिर हर बार बिना इंटरनेट तुरंत. आवाज़ बदली = फ़ाइल का नाम बदला = फिर बनती है.
 *
 * Pure logic - Android import नहीं -> JVM test ([LiveAckRulesTest]).
 */
object LiveAckRules {
    const val RATE = 24_000                 // Gemini Live की आवाज़ = PreviewPlayer (VoiceEngine.RATE)
    const val MIN_MS = 250L
    const val MAX_MS = 2_600L               // "Yes Boss" ~0.6-1.2s; लंबा = Gemini ने कुछ और जोड़ दिया
    const val SILENCE_AMP = 400
    const val PAD_MS = 40L
    /** नाकाम हुआ = इतनी देर बाद फिर (बेकार connection नहीं) */
    const val RETRY_MS = 10 * 60_000L
    /** एक बोल का इंतज़ार */
    const val TURN_TIMEOUT_MS = 12_000L

    const val INSTRUCTION = "You are only a voice. When you receive text, speak exactly that text once, in a warm, natural, " +
        "respectful tone, like a helpful assistant answering its boss. Do not add, translate or change any word. " +
        "No greeting, no question, nothing else."

    fun prompt(text: String) = "Speak exactly this and nothing else: $text"

    /** Live के मॉडल: पिछली बार जो चला वह पहले, फिर बाकी (दोहराव नहीं) */
    fun models(saved: String, fallback: List<String>): List<String> =
        (listOf(saved.trim()) + fallback).filter { it.isNotBlank() }.distinct()

    private fun words(s: String): List<String> =
        s.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    /**
     * जो बोला वह सही है? transcript न मिले = सिर्फ़ लंबाई से. मिले = उतने ही शब्द (Devanagari में "यस बॉस" भी ठीक),
     * ज़्यादा से ज़्यादा 1 ज़्यादा - "Yes Boss, how can I help you?" जैसा = नहीं.
     */
    fun ok(want: String, transcript: String, pcmBytes: Int): Boolean {
        val ms = pcmBytes * 1000L / (RATE * 2)
        if (ms !in MIN_MS..MAX_MS) return false
        if (transcript.isBlank()) return true
        val w = words(want).size; val g = words(transcript).size
        return g in 1..(w + 1)
    }

    /** आगे-पीछे की चुप्पी काटो (ज़रा सी छोड़कर) - दबाते ही आवाज़ */
    fun trim(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        if (n == 0) return pcm
        fun amp(i: Int): Int { val v = (pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8); return kotlin.math.abs(v.toShort().toInt()) }
        var a = 0; while (a < n && amp(a) < SILENCE_AMP) a++
        var b = n - 1; while (b > a && amp(b) < SILENCE_AMP) b--
        if (a >= n) return ByteArray(0)
        val pad = (RATE * PAD_MS / 1000).toInt()
        val s = (a - pad).coerceAtLeast(0); val e = (b + pad).coerceAtMost(n - 1)
        return pcm.copyOfRange(2 * s, 2 * (e + 1))
    }

    /** अभी बनाने की कोशिश करें? Live चालू = नहीं (एक key पर ज़्यादा connection नहीं - Live/caption न टूटें) */
    fun shouldMake(missing: Boolean, liveOn: Boolean, haveKey: Boolean, sinceFailMs: Long, busy: Boolean): Boolean =
        missing && !liveOn && haveKey && !busy && sinceFailMs >= RETRY_MS
}
