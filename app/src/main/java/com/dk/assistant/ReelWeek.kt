package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): "हर 7 दिन का हिसाब - आज कितनी रील, कल कितनी, कम हुई या ज़्यादा. सब ऐप की कुल रोज़ लिमिट (जैसे 100) -
 * पार हो तो DK बोले: सर, लिमिट पार हो गई, अब रख दो, आँखों / सेहत के लिए अच्छा नहीं. लिमिट न हो तो भी बताए कल कितनी, आज कितनी.
 * सारा डेटा ऐप में 7 दिन दिखे, DK लाइव को पता हो, 7 दिन बाद अपने-आप नया हफ़्ता. Accessibility बंद हो तब भी चले."
 *
 * Pure (JVM test). हर दिन, हर ऐप:
 *  n      = सच में गिनी रील / वीडियो (Accessibility चालू हो तब - स्क्रीन पढ़कर)
 *  seen   = उतने सेकंड जब DK (Accessibility) देख रहा था और वह ऐप सामने था
 *  reelS  = उनमें से रील / वीडियो वाली स्क्रीन के सेकंड (एक रील का औसत समय सीखने के लिए)
 *  secs   = ऐप सामने रहने का कुल समय - Android का Usage access (Accessibility की ज़रूरत नहीं)
 * Accessibility बंद वाला समय (secs - seen) = उसकी रील का अंदाज़ा (औसत समय से) - हमेशा "~ अंदाज़ा" लिखकर.
 */
object ReelWeek {
    const val DAYS = 7
    const val DEFAULT_REEL_SECS = 20

    data class AppDay(val n: Int = 0, val seen: Long = 0, val reelS: Long = 0, val secs: Long = 0)

    data class Day(val date: String, val apps: Map<String, AppDay> = emptyMap(), val titles: List<String> = emptyList()) {
        fun app(k: String) = apps[k] ?: AppDay()
    }

    /** start = हफ़्ते का पहला दिन (yyyy-MM-dd); prev = पिछले हफ़्ते का आख़िरी दिन (नए हफ़्ते के पहले दिन "कल" से तुलना) */
    data class Week(val start: String, val days: List<Day>, val prev: Day? = null)

    // ------------------------------------------------------------ तारीख (yyyy-MM-dd, बिना Android)
    fun epochDay(d: String): Long = java.time.LocalDate.parse(d).toEpochDay()
    fun plusDays(d: String, n: Long): String = java.time.LocalDate.parse(d).plusDays(n).toString()

    /** आज के लिए हफ़्ता तैयार: 7 दिन पूरे = अपने-आप नया हफ़्ता (पुराना साफ़, बस आख़िरी दिन तुलना के लिए) */
    fun roll(w: Week?, today: String): Week {
        if (w == null || w.start.isBlank()) return Week(today, listOf(Day(today)))
        val gap = epochDay(today) - epochDay(w.start)
        if (gap < 0 || gap >= DAYS) {
            val last = w.days.maxByOrNull { it.date }
            val prev = last?.takeIf { epochDay(today) - epochDay(it.date) == 1L }
            return Week(today, listOf(Day(today)), prev)
        }
        return if (w.days.any { it.date == today }) w else w.copy(days = w.days + Day(today))
    }

    fun resetDate(w: Week): String = plusDays(w.start, DAYS.toLong())

    fun day(w: Week, date: String): Day? = w.days.firstOrNull { it.date == date } ?: w.prev?.takeIf { it.date == date }

    private fun edit(w: Week, date: String, f: (Day) -> Day): Week =
        w.copy(days = w.days.map { if (it.date == date) f(it) else it }.let { l -> if (l.any { it.date == date }) l else l + f(Day(date)) })

    fun addReel(w: Week, date: String, app: String, title: String?): Week = edit(w, date) { d ->
        val a = d.app(app)
        val t = title?.trim()?.take(40).orEmpty()
        d.copy(apps = d.apps + (app to a.copy(n = a.n + 1)), titles = if (t.isBlank()) d.titles else (d.titles + "$app: $t").takeLast(60))
    }

    /** ms = ऐप सामने (DK देख रहा था), reelMs = उसमें रील / वीडियो वाली स्क्रीन */
    fun addSeen(w: Week, date: String, app: String, ms: Long, reelMs: Long): Week = edit(w, date) { d ->
        val a = d.app(app)
        d.copy(apps = d.apps + (app to a.copy(seen = a.seen + ms, reelS = a.reelS + reelMs)))
    }

    fun setSecs(w: Week, date: String, app: String, secsMs: Long): Week = edit(w, date) { d ->
        d.copy(apps = d.apps + (app to d.app(app).copy(secs = secsMs)))
    }

