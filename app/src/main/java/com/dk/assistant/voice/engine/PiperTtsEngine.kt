package com.dk.assistant.voice.engine

import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.LinearLayout
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.IOException

/**
 * नवंबर 2026: Piper TTS - आवाज़ फ़ोन में ही बनती है (बिना इंटरनेट, बिना key). Microsoft Edge TTS की जगह.
 *
 * - इंजन: sherpa-onnx 1.13.8 (static-link ONNX Runtime) - `app/build.gradle.kts` build के समय लाता है.
 * - आवाज़ें: APK के `assets/piper_models/<नाम>/model.onnx + tokens.txt` (int8 = छोटी), साझा `espeak-ng-data`.
 *   model/tokens सीधे assets से पढ़े जाते हैं; espeak-ng-data को असली रास्ता चाहिए -> पहली बार `filesDir` में कॉपी.
 * - Gemini Live (या कोई और दिमाग़) सिर्फ़ जवाब लिखता है; यह इंजन [PcmOut] में 24 kHz PCM देता है ->
 *   वही कतार / वही AudioTrack, गति और पिच [VoiceTune] (PlaybackParams) से.
 * - भारी काम (आवाज़ लोड करना, बनाना) हमेशा SpeechPipeline की अपनी synth thread पर - UI पर कभी नहीं.
 *
 * सिर्फ़ arm64 फ़ोन (बाकी ABI की .so APK में नहीं) - वहाँ [notReady] वजह बताता है और कतार Gemini TTS / Live की आवाज़ पर.
 */
object PiperTtsEngine : VoiceEngine {
    override val id = VoiceEngines.PIPER_TTS
    override val title = "Piper TTS (फ़ोन में, बिना इंटरनेट)"
    override val note = "आवाज़ फ़ोन में ही बनती है - इंटरनेट / key नहीं चाहिए • भावना के निर्देश नहीं मानता (शब्दों से सिर्फ़ रफ़्तार)"
    override val split = true
    override val defaultOn = true

    private const val TAG = "DKPiper"
    const val ASSET_DIR = "piper_models"
    /** espeak-ng-data बदले (नया sherpa / नई भाषा) तो यह बदलो -> फ़ोन में दोबारा कॉपी */
    const val DATA_VER = "1.13.8-hi-en-1"

    /**
     * नवंबर 2026 (मालिक: "English आवाज़ हटाओ, पुरुष एक ही, महिला 3-4 अलग-अलग अंदाज़ की, रोबोट जैसी नहीं - नैचुरल"):
     * Piper में हिंदी की महिला आवाज़ सिर्फ़ एक (Priyamvada) है -> उसी से 4 अंदाज़ ([Look]): हर एक की अपनी
     * रफ़्तार, उतार-चढ़ाव (VITS noise) और पिच. पुरुष: सिर्फ़ Pratham.
     * पक्का नाम = "assets का फ़ोल्डर" या "फ़ोल्डर@अंदाज़". फ़ोल्डर app/build.gradle.kts के piperVoices जैसे ही (test मिलाता है).
     */
    val VOICES = listOf(
        "hi_IN-priyamvada-medium" to "प्रियंवदा • सहज (आम बातचीत, महिला)",
        "hi_IN-priyamvada-medium@khush" to "प्रियंवदा • खुशमिज़ाज (चहकती, महिला)",
        "hi_IN-priyamvada-medium@shant" to "प्रियंवदा • शांत (नरम, धीमी, महिला)",
        "hi_IN-priyamvada-medium@gambhir" to "प्रियंवदा • गंभीर (गहरी, साफ़, महिला)",
        "hi_IN-pratham-medium" to "प्रथम • पुरुष (हिंदी)",
    )

