package com.dk.assistant

import android.content.Context
import com.dk.assistant.backend.BackendApi
import com.dk.assistant.backend.BackendWire
import okhttp3.Request

/**
 * 18 नवंबर 2026 (मालिक: "WebSocket खुद बनाकर ठीक करो"): DK लाइव उनके लिए भी जिनके पास अपनी Gemini key नहीं.
 *
 * क्रम वही ([AiRouter]): अपनी Live key → AI दिमाग़ की कोई Gemini key → DK सर्वर. सर्वर वाले रास्ते में
 * ऐप पहले की तरह सीधे Google के Live WebSocket से जुड़ता है, बस key की जगह (header में) सर्वर (/live/token) से मिला
 * छोटी मियाद वाला टिकट. असली key फ़ोन पर कभी नहीं; आवाज़ सर्वर से होकर नहीं जाती (देरी नहीं).
 * मुफ़्त वाले का टिकट 60 सेकंड का - मियाद पर अगला टिकट, बातचीत वहीं से (session resumption).
 * रोज़ का समय पूरा = [AiRouter.stopped] (वही तय शब्द) और आज के लिए Live बंद.
 *
 * Live के keys वाले code में यह एक निशान ([KEY]) है - असली key नहीं, log/URL में कभी नहीं जाता.
 */
object LiveDkServer {
    const val KEY = "dk-server-live"
    /** इतनी देर से कम बचा टिकट = नया */
    const val FRESH_MS = 5_000L

    fun isServer(key: String?): Boolean = key == KEY

    /** test बदल सके */
    @Volatile var readyOverride: Boolean? = null
    /** आज की मुफ़्त बातचीत पूरी - इस समय तक सर्वर से Live नहीं (नया लाइसेंस = [clearStop]) */
    @Volatile private var stopUntil = 0L

    fun on(now: Long = System.currentTimeMillis()): Boolean = (readyOverride ?: AiRouter.backendReady()) && now >= stopUntil

    fun clearStop() { stopUntil = 0L }

    /** रोज़ का समय पूरा: भारतीय समय की अगली आधी रात तक रोक */
    fun stopToday(now: Long = System.currentTimeMillis()) {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
        cal.timeInMillis = now
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        stopUntil = cal.timeInMillis
        ticket = null
    }

    @Volatile private var ticket: BackendWire.LiveTicket? = null

    fun fresh(t: BackendWire.LiveTicket?, now: Long): Boolean = t != null && t.expiresAt - now > FRESH_MS

    /** टिकट खत्म / है ही नहीं = अगली बार नया */
    fun expired(now: Long = System.currentTimeMillis()): Boolean = !fresh(ticket, now)

    fun forget() { ticket = null }

    /** चालू टिकट, या सर्वर से नया (network - main thread पर नहीं). गलती = [com.dk.assistant.backend.BackendException] */
    fun ensure(c: Context, now: Long = System.currentTimeMillis()): BackendWire.LiveTicket {
        ticket?.takeIf { fresh(it, now) }?.let { return it }
        synchronized(this) {
            ticket?.takeIf { fresh(it, System.currentTimeMillis()) }?.let { return it }
            return BackendApi.liveToken(c.applicationContext).also { ticket = it }
        }
    }

    /** टिकट header में (Google का तय तरीका "Authorization: Token …") - URL में नहीं, ताकि log / error में न छपे */
    fun auth(t: BackendWire.LiveTicket): String = "Token " + t.token

    /** Live का WebSocket अनुरोध, सर्वर के टिकट से (अपनी key वाला अनुरोध पहले जैसा - header में key) */
    fun serverRequest(c: Context): Request = ensure(c).let { Request.Builder().url(it.wsUrl).header("Authorization", auth(it)).build() }
}