    /** एक रील का औसत समय (सेकंड) - अपने ही गिने हुए हफ़्ते से, नहीं तो 20 */
    fun reelSecs(w: Week): Int {
        val n = w.days.sumOf { d -> d.apps.values.sumOf { it.n } }
        val s = w.days.sumOf { d -> d.apps.values.sumOf { it.reelS } } / 1000
        return if (n >= 10 && s > 0) (s / n).toInt().coerceIn(6, 90) else DEFAULT_REEL_SECS
    }

    /** उस दिन उस ऐप की रील: (सच में गिनी, अंदाज़ा वाली) */
    fun counts(a: AppDay, perReel: Int): Pair<Int, Int> {
        val blind = (a.secs - a.seen).coerceAtLeast(0) / 1000
        return a.n to (blind / perReel.coerceAtLeast(1)).toInt()
    }

    fun total(d: Day?, perReel: Int): Int = d?.apps?.values?.sumOf { counts(it, perReel).let { (x, y) -> x + y } } ?: 0
    fun estimated(d: Day?, perReel: Int): Boolean = d?.apps?.values?.any { counts(it, perReel).second > 0 } == true
    fun minutes(d: Day?): Int = ((d?.apps?.values?.sumOf { maxOf(it.secs, it.seen) } ?: 0L) / 60_000).toInt()

    // ------------------------------------------------------------ Usage access से ऐप का समय (Accessibility नहीं)
    /** events = (package, सामने आया?, समय) - [from] से पहले के भी दें (आधी रात से पहले खुला ऐप). [from, to) में हर ऐप कितने ms सामने रहा */
    fun foregroundMs(events: List<Triple<String, Boolean, Long>>, from: Long, to: Long, pkgs: Set<String>): Map<String, Long> {
        val out = HashMap<String, Long>()
        var cur: String? = null; var since = 0L
        fun close(end: Long) {
            val c = cur ?: return
            val ms = minOf(end, to) - maxOf(since, from)
            if (ms > 0) out[c] = (out[c] ?: 0L) + ms
            cur = null
        }
        for ((p, fg, t) in events.sortedBy { it.third }) {
            if (t >= to) break
            if (fg) { close(t); if (p in pkgs) { cur = p; since = t } }
            else if (p == cur) close(t)
        }
        close(to)
        return out
    }

    // ------------------------------------------------------------ बोलना / दिखाना
    private fun ago(i: Int) = when (i) { 0 -> "आज"; 1 -> "कल"; 2 -> "परसों"; else -> "$i दिन पहले" }

    /** कल बनाम परसों (या कल बनाम आज) - दिन की पहली रील पर DK बोलता है */
    fun dailyLine(w: Week, today: String, limit: Int): String? {
        val per = reelSecs(w)
        val y = day(w, plusDays(today, -1)) ?: return null
        val yn = total(y, per)
        if (yn == 0) return null
        val p = day(w, plusDays(today, -2))
        val pn = total(p, per)
        val sb = StringBuilder("सर, कल आपने $yn रील देखीं")
        if (p != null && pn > 0) {
            val d = yn - pn
            sb.append(when {
                d > 0 -> ", परसों से $d ज़्यादा। इतनी ज़्यादा क्यों? आज थोड़ा कम कीजिए।"
                d < 0 -> ", परसों से ${-d} कम। बहुत बढ़िया, ऐसे ही कम कीजिए।"
                else -> ", परसों जितनी ही।"
            })
        } else sb.append("।")
        if (limit > 0) sb.append(if (yn > limit) " कल लिमिट $limit से ${yn - limit} ज़्यादा हो गई थी।" else " कल आप लिमिट $limit के अंदर रहे।")
        return sb.toString()
    }

    /** आज की गिनती पर कब बोलना: लिमिट का 80%, लिमिट पार, फिर हर +10; लिमिट नहीं = कल से ज़्यादा होते ही एक बार */
    fun alertStep(total: Int, limit: Int, yesterday: Int): Int = when {
        limit > 0 && total > limit -> 2 + (total - limit - 1) / 10      // 2, 3, 4 ... (हर 10 पर)
        limit > 0 && total >= (limit * 8 + 9) / 10 -> 1
        limit <= 0 && yesterday > 0 && total > yesterday -> 1
        else -> 0
    }

    fun alertLine(total: Int, limit: Int, yesterday: Int): String = when {
        limit > 0 && total > limit ->
            "सर, आज आप $total रील देख चुके हैं, आपकी लिमिट $limit थी, लिमिट से ${total - limit} ज़्यादा हो गई हैं। प्लीज़ अब फ़ोन रख दीजिए। " +
                "लगातार रील देखना आँखों के लिए अच्छा नहीं, नींद और ध्यान भी कम होता है, सेहत के लिए भी ठीक नहीं। थोड़ा आराम कीजिए, आँखें बंद करके कुछ देर दूर देखिए।"
        limit > 0 -> "सर, आज $total रील हो गईं, लिमिट $limit है, बस ${(limit - total).coerceAtLeast(0)} बाकी हैं।"
        else -> "सर, आज आप $total रील देख चुके हैं, कल पूरे दिन में $yesterday देखी थीं, आज कल से ज़्यादा हो गईं। थोड़ा ब्रेक ले लीजिए, आँखों के लिए अच्छा रहेगा।"
    }

