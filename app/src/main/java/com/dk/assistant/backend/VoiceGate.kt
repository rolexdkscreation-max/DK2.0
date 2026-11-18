package com.dk.assistant.backend

import android.content.Context
import com.dk.assistant.AiRouter
import com.dk.assistant.Prefs

/**
 * 18 नवंबर 2026 (मालिक): मुफ़्त वाला (बिना लाइसेंस) अपनी Gemini key से आवाज़ में बात करे - पर रोज़ सिर्फ़ 10 मिनट.
 * समय फ़ोन पर नहीं, DK सर्वर (/voice/grant) पर गिना जाता है - data मिटाकर फिर 10 मिनट नहीं.
 * हर 60 सेकंड का हिस्सा पहले से लिया जाता है; मियाद से पहले अगला. आख़िरी हिस्से पर एक बार "बस एक मिनट बचा है".
 * समय पूरा = तय शब्द ([BackendWire.VOICE_DONE]) और भारतीय समय की आधी रात तक आवाज़ बंद - इंटरनेट रहे या न रहे.
 * सर्वर से जवाब नहीं (नेट/सर्वर बंद) = आवाज़ नहीं ([OFFLINE_HI]); नेट आते ही बचा समय फिर.
 * लिखकर चैट पर कोई रोक नहीं (अपनी key). लाइसेंस वाले / मालिक / सर्वर-रहित build = कोई गिनती नहीं.
 */
object VoiceGate {
    const val OFFLINE_HI = "मुफ़्त में आवाज़ से बात के लिए इंटरनेट चाहिए। अभी आप लिखकर चैट कर सकते हैं।"
    const val CODE_OFFLINE = "VOICE_OFFLINE"
    /** इतनी देर से कम बचा हिस्सा = अगला माँगो */
    const val RENEW_BEFORE_MS = 5_000L
    private const val DONE_DAY = "voice_done_day"

    /** सर्वर से मिली मियाद (फ़ोन की घड़ी पर) */
    @Volatile var until = 0L
        private set

    fun metered(c: Context): Boolean = Plan.level(c) == Plan.Level.FREE

    fun doneToday(c: Context, now: Long = System.currentTimeMillis()): Boolean =
        Prefs.str(c, DONE_DAY, "") == AiRouter.istDay(now)

    /** अभी आवाज़ वाला हिस्सा चल रहा है? (इसी समय DK लाइव के सारे काम) */
    fun active(now: Long = System.currentTimeMillis()): Boolean = until > now

    /** अगली बार कब पूछें (मियाद से [RENEW_BEFORE_MS] पहले; 1-60 सेकंड) */
    fun waitMs(now: Long = System.currentTimeMillis()): Long = (until - now - RENEW_BEFORE_MS).coerceIn(1_000L, 60_000L)

    /** सर्वर की घड़ी से मियाद -> फ़ोन की घड़ी पर (फ़ोन का समय गलत हो तब भी सही) */
    fun localUntil(serverNow: Long, serverUntil: Long, localNow: Long): Long =
        if (serverNow <= 0L) serverUntil else localNow + (serverUntil - serverNow).coerceAtLeast(0L)

    /** नया लाइसेंस / नया दिन */
    fun clear(c: Context) { until = 0L; Prefs.edit(c) { remove(DONE_DAY) } }

    /**
     * आवाज़ से बात चल सकती है? null = हाँ; वरना रुकावट (DK यही बोले - [deny]).
     * network - main thread पर नहीं.
     */
    fun ensure(c: Context, now: Long = System.currentTimeMillis()): BackendException? {
        if (!metered(c)) return null
        if (doneToday(c, now)) return toChat()
        if (until - now > RENEW_BEFORE_MS) return null
        return try {
            val g = BackendApi.voiceGrant(c.applicationContext)
            Plan.noteLevel(c, g.level)
            until = localUntil(g.now, g.until, System.currentTimeMillis())
            null
        } catch (e: BackendException) {
            until = 0L
            if (e.stopsTalk) Prefs.edit(c) { putString(DONE_DAY, AiRouter.istDay(now)) }
            if (e.reason == BackendWire.NOT_CONFIGURED) null
            else if (e.code == 0 || (e.code >= 500 && !e.final)) offline()
            else if (e.reason == BackendWire.LICENSE_REQUIRED) toChat() else e
        } catch (e: Exception) {
            until = 0L
            offline()
        }
    }

    private fun offline() = BackendException(0, CODE_OFFLINE, OFFLINE_HI)

    /** आवाज़ के 10 मिनट पूरे = चैट में भेजने वाली रुकावट ([BackendWire.VOICE_DONE_CHAT]) */
    fun toChat() = BackendException(402, BackendWire.LICENSE_REQUIRED, BackendWire.VOICE_DONE_CHAT)

    /** यह रुकावट "अब चैट में बात करें" वाली है? */
    fun isChatHandoff(e: Throwable?): Boolean = (e as? BackendException)?.say == BackendWire.VOICE_DONE_CHAT

    /**
     * रुकावट: DK बोले/लिखे और Live बंद ([AiRouter.onStop]).
     * 10 मिनट पूरे = "अब चैट में बात करें" + चैट अपने-आप खुले ([AiRouter.toChat]) - चैट Live से जुड़ी नहीं.
     */
    fun deny(e: BackendException) = if (isChatHandoff(e)) AiRouter.toChat(BackendWire.VOICE_DONE_CHAT, speak = true)
        else AiRouter.stopped(e, speak = true)
}
