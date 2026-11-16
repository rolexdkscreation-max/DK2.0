package com.dk.assistant.voice

import android.content.Context
import com.dk.assistant.DKLog

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
        val now = System.currentTimeMillis()
        if (strikes.paused(now)) return UnlockRules.reason(UnlockRules.Verdict.PAUSED).also { log(UnlockRules.Verdict.PAUSED, null) }
        if (now - grantAt <= UnlockRules.INTENT_MS) { grantAt = 0L; intentAt = 0L; return null }
        val asked = intentAt
        if (asked <= 0 || now - asked > UnlockRules.INTENT_MS) return UnlockRules.reason(UnlockRules.Verdict.NO_INTENT).also { log(UnlockRules.Verdict.NO_INTENT, null) }
        var u = last
        val waitEnd = now + UnlockRules.UTTER_WAIT_MS
        while ((u == null || !UnlockRules.utterFits(u.at, asked)) && System.currentTimeMillis() < waitEnd) {
            kotlinx.coroutines.delay(120); u = last
        }
        if (u == null || !UnlockRules.utterFits(u.at, asked)) return UnlockRules.reason(UnlockRules.Verdict.NO_VOICE).also { log(UnlockRules.Verdict.NO_VOICE, null) }
        val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { verify(c, u.pcm, System.currentTimeMillis()) }
        intentAt = 0L
        if (v.ok) { last = null; return null }
        return UnlockRules.reason(v)
    }

    private fun verify(c: Context, pcm: ByteArray, now: Long): UnlockRules.Verdict {
        if (strikes.paused(now)) return UnlockRules.Verdict.PAUSED.also { log(it, null) }
        val trimmed = try { VgRules.trimSilence(pcm) } catch (_: Exception) { pcm }
        val ms = trimmed.size * 1000 / (OwnerGate.RATE * 2)
        val r = if (ms >= UnlockRules.MIN_MS && VoiceGuardian.ready(c)) try { VoiceGuardian.unlockScore(c, trimmed) } catch (_: Throwable) { null } else null
        val seen = if (r != null) VoiceGuardian.templates(c) + VoiceGuardian.unlockSeen(c) else emptyList()
        val replay = r != null && UnlockRules.isReplay(r.second, seen)
        val v = UnlockRules.judge(r?.first, UnlockRules.threshold(VoiceGuardian.threshold(c)), replay, ms)
        when {
            v.ok -> { strikes.ok(); try { VoiceGuardian.rememberUnlock(c, r!!.second) } catch (_: Exception) {} }
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
    fun reset() { strikes.reset(); grantAt = 0L; intentAt = 0L; last = null }
}
