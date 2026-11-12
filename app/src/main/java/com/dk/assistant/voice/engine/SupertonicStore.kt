package com.dk.assistant.voice.engine

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * नवंबर 2026 (मालिक: "Supertonic 3 TTS लगाओ - ठीक वैसे ही जैसे Piper लगा था, वही सेटअप. Piper पूरी तरह हटाओ.
 * मॉडल ऐप के अंदर नहीं, इंटरनेट से डाउनलोड. नेचुरल महिला आवाज़ें, तीन-चार"): Supertonic 3 की सारी पसंद और फ़ाइलें.
 *
 * - पसंद अलग prefs ("dk_supertonic") में - मौजूदा आवाज़ इंजन की पसंद (VoiceEngines.sp) को छूती तक नहीं.
 *   इसलिए Supertonic चालू/बंद करने से पुरानी सेटिंग (DK लाइव / Gemini TTS, आवाज़, अंदाज़) जस की तस.
 * - फ़ाइलें APK में नहीं: पहली बार इंटरनेट से [SupertonicDownload] लाता है -> filesDir/supertonic/ (सिर्फ़ इस ऐप की जगह).
 * - सब डिफ़ॉल्ट = बिना इफ़ेक्ट (स्पीड 1x, पिच सामान्य, रिवर्ब बंद, बास बंद, ठहराव पहले जैसा).
 * - Piper (VITS) पूरी तरह हटाया: उसकी आवाज़ें, espeak-ng-data, उसका फ़ोल्डर. फ़ोन में पहले से उतरा Supertonic मॉडल /
 *   इंजन [migrate] एक बार नई जगह ले आता है (दोबारा डाउनलोड नहीं), बाकी पुरानी Piper फ़ाइलें मिटा देता है.
 *   बैकअप (Piper वाला कोड): branch backup/dk-piper-tts-1112.
 */
object SupertonicStore {
    const val PREFS = "dk_supertonic"
    /** हटाए गए Piper की पुरानी पसंद / फ़ोल्डर - सिर्फ़ एक बार [migrate] के लिए */
    const val OLD_PREFS = "dk_piper"
    const val OLD_DIR = "piper"

    /**
     * Supertonic 3 (Supertone, OpenRAIL-M) - sherpa-onnx वाला int8 पैक, Hugging Face पर पक्का commit [REV].
     * 44.1 kHz, हिंदी सीधे देवनागरी से (espeak नहीं), इंसान जैसी लय. एक ही मॉडल (~138 MB, एक बार) में सारी आवाज़ें -
     * हर आवाज़ बस एक [Voice.sid] (आवाज़ बदलने पर न डाउनलोड, न मॉडल दोबारा लोड).
     */
    const val MODEL_DIR = "supertonic-3"
    const val REPO = "csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    const val REV = "cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4"
    /** एक वाक्य कितने चरण में साफ़ हो (ज़्यादा = साफ़ पर धीमा). 8 = Supertone की सुझाई हाई क्वालिटी */
    const val STEPS = 8
    data class ModelFile(val name: String, val sha256: String, val size: Long)
    val FILES = listOf(
        ModelFile("tts.json", "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09", 8_253L),
        ModelFile("unicode_indexer.bin", "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30", 262_144L),
        ModelFile("voice.bin", "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8", 517_168L),
        ModelFile("duration_predictor.int8.onnx", "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db", 3_700_147L),
        ModelFile("vocoder.int8.onnx", "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152", 25_991_073L),
        ModelFile("text_encoder.int8.onnx", "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff", 36_416_150L),
        ModelFile("vector_estimator.int8.onnx", "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd", 78_400_833L),
    )
    val MODEL_SIZE: Long = FILES.sumOf { it.size }

    /**
     * आवाज़ = मॉडल के अंदर की एक [sid]. sandbox नाप (हिंदी वाक्य): sid 0-4 महिला (F1-F5), 5-9 पुरुष.
     * मालिक: "फ़ीमेल, नेचुरल - तीन-चार" = चार महिला आवाज़ें. पहली = डिफ़ॉल्ट. (id पुराने ही - सेव पसंद बनी रहे)
     */
    data class Voice(val id: String, val title: String, val sid: Int)
    val VOICES = listOf(
        Voice("hi-hq-f1-meera", "मीरा — कोमल, नेचुरल महिला", 0),
        Voice("hi-hq-f2-anaya", "अनाया — भावुक, अपनापन भरी महिला", 1),
        Voice("hi-hq-f3-siya", "सिया — शांत, गहरी महिला", 2),
        Voice("hi-hq-f4-tara", "तारा — मीठी, खिली-खिली महिला", 3),
    )
    val DEFAULT_VOICE = VOICES.first().id

    /** pure (JVM test): सेव पसंद न हो / अब मौजूद न हो (जैसे हटाई Piper आवाज़) तो डिफ़ॉल्ट */
    fun pickDefault(saved: String?): String = if (saved != null && VOICES.any { it.id == saved }) saved else DEFAULT_VOICE

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

    // ------------------------------------------------------------------ पसंद
    @Volatile private var migrated = false

    fun sp(c: Context): SharedPreferences {
        val app = c.applicationContext
        if (!migrated) migrate(app)
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    const val SPEED_DEF = 100; const val PITCH_DEF = 100; const val REVERB_DEF = 30; const val BASS_DEF = 0; const val PAUSE_DEF = 100
    /** पुरानी Piper पसंद में से जो Supertonic पर भी लागू होती है (स्विच, आवाज़, इफ़ेक्ट, चरण) */
    val CARRY_INTS = listOf("speed", "pitch", "reverb", "bass", "pause")
    val CARRY_BOOLS = listOf("on", "reverb_on")

    fun on(c: Context): Boolean = sp(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("on", v).apply() }
    fun voice(c: Context): String = pickDefault(sp(c).getString("voice", null))
    fun setVoice(c: Context, v: String) { if (VOICES.any { it.id == v }) sp(c).edit().putString("voice", v).apply() }
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
    /** आख़िरी गड़बड़ी (Supertonic अभी क्यों नहीं चला) */
    fun error(c: Context): String? = sp(c).getString("error", null)
    fun setError(c: Context, why: String?) { sp(c).edit().apply { if (why == null) remove("error") else putString("error", why) }.apply() }

    /** एक टुकड़े के लिए आवाज़ की सेटिंग (एक साथ पढ़ी) */
    data class Tune(val speed: Float, val pitch: Float, val bassDb: Float, val reverb: Float)
    fun tune(c: Context): Tune = Tune(speed(c) / 100f, pitch(c) / 100f, bass(c).toFloat(), if (reverbOn(c)) reverb(c) / 100f else 0f)

    // ------------------------------------------------------------------ फ़ाइलें
    fun root(c: Context) = File(c.applicationContext.filesDir, "supertonic")
    fun libDir(c: Context) = File(root(c), "lib")
    fun libFile(c: Context) = File(libDir(c), LIB_NAME)
    fun libOk(c: Context) = File(libDir(c), ".ok").exists() && libFile(c).exists()
    /** सारी आवाज़ें एक ही फ़ोल्डर (एक मॉडल) में */
    fun modelDir(c: Context) = File(root(c), MODEL_DIR)
    fun modelOk(c: Context) = File(modelDir(c), ".ok").exists()
    fun voiceOf(id: String): Voice = VOICES.firstOrNull { it.id == id } ?: VOICES.first()

    /** इंजन + मॉडल - दोनों फ़ोन में (मॉडल एक = हर आवाज़ तैयार) */
    fun installed(c: Context): Boolean = libOk(c) && modelOk(c)

    fun diskBytes(c: Context): Long = root(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun freeBytes(c: Context): Long = try { android.os.StatFs(c.applicationContext.filesDir.path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }

    /** अभी जो बाकी है उसके लिए कितनी जगह चाहिए (+30 MB गुंजाइश) */
    fun needBytes(c: Context): Long {
        var n = 30L shl 20
        if (!libOk(c)) n += LIBS.maxOf { it.size }
        if (!modelOk(c)) n += MODEL_SIZE
        return n
    }

    /** सब हटाओ (जगह खाली) - Supertonic बंद, बाकी ऐप की सेटिंग वैसी ही */
    fun delete(c: Context): Boolean {
        setOn(c, false)
        setError(c, null)
        return root(c).deleteRecursively()
    }

    // ------------------------------------------------------------------ हटाए Piper से एक बार
    /**
     * पहली बार (इस version में): पुरानी "dk_piper" पसंद में से स्विच / आवाज़ (सिर्फ़ Supertonic वाली) / इफ़ेक्ट यहाँ,
     * पहले से उतरा Supertonic मॉडल + इंजन (.so) filesDir/piper/ से filesDir/supertonic/ (बस नाम बदलना - दोबारा डाउनलोड नहीं).
     * बाकी Piper (VITS आवाज़ें, espeak-ng-data) पीछे के धागे पर मिटती हैं, पुरानी prefs साफ़.
     */
    private fun migrate(app: Context) = synchronized(this) {
        if (migrated) return@synchronized
        try {
            // 1. फ़ाइलें: पहले से उतरा Supertonic मॉडल + इंजन नई जगह (नाम बदलना = पल भर), बाकी Piper पीछे मिटे
            val oldRoot = File(app.filesDir, OLD_DIR)
            if (oldRoot.exists()) {
                root(app).mkdirs()
                val oldModel = File(File(oldRoot, "voices"), MODEL_DIR)
                if (!modelOk(app) && File(oldModel, ".ok").exists()) { modelDir(app).deleteRecursively(); oldModel.renameTo(modelDir(app)) }
                val oldLib = File(oldRoot, "lib")
                if (!libOk(app) && File(oldLib, ".ok").exists()) { libDir(app).deleteRecursively(); oldLib.renameTo(libDir(app)) }
                val gone = File(app.filesDir, "$OLD_DIR.gone.${System.currentTimeMillis()}")
                if (oldRoot.renameTo(gone)) Thread({ gone.deleteRecursively() }, "dk-old-piper-clean").apply { isDaemon = true; start() }
            }
            // 2. पसंद: स्विच ON सिर्फ़ तब जब Supertonic सच में फ़ोन में है (सिर्फ़ Piper था = बंद, पहले वाला इंजन बोले)
            val now = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = app.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
            if (!now.getBoolean("migrated", false)) {
                val e = now.edit()
                if (old.all.isNotEmpty()) {
                    for (k in CARRY_BOOLS) if (old.contains(k)) e.putBoolean(k, old.getBoolean(k, false))
                    for (k in CARRY_INTS) if (old.contains(k)) e.putInt(k, old.getInt(k, 0))
                    if (old.contains(SupertonicSpeed.OLD_PREF)) e.putInt(SupertonicSpeed.PREF, old.getInt(SupertonicSpeed.OLD_PREF, SupertonicSpeed.START_STEPS))
                    carryVoice(old.getString("voice", null))?.let { e.putString("voice", it) }
                    if (!carryOn(old.getBoolean("on", false), installed(app))) e.putBoolean("on", false)
                }
                e.putBoolean("migrated", true).commit()
                old.edit().clear().apply()
            }
        } catch (_: Throwable) {}
        migrated = true
    }

    /** pure (JVM test): पुराना स्विच ON + Supertonic फ़ोन में = ON; सिर्फ़ Piper था (अब हटा) = OFF */
    fun carryOn(oldOn: Boolean, installed: Boolean): Boolean = oldOn && installed

    /** pure (JVM test): पुरानी पसंद की आवाज़ - Supertonic वाली हो तो वही, हटाई Piper आवाज़ = null (डिफ़ॉल्ट लगेगा) */
    fun carryVoice(old: String?): String? = old?.takeIf { id -> VOICES.any { it.id == id } }

    // ------------------------------------------------------------------ pure (JVM test)
    /**
     * यह ऐप फ़ोन पर किस ABI में चल रहा है: ऐप की अपनी native-lib जगह से (lib/arm64 = arm64-v8a ...), नहीं पता तो
     * फ़ोन की सूची में पहली जो हमारे पास है. null = यह फ़ोन Supertonic नहीं चला सकता.
     */
    fun abiFor(nativeLibDir: String?, supported: List<String>): Lib? {
        val tail = nativeLibDir?.trimEnd('/')?.substringAfterLast('/')
        val byDir = when (tail) { "arm64", "arm64-v8a" -> "arm64-v8a"; "arm", "armeabi-v7a" -> "armeabi-v7a"; "x86_64" -> "x86_64"; else -> null }
        byDir?.let { a -> LIBS.firstOrNull { it.abi == a }?.let { return it } }
        for (s in supported) LIBS.firstOrNull { it.abi == s }?.let { return it }
        return null
    }

    /** Hugging Face फ़ाइल का पता (पक्का commit) */
    fun fileUrl(path: String): String = "https://huggingface.co/$REPO/resolve/$REV/" + path.split('/').joinToString("/") { enc(it) }

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