    /**
     * एक अंदाज़: [speed] = रफ़्तार का गुणा (शांत स्वभाव के ऊपर), [noise]/[noiseW] = VITS का उतार-चढ़ाव
     * (कम = सपाट/रोबोट जैसा, ज़्यादा = भावुक; बहुत ज़्यादा = लड़खड़ाती), [pitch] = आवाज़ ऊँची/गहरी.
     * पिच AudioTrack (Sonic) से नहीं: आवाज़ [pitch] गुना लंबी बनती है और फिर उतनी ही तेज़ resample -> लंबाई वही,
     * पिच बदली, और कोई खिंचाव-वाली खरखराहट नहीं.
     */
    data class Look(val speed: Float, val noise: Float, val noiseW: Float, val pitch: Float)
    /** सहज: शांत स्वभाव (-8%) की धीमी चाल बराबर करके लगभग असली बातचीत की रफ़्तार (0.92 x 1.08 ≈ 1.0) */
    val NATURAL = Look(speed = 1.08f, noise = 0.667f, noiseW = 0.8f, pitch = 1.0f)
    val LOOKS = mapOf(
        "" to NATURAL,
        "khush" to Look(speed = 1.15f, noise = 0.75f, noiseW = 0.9f, pitch = 1.07f),
        "shant" to Look(speed = 1.01f, noise = 0.55f, noiseW = 0.7f, pitch = 0.97f),
        "gambhir" to Look(speed = 1.08f, noise = 0.6f, noiseW = 0.75f, pitch = 0.92f),
    )
    fun look(v: String): Look = LOOKS[v.substringAfter('@', "").substringBefore('#')] ?: NATURAL
    val DEFAULT_VOICE = VOICES[0].first

    override fun voices() = VOICES
    override fun voice(c: Context): String =
        VoiceEngines.sp(c).getString("piper_voice", null)?.let { v -> VOICES.firstOrNull { it.first == v }?.let { v } }
            ?: DEFAULT_VOICE
    override fun setVoice(c: Context, v: String) {
        VoiceEngines.sp(c).edit().putString("piper_voice", v).apply()
        warm(c)
    }

    /** "नाम", "नाम@अंदाज़" या "नाम#3" (कई बोलने वालों वाली आवाज़ में बोलने वाला नं.) */
    fun voiceDir(v: String): String = v.substringBefore('#').substringBefore('@')
    fun speakerId(v: String): Int = v.substringAfter('#', "0").toIntOrNull()?.coerceAtLeast(0) ?: 0

    /** Piper की रफ़्तार: शांत स्वभाव (CalmPersona) + स्टाइल के शब्द ("धीरे", "तेज़"...). 1.0 = सामान्य. गति/पिच स्लाइडर अलग (AudioTrack पर) */
    fun speed(style: String): Float {
        val r = com.dk.assistant.CalmPersona.plainRate(WordStyle.adjust(style).first)
        return ((100 + r) / 100f).coerceIn(0.5f, 2f)
    }
    fun style(c: Context): String = VoiceEngines.sp(c).getString("piper_style", null) ?: ""

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

    fun hasVoice(c: Context, v: String): Boolean = try {
        c.assets.list("$ASSET_DIR/${voiceDir(v)}")?.contains("model.onnx") == true
    } catch (_: Exception) { false }

