package com.dk.assistant.voice

import android.content.Context
import com.dk.assistant.DKLog
import kotlinx.coroutines.async

/**
 * 16 नवंबर 2026: आवाज़ से अनलॉक का पहरेदार (नियम [UnlockRules] में). PIN डालने से ठीक पहले [allow] पूछा जाता है.
 *
 * - [heard]: फ़ोन के हर सुनने वाले रास्ते (आम सुनना, बिना इंटरनेट, Gemini Live का माइक) की हर पूरी बात की आवाज़ -
 *   सिर्फ़ मेमोरी में, आख़िरी एक, ~10 सेकंड काम की. कहीं सेव / इंटरनेट पर नहीं.
 * - [intent]: फ़ोन के अपने रास्ते ने "अनलॉक करो" पहचाना (AI ने नहीं).
 * - [granted]: आवाज़-कमांड वाले रास्ते ने उसी बात को खुद जाँचकर पक्का कर दिया.
 */
object UnlockGuard {
    private class Utter(val pcm: ByteArray, val at: Long)

    /** आवाज़-पहचान का हिस्सा (असल में [VoiceGuardian]; JVM simulation test में नकली - UnlockFlowTest) */
    internal interface Voice {
        fun ready(c: Context): Boolean
        fun score(c: Context, pcm: ByteArray): Pair<Float, FloatArray>?
        fun baseThreshold(c: Context): Float
        fun seen(c: Context): List<FloatArray>
        fun remember(c: Context, e: FloatArray)
    }

    private object RealVoice : Voice {
        override fun ready(c: Context) = VoiceGuardian.ready(c)
        override fun score(c: Context, pcm: ByteArray) = VoiceGuardian.unlockScore(c, pcm)
        override fun baseThreshold(c: Context) = VoiceGuardian.threshold(c)
        override fun seen(c: Context) = VoiceGuardian.templates(c) + VoiceGuardian.unlockSeen(c)
        override fun remember(c: Context, e: FloatArray) = VoiceGuardian.rememberUnlock(c, e)
    }

    @Volatile internal var voice: Voice = RealVoice

    @Volatile private var last: Utter? = null
    @Volatile private var intentAt = 0L
    @Volatile private var grantAt = 0L
    private val strikes = UnlockRules.Strikes()

    /** एक पूरी बोली बात (16 kHz PCM16 mono) */
    fun heard(pcm: ByteArray, now: Long = System.currentTimeMillis()) {
        if (pcm.size >= OwnerGate.RATE * 2 * UnlockRules.MIN_MS / 1000) last = Utter(pcm.copyOf(minOf(pcm.size, OwnerGate.RATE * 2 * 8)), now)
    }

    /** मालिक के सुनने वाले रास्ते ने अनलॉक वाला वाक्य पहचाना */
    fun intent(now: Long = System.currentTimeMillis()) { intentAt = now }

    /** आवाज़-कमांड (अनलॉक) ने यही बात खुद जाँच ली */
    private fun grant(now: Long) { grantAt = now; intentAt = now }

    /** आवाज़-कमांड वाला रास्ता: इसी बात की आवाज़ जाँचो; पास = PIN की इजाज़त ([allow] में) */
    fun checkCommand(c: Context, pcm: ByteArray): UnlockRules.Verdict {
        val now = System.currentTimeMillis()
        val v = verify(c, pcm, now)
        if (v.ok) grant(now)
        return v
    }

    /**
     * PIN डालें? null = हाँ; वरना मालिक को बोला जाने वाला कारण.
     * बात अभी चल रही हो (Live caption ने बीच में पकड़ा) तो खत्म होने का थोड़ा इंतज़ार.
     */
    suspend fun allow(c: Context): String? {
        // 22 नवंबर 2026: अनलॉक शुरू होते ही [prefetch] ने जाँच साथ-साथ शुरू कर दी हो तो उसी का नतीजा (दोबारा इंतज़ार नहीं)
        val p = pending; pending = null
        if (p != null) return try { p.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            DKLog.d("आवाज़ से अनलॉक: जाँच नहीं चली (${e.message})"); UnlockRules.reason(UnlockRules.Verdict.NO_VOICE)
        }
        return allow(c, ownerOnly = true)
    }

    @Volatile private var pending: kotlinx.coroutines.Deferred<String?>? = null

    /**
     * 22 नवंबर 2026 (मालिक: "अनलॉक बोलते ही तुरंत"): पहले स्क्रीन जगना + PIN वाला हिस्सा आना (1-2s), उसके *बाद* आवाज़ की जाँच.
     * अब दोनों साथ-साथ: जाँच यहाँ शुरू, PIN डालने से ठीक पहले [allow] इसका नतीजा लेता है.
     */
    fun prefetch(c: Context, ownerOnly: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
        pending?.cancel()
        pending = scope.async(kotlinx.coroutines.Dispatchers.Default) { allow(c, ownerOnly) }
    }

    /** फ़ोन PIN के बिना ही खुल गया - साथ वाली जाँच की ज़रूरत नहीं */
    fun dropPending() { pending?.cancel(); pending = null }

