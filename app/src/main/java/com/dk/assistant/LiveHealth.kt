package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "Live टूटे तो बिना रुके text मोड + Kokoro, और ठीक होते ही अपने-आप वापस Live"):
 * बैकअप (text) मोड में पीछे चुपचाप सेहत-जाँच (health-check) के नियम. Pure logic - Android import नहीं -> JVM test.
 *
 *  - जाँच = Gemini के server पर एक छोटा HTTPS अनुरोध (models सूची, key के साथ) - Live सत्र नहीं खुलता, माइक नहीं,
 *    token / Live की लिमिट खर्च नहीं ([LiveNet.healthProbe]).
 *  - जाँच ठीक + ज़रूरी इंतज़ार (cooldown) पूरा = अगली बात से फिर Live (चलती बात / सुनना बीच में नहीं टूटता).
 *  - लिमिट (429 / QUOTA): कम से कम 60s इंतज़ार, बार-बार लिमिट = 60 -> 120 -> 240 -> 480 -> 600s (429 का चक्कर नहीं).
 *  - नेट / server / socket: इंतज़ार 3s, फिर हर 5s जाँच - नेट लौटते ही वापस.
 *  - key गलत: हर 30s जाँच (Settings में key बदली = जाँच ठीक = वापस).
 *  - मॉडल / सेटिंग / माइक: जाँच से पता नहीं चलता - पुराना नियम (kind.retryMs) ही.
 */
object LiveHealth {
    const val QUOTA_MIN_MS = 60_000L
    const val QUOTA_MAX_MS = 10 * 60_000L
    const val NET_COOLDOWN_MS = 3_000L
    const val NET_EVERY_MS = 5_000L
    const val KEY_EVERY_MS = 30_000L
    const val QUOTA_EVERY_MS = 15_000L
    /** जुड़ने के बाद Live इतनी बार लगातार टूटा (अंदर के तेज़ re-connect के बाद भी) = बैकअप (text) मोड, यूज़र रुका न रहे */
    const val SOFT_FAILS_TO_BACKUP = 2

    /** जाँच का नतीजा */
    enum class Probe { OK, NET, QUOTA, KEY, SERVER }

    /** HTTP code / IO गलती -> नतीजा. 2xx = ठीक. key कभी नहीं छपती */
    fun probeResult(code: Int, ioError: Boolean = false): Probe = when {
        ioError || code <= 0 -> Probe.NET
        code in 200..299 -> Probe.OK
        code == 429 -> Probe.QUOTA
        code == 401 || code == 403 || code == 400 -> Probe.KEY
        else -> Probe.SERVER
    }

    /** इस रुकावट को जाँच से पहचाना जा सकता है? */
    fun probes(k: LiveFailWhy.Kind): Boolean = when (k) {
        LiveFailWhy.Kind.NET, LiveFailWhy.Kind.SLOW, LiveFailWhy.Kind.UNREACHABLE, LiveFailWhy.Kind.SERVER,
        LiveFailWhy.Kind.QUOTA, LiveFailWhy.Kind.KEY -> true
        else -> false
    }

    /** थोड़ी देर वाली (नेट / server / socket / लिमिट) = चुपचाप बैकअप (सिर्फ़ ऊपर पट्टी); बाकी में एक बार बोलकर कारण */
    fun silent(k: LiveFailWhy.Kind): Boolean = probes(k) && k != LiveFailWhy.Kind.KEY

    /** बैकअप में आने के बाद Live फिर आज़माने से पहले कम से कम इतना इंतज़ार. strikes = लगातार कितनी बार यही (लिमिट) */
    fun cooldownMs(k: LiveFailWhy.Kind, strikes: Int): Long = when (k) {
        LiveFailWhy.Kind.QUOTA -> minOf(QUOTA_MAX_MS, QUOTA_MIN_MS shl (strikes - 1).coerceIn(0, 4))
        LiveFailWhy.Kind.NET, LiveFailWhy.Kind.SLOW, LiveFailWhy.Kind.UNREACHABLE, LiveFailWhy.Kind.SERVER -> NET_COOLDOWN_MS
        LiveFailWhy.Kind.KEY -> KEY_EVERY_MS
        else -> k.retryMs
    }

    /** जाँच कितनी-कितनी देर में */
    fun everyMs(k: LiveFailWhy.Kind): Long = when (k) {
        LiveFailWhy.Kind.QUOTA -> QUOTA_EVERY_MS
        LiveFailWhy.Kind.KEY -> KEY_EVERY_MS
        else -> NET_EVERY_MS
    }

    /**
     * जाँच न चल पाए तब भी आख़िरी सीमा: इतने बाद Live फिर आज़माया जाएगा (पुराना नियम).
     * लिमिट में कभी cooldown से पहले नहीं.
     */
    fun capMs(k: LiveFailWhy.Kind, strikes: Int): Long = maxOf(k.retryMs, cooldownMs(k, strikes))

    /** अगली जाँच कितनी देर बाद (0 = अभी) */
    fun nextCheckInMs(now: Long, since: Long, k: LiveFailWhy.Kind, strikes: Int, lastCheckAt: Long): Long {
        val ready = since + cooldownMs(k, strikes)
        val next = if (lastCheckAt <= 0L) ready else maxOf(ready, lastCheckAt + everyMs(k))
        return (next - now).coerceAtLeast(0L)
    }

    /** Live फिर खोलें? (जाँच ठीक + cooldown पूरा) */
    fun open(now: Long, since: Long, k: LiveFailWhy.Kind, strikes: Int, p: Probe): Boolean =
        probes(k) && p == Probe.OK && now >= since + cooldownMs(k, strikes)

