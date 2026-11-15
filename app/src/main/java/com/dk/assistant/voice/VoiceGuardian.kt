package com.dk.assistant.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.PlusVoice
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import java.nio.FloatBuffer

/**
 * 15 नवंबर 2026 (मालिक, हिस्सा 2): वॉइस गार्डियन - फ़ोन पर ही आवाज़-पहचान (RedimNet ONNX).
 *
 *  - मॉडल APK में नहीं: Settings → "मॉडल डाउनलोड करो" ([ModelManager]: progress, resume, SHA-256, filesDir/models).
 *    नया डाउनलोड फेल हो तो पुरानी (पहले से सही) फ़ाइल वैसी ही रहती है - नई फ़ाइल .part में, SHA मिलने पर ही बदलती है.
 *  - Voiceprint सिर्फ़ इसी फ़ोन में (अपनी prefs फ़ाइल, backup बंद); आवाज़ कभी सेव / इंटरनेट पर नहीं जाती.
 *  - मिलान सिर्फ़ तब जब कोई बोल रहा हो (Live का [OwnerGate] रास्ता) - बाकी समय मॉडल CPU नहीं लेता.
 *  - "सिर्फ़ मेरी आवाज़" (v4.5 में हटा पुराना स्विच) फिर से नहीं जुड़ा - यह नया, अलग स्विच है ([FeatureFlags.VG_OWNER_ONLY]).
 */
object VoiceGuardian {
    private const val PREFS = "dk_voice_guardian"
    private const val K_MODEL = "vg_model"
    private const val K_TH = "vg_threshold"
    private fun kPrint(id: String) = "vg_print_$id"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val supported: Boolean get() = OwnerVoice.supported

    fun modelId(c: Context): String = sp(c).getString(K_MODEL, VgRules.DEFAULT_MODEL) ?: VgRules.DEFAULT_MODEL
    fun asset(c: Context): ModelCatalog.Asset = VgRules.model(modelId(c))
    fun setModel(c: Context, id: String) { sp(c).edit().putString(K_MODEL, VgRules.model(id).id).apply(); close() }

    fun threshold(c: Context): Float = VgRules.clampTh(sp(c).getFloat(K_TH, VgRules.TH_DEFAULT))
    fun setThreshold(c: Context, t: Float) { sp(c).edit().putFloat(K_TH, VgRules.clampTh(t)).apply() }

    fun print(c: Context): FloatArray? = VoicePrint.decode(sp(c).getString(kPrint(modelId(c)), null))
    fun enrolled(c: Context) = print(c) != null
    fun savePrint(c: Context, v: FloatArray) { sp(c).edit().putString(kPrint(modelId(c)), VoicePrint.encode(VoicePrint.normalize(v))).apply() }
    fun forget(c: Context) { sp(c).edit().apply { VgRules.MODELS.forEach { remove(kPrint(it.id)) } }.apply(); away.reset() }

    fun hasModel(c: Context) = ModelManager.ready(c, asset(c))

    @Volatile var broken: String? = null
        private set

    /** मिलान के लिए तैयार (मॉडल + सीखी आवाज़) */
    fun ready(c: Context) = supported && hasModel(c) && enrolled(c) && broken == null

    /** मुख्य स्विच + (सिर्फ़ मेरी आवाज़ या Away) + तैयार; बैटरी सेवर में "वॉइस गार्डियन रोको" = बंद */
    fun gateOn(c: Context): Boolean = try {
        FeatureFlags.on(c, FeatureFlags.VOICE_GUARDIAN) &&
            (FeatureFlags.on(c, FeatureFlags.VG_OWNER_ONLY) || FeatureFlags.on(c, FeatureFlags.VG_AWAY)) &&
            !FeatureFlags.suspendedByBattery(c, FeatureFlags.BS_VOICE_GUARDIAN) && ready(c)
    } catch (_: Exception) { false }

    // ------------------------------------------------------------ ONNX
    @Volatile private var session: OrtSession? = null
    @Volatile private var sessionFor = ""
    private val lock = Any()

