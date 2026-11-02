package com.dk.assistant.voice.engine

import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.LinearLayout
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.io.IOException

/**
 * नवंबर 2026 (मालिक: "Piper पूरी तरह हटाओ, Kokoro-82M डिफ़ॉल्ट; सिर्फ़ हिंदी + हिंग्लिश; सिर्फ़ 4 आवाज़ें"):
 * Kokoro TTS - आवाज़ फ़ोन में ही बनती है (बिना इंटरनेट, बिना key).
 *
 * - इंजन: sherpa-onnx 1.13.8 (static-link ONNX Runtime) + Kokoro v1.0 multi-lang int8 (`app/build.gradle.kts` लाता है).
 * - फ़ाइलें: APK के `assets/kokoro_model/` - model.onnx (~109 MB, बिना zip-दबाव = सीधे पढ़ा जाता है), tokens.txt,
 *   voices.bin (सिर्फ़ 4 हिंदी आवाज़ें भरी, बाकी शून्य -> APK में ~2 MB), espeak-ng-data (हिंदी + English शब्दकोश).
 *   espeak-ng-data को असली रास्ता चाहिए -> पहली बार `filesDir` में कॉपी.
 * - भाषा: हमेशा हिंदी (`lang = "hi"`). हिंग्लिश: रोमन में लिखे हिंदी शब्द पहले देवनागरी ([HinglishText]) -
 *   "main aapka reminder set kar deta hoon" -> "मैं आपका reminder set कर देता हूँ" (English शब्द English ही).
 * - आउटपुट 24 kHz = [VoiceEngine.RATE] -> resample नहीं; सीधे [PcmOut] में, वही कतार / वही AudioTrack /
 *   वही [VoiceLevel] (orb / लिप-सिंक उसी पल के असली ज़ोर से).
 * - भारी काम (लोड, बनाना) हमेशा SpeechPipeline की synth thread पर - UI पर कभी नहीं.
 *
 * सिर्फ़ arm64 फ़ोन (बाकी ABI की .so APK में नहीं) - वहाँ [notReady] वजह बताता है और कतार Gemini TTS / Live पर.
 */
object KokoroTtsEngine : VoiceEngine {
    override val id = VoiceEngines.KOKORO_TTS
    override val title = "Kokoro TTS (फ़ोन में, बिना इंटरनेट)"
    override val note = "Kokoro-82M • हिंदी + हिंग्लिश • आवाज़ फ़ोन में ही बनती है - इंटरनेट / key नहीं चाहिए"
    override val split = true
    override val defaultOn = true

    private const val TAG = "DKKokoro"
    const val ASSET_DIR = "kokoro_model"
    /** espeak-ng-data बदले (नया sherpa / नया model) तो यह बदलो -> फ़ोन में दोबारा कॉपी */
    const val DATA_VER = "kokoro-1.0-1.13.8-hi-en-1"
    /** सिर्फ़ हिंदी (हिंग्लिश के English शब्द espeak-ng के en शब्दकोश से) */
    const val LANG = "hi"
    /** Kokoro भारी है (82M) - फ़ोन के 4 core (Piper 2 पर था) */
    const val THREADS = 4

    /** एक आवाज़: पक्का नाम (Kokoro का), दिखने वाला नाम, समूह, voices.bin में नंबर (sherpa kokoro-multi-lang-v1_0) */
    data class Voice(val id: String, val label: String, val group: String, val sid: Int)

    const val FEMALE = "Female voices"
    const val MALE = "Male voices"

    /** सिर्फ़ ये 4 (मालिक की तस्वीर वाली) - app/build.gradle.kts के kokoroSpeakers जैसे ही (test मिलाता है) */
    val VOICE_LIST = listOf(
        Voice("hf_alpha", "Alpha", FEMALE, 31),
        Voice("hf_beta", "Beta", FEMALE, 32),
        Voice("hm_omega", "Omega", MALE, 33),
        Voice("hm_psi", "Psi", MALE, 34),
    )
    val VOICES: List<Pair<String, String>> = VOICE_LIST.map { it.id to it.label }
    /** शुरुआती आवाज़: Female 'Alpha' */
    const val DEFAULT_VOICE = "hf_alpha"
    const val PREF_VOICE = "kokoro_voice"

    fun byVoice(v: String?): Voice? = VOICE_LIST.firstOrNull { it.id == v }
    fun speakerId(v: String): Int = (byVoice(v) ?: byVoice(DEFAULT_VOICE)!!).sid

    override fun voices() = VOICES
    override fun voiceGroup(v: String): String? = byVoice(v)?.group