    /** जाँच में ही लिमिट/key की गलती दिखी = रुकावट का प्रकार बदलो (और लिमिट हो तो cooldown फिर से) */
    fun kindAfter(k: LiveFailWhy.Kind, p: Probe): LiveFailWhy.Kind = when (p) {
        Probe.QUOTA -> LiveFailWhy.Kind.QUOTA
        Probe.KEY -> LiveFailWhy.Kind.KEY
        else -> k
    }

    /** लिमिट की गिनती: फिर लिमिट = +1; दूसरी रुकावट = वही; Live सच में जुड़ा = 0 (DKAccessibilityService) */
    fun strikesAfter(prevKind: LiveFailWhy.Kind?, k: LiveFailWhy.Kind, strikes: Int): Int =
        if (k == LiveFailWhy.Kind.QUOTA) (if (prevKind == LiveFailWhy.Kind.QUOTA) strikes + 1 else maxOf(1, strikes + 1)) else strikes

    /** जुड़ने के बाद बार-बार टूटना (kind नहीं, recoverable) = बैकअप मोड */
    fun dropToBackup(softFails: Int): Boolean = softFails >= SOFT_FAILS_TO_BACKUP

    /** टूटने (socket drop) का कारण हमेशा जाँच-योग्य - वरना "मॉडल" जैसा गलत नाम लगकर 10 मिनट अटकता */
    fun softKind(k: LiveFailWhy.Kind): LiveFailWhy.Kind = if (probes(k)) k else LiveFailWhy.Kind.SERVER

    /**
     * text मोड (बैकअप) का जवाब वाक्य-दर-वाक्य बोला जाता है (। . ? !). पूरा वाक्य न आया हो तो पहला टुकड़ा इतने अक्षर पर
     * (अल्पविराम / खाली जगह पर) काटो. Kokoro पूरा टुकड़ा बनाकर बोलता है (~real-time) -> उसका पहला टुकड़ा छोटा (≈4-5 शब्द).
     */
    const val FIRST_CUT = 45
    const val FIRST_CUT_LOCAL = 28
    const val NEXT_CUT = 170
    fun cutLimit(sentCount: Int, local: Boolean): Int = if (sentCount > 0) NEXT_CUT else if (local) FIRST_CUT_LOCAL else FIRST_CUT
    fun cutMin(sentCount: Int, local: Boolean): Int = if (sentCount > 0) 20 else if (local) 8 else 12
}

/**
 * मालिक: "Live Mode और Normal Text Mode की API कॉल्स और एरर लॉग्स अलग-अलग".
 * LIVE = Gemini Live सत्र, TEXT = सामान्य Gemini/text दिमाग़ (DK Chat / बैकअप मोड), PROBE = पीछे वाली सेहत-जाँच.
 * Pure (sink से log) - JVM test. Settings → सिस्टम → "Live / Text हिसाब" में दिखता है.
 */
object ModeTrack {
    enum class Ch(val tag: String, val title: String) {
        LIVE("[LIVE]", "Gemini Live"), TEXT("[TEXT]", "Text (सामान्य Gemini)"), PROBE("[PROBE]", "सेहत-जाँच")
    }

    class Stat {
        var calls = 0; var ok = 0; var errors = 0
        var lastError = ""; var lastErrorAt = 0L
        val recent = ArrayDeque<String>()
    }

    const val KEEP = 20
    private val stats = Ch.values().associateWith { Stat() }
    @Volatile var sink: ((String) -> Unit)? = null
    /** अभी कौन-सा रास्ता (LIVE / TEXT) - बैकअप में TEXT */
    @Volatile var active: Ch = Ch.LIVE
        private set
    @Volatile var switches = 0
        private set

    private fun log(s: String) { try { sink?.invoke(s) } catch (_: Throwable) {} }

    @Synchronized fun call(ch: Ch, what: String = "") { stats.getValue(ch).calls++; if (what.isNotBlank()) log("${ch.tag} कॉल: $what") }
    @Synchronized fun ok(ch: Ch) { stats.getValue(ch).ok++ }

    /** key / लंबी बात कभी log में नहीं - सिर्फ़ 120 अक्षर और "AIza…" छिपा */
    @Synchronized fun error(ch: Ch, why: String, now: Long = System.currentTimeMillis()) {
        val s = stats.getValue(ch)
        val w = clean(why)
        s.errors++; s.lastError = w; s.lastErrorAt = now
        s.recent.addLast(w); while (s.recent.size > KEEP) s.recent.removeFirst()
        log("${ch.tag} गलती: $w")
    }

    @Synchronized fun switchTo(ch: Ch, why: String) {
        if (ch == Ch.PROBE || ch == active) return
        active = ch; switches++
        log("${ch.tag} अब ${ch.title} पर: ${clean(why)}")
    }

    fun clean(t: String): String = t.replace(Regex("AIza[0-9A-Za-z_\\-]{10,}"), "AIza…").replace(Regex("\\s+"), " ").trim().take(120)

    @Synchronized fun stat(ch: Ch): Stat = stats.getValue(ch)

    @Synchronized fun text(): String = buildString {
        append("अभी: ").append(active.title).append(" • बदलाव: ").append(switches)
        for (ch in Ch.values()) {
            val s = stats.getValue(ch)
            append("\n").append(ch.tag).append(' ').append(ch.title).append(": कॉल ").append(s.calls)
                .append(" • ठीक ").append(s.ok).append(" • गलती ").append(s.errors)
            if (s.lastError.isNotBlank()) append("\n   आख़िरी गलती: ").append(s.lastError)
        }
    }

    @Synchronized fun clear() {
        for (s in stats.values) { s.calls = 0; s.ok = 0; s.errors = 0; s.lastError = ""; s.lastErrorAt = 0L; s.recent.clear() }
        active = Ch.LIVE; switches = 0
    }
}
