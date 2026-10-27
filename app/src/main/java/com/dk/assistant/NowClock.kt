package com.dk.assistant

import java.util.Calendar
import java.util.TimeZone

/**
 * v3.80: DK को हमेशा सही समय.
 *
 * - फ़ोन की घड़ी ग़लत हो सकती है -> इंटरनेट (Cloudflare / Google) से असली समय नापकर फ़र्क ([offsetMs]) रखते हैं.
 * - हर prompt / हर Live जुड़ाव / current_time tool में यही [line] जाता है: दिन, तारीख़, घंटा-मिनट, सुबह/दोपहर/शाम/रात.
 * - नाम (महीने/दिन) अपने हाथ से - Locale पर निर्भर नहीं, JVM test में भी वही.
 */
object NowClock {
    /** इंटरनेट से नापा फ़र्क: असली समय = फ़ोन का समय + offset */
    @Volatile var offsetMs = 0L
        private set
    @Volatile var syncedAt = 0L
        private set
    /** "इंटरनेट से मिलाया" / "फ़ोन की घड़ी" */
    @Volatile var source = "फ़ोन की घड़ी"
        private set

    const val RESYNC_MS = 3 * 60 * 60 * 1000L
    /** इससे कम फ़र्क = फ़ोन की घड़ी ठीक (नेटवर्क की देरी से छोटा) */
    const val TRUST_MS = 2_000L
    /** जवाब आने में इससे ज़्यादा लगा = नाप भरोसे का नहीं */
    const val MAX_RTT_MS = 5_000L

    private val DAYS = arrayOf("रविवार", "सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार")
    private val MONTHS = arrayOf("जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर")
    private val DAYS_EN = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val MONTHS_EN = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** अभी का सही समय (ms) */
    fun now(): Long = System.currentTimeMillis() + offsetMs

    fun zone(): TimeZone = TimeZone.getDefault()

    /**
     * नाप से फ़र्क निकालो. phoneBefore/phoneAfter = माँगने से पहले/जवाब के बाद फ़ोन का समय, net = सर्वर का समय.
     * null = नाप भरोसे का नहीं. छोटा फ़र्क (< [TRUST_MS]) = 0 (फ़ोन ठीक).
     */
    fun offsetFrom(phoneBefore: Long, phoneAfter: Long, net: Long): Long? {
        val rtt = phoneAfter - phoneBefore
        if (rtt < 0 || rtt > MAX_RTT_MS || net <= 0) return null
        val off = net - (phoneBefore + rtt / 2)
        return if (kotlin.math.abs(off) < TRUST_MS) 0L else off
    }

    /** नापा फ़र्क रखो (सिर्फ़ भरोसे वाला) */
    fun accept(off: Long?, at: Long = System.currentTimeMillis()) {
        if (off == null) return
        offsetMs = off; syncedAt = at
        source = if (off == 0L) "फ़ोन की घड़ी (इंटरनेट से मिलाई, सही)" else "इंटरनेट से मिलाया (फ़ोन की घड़ी ${secs(off)} ${if (off > 0) "पीछे" else "आगे"})"
    }

    fun needsSync(at: Long = System.currentTimeMillis()): Boolean = syncedAt == 0L || at - syncedAt > RESYNC_MS

    /** Cloudflare का "ts=1729999999.123" (ms तक सही) */
    fun parseTrace(body: String): Long? =
        Regex("(?m)^ts=(\\d{9,11})(?:\\.(\\d{1,6}))?").find(body)?.let { m ->
            val s = m.groupValues[1].toLongOrNull() ?: return null
            val frac = m.groupValues[2].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            s * 1000 + frac
        }

