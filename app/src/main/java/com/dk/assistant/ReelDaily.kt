package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * नवंबर 2026: 7 दिन का रील हिसाब ([ReelWeek]) फ़ोन में सेव + DK का बोलना (फ़ोन की अपनी आवाज़).
 * गिनती सिर्फ़ [ReelWatch] (Accessibility) से - सच्ची. मालिक: "जो नहीं हो सकता वह हटाओ" - Usage access वाला अंदाज़ा हटाया.
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
    fun addSeen(c: Context, app: ReelRules.App, ms: Long) = update(c) { w, d -> ReelWeek.addSeen(w, d, app.key, ms) }

    // ------------------------------------------------------------ आज की हालत
    data class Now(val total: Int, val yesterday: Int, val limit: Int, val minutes: Int)

    fun now(c: Context): Now {
        val w = week(c); val d = today()
        val t = ReelWeek.day(w, d)
        return Now(ReelWeek.total(t), ReelWeek.total(ReelWeek.day(w, ReelWeek.plusDays(d, -1))), totalLimit(c), ReelWeek.minutes(t))
    }

    fun table(c: Context): String = ReelWeek.table(week(c), today(), totalLimit(c))

    /** DK लाइव का reel_stats tool */
    fun liveTool(c: Context): String = ReelWeek.liveText(week(c), today(), totalLimit(c), Setup.accessibilityOn())

    // ------------------------------------------------------------ DK बोलता है (फ़ोन की अपनी आवाज़) - हर गिनी रील के बाद ReelWatch से
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
            lines += ReelWeek.alertLine(n.total, n.limit, n.yesterday)
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