    /** ऐप में और DK लाइव के लिए 7 दिन की तालिका */
    fun table(w: Week, today: String, limit: Int): String = buildString {
        val per = reelSecs(w)
        val ds = w.days.sortedByDescending { it.date }
        for (d in ds) {
            val i = (epochDay(today) - epochDay(d.date)).toInt()
            val t = total(d, per)
            val prev = day(w, plusDays(d.date, -1))?.let { total(it, per) }
            val parts = ReelRules.App.values().map { a -> d.app(a.key).let { x -> counts(x, per) }.let { (n, e) -> "${a.short()} ${n + e}" } }
            append("${ago(i)} (${d.date.substring(5)}): ${if (estimated(d, per)) "~" else ""}$t रील • ${minutes(d)} मिनट • ${parts.joinToString(", ")}")
            if (prev != null && prev > 0) append(if (t > prev) " • पिछले दिन से ${t - prev} ज़्यादा" else if (t < prev) " • पिछले दिन से ${prev - t} कम" else " • पिछले दिन जितनी")
            if (limit > 0 && t > limit) append(" • लिमिट $limit पार")
            append('\n')
        }
        append("हफ़्ता ${w.start.substring(5)} से • ${resetDate(w).substring(5)} को अपने-आप नया हफ़्ता")
        if (ds.any { estimated(it, per) }) append("\n~ = Accessibility बंद वाले समय का अंदाज़ा (ऐप में बिताए समय ÷ एक रील ~$per सेकंड), सच्ची गिनती नहीं")
    }

    private fun ReelRules.App.short() = when (this) { ReelRules.App.INSTAGRAM -> "इंस्टा"; ReelRules.App.YOUTUBE -> "YouTube"; ReelRules.App.FACEBOOK -> "फेसबुक" }

    /** DK लाइव का tool जवाब */
    fun liveText(w: Week, today: String, limit: Int, accOn: Boolean, usageOn: Boolean): String = buildString {
        append("REEL / VIDEO WATCHING of the user (Instagram, YouTube incl. Shorts, Facebook) - last 7 days, real data from the phone. ")
        append("Exact counts need Accessibility (now ${if (accOn) "ON" else "OFF"}); app time comes from Usage access (now ${if (usageOn) "ON" else "OFF"}); '~' = estimate from time - say 'लगभग' for it. ")
        append(if (limit > 0) "Daily limit for all apps together: $limit reels. " else "No daily limit is set. ")
        append("Answer in short Hindi, compare today / yesterday / earlier days, never invent numbers.\n")
        append(table(w, today, limit))
        val t = day(w, today)?.titles.orEmpty().takeLast(12)
        if (t.isNotEmpty()) append("\nToday's last reels (creator / caption as read on screen): ${t.joinToString("; ")}")
    }

    // ------------------------------------------------------------ सेव (JSON)
    fun encode(w: Week): String = JSONObject().put("start", w.start).put("days", JSONArray().apply { w.days.forEach { put(dayJ(it)) } })
        .apply { w.prev?.let { put("prev", dayJ(it)) } }.toString()

    private fun dayJ(d: Day) = JSONObject().put("date", d.date).put("titles", JSONArray(d.titles)).put("apps", JSONObject().apply {
        d.apps.forEach { (k, a) -> put(k, JSONObject().put("n", a.n).put("seen", a.seen).put("reelS", a.reelS).put("secs", a.secs)) }
    })

    fun decode(s: String?): Week? = try {
        if (s.isNullOrBlank()) null else JSONObject(s).let { o ->
            val ds = o.getJSONArray("days")
            Week(o.getString("start"), (0 until ds.length()).map { day(ds.getJSONObject(it)) }, o.optJSONObject("prev")?.let { day(it) })
        }
    } catch (_: Exception) { null }

    private fun day(o: JSONObject): Day {
        val a = o.optJSONObject("apps") ?: JSONObject()
        val t = o.optJSONArray("titles") ?: JSONArray()
        return Day(o.getString("date"), a.keys().asSequence().associateWith { k -> a.getJSONObject(k).let { x -> AppDay(x.optInt("n"), x.optLong("seen"), x.optLong("reelS"), x.optLong("secs")) } },
            (0 until t.length()).map { t.getString(it) })
    }
}