    /**
     * इंटरनेट से समय मिलाओ (network - background thread पर बुलाओ). कुछ न मिले = पुराना फ़र्क वैसा ही.
     * पहले Cloudflare trace (ms तक), फिर HTTP Date header (सेकंड तक).
     */
    fun sync(force: Boolean = false) {
        if (!force && !needsSync()) return
        val http = okhttp3.OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            val t0 = System.currentTimeMillis()
            http.newCall(okhttp3.Request.Builder().url("https://cloudflare.com/cdn-cgi/trace").build()).execute().use { r ->
                val t1 = System.currentTimeMillis()
                val net = parseTrace(r.body?.string().orEmpty())
                    ?: r.header("Date")?.let { parseHttpDate(it) }
                val off = net?.let { offsetFrom(t0, t1, it) }
                if (off != null) { accept(off, t1); return }
            }
        } catch (_: Exception) {}
        try {
            val t0 = System.currentTimeMillis()
            http.newCall(okhttp3.Request.Builder().url("https://www.google.com/generate_204").head().build()).execute().use { r ->
                val t1 = System.currentTimeMillis()
                // Date सिर्फ़ सेकंड तक -> आधा सेकंड जोड़ो (औसत)
                r.header("Date")?.let { parseHttpDate(it) }?.let { accept(offsetFrom(t0, t1, it + 500), t1) }
            }
        } catch (_: Exception) {}
    }

    /** पीछे से मिलाओ (कभी रुकाओ नहीं) */
    fun syncAsync() {
        if (!needsSync()) return
        Thread({ sync() }, "dk-clock").apply { isDaemon = true }.start()
    }

    fun parseHttpDate(s: String): Long? = try {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(s)?.time
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ बोलने/लिखने लायक

    /** घंटा (0-23) -> दिन का हिस्सा */
    fun partOfDay(hour: Int): String = when (hour) {
        in 4..5 -> "सुबह-सुबह"
        in 6..11 -> "सुबह"
        in 12..15 -> "दोपहर"
        in 16..19 -> "शाम"
        in 20..23 -> "रात"
        else -> "देर रात"
    }

    private fun partEn(hour: Int): String = when (hour) {
        in 4..5 -> "early morning"
        in 6..11 -> "morning"
        in 12..15 -> "afternoon"
        in 16..19 -> "evening"
        in 20..23 -> "night"
        else -> "late night (past midnight)"
    }

    private fun cal(ms: Long, tz: TimeZone): Calendar = Calendar.getInstance(tz).apply { timeInMillis = ms }

    fun hour(ms: Long = now(), tz: TimeZone = zone()): Int = cal(ms, tz).get(Calendar.HOUR_OF_DAY)

    /** "रात 11:42 बजे" / "दोपहर 1:05 बजे" (12 घंटे वाली घड़ी, जैसे लोग बोलते हैं) */
    fun spoken(ms: Long = now(), tz: TimeZone = zone()): String {
        val c = cal(ms, tz)
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "${partOfDay(h)} $h12:${m.toString().padStart(2, '0')} बजे"
    }

    /** "मंगलवार, 27 अक्टूबर 2026" */
    fun dateHi(ms: Long = now(), tz: TimeZone = zone()): String {
        val c = cal(ms, tz)
        return "${DAYS[c.get(Calendar.DAY_OF_WEEK) - 1]}, ${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** "Tuesday, 27 October 2026" (search की query/निर्देश के लिए) */
    fun dateEn(ms: Long = now(), tz: TimeZone = zone()): String {
        val c = cal(ms, tz)
        return "${DAYS_EN[c.get(Calendar.DAY_OF_WEEK) - 1]}, ${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS_EN[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** "UTC+05:30" */
    fun utcOffset(ms: Long, tz: TimeZone): String {
        val o = tz.getOffset(ms) / 60_000
        val sign = if (o < 0) "-" else "+"
        val a = kotlin.math.abs(o)
        return "UTC$sign${(a / 60).toString().padStart(2, '0')}:${(a % 60).toString().padStart(2, '0')}"
    }

    /** हर prompt में जाने वाली पूरी पंक्ति (मॉडल के लिए - साफ़ और पक्की) */
    fun line(ms: Long = now(), tz: TimeZone = zone()): String {
        val c = cal(ms, tz)
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        val hm = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
        return "CURRENT DATE AND TIME (exact, ${tz.id}, ${utcOffset(ms, tz)}): ${dateEn(ms, tz)}, $hm (${partEn(h)}) = " +
            "${dateHi(ms, tz)}, ${spoken(ms, tz)}."
    }

    /** current_time tool का जवाब */
    fun toolAnswer(ms: Long = now(), tz: TimeZone = zone()): String =
        line(ms, tz) + " Source: $source. Say the time in natural Hindi (e.g. \"${spoken(ms, tz)}\"), exactly as given."

    private fun secs(off: Long): String = "${kotlin.math.abs(off) / 1000} सेकंड"
}

/**
 * v3.80: यूज़र की और गहरी फ़िक्र - समय देखकर (सुबह/खाना/देर रात), पिछली बातें याद करके, भाव पहचानकर.
 * GirlfriendPersona / CalmPersona के साथ - उनसे टकराता नहीं (एक सवाल, छोटे जवाब वैसे ही).
 */
object CareTime {
    /** इस घंटे पर किस बात की फ़िक्र स्वाभाविक है */
    fun hintFor(hour: Int): String = when (hour) {
        in 4..5 -> "It is very early morning: gently ask if they slept enough, or wish them a peaceful start."
        in 6..10 -> "It is morning: a warm good-morning feeling; you may ask if they had breakfast/tea or slept well."
        11 -> "It is late morning: you may ask how the day is going."
        in 12..14 -> "It is lunch time: you may lovingly ask if they have eaten lunch."
        15 -> "It is afternoon: you may ask if they are tired, had water/tea."
        in 16..19 -> "It is evening: you may ask how the day went and if they are tired."
        in 20..22 -> "It is night: you may ask if they had dinner and how they are feeling after the day."
        23 -> "It is late night: lovingly remind them to rest soon - their health matters to you."
        else -> "It is past midnight: show real worry that they are still awake; lovingly ask them to rest/sleep (unless they are busy or upset - then stay with them)."
    }

    fun block(ms: Long = NowClock.now(), tz: TimeZone = NowClock.zone()): String = buildString {
        val h = NowClock.hour(ms, tz)
        append("\n\nCARE AND TIME (you truly love and care for the user, like your own family):\n")
        append("- Right now it is ").append(NowClock.spoken(ms, tz)).append(" (").append(NowClock.dateHi(ms, tz)).append("). ")
        append(hintFor(h)).append("\n")
        append("- Greet by the REAL time of day: never say good morning at night or good night in the morning.\n")
        append("- Show your love and concern in your own words: you missed them, you are happy they came, you worry when they are tired, ill, sad or up very late. ")
        append("Ask how they really are, and listen.\n")
        append("- Remember what they told you before (memory/facts above): ask how that worry, health issue, exam, work or plan went.\n")
        append("- If they sound tired, sad, unwell or stressed: comfort them first, gently ask what happened - everything else after.\n")
        append("- Keep it real: at most ONE caring touch per reply, never the same line twice in a row, never dramatic, clingy or fake.\n")
        append("- Time questions: always use the CURRENT DATE AND TIME given to you (in Live call current_time) - say it exactly, never guess.\n")
    }
}
