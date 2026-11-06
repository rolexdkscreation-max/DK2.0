package com.dk.assistant

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * नवंबर 2026: 7 दिन का रील हिसाब ([ReelWeek]) फ़ोन में सेव + Usage access से ऐप का समय (Accessibility नहीं चाहिए)
 * + DK का बोलना (फ़ोन की अपनी आवाज़ - Accessibility नहीं चाहिए).
 */
object ReelDaily {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    // ------------------------------------------------------------ सेटिंग
    /** सब ऐप मिलाकर रोज़ की लिमिट (0 = कोई नहीं) - पार हो तो DK बोलेगा (रोकता नहीं) */
    fun totalLimit(c: Context) = sp(c).getInt("reel_limit_all", 0)
    fun setTotalLimit(c: Context, n: Int) = sp(c).edit().putInt("reel_limit_all", n.coerceIn(0, 10_000)).apply()
    fun voiceOn(c: Context) = sp(c).getBoolean("reel_voice", true)
    fun setVoice(c: Context, on: Boolean) = sp(c).edit().putBoolean("reel_voice", on).apply()
    /** बिना Accessibility वाला हिस्सा (Usage access से समय + बोलना) चालू? */
    fun bgOn(c: Context) = sp(c).getBoolean("reel_bg", true)
    fun setBg(c: Context, on: Boolean) = sp(c).edit().putBoolean("reel_bg", on).apply()

    // ------------------------------------------------------------ हफ़्ता
    fun week(c: Context): ReelWeek.Week = synchronized(lock) {
        val old = ReelWeek.decode(sp(c).getString("reel_week", null))
        val w = ReelWeek.roll(old, today())
        if (w != old) save(c, w)
        w
    }

    private fun save(c: Context, w: ReelWeek.Week) = sp(c).edit().putString("reel_week", ReelWeek.encode(w)).apply()

    private fun update(c: Context, f: (ReelWeek.Week, String) -> ReelWeek.Week) = synchronized(lock) {
        val d = today()
        save(c, f(ReelWeek.roll(ReelWeek.decode(sp(c).getString("reel_week", null)), d), d))
    }

    fun addReel(c: Context, app: ReelRules.App, title: String?) = update(c) { w, d -> ReelWeek.addReel(w, d, app.key, title) }
    fun addSeen(c: Context, app: ReelRules.App, ms: Long, reelMs: Long) = update(c) { w, d -> ReelWeek.addSeen(w, d, app.key, ms, reelMs) }

    // ------------------------------------------------------------ Usage access: हफ़्ते के हर दिन का ऐप-समय
    private val PKGS = ReelRules.App.values().flatMap { it.pkgs }.toSet()

    fun refreshUsage(c: Context, onlyToday: Boolean = false) {
        if (!PayGuard.usageOk(c)) return
        val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val w = week(c)
        val dates = if (onlyToday) listOf(today()) else w.days.map { it.date }
        val res = dates.associateWith { d ->
            val from = dayStart(d); val to = minOf(from + 86_400_000L, System.currentTimeMillis())
            val ev = ArrayList<Triple<String, Boolean, Long>>()
            try {
                val q = um.queryEvents(from - 6 * 3_600_000L, to); val e = UsageEvents.Event()
                while (q.hasNextEvent()) {
                    q.getNextEvent(e)
                    @Suppress("DEPRECATION")
                    when (e.eventType) {
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> ev += Triple(e.packageName, true, e.timeStamp)
                        UsageEvents.Event.MOVE_TO_BACKGROUND -> ev += Triple(e.packageName, false, e.timeStamp)
                    }
                }
            } catch (_: Exception) {}
            ReelWeek.foregroundMs(ev, from, to, PKGS)
        }
        update(c) { cur, _ ->
            var x = cur
            for ((d, m) in res) for (a in ReelRules.App.values()) {
                val ms = a.pkgs.sumOf { m[it] ?: 0L }
                val old = ReelWeek.day(x, d)?.app(a.key)?.secs ?: 0L
                if (ms > old && x.days.any { it.date == d }) x = ReelWeek.setSecs(x, d, a.key, ms)   // पुराना Android डेटा मिट गया हो तो कम न करो
            }
            x
        }
    }

    private fun dayStart(d: String): Long = Calendar.getInstance().apply {
        val (y, m, dd) = d.split("-").map { it.toInt() }
        set(y, m - 1, dd, 0, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Usage access से अभी सामने वाला ऐप (Accessibility बंद हो तब) */
    fun foregroundNow(c: Context): String? = try {
        val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val q = um.queryEvents(now - 3 * 3_600_000L, now); val e = UsageEvents.Event()
        var last: String? = null
        while (q.hasNextEvent()) {
            q.getNextEvent(e)
            @Suppress("DEPRECATION")
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> last = e.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (e.packageName == last) last = null
            }
        }
        last
    } catch (_: Exception) { null }

    // ------------------------------------------------------------ आज की हालत
    data class Now(val total: Int, val yesterday: Int, val limit: Int, val estimated: Boolean, val minutes: Int)

    fun now(c: Context): Now {
        val w = week(c); val per = ReelWeek.reelSecs(w); val d = today()
        val t = ReelWeek.day(w, d)
        return Now(ReelWeek.total(t, per), ReelWeek.total(ReelWeek.day(w, ReelWeek.plusDays(d, -1)), per), totalLimit(c), ReelWeek.estimated(t, per), ReelWeek.minutes(t))
    }

    fun table(c: Context): String = ReelWeek.table(week(c), today(), totalLimit(c))

    /** DK लाइव का reel_stats tool */
    fun liveTool(c: Context): String {
        try { refreshUsage(c) } catch (_: Exception) {}
        return ReelWeek.liveText(week(c), today(), totalLimit(c), Setup.accessibilityOn(), PayGuard.usageOk(c))
    }

    // ------------------------------------------------------------ DK बोलता है (फ़ोन की आवाज़, Accessibility नहीं चाहिए)
    @Volatile private var speaking = false

    /** रील ऐप सामने है: दिन की पहली बार कल का हिसाब, फिर लिमिट 80% / पार / हर +10 पर, लिमिट नहीं = कल से ज़्यादा होते ही */
    fun coach(c: Context) {
        if (!voiceOn(c) || speaking) return
        val s = sp(c); val d = today(); val n = now(c)
        val lines = ArrayList<String>()
        if (s.getString("reel_said_daily", "") != d) {
            s.edit().putString("reel_said_daily", d).apply()
            ReelWeek.dailyLine(week(c), d, n.limit)?.let { lines += it }
        }
        val step = ReelWeek.alertStep(n.total, n.limit, n.yesterday)
        val key = "reel_said_step_$d"
        if (step > s.getInt(key, 0)) {
            s.edit().putInt(key, step).apply()
            lines += ReelWeek.alertLine(n.total, n.limit, n.yesterday).let { if (n.estimated) it.replaceFirst("आज आप ", "आज आप लगभग ").replaceFirst("आज ${n.total}", "आज लगभग ${n.total}") else it }
        }
        if (lines.isEmpty()) return
        speaking = true
        scope.launch {
            try {
                val text = lines.joinToString(" ")
                DKLog.d("रील हिसाब (DK बोला): $text")
                PhoneVoice.say(c.applicationContext, text)
            } catch (_: Exception) {} finally { speaking = false }
        }
    }
}
