package com.dk.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * v3.88: फ़ोन की लोकल याददाश्त (SQLite, सिर्फ़ इसी फ़ोन में - कहीं upload नहीं).
 *
 *  turns  = हर बात (यूज़र / DK, आवाज़ / टेक्स्ट, तारीख-समय). 30 दिन बाद अपने-आप मिटती है.
 *  points = हर दिन का छोटा सार (पसंद, लोग, काम, तारीखें, वादे...). कभी अपने-आप नहीं मिटता - यूज़र कहे तभी.
 *  days   = किन दिनों का सार बन चुका (पुरानी बात मिटाने से पहले सार ज़रूर बने).
 *  dropped = यूज़र ने जो सार मिटाया - दोबारा न जुड़े.
 *
 * सब काम एक ही पीछे वाले thread पर (लिखना-पढ़ना क्रम से = "अभी क्या बोला था" तुरंत सही).
 */
object LocalMemory {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-local-memory").apply { isDaemon = true } }
    private val dispatcher = io.asCoroutineDispatcher()
    @Volatile private var helper: Db? = null
    @Volatile private var lastRec: Triple<String, String, Long>? = null

    private class Db(c: Context) : SQLiteOpenHelper(c, "dk_local_memory.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE turns(id INTEGER PRIMARY KEY AUTOINCREMENT, who TEXT NOT NULL, text TEXT NOT NULL, at INTEGER NOT NULL, src TEXT NOT NULL DEFAULT '', day TEXT NOT NULL)")
            db.execSQL("CREATE INDEX turns_at ON turns(at)")
            db.execSQL("CREATE INDEX turns_day ON turns(day)")
            db.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, text TEXT NOT NULL, at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE days(day TEXT PRIMARY KEY, done INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE TABLE dropped(norm TEXT PRIMARY KEY)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private fun db(c: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Db(c.applicationContext).also { helper = it } }
        return h.writableDatabase
    }

    // ------------------------------------------------------------------ लिखना (हर बात तुरंत)
    /** who = "u" (यूज़र) / "d" (DK). src = "voice" / "text". पासवर्ड/बैंक वाली बात सेव नहीं, नंबर हटाकर */
    fun record(c: Context?, who: String, text: String, src: String) {
        val ctx = c?.applicationContext ?: return
        if (!Prefs.localMemoryOn(ctx)) return
        val t = LocalRecall.storable(text) ?: return
        val now = System.currentTimeMillis()
        val last = lastRec
        if (last != null && last.first == who && last.second == t && now - last.third < 4000) return
        lastRec = Triple(who, t, now)
        io.execute {
            try {
                db(ctx).insert("turns", null, ContentValues().apply {
                    put("who", who); put("text", t); put("at", now); put("src", src); put("day", LocalRecall.dayOf(now))
                })
            } catch (e: Exception) { DKLog.d("याददाश्त: सेव नहीं हुआ - ${e.message}") }
        }
    }

