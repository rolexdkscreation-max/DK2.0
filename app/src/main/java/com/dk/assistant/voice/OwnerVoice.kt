package com.dk.assistant.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v3.66: फ़ोन पर ही (इंटरनेट के बिना) आवाज़ पहचानने वाला मॉडल।
 * मॉडल: 3D-Speaker CAM++ (zh_en 16k common advanced, Apache-2.0), sherpa-onnx के ONNX रूप में - पहली बार
 * "मेरी आवाज़ सिखाओ" पर एक बार डाउनलोड (~28 MB, SHA-256 जाँच), फिर filesDir में।
 * आवाज़ या पहचान कभी log/इंटरनेट पर नहीं जाती; log में सिर्फ़ मिलान का अंक।
 */
object OwnerVoice {
    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
    const val MODEL_SHA256 = "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2"
    const val MODEL_BYTES = 28_281_164L

    /** मिलान mic thread पर नहीं - अलग एक thread (एक बार में एक) */
    val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-owner-voice").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 } }

    @Volatile private var session: OrtSession? = null
    @Volatile var broken: String? = null
        private set
    private val lock = Any()

    fun modelFile(c: Context) = File(File(c.filesDir, "voice"), "campplus_zh_en.onnx")
    fun hasModel(c: Context): Boolean = modelFile(c).let { it.exists() && it.length() == MODEL_BYTES }
    fun enrolled(c: Context) = Prefs.ownerPrint(c) != null
    /** परत 3 चालू और तैयार */
    /** पहचान वाली लाइब्रेरी सिर्फ़ 64-bit ARM (arm64) फ़ोनों के लिए APK में है (APK छोटा रखने को) */
    val supported: Boolean by lazy {
        try { android.os.Process.is64Bit() && android.os.Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") } catch (_: Throwable) { false }
    }

    fun active(c: Context) = supported && Prefs.ownerVoiceOn(c) && enrolled(c) && hasModel(c) && broken == null

    /** मॉडल पहले से लोड (Live शुरू होते समय, अलग thread पर) - पहली बात पर देरी न हो */
    fun warm(c: Context) { if (active(c) && session == null) exec.execute { try { open(c) } catch (_: Throwable) {} } }

    private fun open(c: Context): OrtSession? {
        session?.let { return it }
        synchronized(lock) {
            session?.let { return it }
            if (!supported || !hasModel(c)) return null
            return try {
                val t0 = System.currentTimeMillis()
                val env = OrtEnvironment.getEnvironment()
                val o = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                env.createSession(modelFile(c).absolutePath, o).also {
                    session = it; broken = null
                    DKLog.d("आवाज़-पहचान मॉडल तैयार (${System.currentTimeMillis() - t0} ms)")
                }
            } catch (e: Throwable) {
                broken = e.javaClass.simpleName
                DKLog.d("आवाज़-पहचान मॉडल नहीं खुला (${e.javaClass.simpleName}) - DK पहले की तरह सब सुनेगा")
                null
            }
        }
    }

    /** PCM16 (16 kHz mono) -> 192 अंकों की पहचान (normalize). null = नहीं बना */
    fun embed(c: Context, pcm: ByteArray, len: Int = pcm.size): FloatArray? {
        val s = open(c) ?: return null
        val x = SpeakerFbank.pcm16(pcm, len)
        val nf = SpeakerFbank.numFrames(x.size)
        if (nf < 10) return null
        val f = SpeakerFbank.compute(x)
        return try {
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(f), longArrayOf(1, nf.toLong(), SpeakerFbank.BINS.toLong())).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val out = (r[0].value as Array<FloatArray>)[0]
                    if (out.size != VoicePrint.DIM || out.any { !it.isFinite() }) null else VoicePrint.normalize(out)
                }
            }
        } catch (e: Throwable) {
            broken = e.javaClass.simpleName
            DKLog.d("आवाज़-पहचान में गड़बड़ (${e.javaClass.simpleName}) - पहचान बंद, DK सब सुनेगा")
            null
        }
    }

    /** मालिक से कितनी मिलती है (-1..1). null = मिलान नहीं हो पाया */
    fun score(c: Context, pcm: ByteArray): Float? {
        val p = Prefs.ownerPrint(c) ?: return null
        val e = embed(c, pcm) ?: return null
        return VoicePrint.cosine(p, e)
    }

    /** मॉडल डाउनलोड (Settings से, IO thread पर). @return null = ठीक, वरना गलती का हिंदी कारण */
    fun download(c: Context, progress: (Int) -> Unit): String? {
        if (hasModel(c)) return null
        val dir = modelFile(c).parentFile!!; dir.mkdirs()
        val part = File(dir, "model.part")
        return try {
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            http.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) return "डाउनलोड नहीं हुआ (HTTP ${resp.code})"
                val body = resp.body ?: return "डाउनलोड खाली आया"
                val md = MessageDigest.getInstance("SHA-256")
                var got = 0L; var lastPct = -1
                body.byteStream().use { inp ->
                    part.outputStream().use { out ->
                        val b = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(b); if (n < 0) break
                            out.write(b, 0, n); md.update(b, 0, n); got += n
                            if (got > MODEL_BYTES + 1024) return "फ़ाइल उम्मीद से बड़ी - रोक दिया"
                            val pct = (got * 100 / MODEL_BYTES).toInt()
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (got != MODEL_BYTES || hex != MODEL_SHA256) { part.delete(); return "फ़ाइल जाँच में ग़लत निकली - फिर से कोशिश करें" }
                if (!part.renameTo(modelFile(c))) { part.delete(); return "फ़ाइल सेव नहीं हुई" }
                null
            }
        } catch (e: Exception) {
            part.delete()
            "इंटरनेट से नहीं आया (${e.javaClass.simpleName})"
        }
    }

    /** सीखी हुई पहचान सेव */
    fun saveEnrollment(c: Context, print: FloatArray, level: Double) {
        Prefs.edit(c) {
            putString("owner_print", VoicePrint.encode(print))
            putFloat("owner_level", level.toFloat()); putFloat("owner_level_now", level.toFloat())
        }
    }

    /** पहचान और मॉडल मिटाओ */
    fun forget(c: Context) {
        Prefs.edit(c) { remove("owner_print"); remove("owner_level"); remove("owner_level_now"); putBoolean("owner_voice_on", false) }
    }

    /** पहचानी गई मालिक की बात का स्तर - दूर/पास का अंदाज़ा धीरे-धीरे सुधरे */
    fun learnLevel(c: Context, utter: Double) {
        val enrolled = Prefs.ownerLevel(c)
        val now = VoicePrint.adaptLevel(enrolled, Prefs.ownerLevelNow(c), utter)
        if (now > 0) Prefs.edit(c) { putFloat("owner_level_now", now.toFloat()) }
    }

    /** परत 4 की सीमा (0 = बंद): Bluetooth headset पर बंद (वहाँ माइक मुँह के पास, स्तर अलग) */
    fun farFloor(c: Context, bluetooth: Boolean): Double {
        if (bluetooth || !Prefs.ownerFarOn(c)) return 0.0
        val lv = Prefs.ownerLevelNow(c).takeIf { it > 0 } ?: Prefs.ownerLevel(c)
        return VoicePrint.farFloor(lv, Prefs.ownerVoiceStrict(c))
    }
}
