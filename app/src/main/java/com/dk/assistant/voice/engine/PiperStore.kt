package com.dk.assistant.voice.engine

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * नवंबर 2026 (मालिक: "ऑफ़लाइन Piper TTS - डिफ़ॉल्ट बंद, खुद डाउनलोड करके चालू"): Piper की सारी पसंद और फ़ाइलें.
 *
 * - पसंद अलग prefs ("dk_piper") में - मौजूदा आवाज़ इंजन की पसंद (VoiceEngines.sp) को छूती तक नहीं.
 *   इसलिए Piper चालू/बंद करने से पुरानी सेटिंग (DK लाइव / Gemini TTS, आवाज़, अंदाज़) जस की तस.
 * - फ़ाइलें APK में नहीं: पहली बार इंटरनेट से [PiperDownload] लाता है -> filesDir/piper/ (सिर्फ़ इस ऐप की जगह).
 * - सब डिफ़ॉल्ट = बिना इफ़ेक्ट (स्पीड 1x, पिच सामान्य, रिवर्ब बंद, बास बंद, ठहराव पहले जैसा).
 */
object PiperStore {
    const val PREFS = "dk_piper"

    /** Hugging Face पर sherpa-onnx वाला Piper हिंदी मॉडल - [rev] पक्का commit (बाद में बदले तो भी यही फ़ाइल) */
    data class Voice(val id: String, val title: String, val repo: String, val rev: String, val onnxSha256: String, val onnxSize: Long, val sid: Int = -1) {
        val onnxName: String get() = "$id.onnx"
        /** हाई क्वालिटी (Supertonic-3, उसी sherpa-onnx इंजन में) - सारी HQ आवाज़ें एक ही मॉडल की अलग-अलग [sid] */
        val hq: Boolean get() = sid >= 0
    }

    /** सब medium क्वालिटी (Hugging Face पर हिंदी का low / high मॉडल है ही नहीं) - पहली = डिफ़ॉल्ट */
    val VOICES = listOf(
        Voice("hi_IN-priyamvada-medium", "प्रियंवदा (महिला)", "csukuangfj/vits-piper-hi_IN-priyamvada-medium",
            "ec05e05b3eae80817ea62258d13f4d42e46cd1c2", "8871f3e07adb6ca490f8dbcd3956a8647c53c35b5d0a1c2a8d097b3bf721a31b", 63145178L),
        Voice("hi_IN-pratham-medium", "प्रथम (पुरुष)", "csukuangfj/vits-piper-hi_IN-pratham-medium",
            "94517289ea9291e9a76ace527d8c34d6b170aa97", "1d77908e8ab724c813c5cefaa48feadc9f2670ec5d145f83bade981c428740a0", 63145178L),
        Voice("hi_IN-rohan-medium", "रोहन (पुरुष)", "csukuangfj/vits-piper-hi_IN-rohan-medium",
            "fe149a55f4949f711d42bfca3e214d5c6c8d797b", "353d2a20bdb6dd03b74e1aff82b7a10a1646f677f8474c6c81fd606705536224", 62946428L),
    )
    val DEFAULT_VOICE = VOICES.first().id

    /**
     * नवंबर 2026 (मालिक: "आवाज़ हाई क्वालिटी, 2-3 और महिला आवाज़ें - नेचुरल, कोमल, इमोशनल; इंटरनेट से, APK में नहीं"):
     * हिंदी Piper का high मॉडल है ही नहीं, इसलिए उसी sherpa-onnx इंजन में Supertonic-3 (Supertone, OpenRAIL-M) -
     * 44.1 kHz, हिंदी सीधे देवनागरी से (espeak नहीं), इंसान जैसी लय. एक ही मॉडल (~138 MB, एक बार) में सारी आवाज़ें.
     * sandbox नाप (हिंदी वाक्य): sid 0-4 महिला (औसत पिच 163-208 Hz), 5-9 पुरुष. ये 3 महिला चुनीं.
     */
    const val ST_DIR = "supertonic-3"
    const val ST_REPO = "csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    const val ST_REV = "cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4"
    /** एक वाक्य कितने चरण में साफ़ हो (ज़्यादा = साफ़ पर धीमा). 8 = Supertone की सुझाई हाई क्वालिटी */
    const val ST_STEPS = 8
    data class StFile(val name: String, val sha256: String, val size: Long)
    val ST_FILES = listOf(
        StFile("tts.json", "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09", 8_253L),
        StFile("unicode_indexer.bin", "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30", 262_144L),
        StFile("voice.bin", "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8", 517_168L),
        StFile("duration_predictor.int8.onnx", "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db", 3_700_147L),
        StFile("vocoder.int8.onnx", "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152", 25_991_073L),
        StFile("text_encoder.int8.onnx", "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff", 36_416_150L),
        StFile("vector_estimator.int8.onnx", "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd", 78_400_833L),
    )
    val ST_SIZE: Long = ST_FILES.sumOf { it.size }
    private fun hqVoice(id: String, title: String, sid: Int) = Voice(id, title, ST_REPO, ST_REV, "", ST_SIZE, sid)
    val HQ_VOICES = listOf(
        hqVoice("hi-hq-f1-meera", "मीरा — कोमल महिला (हाई क्वालिटी)", 0),
        hqVoice("hi-hq-f2-anaya", "अनाया — भावुक महिला (हाई क्वालिटी)", 1),
        hqVoice("hi-hq-f3-siya", "सिया — शांत, गहरी महिला (हाई क्वालिटी)", 2),
    )
    /** स्क्रीन पर क्रम: पहले हाई क्वालिटी, फिर पुराने Piper medium */
    val ALL_VOICES: List<Voice> = HQ_VOICES + VOICES
    /** नई पसंद का डिफ़ॉल्ट = हाई क्वालिटी महिला (पहले से Piper medium फ़ोन में हो तो वही - दोबारा डाउनलोड न करना पड़े) */
    val BEST_VOICE = HQ_VOICES.first().id

