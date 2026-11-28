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

    // ---- 21 नवंबर 2026 (मालिक: "ऐप की key फेल / WebSocket न खुले = सीधे backend की key, user को खाली नहीं लौटाना")
    /** अपनी key पक्की फेल होने के बाद इतनी देर Live सर्वर के टिकट से, फिर अपनी key दोबारा */
    const val SPARE_MS = 10 * 60_000L
    @Volatile private var spareUntil = 0L
    /** पिछली Live बातचीत सर्वर के टिकट से थी (वहाँ भी फेल = फिर सर्वर नहीं, पहले जैसा रुकना) */
    @Volatile var lastRunServer = false

    /** कौन सी गलती अपनी key की है (सर्वर का टिकट मदद करेगा). नेट / माइक / setup = नहीं */
    fun spareKind(kind: LiveFailWhy.Kind?): Boolean =
        kind == LiveFailWhy.Kind.KEY || kind == LiveFailWhy.Kind.QUOTA || kind == LiveFailWhy.Kind.MODEL || kind == LiveFailWhy.Kind.SERVER

    /** अपनी key से Live रुका: सर्वर से फिर जोड़ें? true = हाँ (तुरंत फिर कोशिश) */
    fun noteFail(kind: LiveFailWhy.Kind?, serverAi: Boolean = AiRouter.serverAiOk(), now: Long = System.currentTimeMillis()): Boolean {
        if (lastRunServer || !spareKind(kind) || !serverAi || !on(now)) return false
        val daily = dailyHit && kind == LiveFailWhy.Kind.QUOTA
        dailyHit = false
        spareUntil = now + spareMs(daily, now)
        return true
    }

    // ---- 28 नवंबर 2026 (मालिक: "ऑल-राउंडर key की limit बातचीत के बीच खत्म = बात रुके नहीं, तुरंत बैकएंड से, limit लौटने तक")
    /** अपनी key की limit आज की (रोज़ वाली) थी - [noteFail] तब Google की limit लौटने (Pacific आधी रात) तक सर्वर से */
    @Volatile var dailyHit = false

    /** अभी अपनी key से चल रही बातचीत को सर्वर पर ले जा सकते हैं? (तब एक key पर रुक-रुककर इंतज़ार नहीं) */
    fun canSpare(serverAi: Boolean = AiRouter.serverAiOk(), now: Long = System.currentTimeMillis()): Boolean = !lastRunServer && serverAi && on(now)

    /** pure: सर्वर पर कितनी देर. रोज़ की limit = Google की अगली रीसेट (Pacific आधी रात) तक, वरना [SPARE_MS] */
    fun spareMs(daily: Boolean, now: Long): Long {
        if (!daily) return SPARE_MS
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
        cal.timeInMillis = now
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        return (cal.timeInMillis - now).coerceAtLeast(SPARE_MS)
    }

    /** pure: अपनी key पर छोटी रोक (प्रति मिनट) में रुककर उसी key से फिर? सर्वर बचाव हो = नहीं, तुरंत सर्वर (चुप्पी नहीं) */
    fun waitOnOwnKey(soft: Boolean, spareOk: Boolean): Boolean = soft && !spareOk

    fun spare(now: Long = System.currentTimeMillis()): Boolean = now < spareUntil && on(now)

    fun clearSpare() { spareUntil = 0L }

    /** बिना अपनी key (सर्वर से) Live: बातचीत की शुरुआत में एक बार key डालने की नरम याद */
    const val ASK_KEY_HI = "पहले API key डाल लीजिए एक बार, फिर और अच्छे से बात होगी"
    val ASK_KEY_NOTE = "\n\nKEY NOTE: The user has not added their own API key yet, so this talk runs on DK's server. " +
        "In your FIRST reply only (once, never repeat), after answering, politely say exactly: \"$ASK_KEY_HI\". Then talk normally."

    fun keyNote(ask: Boolean): String = if (ask) ASK_KEY_NOTE else ""

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

    // ---- 22 नवंबर 2026 (मालिक: "DK लाइव का connection बार-बार, तुरंत-तुरंत टूट रहा"): असली कारण - टिकट की मियाद पर
    // Google चालू बातचीत काट देता था (मुफ़्त = हर 60 सेकंड, बाकी = 30 मिनट) और ऐप कटने के बाद ही नया टिकट लेता था
    // (बीच का जवाब कटा, 1-3s चुप). अब मियाद से [RENEW_AHEAD_MS] पहले अगला टिकट (सर्वर: {next: true}) और उसी से पीछे
    // नया connection (goAway वाला बिना-रुकावट रास्ता, [LiveSpare]) - कटने से पहले ही बदल जाता है.
    /** मियाद से इतना पहले अगला टिकट माँगो (सर्वर अगला हिस्सा आख़िरी 20s में ही देता है - backend LIVE_NEXT_WINDOW_MS) */
    const val RENEW_AHEAD_MS = 15_000L
    /** अगला टिकट न मिला / पुराना सर्वर वही टिकट लौटाए = इतने बाद फिर (आख़िरी 5s में पुराना सर्वर भी नया देता है) */
    const val RENEW_RETRY_MS = 2_000L
    /** connection इस मियाद के इतने पास कटा = टिकट की वजह से (key/limit की गलती नहीं) */
    const val EXPIRY_SLACK_MS = 2_000L

    /** चालू टिकट की मियाद (0 = टिकट नहीं) */
    fun until(): Long = ticket?.expiresAt ?: 0L

    /** अब अगला टिकट माँगें? leftMs = चालू टिकट में कितना बचा */
    fun renewDue(leftMs: Long, sinceTryMs: Long, renewing: Boolean): Boolean =
        !renewing && leftMs in 1..RENEW_AHEAD_MS && sinceTryMs >= RENEW_RETRY_MS

    /** सर्वर का नया टिकट सच में आगे तक चलता है? (पुराना सर्वर मियाद से पहले वही हिस्सा लौटाता है) */
    fun extends(old: BackendWire.LiveTicket?, new: BackendWire.LiveTicket): Boolean =
        old == null || new.expiresAt > old.expiresAt

    /** connection टूटा - क्या वजह टिकट की मियाद? connUntil = उस connection के टिकट की मियाद */
    fun dropByTicket(expiredNow: Boolean, connUntil: Long, dropAt: Long): Boolean =
        expiredNow || connUntil > 0L && dropAt >= connUntil - EXPIRY_SLACK_MS

    @Volatile var renewing = false
    @Volatile var renewTriedAt = 0L

    /** मियाद से पहले अगला टिकट (network - main thread पर नहीं). true = नया, आगे तक चलने वाला टिकट अब चालू */
    fun renew(c: Context): Boolean {
        val old = ticket
        val t = BackendApi.liveToken(c.applicationContext, next = true)
        if (!extends(old, t)) return false
        ticket = t
        return true
    }

    /** टिकट header में (Google का तय तरीका "Authorization: Token …") - URL में नहीं, ताकि log / error में न छपे */
    fun auth(t: BackendWire.LiveTicket): String = "Token " + t.token

    /** Live का WebSocket अनुरोध, सर्वर के टिकट से (अपनी key वाला अनुरोध पहले जैसा - header में key) */
    fun serverRequest(c: Context): Request = ensure(c).let { Request.Builder().url(it.wsUrl).header("Authorization", auth(it)).build() }
}
