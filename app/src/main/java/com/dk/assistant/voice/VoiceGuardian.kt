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
    /** 16 नवंबर 2026: हर सैंपल की अलग पहचान (15 सैंपल वाली पहचान) */
    private fun kTpl(id: String) = "vg_tpl_$id"
    /** 16 नवंबर 2026: "हे डीके" वाले छोटे सैंपल (सिर्फ़ वेक वर्ड के आवाज़-मिलान में) */
    private fun kWake(id: String) = "vg_wake_$id"
    /** 16 नवंबर 2026: पिछले सफल आवाज़-अनलॉक की पहचान (replay पकड़ने को, [UnlockRules.REPLAY_COS]) */
    private fun kSeen(id: String) = "vg_unlock_seen_$id"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val supported: Boolean get() = VoiceExec.supported

    fun modelId(c: Context): String = sp(c).getString(K_MODEL, VgRules.DEFAULT_MODEL) ?: VgRules.DEFAULT_MODEL
    fun asset(c: Context): ModelCatalog.Asset = VgRules.model(modelId(c))
    fun setModel(c: Context, id: String) { sp(c).edit().putString(K_MODEL, VgRules.model(id).id).apply(); close() }

    fun threshold(c: Context): Float = VgRules.clampTh(sp(c).getFloat(K_TH, VgRules.TH_DEFAULT))
    fun setThreshold(c: Context, t: Float) { sp(c).edit().putFloat(K_TH, VgRules.clampTh(t)).apply() }

    // 16 नवंबर 2026 (वेक वर्ड): voiceprint अब Android Keystore से बंद (encrypted) - पुराना सादा भी पढ़ा जाता है ([vaultRead])
    fun print(c: Context): FloatArray? = VoicePrint.decode(vaultRead(c, kPrint(modelId(c))))
    fun enrolled(c: Context) = print(c) != null
    fun savePrint(c: Context, v: FloatArray) { wakeRef = null; sp(c).edit().putString(kPrint(modelId(c)), com.dk.assistant.wake.VoiceVault.seal(VoicePrint.encode(VoicePrint.normalize(v)))).apply() }
    fun forget(c: Context) { wakeRef = null; sp(c).edit().apply { VgRules.MODELS_ALL.forEach { remove(kPrint(it.id)); remove(kTpl(it.id)); remove(kWake(it.id)); remove(kSeen(it.id)) } }.apply(); away.reset() }

    // ------------------------------------------------------------ 16 नवंबर 2026: 15 सैंपल
    fun templates(c: Context): List<FloatArray> = VgRules.decodeAll(vaultRead(c, kTpl(modelId(c))))

    /** बंद हो तो खोलो; पुराना सादा मिला = पढ़ो और उसी समय बंद करके फिर सेव (एक बार की बदली) */
    private fun vaultRead(c: Context, k: String): String? {
        val raw = sp(c).getString(k, null) ?: return null
        if (!com.dk.assistant.wake.VoiceVault.sealed(raw)) {
            val s = com.dk.assistant.wake.VoiceVault.seal(raw)
            if (s != null && s != raw) sp(c).edit().putString(k, s).apply()
            return raw
        }
        return com.dk.assistant.wake.VoiceVault.open(raw)
    }

    /** सैंपल सेव + उन्हीं का औसत (पुराना रास्ता भी वैसा ही चले) */
    fun saveTemplates(c: Context, list: List<FloatArray>) {
        wakeRef = null
        val keep = list.takeLast(VgRules.MAX_TEMPLATES).map { VoicePrint.normalize(it) }
        val m = VoicePrint.mean(keep) ?: return
        sp(c).edit().putString(kTpl(modelId(c)), com.dk.assistant.wake.VoiceVault.seal(VgRules.encodeAll(keep)))
            .putString(kPrint(modelId(c)), com.dk.assistant.wake.VoiceVault.seal(VoicePrint.encode(m))).apply()
    }

    /** 16 नवंबर 2026: पिछले सफल आवाज़-अनलॉक (ताले में, सिर्फ़ इसी फ़ोन में) */
    fun unlockSeen(c: Context): List<FloatArray> = VgRules.decodeAll(vaultRead(c, kSeen(modelId(c))))
    fun rememberUnlock(c: Context, e: FloatArray) {
        val keep = (unlockSeen(c) + VoicePrint.normalize(e)).takeLast(UnlockRules.SEEN_MAX)
        sp(c).edit().putString(kSeen(modelId(c)), com.dk.assistant.wake.VoiceVault.seal(VgRules.encodeAll(keep))).apply()
    }

    /** 16 नवंबर 2026: "हे डीके" के छोटे सैंपल (ताले में बंद, सिर्फ़ इसी फ़ोन में) */
    fun wakeTemplates(c: Context): List<FloatArray> = VgRules.decodeAll(vaultRead(c, kWake(modelId(c))))
    fun saveWakeTemplates(c: Context, list: List<FloatArray>) {
        wakeRef = null
        val keep = list.takeLast(VgRules.MAX_TEMPLATES).map { VoicePrint.normalize(it) }
        sp(c).edit().putString(kWake(modelId(c)), com.dk.assistant.wake.VoiceVault.seal(VgRules.encodeAll(keep))).apply()
    }

    /**
     * 16 नवंबर 2026 (मालिक: "हे डीके पर तुरंत चालू नहीं होता"): वेक वर्ड वाला मिलान.
     * पहले सिर्फ़ लंबे वाक्यों के औसत से मिलाता था - छोटे "हे डीके" पर score बहुत कम आता था (मालिक भी अनसुना).
     * अब: आगे-पीछे की चुप्पी काटकर, सारे सैंपल (छोटे "हे डीके" वाले भी) से [VgRules.multiScore].
     */
    fun scoreWake(c: Context, pcm: ByteArray): Float? {
        val id = modelId(c)
        // सैंपल हर बार ताले (Keystore) से खोलना = देर; एक बार खोलकर मेमोरी में (नया सीखा / मिटाया = फिर से)
        val ref = wakeRef?.takeIf { it.first == id }?.second
            ?: (print(c)?.let { it to (templates(c) + wakeTemplates(c)) })?.also { wakeRef = id to it }
            ?: return null
        val e = embed(c, VgRules.trimSilence(pcm)) ?: return null
        return if (ref.second.isNotEmpty()) VgRules.multiScore(ref.first, ref.second, e) else VoicePrint.cosine(ref.first, e)
    }
    @Volatile private var wakeRef: Pair<String, Pair<FloatArray, List<FloatArray>>>? = null

    /** वेक वर्ड का "सिर्फ़ मालिक" मोड: मॉडल पहले से खोलकर रखो (पहले "हे डीके" पर मॉडल खुलने में देर = मालिक भी अनसुना होता था) */
    fun prime(c: Context) { if (supported && session == null && enrolled(c) && hasModel(c)) VoiceExec.exec.execute { try { open(c) } catch (_: Throwable) {} } }

    /** 16 नवंबर 2026: पक्की रोक (हर बात पहचान तक रुकी) */
    fun strictOn(c: Context): Boolean = try { FeatureFlags.on(c, FeatureFlags.VG_STRICT) } catch (_: Exception) { false }

    fun deepOn(c: Context): Boolean = try { FeatureFlags.on(c, FeatureFlags.VG_DEEP) } catch (_: Exception) { false }

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

    fun close() { synchronized(lock) { try { session?.close() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }; session = null; sessionFor = ""; broken = null } }

    fun warm(c: Context) { if (gateOn(c) && session == null) VoiceExec.exec.execute { try { open(c) } catch (_: Throwable) {} } }

    private fun open(c: Context): OrtSession? {
        val a = asset(c)
        session?.takeIf { sessionFor == a.id }?.let { return it }
        synchronized(lock) {
            session?.takeIf { sessionFor == a.id }?.let { return it }
            try { session?.close() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
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
        if (deepOn(c)) { val t = templates(c); if (t.isNotEmpty()) return VgRules.multiScore(p, t, e) }
        return VoicePrint.cosine(p, e)
    }

    /**
     * 16 नवंबर 2026: आवाज़ से अनलॉक वाला मिलान - हमेशा सारे सैंपल से ([VgRules.multiScore], "गहरी जाँच" स्विच हो या न हो).
     * @return अंक + उसी बात की पहचान (replay जाँच के लिए); null = मिलान नहीं हो पाया
     */
    fun unlockScore(c: Context, pcm: ByteArray): Pair<Float, FloatArray>? {
        val p = print(c) ?: return null
        val e = embed(c, pcm) ?: return null
        val t = templates(c)
        return (if (t.isNotEmpty()) VgRules.multiScore(p, t, e) else VoicePrint.cosine(p, e)) to e
    }

    // ------------------------------------------------------------ 21 नवंबर 2026: परिवार की आवाज़ें + अवेयरनेस मोड
    /** परिवार के सैंपल - हर मॉडल के अलग (मॉडल बदला = फिर से सिखाने होंगे), ताले में बंद */
    private fun kFam(id: String) = "vg_family_$id"
    @Volatile private var famCache: Pair<String, List<VoiceFamily.Member>>? = null

    fun family(c: Context): List<VoiceFamily.Member> {
        val id = modelId(c)
        famCache?.takeIf { it.first == id }?.let { return it.second }
        return VoiceFamily.decode(vaultRead(c, kFam(id))).also { famCache = id to it }
    }

    fun saveFamily(c: Context, list: List<VoiceFamily.Member>) {
        val id = modelId(c)
        val keep = list.take(VoiceFamily.MAX_MEMBERS)
        famCache = id to keep
        val e = sp(c).edit()
        if (keep.isEmpty()) e.remove(kFam(id)) else e.putString(kFam(id), com.dk.assistant.wake.VoiceVault.seal(VoiceFamily.encode(keep)))
        e.apply()
    }

    /** परिवार के सारे सैंपल मिटाओ (हर मॉडल के) */
    fun forgetFamily(c: Context) { famCache = null; sp(c).edit().apply { VgRules.MODELS_ALL.forEach { remove(kFam(it.id)) } }.apply(); Awareness.global.reset() }

    /** अवेयरनेस मोड: स्विच चालू + मॉडल + (बॉस या परिवार की सीखी आवाज़); बैटरी सेवर में "वॉइस गार्डियन रोको" = बंद */
    fun awareOn(c: Context): Boolean = try {
        FeatureFlags.on(c, FeatureFlags.VG_AWARE) && supported && broken == null && hasModel(c) &&
            !FeatureFlags.suspendedByBattery(c, FeatureFlags.BS_VOICE_GUARDIAN) && (enrolled(c) || family(c).isNotEmpty())
    } catch (_: Exception) { false }

    /**
     * कौन बोल रहा है: एक ही बार पहचान निकालकर बॉस + परिवार सबसे मिलान.
     * @return (बॉस से मिलान - [score] जैसा ही, सीखा न हो तो null) + फ़ैसला; null = आवाज़ से पहचान नहीं बनी
     */
    fun identify(c: Context, pcm: ByteArray): Pair<Float?, VoiceFamily.Pick>? {
        val e = embed(c, pcm) ?: return null
        val p = print(c)
        val own = if (p == null) null else {
            val t = if (deepOn(c)) templates(c) else emptyList()
            if (t.isNotEmpty()) VgRules.multiScore(p, t, e) else VoicePrint.cosine(p, e)
        }
        return own to VoiceFamily.identify(own, threshold(c), family(c), e)
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
                if (svc != null && Build.VERSION.SDK_INT >= 28) try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
            }
            else -> {}
        }
    }
}