    /** pure (JVM test): सेव पसंद न हो तो कौन-सी आवाज़ */
    fun pickDefault(saved: String?, oldInstalled: Boolean): String = when {
        saved != null && ALL_VOICES.any { it.id == saved } -> saved
        oldInstalled -> DEFAULT_VOICE
        else -> BEST_VOICE
    }

    /**
     * आवाज़ बनाने वाला इंजन (sherpa-onnx 1.13.8, Apache-2.0) - उसकी .aar (zip) में से सिर्फ़ इस फ़ोन की एक .so.
     * [zipEnd] = .aar में इस फ़ाइल के ख़त्म होने तक के बाइट (बस उतना ही डाउनलोड - arm64 पर ~9 MB, पूरी 38 MB नहीं).
     */
    data class Lib(val abi: String, val sha256: String, val size: Long, val zipEnd: Long)
    const val AAR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
    const val AAR_SIZE = 38_691_998L
    const val LIB_NAME = "libsherpa-onnx-jni.so"
    val LIBS = listOf(
        Lib("arm64-v8a", "b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8", 24_169_352L, 9_208_307L),
        Lib("armeabi-v7a", "2c0350f9c23dda60318c0d195fcc02d505f05cdbfba89492a4468d2c4276be28", 16_437_284L, 17_300_809L),
        Lib("x86_64", "968e24f61d1e4dae0d5e7860aded42fee45b0f804e1c5a42dfa49186864cefc4", 27_359_872L, AAR_SIZE),
    )

    /** espeak-ng-data (हिंदी उच्चारण के नियम, ~1 MB) - सारी आवाज़ों में एक ही, इसलिए एक बार */
    const val ESPEAK = "espeak-ng-data"
    private val KEEP_DICTS = setOf("hi_dict", "en_dict")

    // ------------------------------------------------------------------ पसंद
    fun sp(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val SPEED_DEF = 100; const val PITCH_DEF = 100; const val REVERB_DEF = 30; const val BASS_DEF = 0; const val PAUSE_DEF = 100

    fun on(c: Context): Boolean = sp(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("on", v).apply() }
    fun voice(c: Context): String = pickDefault(sp(c).getString("voice", null), voiceOk(c, DEFAULT_VOICE))
    fun setVoice(c: Context, v: String) { if (ALL_VOICES.any { it.id == v }) sp(c).edit().putString("voice", v).apply() }
    /** % (100 = 1x) */
    fun speed(c: Context): Int = sp(c).getInt("speed", SPEED_DEF).coerceIn(50, 200)
    /** % (100 = सामान्य) */
    fun pitch(c: Context): Int = sp(c).getInt("pitch", PITCH_DEF).coerceIn(70, 140)
    fun reverbOn(c: Context): Boolean = sp(c).getBoolean("reverb_on", false)
    fun reverb(c: Context): Int = sp(c).getInt("reverb", REVERB_DEF).coerceIn(0, 100)
    /** dB (0 = बंद) */
    fun bass(c: Context): Int = sp(c).getInt("bass", BASS_DEF).coerceIn(0, 12)
    /** वाक्यों के बीच का ठहराव % (100 = पहले जैसा) */
    fun pause(c: Context): Int = sp(c).getInt("pause", PAUSE_DEF).coerceIn(0, 300)
    fun setInt(c: Context, k: String, v: Int) { sp(c).edit().putInt(k, v).apply() }
    fun setReverbOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("reverb_on", v).apply() }
    fun resetTune(c: Context) {
        sp(c).edit().remove("speed").remove("pitch").remove("reverb_on").remove("reverb").remove("bass").remove("pause").apply()
    }
    /** आख़िरी गड़बड़ी (Piper अपने-आप बंद हुआ तो क्यों) */
    fun error(c: Context): String? = sp(c).getString("error", null)
    fun setError(c: Context, why: String?) { sp(c).edit().apply { if (why == null) remove("error") else putString("error", why) }.apply() }

