package com.dk.assistant.voice.st3

import android.content.Context
import com.dk.assistant.plus.models.ModelManager

/**
 * 19 नवंबर 2026: नई आवाज़ (Supertonic 3) की पसंद - कौन-सी आवाज़ (F1-F5), रफ़्तार, कदम. अलग prefs "dk_st3"
 * (पुराने हटाए गए "dk_supertonic*" से नाम अलग, ताकि पुरानी सफ़ाई इसे न मिटाए). कोई पुरानी setting नहीं बदलती.
 */
object St3Store {
    private const val P = "dk_st3"
    private fun p(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun voice(c: Context): String = St3Config.cleanVoice(p(c).getString("voice", St3Config.DEFAULT_VOICE))
    fun setVoice(c: Context, id: String) { p(c).edit().putString("voice", St3Config.cleanVoice(id)).apply() }
    fun speed(c: Context): Float = St3Config.cleanSpeed(p(c).getFloat("speed", St3Config.SPEED_DEF))
    fun setSpeed(c: Context, s: Float) { p(c).edit().putFloat("speed", St3Config.cleanSpeed(s)).apply() }
    fun steps(c: Context): Int = St3Config.cleanSteps(p(c).getInt("steps", St3Config.STEPS_DEF))
    fun setSteps(c: Context, n: Int) { p(c).edit().putInt("steps", St3Config.cleanSteps(n)).apply() }

    /**
     * 19 नवंबर 2026: इस फ़ोन पर सबसे तेज़ थ्रेड-गिनती (4-6 में से नापकर चुनी, [St3Engine.warm]). 0 = अभी नहीं नापा.
     * core की गिनती साथ में - फ़ोन/सेटिंग बदले तो फिर से नापो.
     */
    fun threads(c: Context): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val ok = p(c).getInt("threads_cores", 0) == cores && p(c).getInt("bench_v", 1) == BENCH_V &&
            p(c).getBoolean("bench_fast", false) == fastOn(c)       // तेज़ मॉडल आया / गया = फिर से नापो
        return if (ok) p(c).getInt("threads", 0) else 0
    }
    /** नापने में XNNPACK (ARM का तेज़ गणित) जीता */
    fun xnn(c: Context): Boolean = threads(c) > 0 && p(c).getBoolean("xnn", false)
    fun setThreads(c: Context, n: Int, xnn: Boolean = false) {
        p(c).edit().putInt("threads", n).putBoolean("xnn", xnn).putInt("bench_v", BENCH_V).putBoolean("bench_fast", fastOn(c))
            .putInt("threads_cores", Runtime.getRuntime().availableProcessors()).apply()
    }
    /** 19 नवंबर 2026: नाप का तरीका बदला (XNNPACK + धागों का इंतज़ार बंद) - पुराना नाप फिर से */
    private const val BENCH_V = 2

    /**
     * मुख्य मॉडल फ़ोन में पूरे: साझा तीन ([St3Config.SHARED]) + तेज़ वाले तीन ([St3Config.FAST]) या पुराने तीन ([St3Config.SLOW]).
     * 20 नवंबर 2026: तेज़ वाले आ गए = वही चलते हैं; पुराने कभी अपने-आप नहीं मिटते (मालिक का नियम - Settings से ही हटें).
     */
    fun coreReady(c: Context): Boolean = St3Config.SHARED.all { ModelManager.ready(c, it) } && (fastOn(c) || slowReady(c))
    fun slowReady(c: Context): Boolean = St3Config.SLOW.all { ModelManager.ready(c, it) }
    /** तेज़ वाले तीनों फ़ाइलें पूरी (खुलें या नहीं, यह अलग) */
    fun fastFiles(c: Context): Boolean = St3Config.FAST.all { ModelManager.ready(c, it) }
    /** तेज़ मॉडल इस फ़ोन पर नहीं खुला (ONNX Runtime ने मना किया) - फिर पुराना, और यह याद रहे */
    fun fastBad(c: Context): Boolean = p(c).getBoolean("fast_bad", false)
    fun setFastBad(c: Context, bad: Boolean) { p(c).edit().putBoolean("fast_bad", bad).apply() }
    /** अभी तेज़ मॉडल चलेगा */
    fun fastOn(c: Context): Boolean = fastFiles(c) && !fastBad(c)
    /** Settings का "डाउनलोड" बटन क्या लाए: तेज़ ठीक = तेज़ वाला सेट (~150 MB), इस फ़ोन पर तेज़ नहीं खुला = पुराना पूरा */
    fun neededSet(c: Context): List<com.dk.assistant.plus.models.ModelCatalog.Asset> = if (fastBad(c)) St3Config.CORE else St3Config.FAST_SET
    fun voiceReady(c: Context, id: String): Boolean = ModelManager.ready(c, St3Config.voice(id).asset)
    /** चुनी हुई आवाज़ डाउनलोड न हो तो जो पहली डाउनलोड है वह; कोई नहीं तो null */
    fun usableVoice(c: Context): String? = voice(c).takeIf { voiceReady(c, it) } ?: St3Config.VOICES.firstOrNull { voiceReady(c, it.id) }?.id
    fun ready(c: Context): Boolean = coreReady(c) && usableVoice(c) != null

    fun files(c: Context, fast: Boolean = fastOn(c)) = St3Synth.Files(
        ModelManager.file(c, St3Config.DP),
        ModelManager.file(c, if (fast) St3Config.TEXT_FAST else St3Config.TEXT_ENC),
        ModelManager.file(c, if (fast) St3Config.VECTOR_FAST else St3Config.VECTOR),
        ModelManager.file(c, if (fast) St3Config.VOCODER_FAST else St3Config.VOCODER),
        ModelManager.file(c, St3Config.CFG), ModelManager.file(c, St3Config.INDEXER))
}
