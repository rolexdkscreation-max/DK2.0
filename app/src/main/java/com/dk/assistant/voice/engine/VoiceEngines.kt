package com.dk.assistant.voice.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * v3.68: आवाज़ इंजनों (नवंबर 2026: DK लाइव = Direct Gemini Live Voice डिफ़ॉल्ट, Gemini TTS बैकअप - Edge / Piper / Kokoro हटाए) की सूची + कौन चालू है + पिछली बार नापा समय.
 *
 * इंजन नाम से ढूँढे जाते हैं ([CLASSES]) - किसी इंजन की फ़ाइल हटा दो तो वह सूची से अपने-आप गायब,
 * बाकी सब वैसे ही चलता है. एक समय में सिर्फ़ एक इंजन चालू ([toggle]).
 */
object VoiceEngines {
    const val GEMINI_TTS = "gemini_tts"
    /** नवंबर 2026: हटाया गया Kokoro TTS - सिर्फ़ पुरानी सेव पसंद पहचानने के लिए (migration). बैकअप: branch backup/dk-kokoro-default */
    const val OLD_KOKORO_ID = "kokoro_tts"
    /** नवंबर 2026: हटाया गया Piper TTS - सिर्फ़ पुरानी सेव पसंद पहचानने के लिए (migration) */
    const val OLD_PIPER_ID = "piper_tts"
    /** नवंबर 2026: हटाया गया Microsoft Edge TTS - सिर्फ़ पुरानी सेव पसंद पहचानने के लिए (migration) */
    const val OLD_EDGE_ID = "edge_tts"
    const val LIVE_NATIVE = "live_native"
    const val DEFAULT_SAMPLE = "मुझे तुम्हारी बहुत याद आ रही थी"

    /** क्रम = स्क्रीन पर क्रम: पहले DK लाइव (Direct Gemini Live Voice, डिफ़ॉल्ट), फिर Gemini TTS (बैकअप) */
    private val CLASSES = listOf(
        "com.dk.assistant.voice.engine.LiveNativeEngine",
        "com.dk.assistant.voice.engine.GeminiTtsEngine",
    )

    /** एक बार का बदलाव: पुराना डिफ़ॉल्ट (Gemini TTS) सेव था -> DK लाइव. इसके बाद यूज़र जो चुने वही रहे */
    const val MIGRATE_KEY = "dk_live_default_v1"

    /** pure: सेव इंजन + migration हुआ या नहीं -> अब कौन-सा (JVM test) */
    fun migrated(saved: String?, done: Boolean): String? = if (!done && (saved == null || saved == GEMINI_TTS)) LIVE_NATIVE else saved

    /**
     * नवंबर 2026 (मालिक: "Kokoro पूरी तरह हटाओ, डिफ़ॉल्ट Direct Gemini Live, बैकअप Gemini TTS / फ़ोन की आवाज़"):
     * एक बार हर फ़ोन पर DK लाइव (Direct Gemini Live Voice) चालू. उसके बाद यूज़र जो चुने (जैसे Gemini TTS) वही रहे.
     * हटाए गए इंजन (Kokoro / Piper / Edge) की पुरानी पसंद = हमेशा DK लाइव.
     */
    const val DIRECT_KEY = "live_direct_default_v2"
    private val REMOVED = setOf(OLD_KOKORO_ID, OLD_PIPER_ID, OLD_EDGE_ID)

    /** pure (JVM test) */
    fun directMigrated(saved: String?, done: Boolean): String? = when {
        !done -> LIVE_NATIVE
        saved in REMOVED -> LIVE_NATIVE
        else -> saved
    }

    val all: List<VoiceEngine> by lazy {
        CLASSES.mapNotNull { n ->
            try { Class.forName(n).getField("INSTANCE").get(null) as? VoiceEngine } catch (_: Throwable) { null }
        }
    }

    fun byId(id: String?): VoiceEngine? = all.firstOrNull { it.id == id } ?: PiperTtsEngine.takeIf { id == PiperTtsEngine.ID }

    /** सुरक्षा: worker_token ताले में ([com.dk.assistant.SecretPrefs]) - बाकी settings वैसी ही */
    fun sp(c: Context): SharedPreferences =
        com.dk.assistant.SecretPrefs.of(c.applicationContext.getSharedPreferences("dk_voice_engine", Context.MODE_PRIVATE))

    /** कौन-सा इंजन चालू (कुछ न चुना = जिसका defaultOn; वह भी न हो = Live की अपनी आवाज़) */
    fun activeId(c: Context): String {
        val p = sp(c)
        if (!p.getBoolean(MIGRATE_KEY, false)) {
            val m = migrated(p.getString("active", null), false)
            val ed = p.edit().putBoolean(MIGRATE_KEY, true)
            if (m != null) ed.putString("active", m)
            ed.apply()
        }
        if (!p.getBoolean(DIRECT_KEY, false) || p.getString("active", null) in REMOVED) {
            val m = directMigrated(p.getString("active", null), p.getBoolean(DIRECT_KEY, false))
            val ed = p.edit().putBoolean(DIRECT_KEY, true)
            if (m != null) ed.putString("active", m)
            ed.apply()
            // पुराने Kokoro की फ़ोन में कॉपी की हुई फ़ाइलें (espeak-ng-data) - जगह खाली करो
            try { java.io.File(c.applicationContext.filesDir, "kokoro_model").deleteRecursively() } catch (_: Throwable) {}
        }
        val saved = p.getString("active", null)
        if (byId(saved) != null) return saved!!
        return all.firstOrNull { it.defaultOn }?.id ?: LIVE_NATIVE
    }

    /** नवंबर 2026: Piper का स्विच ON (और डाउनलोड पूरा) = Piper; OFF = पहले जैसा (सेव पसंद छुई तक नहीं) */
    fun active(c: Context): VoiceEngine? = PiperTtsEngine.override(c) ?: byId(activeId(c))

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

    /** नवंबर 2026: Piper का स्विच बदला - चलता Live चुप होते ही सही आवाज़ ले (सेव पसंद नहीं बदलती) */
    fun changed() { version++ }

    fun setActive(c: Context, id: String) {
        if (byId(id) == null) return
        sp(c).edit().putString("active", id).apply()
        version++
    }

    /**
     * स्विच दबाया: एक चालू = बाकी अपने-आप बंद. चालू वाले को बंद किया = डिफ़ॉल्ट (DK लाइव) पर लौटो;
     * DK लाइव ही बंद किया = दूसरा नंबर (Gemini TTS). कोई भी इंजन चालू न रहे - ऐसा नहीं होने देते. लौटाता है: अब कौन चालू.
     */
    fun toggle(current: String, id: String, on: Boolean, default: String = LIVE_NATIVE, available: Set<String>, second: String = GEMINI_TTS): String = when {
        on && id in available -> id
        !on && id == current && default in available && default != id -> default
        !on && id == current && second in available && second != id -> second
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
