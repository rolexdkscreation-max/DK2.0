package com.dk.assistant.voice.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * v3.68: तीनों आवाज़ इंजनों की सूची + कौन चालू है + पिछली बार नापा समय.
 *
 * इंजन नाम से ढूँढे जाते हैं ([CLASSES]) - किसी इंजन की फ़ाइल हटा दो तो वह सूची से अपने-आप गायब,
 * बाकी सब वैसे ही चलता है. एक समय में सिर्फ़ एक इंजन चालू ([toggle]).
 */
object VoiceEngines {
    const val GEMINI_TTS = "gemini_tts"
    const val EDGE_TTS = "edge_tts"
    const val LIVE_NATIVE = "live_native"
    const val DEFAULT_SAMPLE = "मुझे तुम्हारी बहुत याद आ रही थी"

    private val CLASSES = listOf(
        "com.dk.assistant.voice.engine.GeminiTtsEngine",
        "com.dk.assistant.voice.engine.EdgeTtsEngine",
        "com.dk.assistant.voice.engine.LiveNativeEngine",
    )

    val all: List<VoiceEngine> by lazy {
        CLASSES.mapNotNull { n ->
            try { Class.forName(n).getField("INSTANCE").get(null) as? VoiceEngine } catch (_: Throwable) { null }
        }
    }

    fun byId(id: String?): VoiceEngine? = all.firstOrNull { it.id == id }

    fun sp(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("dk_voice_engine", Context.MODE_PRIVATE)

    /** कौन-सा इंजन चालू (कुछ न चुना = जिसका defaultOn; वह भी न हो = Live की अपनी आवाज़) */
    fun activeId(c: Context): String {
        val saved = sp(c).getString("active", null)
        if (byId(saved) != null) return saved!!
        return all.firstOrNull { it.defaultOn }?.id ?: LIVE_NATIVE
    }

    fun active(c: Context): VoiceEngine? = byId(activeId(c))

    /** बदला तो गिनती बढ़ती है - चलता Live इसे देखकर अगली बार नया इंजन लेता है */
    @Volatile var version = 0
        private set

    /**
     * v3.80: Live की अपनी आवाज़ / अंदाज़ / Live मॉडल बदला -> गिनती. चलता Live चुप होते ही नया connection लेता है
     * (पहले: "अगली बार जुड़ने पर" - बीच बातचीत में आवाज़ नहीं बदलती थी).
     */
    @Volatile var liveVersion = 0
        private set

    fun liveChanged() { liveVersion++ }

    fun setActive(c: Context, id: String) {
        if (byId(id) == null) return
        sp(c).edit().putString("active", id).apply()
        version++
    }

    /**
     * स्विच दबाया: एक चालू = बाकी अपने-आप बंद. चालू वाले को बंद किया = डिफ़ॉल्ट (Gemini TTS) पर लौटो
     * (कोई भी इंजन चालू न रहे - ऐसा नहीं होने देते). लौटाता है: अब कौन चालू.
     */
    fun toggle(current: String, id: String, on: Boolean, default: String = GEMINI_TTS, available: Set<String>): String = when {
        on && id in available -> id
        !on && id == current && default in available && default != id -> default
        else -> current
    }

    /** इंजन फ़ेल हो तो लौटने की जगह */
    fun fallback(): VoiceEngine? = byId(GEMINI_TTS)

    // ---------------------------------------------------------------- अभी-अभी टूटे इंजन (2 मिनट तक सीधे अगला)
    private val failed = ConcurrentHashMap<String, Pair<Long, String>>()
    const val FAIL_MS = 120_000L
    fun markFailed(id: String, why: String) { failed[id] = System.currentTimeMillis() to why }
    fun isFailed(id: String, now: Long = System.currentTimeMillis()): Boolean = failed[id]?.let { now - it.first < FAIL_MS } ?: false
    fun clearFailed(id: String) { failed.remove(id) }
    fun lastError(id: String): String? = failed[id]?.second

    // ---------------------------------------------------------------- नापा हुआ समय
    /** बोलना ख़त्म -> पहली आवाज़ (असली बातचीत में) */
    fun recordLatency(c: Context, id: String, ms: Long) {
        if (ms < 0 || ms > 60_000) return
        sp(c).edit().putLong("lat_$id", ms).putLong("lat_at_$id", System.currentTimeMillis()).apply()
    }
    fun lastLatency(c: Context, id: String): Long = sp(c).getLong("lat_$id", -1L)

    /** ▶ सुनो दबाने -> पहली आवाज़ */
    fun recordPreview(c: Context, id: String, ms: Long) { if (ms >= 0) sp(c).edit().putLong("pre_$id", ms).apply() }
    fun lastPreview(c: Context, id: String): Long = sp(c).getLong("pre_$id", -1L)

    fun latencyLine(c: Context, id: String): String {
        val l = lastLatency(c, id); val p = lastPreview(c, id)
        val a = if (l >= 0) "बोलना ख़त्म → पहली आवाज़: ${secs(l)}" else "बातचीत में अभी नापा नहीं"
        val b = if (p >= 0) " • नमूना: ${secs(p)}" else ""
        return "पिछली बार: $a$b"
    }

    fun secs(ms: Long): String = String.format(java.util.Locale.US, "%.2f सेकंड", ms / 1000.0)

    // ---------------------------------------------------------------- नमूना-वाक्य
    fun sample(c: Context): String = sp(c).getString("sample", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SAMPLE
    fun setSample(c: Context, t: String) { sp(c).edit().putString("sample", t.trim()).apply() }
}