    /** आख़िरी "आप" वाली बात का पक्का text बाद में आया */
    fun fixLastUser(c: Context?, text: String) {
        val ctx = c?.applicationContext ?: return
        if (!Prefs.localMemoryOn(ctx)) return
        val t = LocalRecall.storable(text) ?: return
        io.execute {
            try {
                val d = db(ctx)
                d.rawQuery("SELECT id FROM turns WHERE who='u' ORDER BY id DESC LIMIT 1", null).use { cur ->
                    if (cur.moveToFirst()) d.update("turns", ContentValues().apply { put("text", t) }, "id=?", arrayOf(cur.getLong(0).toString()))
                }
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ पढ़ना
    private fun turnsSince(d: SQLiteDatabase, from: Long): List<LocalRecall.Turn> {
        val out = ArrayList<LocalRecall.Turn>()
        d.rawQuery("SELECT id, who, text, at, src FROM turns WHERE at>=? ORDER BY at, id", arrayOf(from.toString())).use { c ->
            while (c.moveToNext()) out.add(LocalRecall.Turn(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4)))
        }
        return out
    }

    private fun allPoints(d: SQLiteDatabase): List<LocalRecall.Point> {
        val out = ArrayList<LocalRecall.Point>()
        d.rawQuery("SELECT id, day, text, at FROM points ORDER BY at, id", null).use { c ->
            while (c.moveToNext()) out.add(LocalRecall.Point(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
        }
        return out
    }

    /** याद वाले सवाल का जवाब - पूरा फ़ोन पर (कोई API/इंटरनेट नहीं). पहले 30 दिन की बातें, फिर सार */
    suspend fun answer(c: Context, question: String): String = withContext(dispatcher) {
        try {
            val now = System.currentTimeMillis()
            val q = LocalRecall.parse(question, now)
            val d = db(c)
            val h = LocalRecall.search(q, turnsSince(d, minOf(q.from, LocalRecall.cutoff(now))), allPoints(d))
            DKLog.d("याददाश्त खोज: \"${question.take(40)}\" → ${h.turns.size} बातें, ${h.points.size} सार (${q.label.ifBlank { "30 दिन" }}, शब्द: ${q.words})")
            LocalRecall.answer(q, h, now)
        } catch (e: Exception) { DKLog.d("याददाश्त खोज गड़बड़: ${e.message}"); LocalRecall.NOT_FOUND }
    }

    /** स्क्रीन के लिए (नई पहले) */
    suspend fun recent(c: Context, limit: Int = 300): List<LocalRecall.Turn> = withContext(dispatcher) {
        try {
            val out = ArrayList<LocalRecall.Turn>()
            db(c).rawQuery("SELECT id, who, text, at, src FROM turns ORDER BY at DESC, id DESC LIMIT $limit", null).use { cur ->
                while (cur.moveToNext()) out.add(LocalRecall.Turn(cur.getLong(0), cur.getString(1), cur.getString(2), cur.getLong(3), cur.getString(4)))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    suspend fun points(c: Context): List<LocalRecall.Point> = withContext(dispatcher) {
        try { allPoints(db(c)).reversed() } catch (_: Exception) { emptyList() }
    }

    suspend fun counts(c: Context): Pair<Int, Int> = withContext(dispatcher) {
        try {
            val d = db(c)
            fun n(t: String) = d.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            n("turns") to n("points")
        } catch (_: Exception) { 0 to 0 }
    }

    /** prompt में पूरी पुरानी बातचीत की जगह छोटा सार (तेज़, कम डेटा). पीछे वाले thread को नहीं रोकता */
    @Volatile private var blockCache: Pair<Long, String> = 0L to ""
    fun promptBlock(c: Context): String {
        val (at, s) = blockCache
        if (System.currentTimeMillis() - at < 10 * 60_000L && at > 0) return s
        io.execute {
            blockCache = System.currentTimeMillis() to try { LocalRecall.promptBlock(allPoints(db(c)).takeLast(30)) } catch (_: Exception) { "" }
        }
        return s
    }

    /**
     * v3.6: DK लाइव शुरू होते समय - पुराने दिनों का सार + पिछले कुछ घंटों की असली बातें. ताज़ा पढ़ो (पहली बार
     * cache खाली होने से कुछ नहीं जाता था), पर ज़्यादा से ज़्यादा ~400ms रुककर.
     */
    fun liveBlock(c: Context): String {
        if (!Prefs.localMemoryOn(c)) return promptBlock(c)
        val ctx = c.applicationContext
        return try {
            io.submit<String> {
                val d = db(ctx); val now = System.currentTimeMillis()
                val pts = LocalRecall.promptBlock(allPoints(d).takeLast(30))
                blockCache = now to pts
                pts + LocalRecall.recentBlock(turnsSince(d, now - 6 * 3_600_000L), now)
            }.get(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) { promptBlock(c) }
    }

    // ------------------------------------------------------------------ मिटाना (यूज़र के कहने पर)
    suspend fun deleteTurn(c: Context, id: Long) = withContext(dispatcher) { try { db(c).delete("turns", "id=?", arrayOf(id.toString())) } catch (_: Exception) { 0 } }

    suspend fun deletePoint(c: Context, id: Long) = withContext(dispatcher) {
        try {
            val d = db(c)
            d.rawQuery("SELECT text FROM points WHERE id=?", arrayOf(id.toString())).use { cur ->
                if (cur.moveToFirst()) d.insertWithOnConflict("dropped", null, ContentValues().apply { put("norm", LocalRecall.norm(cur.getString(0))) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            d.delete("points", "id=?", arrayOf(id.toString()))
        } catch (_: Exception) { 0 }
    }

    suspend fun clearAll(c: Context) = withContext(dispatcher) {
        try { val d = db(c); d.delete("turns", null, null); d.delete("points", null, null); d.delete("days", null, null); d.delete("dropped", null, null) } catch (_: Exception) {}
        blockCache = 0L to ""
    }

    /**
     * "ये भूल जाओ" (पिछली बात + उसका जवाब) या "X भूल जाओ" (जिन बातों/सार में X के सारे शब्द हों).
     * Memory.forget से बुलाया जाता है (blocking, पीछे वाले thread पर इंतज़ार).
     */
    fun forgetBlocking(c: Context, about: String): Int = try {
        io.submit<Int> {
            val d = db(c)
            val now = System.currentTimeMillis()
            var n = 0
            if (LocalRecall.forgetsLast(about)) {
                val turns = turnsSince(d, now - 6 * 3_600_000L)
                val last = LocalRecall.lastRealUser(turns, now - 500)
                if (last != null) {
                    val i = turns.indexOf(last)
                    val reply = turns.getOrNull(i + 1)?.takeIf { it.who == "d" }
                    n += d.delete("turns", "id=?", arrayOf(last.id.toString()))
                    if (reply != null) n += d.delete("turns", "id=?", arrayOf(reply.id.toString()))
                    val w = LocalRecall.keywords(last.text)
                    if (w.isNotEmpty()) for (p in allPoints(d)) if (LocalRecall.score(w, p.text) >= minOf(2, w.size)) { n += d.delete("points", "id=?", arrayOf(p.id.toString())); d.insertWithOnConflict("dropped", null, ContentValues().apply { put("norm", LocalRecall.norm(p.text)) }, SQLiteDatabase.CONFLICT_IGNORE) }
                }
            } else {
                val w = LocalRecall.keywords(about)
                if (w.isNotEmpty()) {
                    for (t in turnsSince(d, 0)) if (LocalRecall.score(w, t.text) == w.size) n += d.delete("turns", "id=?", arrayOf(t.id.toString()))
                    for (p in allPoints(d)) if (LocalRecall.score(w, p.text) == w.size) {
                        n += d.delete("points", "id=?", arrayOf(p.id.toString()))
                        d.insertWithOnConflict("dropped", null, ContentValues().apply { put("norm", LocalRecall.norm(p.text)) }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
            }
            blockCache = 0L to ""
            DKLog.d("याददाश्त: \"${about.take(30)}\" भूल गई ($n)")
            n
        }.get(5, java.util.concurrent.TimeUnit.SECONDS)
    } catch (_: Exception) { 0 }

    // ------------------------------------------------------------------ सार + 30 दिन की सफ़ाई
    /** एक दिन का सार (पहले से जुड़ा / यूज़र का मिटाया दोबारा नहीं) */
    private fun summarizeDay(d: SQLiteDatabase, day: String, final: Boolean): Int {
        val turns = ArrayList<LocalRecall.Turn>()
        d.rawQuery("SELECT id, who, text, at, src FROM turns WHERE day=? ORDER BY at, id", arrayOf(day)).use { c ->
            while (c.moveToNext()) turns.add(LocalRecall.Turn(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4)))
        }
        val have = HashSet<String>()
        d.rawQuery("SELECT text FROM points WHERE day=?", arrayOf(day)).use { c -> while (c.moveToNext()) have.add(LocalRecall.norm(c.getString(0))) }
        d.rawQuery("SELECT norm FROM dropped", null).use { c -> while (c.moveToNext()) have.add(c.getString(0)) }
        var added = 0
        val at = turns.lastOrNull()?.at ?: System.currentTimeMillis()
        for (p in LocalRecall.summarize(turns)) {
            if (LocalRecall.norm(p) in have) continue
            d.insert("points", null, ContentValues().apply { put("day", day); put("text", p); put("at", at) })
            added++
        }
        if (final) d.insertWithOnConflict("days", null, ContentValues().apply { put("day", day); put("done", 1) }, SQLiteDatabase.CONFLICT_REPLACE)
        return added
    }

    /**
     * ऐप खुलने पर + रोज़ एक बार + बातचीत खत्म होने पर:
     * 1) आज से पहले के जिन दिनों का सार नहीं बना - बनाओ  2) आज का अब तक का सार  3) 30 दिन से पुरानी बातें मिटाओ
     * (सिर्फ़ उन्हीं दिनों की जिनका सार बन चुका - सार पहले, मिटाना बाद में)
     */
    fun maintain(c: Context, why: String) {
        val ctx = c.applicationContext
        io.execute {
            try {
                val d = db(ctx)
                val now = System.currentTimeMillis()
                val today = LocalRecall.dayOf(now)
                val days = ArrayList<String>()
                d.rawQuery("SELECT DISTINCT day FROM turns", null).use { cur -> while (cur.moveToNext()) days.add(cur.getString(0)) }
                val done = HashSet<String>()
                d.rawQuery("SELECT day FROM days", null).use { cur -> while (cur.moveToNext()) done.add(cur.getString(0)) }
                var added = 0
                for (day in LocalRecall.daysToSummarize(days, done, today)) added += summarizeDay(d, day, final = true)
                if (today in days) added += summarizeDay(d, today, final = false)
                done.clear()
                d.rawQuery("SELECT day FROM days", null).use { cur -> while (cur.moveToNext()) done.add(cur.getString(0)) }
                val cut = LocalRecall.cutoff(now)
                var removed = 0
                val old = ArrayList<String>()
                d.rawQuery("SELECT DISTINCT day FROM turns WHERE at<?", arrayOf(cut.toString())).use { cur -> while (cur.moveToNext()) old.add(cur.getString(0)) }
                for (day in old) if (day in done) removed += d.delete("turns", "day=? AND at<?", arrayOf(day, cut.toString()))
                if (added > 0) blockCache = 0L to ""
                DKLog.d("याददाश्त ($why): $added नए सार, 30 दिन से पुरानी $removed बातें मिटाईं")
            } catch (e: Exception) { DKLog.d("याददाश्त सफ़ाई गड़बड़: ${e.message}") }
        }
    }

    /** रोज़ एक बार पीछे सफ़ाई (फ़ोन restart के बाद ऐप खुलते ही फिर से लगती है) */
    fun scheduleDaily(c: Context) {
        try {
            val ctx = c.applicationContext
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(ctx, 7301, Intent(ctx, LocalMemoryReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_DAY, pi)
        } catch (e: Exception) { DKLog.d("याददाश्त: रोज़ की सफ़ाई तय नहीं हुई - ${e.message}") }
    }
}

/** रोज़ वाली सफ़ाई (AlarmManager) */
class LocalMemoryReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = goAsync()
        LocalMemory.maintain(c, "रोज़")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { p.finish() } catch (_: Exception) {} }, 4000)
    }
}