    fun close() { synchronized(lock) { try { session?.close() } catch (_: Exception) {}; session = null; sessionFor = ""; broken = null } }

    fun warm(c: Context) { if (gateOn(c) && session == null) OwnerVoice.exec.execute { try { open(c) } catch (_: Throwable) {} } }

    private fun open(c: Context): OrtSession? {
        val a = asset(c)
        session?.takeIf { sessionFor == a.id }?.let { return it }
        synchronized(lock) {
            session?.takeIf { sessionFor == a.id }?.let { return it }
            try { session?.close() } catch (_: Exception) {}
            session = null
            if (!supported || !ModelManager.ready(c, a)) return null
            return try {
                val o = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
                OrtEnvironment.getEnvironment().createSession(ModelManager.file(c, a).absolutePath, o).also { session = it; sessionFor = a.id; broken = null }
            } catch (e: Throwable) {
                broken = e.javaClass.simpleName
                DKLog.d("वॉइस गार्डियन: मॉडल नहीं खुला (${e.javaClass.simpleName}) - DK पहले की तरह सब सुनेगा")
                null
            }
        }
    }

    /** PCM16 16 kHz mono -> 192 अंक (normalize). RedimNet कच्ची आवाज़ (-1..1) लेता है - fbank नहीं */
    fun embed(c: Context, pcm: ByteArray, len: Int = pcm.size): FloatArray? {
        val s = open(c) ?: return null
        val n = len / 2
        if (n < OwnerGate.RATE / 4) return null
        val x = FloatArray(n) { i -> ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        return try {
            val name = s.inputNames.first()
            val rank = (s.inputInfo[name]?.info as? TensorInfo)?.shape?.size ?: 2
            val shape = if (rank >= 3) longArrayOf(1, 1, n.toLong()) else longArrayOf(1, n.toLong())
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(x), shape).use { t ->
                s.run(mapOf(name to t)).use { r ->
                    val v = r[0].value
                    @Suppress("UNCHECKED_CAST")
                    val out = when (v) { is Array<*> -> (v as Array<FloatArray>)[0]; is FloatArray -> v; else -> null }
                    if (out == null || out.size != VoicePrint.DIM || out.any { !it.isFinite() }) null else VoicePrint.normalize(out)
                }
            }
        } catch (e: Throwable) {
            broken = e.javaClass.simpleName
            DKLog.d("वॉइस गार्डियन: मिलान में गड़बड़ (${e.javaClass.simpleName}) - पहचान बंद, DK सब सुनेगा")
            null
        }
    }

    /** मालिक से कितनी मिलती है. null = मिलान नहीं हो पाया (तब DK आवाज़ नहीं खोता) */
    fun score(c: Context, pcm: ByteArray): Float? {
        val p = print(c) ?: return null
        val e = embed(c, pcm) ?: return null
        return VoicePrint.cosine(p, e)
    }

    // ------------------------------------------------------------ Away मोड
    private val away = VgRules.AwayState()

    fun onOwner() = away.reset()

    /** अनजान आवाज़ पहचानी गई (Live या आम सुनना) */
    fun onStranger(c: Context) {
        if (!FeatureFlags.on(c, FeatureFlags.VG_AWAY) || !gateOn(c)) return
        when (away.onStranger(System.currentTimeMillis())) {
            VgRules.Away.WARN -> { DKLog.d("वॉइस गार्डियन (Away): अनजान आवाज़ - चेतावनी"); PlusVoice.say(c, VgRules.WARN_SPEECH) }
            VgRules.Away.LOCK -> {
                DKLog.d("वॉइस गार्डियन (Away): अनजान आवाज़ दोबारा - फ़ोन लॉक")
                PlusVoice.say(c, VgRules.LOCK_SPEECH)
                val svc = DKAccessibilityService.instance
                if (svc != null && Build.VERSION.SDK_INT >= 28) try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) } catch (_: Exception) {}
            }
            else -> {}
        }
    }
}