    /**
     * 22 नवंबर 2026 (मालिक: "सिर्फ़ मालिक की आवाज़" वाला switch): ownerOnly = false = कोई भी बोले तो PIN -
     * पर फ़ोन के अपने सुनने ने अनलॉक कहा हो ([intent]) यह तब भी ज़रूरी (AI / बाहर की लिखावट अपने-आप PIN नहीं डलवा सकती).
     */
    suspend fun allow(c: Context, ownerOnly: Boolean): String? {
        val now = System.currentTimeMillis()
        if (ownerOnly && strikes.paused(now)) return UnlockRules.reason(UnlockRules.Verdict.PAUSED).also { log(UnlockRules.Verdict.PAUSED, null) }
        if (now - grantAt <= UnlockRules.INTENT_MS) { grantAt = 0L; intentAt = 0L; return null }
        val asked = intentAt
        if (asked <= 0 || now - asked > UnlockRules.INTENT_MS) return UnlockRules.reason(UnlockRules.Verdict.NO_INTENT).also { log(UnlockRules.Verdict.NO_INTENT, null) }
        if (!ownerOnly) { intentAt = 0L; DKLog.d("आवाज़ से अनलॉक: मालिक-आवाज़ वाली जाँच बंद (Settings) - कोई भी बोले"); return null }
        val v = ownerSaid(c, asked)
        if (v == UnlockRules.Verdict.NO_VOICE) return UnlockRules.reason(v)   // पहले जैसा: बात न मिली = इरादा बना रहे
        intentAt = 0L
        if (v.ok) { last = null; return null }
        return UnlockRules.reason(v)
    }

    /**
     * 22 नवंबर 2026: आवाज़ से फ़ोन लॉक - switch चालू और वॉइस गार्डियन सिखाया हुआ हो तो इसी बात की आवाज़ मालिक की हो.
     * null = लॉक करो; वरना कारण. वॉइस गार्डियन सिखाया ही नहीं = पहले जैसा लॉक (लॉक करने से कुछ खुलता नहीं).
     */
    suspend fun lockAllow(c: Context, ownerOnly: Boolean): String? {
        if (!ownerOnly) return null
        val ready = try { voice.ready(c) } catch (_: Throwable) { false }
        if (!ready) return null
        val v = ownerSaid(c, System.currentTimeMillis())
        if (v.ok) { last = null; return null }
        return lockReason(v)
    }

    /** लॉक न करने का बोला जाने वाला कारण (PIN वाली बात नहीं) */
    fun lockReason(v: UnlockRules.Verdict): String = "बॉस, सुरक्षा: ${v.hi}, इसलिए फ़ोन लॉक नहीं किया।"

    /** अभी कही बात (asked के आसपास की) की आवाज़ मालिक की? बात अभी चल रही हो तो थोड़ा इंतज़ार */
    private suspend fun ownerSaid(c: Context, asked: Long): UnlockRules.Verdict {
        var u = last
        val waitEnd = System.currentTimeMillis() + UnlockRules.UTTER_WAIT_MS
        while ((u == null || !UnlockRules.utterFits(u.at, asked)) && System.currentTimeMillis() < waitEnd) {
            kotlinx.coroutines.delay(UTTER_POLL_MS); u = last
        }
        if (u == null || !UnlockRules.utterFits(u.at, asked)) return UnlockRules.Verdict.NO_VOICE.also { log(it, null) }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { verify(c, u.pcm, System.currentTimeMillis()) }
    }

    /** 22 नवंबर 2026: बात आने की जाँच हर 60 ms (पहले 120) - आवाज़ आते ही फ़ैसला */
    private const val UTTER_POLL_MS = 60L

    private fun verify(c: Context, pcm: ByteArray, now: Long): UnlockRules.Verdict {
        if (strikes.paused(now)) return UnlockRules.Verdict.PAUSED.also { log(it, null) }
        val trimmed = try { VgRules.trimSilence(pcm) } catch (_: Exception) { pcm }
        val ms = trimmed.size * 1000 / (OwnerGate.RATE * 2)
        val vo = voice
        val r = if (ms >= UnlockRules.MIN_MS && vo.ready(c)) try { vo.score(c, trimmed) } catch (_: Throwable) { null } else null
        val seen = if (r != null) vo.seen(c) else emptyList()
        val replay = r != null && UnlockRules.isReplay(r.second, seen)
        val v = UnlockRules.judge(r?.first, UnlockRules.threshold(vo.baseThreshold(c)), replay, ms)
        when {
            v.ok -> { strikes.ok(); try { vo.remember(c, r!!.second) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
            v == UnlockRules.Verdict.LOW || v == UnlockRules.Verdict.REPLAY -> strikes.fail(now)
            else -> {}
        }
        log(v, r?.first)
        return v
    }

    private fun log(v: UnlockRules.Verdict, score: Float?) {
        // PIN / आवाज़ कभी log में नहीं - सिर्फ़ फ़ैसला और अंक
        DKLog.d("आवाज़ से अनलॉक: ${v.hi}" + (score?.let { " (मिलान %.2f)".format(java.util.Locale.US, it) } ?: ""))
    }

    /** नया PIN सेव / आवाज़ दोबारा सिखाई = रोक हटाओ */
    fun reset() { strikes.reset(); grantAt = 0L; intentAt = 0L; last = null; dropPending() }
}
