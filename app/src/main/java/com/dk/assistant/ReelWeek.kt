package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): "हर 7 दिन का हिसाब - आज कितनी रील, कल कितनी, कम हुई या ज़्यादा. सब ऐप की कुल रोज़ लिमिट (जैसे 100) -
 * पार हो तो DK बोले: सर, लिमिट पार हो गई, अब रख दो, आँखों / सेहत के लिए अच्छा नहीं. लिमिट न हो तो भी बताए कल कितनी, आज कितनी.
 * सारा डेटा ऐप में 7 दिन दिखे, DK लाइव को पता हो, 7 दिन बाद अपने-आप नया हफ़्ता."
 *
 * Pure (JVM test). हर दिन, हर ऐप:
 *  n    = सच में गिनी रील / वीडियो (Accessibility चालू - DK स्क्रीन पढ़कर गिनता है)
 *  seen = उतने सेकंड वह ऐप सामने था (DK देख रहा था)
 * मालिक (नवंबर 2026): "जो नहीं हो सकता वह हटाओ, सिर्फ़ एक्यूरेट" - अंदाज़ा (~) / Usage access वाला हिस्सा हटाया:
 * सिर्फ़ सच्ची गिनती. Accessibility बंद हो तो उस समय की गिनती नहीं होती (हिसाब में कुछ नहीं जुड़ता, झूठा अंदाज़ा भी नहीं).
 */
object ReelWeek {
    const val DAYS = 7

    data class AppDay(val n: Int = 0, val seen: Long = 0)

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

    /** ms = ऐप सामने रहा (DK देख रहा था) */
    fun addSeen(w: Week, date: String, app: String, ms: Long): Week = edit(w, date) { d ->
        val a = d.app(app)
        d.copy(apps = d.apps + (app to a.copy(seen = a.seen + ms)))
    }

    fun total(d: Day?): Int = d?.apps?.values?.sumOf { it.n } ?: 0
    fun minutes(d: Day?): Int = ((d?.apps?.values?.sumOf { it.seen } ?: 0L) / 60_000).toInt()

    // ------------------------------------------------------------ बोलना / दिखाना
    private fun ago(i: Int) = when (i) { 0 -> "आज"; 1 -> "कल"; 2 -> "परसों"; else -> "$i दिन पहले" }

    /** कल बनाम परसों (या कल बनाम आज) - दिन की पहली रील पर DK बोलता है */
    fun dailyLine(w: Week, today: String, limit: Int): String? {
        val y = day(w, plusDays(today, -1)) ?: return null
        val yn = total(y)
        if (yn == 0) return null
        val p = day(w, plusDays(today, -2))
        val pn = total(p)
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
        val ds = w.days.sortedByDescending { it.date }
        for (d in ds) {
            val i = (epochDay(today) - epochDay(d.date)).toInt()
            val t = total(d)
            val prev = day(w, plusDays(d.date, -1))?.let { total(it) }
            val parts = ReelRules.App.values().map { a -> "${a.short()} ${d.app(a.key).n}" }
            append("${ago(i)} (${d.date.substring(5)}): $t रील • ${minutes(d)} मिनट • ${parts.joinToString(", ")}")
            if (prev != null && prev > 0) append(if (t > prev) " • पिछले दिन से ${t - prev} ज़्यादा" else if (t < prev) " • पिछले दिन से ${prev - t} कम" else " • पिछले दिन जितनी")
            if (limit > 0 && t > limit) append(" • लिमिट $limit पार")
            append('\n')
        }
        append("हफ़्ता ${w.start.substring(5)} से • ${resetDate(w).substring(5)} को अपने-आप नया हफ़्ता")
    }

    private fun ReelRules.App.short() = when (this) { ReelRules.App.INSTAGRAM -> "इंस्टा"; ReelRules.App.YOUTUBE -> "YouTube"; ReelRules.App.FACEBOOK -> "फेसबुक" }

    /** DK लाइव का tool जवाब */
    fun liveText(w: Week, today: String, limit: Int, accOn: Boolean): String = buildString {
        append("REEL / VIDEO WATCHING of the user (Instagram, YouTube incl. Shorts, Facebook) - last 7 days, real counts read on the phone screen. ")
        append("Counting works only while DK's Accessibility is ON (now ${if (accOn) "ON" else "OFF - nothing is being counted right now, tell him to turn it on"}); time when it was off is not counted. ")
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
        d.apps.forEach { (k, a) -> put(k, JSONObject().put("n", a.n).put("seen", a.seen)) }
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
        return Day(o.getString("date"), a.keys().asSequence().associateWith { k -> a.getJSONObject(k).let { x -> AppDay(x.optInt("n"), x.optLong("seen")) } },
            (0 until t.length()).map { t.getString(it) })
    }
}