    /** एक टुकड़े के लिए आवाज़ की सेटिंग (एक साथ पढ़ी) */
    data class Tune(val speed: Float, val pitch: Float, val bassDb: Float, val reverb: Float)
    fun tune(c: Context): Tune = Tune(speed(c) / 100f, pitch(c) / 100f, bass(c).toFloat(), if (reverbOn(c)) reverb(c) / 100f else 0f)

    // ------------------------------------------------------------------ फ़ाइलें
    fun root(c: Context) = File(c.applicationContext.filesDir, "piper")
    fun libDir(c: Context) = File(root(c), "lib")
    fun libFile(c: Context) = File(libDir(c), LIB_NAME)
    fun libOk(c: Context) = File(libDir(c), ".ok").exists() && libFile(c).exists()
    fun espeakDir(c: Context) = File(root(c), ESPEAK)
    fun espeakOk(c: Context) = File(root(c), ".espeak_ok").exists() && espeakDir(c).isDirectory
    /** HQ आवाज़ें सब एक ही फ़ोल्डर (एक मॉडल) में */
    fun voiceDir(c: Context, id: String) = File(File(root(c), "voices"), if (voiceOf(id).hq) ST_DIR else id)
    fun voiceOk(c: Context, id: String) = File(voiceDir(c, id), ".ok").exists()
    fun voiceOf(id: String): Voice = ALL_VOICES.firstOrNull { it.id == id } ?: VOICES.first()

    /** इंजन + भाषा फ़ाइलें + यह आवाज़ - सब फ़ोन में */
    fun installed(c: Context, id: String = voice(c)): Boolean = libOk(c) && espeakOk(c) && voiceOk(c, id)
    fun anyInstalled(c: Context): Boolean = libOk(c) && espeakOk(c) && ALL_VOICES.any { voiceOk(c, it.id) }
    fun installedVoices(c: Context): List<Voice> = ALL_VOICES.filter { voiceOk(c, it.id) }

    fun diskBytes(c: Context): Long = root(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun freeBytes(c: Context): Long = try { android.os.StatFs(c.applicationContext.filesDir.path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }

    /** अभी जो बाकी है उसके लिए कितनी जगह चाहिए (+30 MB गुंजाइश) */
    fun needBytes(c: Context, id: String): Long {
        var n = 30L shl 20
        if (!libOk(c)) n += LIBS.maxOf { it.size }
        if (!espeakOk(c)) n += 2L shl 20
        if (!voiceOk(c, id)) n += voiceOf(id).onnxSize
        return n
    }

    /** सब हटाओ (जगह खाली) - Piper बंद, बाकी ऐप की सेटिंग वैसी ही */
    fun delete(c: Context): Boolean {
        setOn(c, false)
        setError(c, null)
        return root(c).deleteRecursively()
    }

    // ------------------------------------------------------------------ pure (JVM test)
    /** espeak-ng-data में से रखना है? बाकी सब भाषाओं की बड़ी dict फ़ाइलें नहीं (सिर्फ़ हिंदी + English) */
    fun keepEspeak(path: String): Boolean {
        if (!path.startsWith("$ESPEAK/")) return false
        val name = path.substringAfterLast('/')
        if (path.count { it == '/' } == 1 && name.endsWith("_dict")) return name in KEEP_DICTS
        return true
    }

    /**
     * यह ऐप फ़ोन पर किस ABI में चल रहा है: ऐप की अपनी native-lib जगह से (lib/arm64 = arm64-v8a ...), नहीं पता तो
     * फ़ोन की सूची में पहली जो हमारे पास है. null = यह फ़ोन Piper नहीं चला सकता.
     */
    fun abiFor(nativeLibDir: String?, supported: List<String>): Lib? {
        val tail = nativeLibDir?.trimEnd('/')?.substringAfterLast('/')
        val byDir = when (tail) { "arm64", "arm64-v8a" -> "arm64-v8a"; "arm", "armeabi-v7a" -> "armeabi-v7a"; "x86_64" -> "x86_64"; else -> null }
        byDir?.let { a -> LIBS.firstOrNull { it.abi == a }?.let { return it } }
        for (s in supported) LIBS.firstOrNull { it.abi == s }?.let { return it }
        return null
    }

    /** Hugging Face फ़ाइल का पता (रास्ते में खाली जगह / ! जैसे निशान: "voices/!v/Mr serious") */
    fun fileUrl(v: Voice, path: String): String = "https://huggingface.co/${v.repo}/resolve/${v.rev}/" + path.split('/').joinToString("/") { enc(it) }
    fun treeUrl(v: Voice): String = "https://huggingface.co/api/models/${v.repo}/tree/${v.rev}/$ESPEAK?recursive=true"

    fun enc(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val ch = b.toInt() and 0xff
            val ok = (ch in 'a'.code..'z'.code) || (ch in 'A'.code..'Z'.code) || (ch in '0'.code..'9'.code) || ch == '-'.code || ch == '_'.code || ch == '.'.code || ch == '~'.code
            if (ok) sb.append(ch.toChar()) else sb.append('%').append(String.format("%02X", ch))
        }
        return sb.toString()
    }
}