    /** यूज़र ने खुद (Settings में radio दबाकर) चुनी - तभी Alpha के अलावा कोई आवाज़ */
    const val PREF_PICKED = "kokoro_voice_by_user"

    /**
     * चुनी आवाज़ SharedPreferences में (Android का localStorage; ऐप बंद-चालू / फ़ोन restart पर भी वही).
     * मालिक (नवंबर 2026): डिफ़ॉल्ट Female 'Alpha' पर पक्का ताला - Beta या कोई और अपने-आप कभी नहीं:
     * सिर्फ़ यूज़र के radio दबाने ([setVoice]) से बदलती है. अनजान / पुरानी / बिना-यूज़र लिखी (restore, पुरानी Piper) = Alpha.
     */
    override fun voice(c: Context): String {
        val p = VoiceEngines.sp(c)
        if (!p.getBoolean(PREF_PICKED, false)) return DEFAULT_VOICE
        return p.getString(PREF_VOICE, null)?.takeIf { byVoice(it) != null } ?: DEFAULT_VOICE
    }
    /** सिर्फ़ Settings की आवाज़-सूची से (यूज़र) - बाकी code इसे नहीं बुलाता */
    override fun setVoice(c: Context, v: String) {
        if (byVoice(v) == null) return
        VoiceEngines.sp(c).edit().putString(PREF_VOICE, v).putBoolean(PREF_PICKED, true).apply()
        warm(c)
    }

    /** रफ़्तार: शांत स्वभाव (CalmPersona) + स्टाइल के शब्द ("धीरे", "तेज़"...). 1.0 = सामान्य. गति/पिच स्लाइडर अलग (AudioTrack पर) */
    fun speed(style: String): Float {
        val r = com.dk.assistant.CalmPersona.plainRate(WordStyle.adjust(style).first)
        return ((100 + r) / 100f).coerceIn(0.5f, 2f)
    }
    fun style(c: Context): String = VoiceEngines.sp(c).getString("kokoro_style", null) ?: ""

    // --------------------------------------------------------------------------------- native / फ़ाइलें
    @Volatile private var nativeOk: Boolean? = null
    /** सिर्फ़ tests (Robolectric में .so नहीं होती) */
    @Volatile internal var nativeForTest: Boolean? = null
    /** .so लोड हुई? (arm64 के बिना या टूटी APK में false - ऐप गिरता नहीं) */
    fun nativeReady(): Boolean = nativeForTest ?: nativeOk ?: synchronized(this) {
        nativeOk ?: (try {
            if (Build.SUPPORTED_ABIS?.contains("arm64-v8a") != true) false
            else { System.loadLibrary("sherpa-onnx-jni"); true }
        } catch (t: Throwable) { Log.w(TAG, "sherpa-onnx-jni: ${t.message}"); false }).also { nativeOk = it }
    }

    fun hasModel(c: Context): Boolean = try {
        c.assets.list(ASSET_DIR)?.let { "model.onnx" in it && "voices.bin" in it } == true
    } catch (_: Exception) { false }

    override fun notReady(c: Context): String? = when {
        !nativeReady() -> "यह फ़ोन Kokoro नहीं चला सकता (सिर्फ़ arm64 फ़ोन) - Gemini TTS या DK लाइव चुनें"
        !hasModel(c) -> "Kokoro की model फ़ाइल APK में नहीं मिली - नया APK (GitHub Actions) डालें"
        else -> null
    }

    /** espeak-ng-data -> filesDir (एक बार; DATA_VER बदले तो दोबारा). लौटाता है असली रास्ता */
    fun dataDir(c: Context): String = synchronized(this) {
        val dst = File(c.filesDir, "$ASSET_DIR/espeak-ng-data")
        val stamp = File(dst, ".ver")
        if (stamp.exists() && stamp.readText() == DATA_VER) return dst.absolutePath
        dst.deleteRecursively()
        copyAssets(c, "$ASSET_DIR/espeak-ng-data", dst)
        stamp.writeText(DATA_VER)
        // पुरानी Piper वाली फ़ाइलें (पिछले APK से) - जगह ख़ाली
        File(c.filesDir, "piper_models").deleteRecursively()
        dst.absolutePath
    }

    private fun copyAssets(c: Context, from: String, to: File) {
        val kids = c.assets.list(from).orEmpty()
        if (kids.isEmpty()) {                              // फ़ाइल
            to.parentFile?.mkdirs()
            c.assets.open(from).use { i -> to.outputStream().use { i.copyTo(it, 1 shl 16) } }
            return
        }
        to.mkdirs()
        for (k in kids) copyAssets(c, "$from/$k", File(to, k))
    }

