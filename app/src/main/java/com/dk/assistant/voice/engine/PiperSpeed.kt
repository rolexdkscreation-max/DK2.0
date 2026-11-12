package com.dk.assistant.voice.engine

import android.content.Context

/**
 * नवंबर 2026 (मालिक का फ़ोन-नाप: "टेस्ट 29.95 s में पहली आवाज़; बातचीत में 75 अक्षर का वाक्य 33.87 s में, आवाज़ 8.52 s").
 * यानी HQ (Supertonic-3, 8 चरण) इस फ़ोन पर 1 s आवाज़ ~4 s में बनाता था - बोलने से 4 गुना धीमा. ऐसे में टुकड़ों में
 * बोलना भी काम नहीं करता (हर टुकड़ा बजने से पहले अगला तैयार नहीं), और Live की आवाज़ हर बार आगे निकल जाती थी.
 *
 * sandbox नाप (वही मॉडल, 2 धागे, "नमस्ते, मैं DK हूँ। …" 3.4 s आवाज़): 8 चरण 1.13 s • 5 = 0.77 • 4 = 0.67 • 3 = 0.50 • 2 = 0.38 s.
 * Piper medium (VITS, प्रियंवदा) उसी वाक्य पर 0.24 s, छोटे टुकड़े ("जी बिल्कुल,") पर 0.06 s बनाम HQ 0.79 s.
 *
 * अब (pure, JVM test):
 * - HQ के चरण अपने-आप: शुरू 4; हर वाक्य की रफ़्तार (बनने का समय / आवाज़ की लंबाई) देखकर धीमा = कम चरण, बहुत तेज़ = एक चरण ज़्यादा.
 * - धागे सिर्फ़ फ़ोन के तेज़ (बड़े) कोर जितने - धीमे छोटे कोर मिलाने से ONNX हर चरण पर सबसे धीमे कोर का इंतज़ार करता है.
 * - सबसे कम चरण पर भी धीमा = साफ़ सलाह: Piper medium आवाज़ (कई गुना तेज़).
 */
object PiperSpeed {
    /** ज़्यादा से कम (8 = पहले वाली हाई क्वालिटी) */
    val STEPS = listOf(8, 5, 4, 3, 2)
    const val START_STEPS = 4
    /** बनने का समय / आवाज़ की लंबाई - इससे ऊपर = धीमा (टुकड़ों के बीच रुकावट), एक चरण कम */
    const val SLOW_RTF = 0.8
    /** इससे नीचे = बहुत तेज़, एक चरण ज़्यादा (बेहतर आवाज़) */
    const val FAST_RTF = 0.3
    /** इतनी छोटी आवाज़ पर नाप भरोसे का नहीं (हर बार का तय खर्च ज़्यादा दिखता है) */
    const val MIN_AUDIO_MS = 1_200L
    const val PREF = "hq_steps"

    fun steps(c: Context): Int = try { norm(PiperStore.sp(c).getInt(PREF, START_STEPS)) } catch (_: Throwable) { START_STEPS }
    fun setSteps(c: Context, v: Int) { try { PiperStore.sp(c).edit().putInt(PREF, norm(v)).apply() } catch (_: Throwable) {} }

    fun norm(v: Int): Int = STEPS.firstOrNull { it == v } ?: STEPS.minByOrNull { kotlin.math.abs(it - v) } ?: START_STEPS

    fun rtf(genMs: Long, audioMs: Long): Double = if (audioMs <= 0) -1.0 else genMs.toDouble() / audioMs

    /** pure: इस वाक्य के नाप के बाद कितने चरण */
    fun nextSteps(cur: Int, genMs: Long, audioMs: Long): Int {
        val c = norm(cur)
        if (audioMs < MIN_AUDIO_MS || genMs < 0) return c
        val r = rtf(genMs, audioMs)
        val i = STEPS.indexOf(c)
        return when {
            r > 2.0 -> STEPS[minOf(i + 2, STEPS.lastIndex)]          // बहुत धीमा (मालिक का फ़ोन ~4) = दो चरण एक साथ
            r > SLOW_RTF && i < STEPS.lastIndex -> STEPS[i + 1]
            r < FAST_RTF && i > 0 -> STEPS[i - 1]
            else -> c
        }
    }

    /** pure: सबसे कम चरण पर भी बोलने से धीमा = यह फ़ोन HQ के लिए धीमा */
    fun tooSlowForHq(steps: Int, rtf: Double): Boolean = norm(steps) == STEPS.last() && rtf > 1.0

    const val ADVICE = "यह फ़ोन HQ आवाज़ के लिए धीमा है — Piper medium आवाज़ (प्रियंवदा / प्रथम / रोहन) चुनें, वह कई गुना तेज़ बनती है"

    /**
     * pure: कितने धागे. [power] = हर कोर की ताक़त ([cpuPower]: पहले cpu_capacity, न मिले तो सबसे ऊँची रफ़्तार);
     * खाली = पता नहीं -> पुराना [PiperTtsEngine.threadsFor]. तेज़ कोर = सबसे तेज़ के [bigShare] या ज़्यादा.
     * HQ: तेज़ कोर जितने (2-4), बाकी: 1-2.
     */
    fun threads(power: List<Long>, cores: Int, hq: Boolean, bigShare: Double = 0.6): Int {
        val f = power.filter { it > 0 }
        if (f.size < 2) return PiperTtsEngine.threadsFor(cores, hq)
        val top = f.max().toDouble()
        val big = f.count { it >= top * bigShare }
        return if (hq) big.coerceIn(2, 4).coerceAtMost(maxOf(cores, 2)) else big.coerceIn(1, 2)
    }

    /** फ़ोन के कोर की ताक़त: cpu_capacity (Android के ज़्यादातर फ़ोन, बड़े/छोटे कोर का सही फ़र्क) - न हो तो रफ़्तार (kHz) */
    fun cpuPower(cores: Int = Runtime.getRuntime().availableProcessors()): Pair<List<Long>, Double> = try {
        fun read(i: Int, n: String) = java.io.File("/sys/devices/system/cpu/cpu$i/$n").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
        val cap = (0 until cores).mapNotNull { read(it, "cpu_capacity") }
        if (cap.size == cores && cap.distinct().size > 1) cap to 0.6
        else (0 until cores).mapNotNull { read(it, "cpufreq/cpuinfo_max_freq") } to 0.75
    } catch (_: Throwable) { emptyList<Long>() to 0.6 }

    /** इस फ़ोन पर कितने धागे (एक बार नापकर) */
    fun threadsHere(hq: Boolean): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val (p, share) = cpuPower(cores)
        return threads(p, cores, hq, share)
    }
}