    override fun notReady(c: Context): String? = when {
        !nativeReady() -> "यह फ़ोन Piper नहीं चला सकता (सिर्फ़ arm64 फ़ोन) - Gemini TTS या DK लाइव चुनें"
        !hasVoice(c, voice(c)) -> "Piper की आवाज़ की फ़ाइल APK में नहीं मिली - नया APK (GitHub Actions) डालें"
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
    /** एक समय में एक ही आवाज़ याद में (हर आवाज़ ~20-60 MB) */
    private var tts: OfflineTts? = null
    /** लोड हुई आवाज़ = फ़ोल्डर + उतार-चढ़ाव (noise load के समय तय होता है -> अंदाज़ बदला = दोबारा लोड, ~1-2 s) */
    private var ttsVoice = ""
    private fun key(v: String) = look(v).let { "${voiceDir(v)}|${it.noise}|${it.noiseW}" }
    private val lock = Any()
    /** stop() पर बढ़ता है - चल रहा बनाना अगले टुकड़े पर रुक जाता है */
    @Volatile private var gen = 0

    private fun load(c: Context, v: String): OfflineTts {
        val dir = voiceDir(v)
        val lk = look(v)
        tts?.takeIf { ttsVoice == key(v) }?.let { return it }
        release()
        val t0 = System.currentTimeMillis()
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$ASSET_DIR/$dir/model.onnx",
                    tokens = "$ASSET_DIR/$dir/tokens.txt",
                    dataDir = dataDir(c),
                    noiseScale = lk.noise,
                    noiseScaleW = lk.noiseW,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        val t = OfflineTts(assetManager = c.assets, config = cfg)
        Log.i(TAG, "आवाज़ $dir लोड: ${System.currentTimeMillis() - t0} ms, ${t.sampleRate()} Hz")
        tts = t; ttsVoice = key(v)
        return t
    }

    override fun warm(c: Context) {
        if (notReady(c) != null) return
        val v = voice(c)
        if (ttsVoice == key(v) && tts != null) return
        Thread({ try { synchronized(lock) { load(c, v) } } catch (t: Throwable) { Log.w(TAG, "warm: ${t.message}") } },
            "dk-piper-warm").apply { isDaemon = true }.start()
    }

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        notReady(c)?.let { throw IOException("Piper: $it") }
        val my = gen
        val v = voice.takeIf { hasVoice(c, it) } ?: voice(c)
        val lk = look(v)
        // पिच: [pitch] गुना लंबी बनाओ, फिर [pitch] गुना तेज़ resample -> लंबाई = speed के हिसाब से, पिच ऊँची/गहरी
        val speed = (speed(style(c)) * lk.speed / lk.pitch).coerceIn(0.5f, 2f)
        // espeak-ng thread-safe नहीं -> एक समय में एक ही बनाना (दूसरी synth thread इंतज़ार करती है, बजना चलता रहता है)
        synchronized(lock) {
            if (my != gen) return
            val t = try { load(c, v) } catch (e: Throwable) { throw IOException("Piper: आवाज़ लोड नहीं हुई (${e.message})", e) }
            val rate = Math.round(t.sampleRate() * lk.pitch)
            var any = false
            try {
                t.generateWithCallback(text = text, sid = speakerId(v), speed = speed) { samples ->
                    if (my != gen) return@generateWithCallback 0          // stop() - आगे मत बनाओ
                    if (samples.isNotEmpty()) { any = true; out.write(Resample.to24k(pcm16(samples), rate)) }
                    1
                }
            } catch (e: Throwable) {
                if (my != gen) return
                throw IOException("Piper: आवाज़ नहीं बनी (${e.message})", e)
            }
            if (!any && my == gen && text.any { it.isLetterOrDigit() }) throw IOException("Piper: खाली आवाज़")
        }
    }

    override fun stop() { gen++ }

    /** याद ख़ाली करो (दूसरा इंजन चुना / आवाज़ बदली). बनाना चल रहा हो तो उसके बाद */
    fun release() {
        val t = tts ?: return
        tts = null; ttsVoice = ""
        try { t.release() } catch (_: Throwable) {}
    }
    /** बाहर से (VoiceModeController): lock लेकर छोड़ो */
    fun releaseNow() { gen++; Thread({ synchronized(lock) { release() } }, "dk-piper-release").apply { isDaemon = true }.start() }

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
        ui.field(box, "भावना / स्टाइल (Piper के लिए)", style(c), "जैसे: धीरे, आराम से  •  या: तेज़, जल्दी", multiLine = true) { v ->
            VoiceEngines.sp(c).edit().putString("piper_style", v.trim()).apply()
        }
        ui.hint(box, "Piper भावना के निर्देश नहीं समझता - \"धीरे / तेज़\" जैसे शब्दों से सिर्फ़ रफ़्तार बदलती है. गति / पिच नीचे के स्लाइडर से.")
        ui.hint(box, "अंदाज़: सहज = आम बातचीत, खुशमिज़ाज = चहकती, शांत = नरम-धीमी, गंभीर = गहरी. बदलने पर पहली बार 1-2 सेकंड लोड.")
        ui.hint(box, "आवाज़ फ़ोन में ही बनती है: इंटरनेट न हो तब भी DK बोलेगा. पहली बार (या आवाज़ बदलने पर) लोड होने में 1-2 सेकंड.")
        ui.hint(box, "आवाज़ें: Piper (rhasspy) - हिंदी आवाज़ें CC BY-NC-SA 4.0 (बेचने वाले काम के लिए नहीं).")
    }
}
