package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * v3.45: दोस्त वाली याददाश्त - फ़ाइल सिर्फ़ फ़ोन पर (filesDir/dk_friend.json; Android backup से बाहर).
 * - [sessionEnd]: बातचीत खत्म -> समय/देर + AI से छोटा सारांश, मूड, नई बातें (facts), अधूरी बातें
 * - [block]: Live/चैट के system instruction में (आखिरी मुलाकात, सारांश, अधूरी बातें)
 * - [openerNote]: ऐप खुलते ही DK खुद क्या बोले (समय, त्योहार, रूठना, अधूरी बात, ताज़ा लोकेशन+मौसम)
 */
object FriendTalk {
    private const val FILE = "dk_friend.json"
    private val lock = Any()

    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE)

    fun load(c: Context): FriendBook = synchronized(lock) {
        try { file(c).takeIf { it.exists() }?.readText()?.let { FriendBook.fromJson(it) } ?: FriendBook() } catch (_: Exception) { FriendBook() }
    }

    private fun save(c: Context, b: FriendBook) = synchronized(lock) {
        try {
            val f = file(c); val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(b.toJson()); if (!tmp.renameTo(f)) { f.writeText(b.toJson()); tmp.delete() }
        } catch (e: Exception) { DKLog.d("दोस्त-याद सेव नहीं हुई: ${e.message}") }
    }

    private fun edit(c: Context, f: (FriendBook) -> Unit) = synchronized(lock) { val b = load(c); f(b); save(c, b) }

    fun block(c: Context): String = try { load(c).promptBlock(System.currentTimeMillis()) } catch (_: Exception) { "" }

    fun forget(c: Context, about: String): Int {
        var n = 0
        edit(c) { n = it.forget(about) }
        return n
    }

    fun clear(c: Context) = edit(c) { it.clear() }

    /** ऐप खुला और DK ने बात शुरू की - 20 मिनट तक दोबारा अभिवादन नहीं */
    fun markGreeted(c: Context) = edit(c) { it.lastGreet = System.currentTimeMillis() }

    fun shouldGreet(c: Context): Boolean { val b = load(c); return FriendStyle.shouldGreet(System.currentTimeMillis(), b.lastSeen, b.lastGreet) }

    /**
     * v4.3 (मालिक): ऐप खुलते ही "इतनी देर कहाँ थे?" - पिछली बातचीत हो चुकी हो तो कुछ मिनट बाद भी पूछे ([ReturnGreet]).
     * पहली बार (कभी बात नहीं हुई) = पुराना नियम (परिचय, 20 मिनट).
     */
    fun shouldGreetNow(c: Context, lastMsg: Long): Boolean {
        val b = load(c); val now = System.currentTimeMillis()
        if (b.lastSeen <= 0) return FriendStyle.shouldGreet(now, b.lastSeen, b.lastGreet) && now - lastMsg >= 20 * 60_000L
        return ReturnGreet.shouldAsk(now, b.lastSeen, b.lastGreet, lastMsg)
    }

    @Volatile private var lastTouch = 0L

    /** v4.3: हर बातचीत (turn) के अंत में समय फ़ोन में सेव - ऐप अचानक बंद हो तब भी "कब बात हुई" सही रहे. 30 s में एक बार लिखता है */
    fun touch(c: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTouch < 30_000L) return
        lastTouch = now
        try { edit(c.applicationContext) { it.lastSeen = maxOf(it.lastSeen, now) } } catch (_: Exception) {}
    }

    /**
     * ऐप खुलते ही DK का पहला SYSTEM NOTE. ताज़ा लोकेशन+मौसम यहीं (हर बार नई; कहीं सेव नहीं).
     * स्थानीय खबर के लिए Google Search: 3 घंटे में एक बार (API खर्च बचे).
     */
    suspend fun openerNote(c: Context): String {
        val now = System.currentTimeMillis()
        val local = try { LocalInfo.now(c)?.second } catch (_: Exception) { null }
        val newsOk = local != null && now - Prefs.lastLocalNewsAt(c) > 3 * 3_600_000L
        if (newsOk) Prefs.edit(c) { putLong("friend_local_news_at", now) }
        val z = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        return FriendStyle.opener(z.hour, z.toLocalDate(), load(c), now, local, newsOk)
    }

    /**
     * बातचीत खत्म ([start] से अब तक DKState की बातें). कम से कम एक असली बात हो तभी.
     * सारांश पीछे (IO) बनता है - user को इंतज़ार नहीं.
     */
    fun sessionEnd(c: Context, start: Long, scope: CoroutineScope) {
        val msgs = DKState.messages.filter { it.time >= start && it.text.isNotBlank() }
        val userLines = msgs.filter { it.role == "u" }.map { it.text }
        if (userLines.none { it.trim().length > 2 }) return
        val end = msgs.maxOfOrNull { it.time } ?: System.currentTimeMillis()
        val app = c.applicationContext
        // आखिरी मुलाकात तुरंत (सारांश बाद में आए तो भी "कब बात हुई" सही रहे)
        edit(app) { it.lastSeen = maxOf(it.lastSeen, end) }
        scope.launch(Dispatchers.IO) {
            val transcript = msgs.joinToString("\n") { (if (it.role == "u") "User: " else "DK: ") + Sensitive.redact(it.text).take(400) }.takeLast(6000)
            val raw = try { BrainManager(app).quickText(FriendStyle.SUMMARY_SYSTEM, FriendStyle.summaryPrompt(transcript)) } catch (_: Exception) { null }
            val s = raw?.let { FriendStyle.parseSummary(it) }
            edit(app) { b ->
                b.addSession(start, end, s?.summary ?: FriendStyle.fallbackSummary(userLines), s?.mood ?: "")
                if (s != null) b.updateThreads(s.open, s.done, end)
            }
            s?.facts?.forEach { Memory.addFact(app, it) }
            DKLog.d("बातचीत याद: ${((end - start) / 60_000)} मिनट" + (s?.let { " | ${it.summary.take(60)}" } ?: " (AI सारांश नहीं बना - छोटा नोट)"))
        }
    }
}