    // --------------------------------------------------------------------------------- बनाना
    /** एक model याद में - चारों आवाज़ें उसी में (आवाज़ बदलना = सिर्फ़ sid, दोबारा लोड नहीं) */
    private var tts: OfflineTts? = null
    private val lock = Any()
    /** stop() पर बढ़ता है - चल रहा बनाना अगले टुकड़े पर रुक जाता है */
    @Volatile private var gen = 0

    private fun load(c: Context): OfflineTts {
        tts?.let { return it }
        val t0 = System.currentTimeMillis()
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$ASSET_DIR/model.onnx",
                    voices = "$ASSET_DIR/voices.bin",
                    tokens = "$ASSET_DIR/tokens.txt",
                    dataDir = dataDir(c),
                    lang = LANG,
                ),
                numThreads = THREADS,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        val t = OfflineTts(assetManager = c.assets, config = cfg)
        Log.i(TAG, "Kokoro लोड: ${System.currentTimeMillis() - t0} ms, ${t.sampleRate()} Hz, ${t.numSpeakers()} आवाज़ें")
        tts = t
        return t
    }

    override fun warm(c: Context) {
        if (notReady(c) != null || tts != null) return
        Thread({ try { synchronized(lock) { load(c) } } catch (t: Throwable) { Log.w(TAG, "warm: ${t.message}") } },
            "dk-kokoro-warm").apply { isDaemon = true }.start()
    }

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        notReady(c)?.let { throw IOException("Kokoro: $it") }
        val my = gen
        val v = byVoice(voice)?.id ?: voice(c)
        val speed = speed(style(c))
        val say = HinglishText.toSpeakable(text)
        // espeak-ng thread-safe नहीं -> एक समय में एक ही बनाना (दूसरी synth thread इंतज़ार करती है, बजना चलता रहता है)
        synchronized(lock) {
            if (my != gen) return
            val t = try { load(c) } catch (e: Throwable) { throw IOException("Kokoro: model लोड नहीं हुआ (${e.message})", e) }
            val rate = t.sampleRate()
            var any = false
            try {
                t.generateWithCallback(text = say, sid = speakerId(v), speed = speed) { samples ->
                    if (my != gen) return@generateWithCallback 0          // stop() - आगे मत बनाओ
                    if (samples.isNotEmpty()) { any = true; out.write(Resample.to24k(pcm16(samples), rate)) }
                    1
                }
            } catch (e: Throwable) {
                if (my != gen) return
                throw IOException("Kokoro: आवाज़ नहीं बनी (${e.message})", e)
            }
            if (!any && my == gen && say.any { it.isLetterOrDigit() }) throw IOException("Kokoro: खाली आवाज़")
        }
    }

    override fun stop() { gen++ }

    /** याद ख़ाली करो (दूसरा इंजन चुना). बनाना चल रहा हो तो उसके बाद */
    fun release() {
        val t = tts ?: return
        tts = null
        try { t.release() } catch (_: Throwable) {}
    }
    /** बाहर से (VoiceModeController): lock लेकर छोड़ो */
    fun releaseNow() { gen++; Thread({ synchronized(lock) { release() } }, "dk-kokoro-release").apply { isDaemon = true }.start() }

    /** float (-1..1) -> PCM 16-bit LE */
    fun pcm16(f: FloatArray): ByteArray {
        val o = ByteArray(f.size * 2)
        for (i in f.indices) {
            val s = (f[i].coerceIn(-1f, 1f) * 32767f).toInt()
            o[i * 2] = (s and 0xff).toByte(); o[i * 2 + 1] = (s shr 8 and 0xff).toByte()
        }
        return o
    }

    override fun settings(c: Context, box: LinearLayout, ui: VoiceUi) {
        ui.field(box, "भावना / स्टाइल (Kokoro के लिए)", style(c), "जैसे: धीरे, आराम से  •  या: तेज़, जल्दी", multiLine = true) { v ->
            VoiceEngines.sp(c).edit().putString("kokoro_style", v.trim()).apply()
        }
        ui.hint(box, "Kokoro सिर्फ़ हिंदी और हिंग्लिश बोलता है - रोमन में लिखे हिंदी शब्द (\"main aapka kaam kar deta hoon\") अपने-आप देवनागरी उच्चारण में.")
        ui.hint(box, "आवाज़ फ़ोन में ही बनती है: इंटरनेट न हो तब भी DK बोलेगा. पहली बार लोड होने में 2-4 सेकंड; आवाज़ बदलना तुरंत (दोबारा लोड नहीं).")
        ui.hint(box, "Kokoro-82M (hexgrad) - Apache-2.0.")
    }
}
