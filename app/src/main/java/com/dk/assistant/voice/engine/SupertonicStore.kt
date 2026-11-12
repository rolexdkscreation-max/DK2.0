package com.dk.assistant.voice.engine

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * नवंबर 2026 (मालिक: "पहला TTS पूरी तरह हटाओ, फिर वही TTS नए सिरे से - 5-6 महिला आवाज़ें, सिर्फ़ स्पीड और पिच,
 * बाकी कोई option नहीं, मॉडल APK में नहीं - इंटरनेट से"): Supertonic 3 (नया) की सारी पसंद और फ़ाइलें.
 *
 * - बिल्कुल नई जगह: prefs [PREFS] ("dk_supertonic3"), फ़ोल्डर filesDir/[DIR] ("supertonic3"). पुराने Supertonic / Piper
 *   की कोई चीज़ नहीं पढ़ी जाती (कोई migration नहीं) - फ़ोन में बची पुरानी गड़बड़ नई आवाज़ को छू भी न सके.
 *   पुराना सब [VoiceEngines.wipeOldOffline] एक बार मिटा देता है.
 * - चालू है या नहीं = [VoiceEngines] की "active" पसंद (एक ही जगह). यहाँ सिर्फ़ आवाज़, स्पीड, पिच.
 * - फ़ाइलें APK में नहीं: [SupertonicDownload] पहली बार इंटरनेट से लाता है.
 */
object SupertonicStore {
    const val PREFS = "dk_supertonic3"
    const val DIR = "supertonic3"

    /**
     * Supertonic 3 (Supertone, OpenRAIL-M) - sherpa-onnx वाला int8 पैक, Hugging Face पर पक्का commit [REV].
     * 44.1 kHz, हिंदी सीधे देवनागरी से. एक ही मॉडल (~138 MB, एक बार) में सारी आवाज़ें - हर आवाज़ बस एक [Voice.sid].
     */
    const val MODEL_DIR = "model"
    const val REPO = "csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    const val REV = "cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4"
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
     * आवाज़ = मॉडल के अंदर की एक sid. Supertonic 3 में sid 0-4 महिला (F1-F5), 5-9 पुरुष. मालिक: "5-6 महिला आवाज़ें" -
     * मॉडल में महिला आवाज़ें पाँच ही हैं, तो पाँचों (sandbox नाप, हिंदी वाक्य: 169-213 Hz - सब महिला सुर). पहली = डिफ़ॉल्ट.
     */
    data class Voice(val id: String, val title: String, val sid: Int)
    val VOICES = listOf(
        Voice("st3-f1-meera", "मीरा — कोमल, नेचुरल महिला", 0),
        Voice("st3-f2-anaya", "अनाया — भावुक, अपनापन भरी महिला", 1),
        Voice("st3-f3-siya", "सिया — शांत, सधी हुई महिला", 2),
        Voice("st3-f4-tara", "तारा — मीठी, खिली-खिली महिला", 3),
        Voice("st3-f5-kavya", "काव्या — गहरी, गंभीर महिला", 4),
    )
    val DEFAULT_VOICE = VOICES.first().id

    /** pure (JVM test): सेव पसंद न हो / अब मौजूद न हो तो डिफ़ॉल्ट */
    fun pickDefault(saved: String?): String = if (saved != null && VOICES.any { it.id == saved }) saved else DEFAULT_VOICE
    fun voiceOf(id: String?): Voice = VOICES.firstOrNull { it.id == id } ?: VOICES.first()

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

    // ------------------------------------------------------------------ पसंद (सिर्फ़ आवाज़, स्पीड, पिच)
    fun sp(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val SPEED_MIN = 50; const val SPEED_MAX = 200
    const val PITCH_MIN = 70; const val PITCH_MAX = 140
    const val NORMAL = 100

    fun voice(c: Context): String = pickDefault(sp(c).getString("voice", null))
    fun setVoice(c: Context, v: String) { if (VOICES.any { it.id == v }) sp(c).edit().putString("voice", v).apply() }
    /** % (100 = 1x) */
    fun speed(c: Context): Int = sp(c).getInt("speed", NORMAL).coerceIn(SPEED_MIN, SPEED_MAX)
    fun setSpeed(c: Context, v: Int) { sp(c).edit().putInt("speed", v.coerceIn(SPEED_MIN, SPEED_MAX)).apply() }
    /** % (100 = सामान्य) */
    fun pitch(c: Context): Int = sp(c).getInt("pitch", NORMAL).coerceIn(PITCH_MIN, PITCH_MAX)
    fun setPitch(c: Context, v: Int) { sp(c).edit().putInt("pitch", v.coerceIn(PITCH_MIN, PITCH_MAX)).apply() }

    /** आख़िरी गड़बड़ी (Supertonic अभी क्यों नहीं चला) */
    fun error(c: Context): String? = sp(c).getString("error", null)
    fun setError(c: Context, why: String?) { sp(c).edit().apply { if (why == null) remove("error") else putString("error", why) }.apply() }

    // ------------------------------------------------------------------ फ़ाइलें
    fun root(c: Context) = File(c.applicationContext.filesDir, DIR)
    fun libDir(c: Context) = File(root(c), "lib")
    fun libFile(c: Context) = File(libDir(c), LIB_NAME)
    fun libOk(c: Context) = File(libDir(c), ".ok").exists() && libFile(c).exists()
    fun modelDir(c: Context) = File(root(c), MODEL_DIR)
    fun modelOk(c: Context) = File(modelDir(c), ".ok").exists()

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

    /** सब हटाओ (जगह खाली) - फ़ाइलें + इसकी पसंद */
    fun delete(c: Context): Boolean {
        sp(c).edit().clear().apply()
        return root(c).deleteRecursively()
    }

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
