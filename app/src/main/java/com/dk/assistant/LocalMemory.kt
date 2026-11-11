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
 * v3.88: फ़ोन की लोकल याददाश्त (SQLite, सिर्फ़ इसी फ़ोन में - कहीं upload नहीं;
 * नवंबर 2026: user खुद सेटिंग्स → बैकअप दबाए तभी उसके अपने Google Drive में - [com.dk.assistant.backup.MemoryBackup]).
 *
 *  turns  = हर बात (यूज़र / DK, आवाज़ / टेक्स्ट, तारीख-समय). नवंबर 2026: हमेशा रहती है - कभी अपने-आप नहीं मिटती
 *           (सिर्फ़ मालिक के "भूल जाओ" / याददाश्त स्क्रीन से मिटाने पर).
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

    /**
     * नवंबर 2026: हमेशा रहने वाली एक याद (सार की टेबल में) - जैसे "याद रखना" वाली पुरानी बात जो छोटी सूची से बाहर हुई,
     * या DK ने किसी AI से जो सीखा. याद वाली खोज में मिलती है, अपने-आप कभी नहीं मिटती.
     */
    fun keepPoint(c: Context?, text: String) {
        val ctx = c?.applicationContext ?: return
        val t = LocalRecall.storable(text) ?: return
        io.execute {
            try {
                val d = db(ctx)
                val n = LocalRecall.norm(t)
                val dup = d.rawQuery("SELECT COUNT(*) FROM points WHERE text=?", arrayOf(t)).use { it.moveToFirst() && it.getInt(0) > 0 }
                if (!dup) {
                    val now = System.currentTimeMillis()
                    d.insert("points", null, ContentValues().apply { put("day", LocalRecall.dayOf(now)); put("text", t); put("at", now) })
                    d.delete("dropped", "norm=?", arrayOf(n))
                    blockCache = 0L to ""
                }
            } catch (e: Exception) { DKLog.d("याददाश्त: पक्की याद सेव नहीं हुई - ${e.message}") }
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

    /**
     * नवंबर 2026: पूरी याद (शुरू से) में खोज. बहुत बड़ी याद में भी तेज़ - आख़िरी [RECENT_SCAN] बातें पूरी,
     * उससे पुरानी में सिर्फ़ वे जिनमें सवाल का कोई शब्द है (SQL LIKE).
     */
    private const val RECENT_SCAN = 20_000
    private const val OLD_MATCH = 5_000
    private fun turnsFor(d: SQLiteDatabase, q: LocalRecall.Query): List<LocalRecall.Turn> {
        // समय बोला हो (कल/आज/2 दिन पहले) - उससे एक दिन पहले से (याद वाले सवाल पहचानने को), बस
        if (q.from > LocalRecall.ALL_TIME) return turnsSince(d, maxOf(0L, q.from - LocalRecall.DAY_MS))
        val recent = ArrayList<LocalRecall.Turn>()
        d.rawQuery("SELECT id, who, text, at, src FROM turns ORDER BY at DESC, id DESC LIMIT $RECENT_SCAN", null).use { c ->
            while (c.moveToNext()) recent.add(LocalRecall.Turn(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4)))
        }
        recent.reverse()
        if (recent.size < RECENT_SCAN || q.words.isEmpty()) return recent
        val oldest = recent.first().at
        val ws = q.words.take(6)
        val like = ws.joinToString(" OR ") { "text LIKE ?" }
        val args = arrayOf(oldest.toString()) + ws.map { "%$it%" }
        val old = ArrayList<LocalRecall.Turn>()
        d.rawQuery("SELECT id, who, text, at, src FROM turns WHERE at<? AND ($like) ORDER BY at DESC, id DESC LIMIT $OLD_MATCH", args).use { c ->
            while (c.moveToNext()) old.add(LocalRecall.Turn(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4)))
        }
        old.reverse()
        return old + recent
    }

    /** याद वाले सवाल का जवाब - पूरा फ़ोन पर (कोई API/इंटरनेट नहीं). पूरी याद (शुरू से) की बातें, फिर सार */
    suspend fun answer(c: Context, question: String): String = withContext(dispatcher) {
        try {
            val now = System.currentTimeMillis()
            val q = LocalRecall.parse(question, now)
            val d = db(c)
            val h = LocalRecall.search(q, turnsFor(d, q), allPoints(d))
            DKLog.d("याददाश्त खोज: \"${question.take(40)}\" → ${h.turns.size} बातें, ${h.points.size} सार (${q.label.ifBlank { "पूरी याद" }}, शब्द: ${q.words})")
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

    /**
     * नवंबर 2026: Google Drive बैकअप ([com.dk.assistant.backup.MemoryBackup]) - पूरी लोकल याद (हर बात शुरू से + सार).
     * पीछे वाले उसी thread पर (लिखना-पढ़ना क्रम से), इंतज़ार के साथ.
     */
    fun exportBlocking(c: Context): Triple<List<com.dk.assistant.backup.MemoryBackup.T>, List<com.dk.assistant.backup.MemoryBackup.P>, Pair<List<String>, List<String>>> =
        io.submit<Triple<List<com.dk.assistant.backup.MemoryBackup.T>, List<com.dk.assistant.backup.MemoryBackup.P>, Pair<List<String>, List<String>>>> {
            val d = db(c.applicationContext)
            val turns = ArrayList<com.dk.assistant.backup.MemoryBackup.T>()
            d.rawQuery("SELECT who, text, at, src, day FROM turns ORDER BY at, id", null).use { k ->
                while (k.moveToNext()) turns.add(com.dk.assistant.backup.MemoryBackup.T(k.getString(0), k.getString(1), k.getLong(2), k.getString(3).orEmpty(), k.getString(4)))
            }
            val points = allPoints(d).map { com.dk.assistant.backup.MemoryBackup.P(it.day, it.text, it.at) }
            val days = ArrayList<String>(); d.rawQuery("SELECT day FROM days", null).use { k -> while (k.moveToNext()) days.add(k.getString(0)) }
            val dropped = ArrayList<String>(); d.rawQuery("SELECT norm FROM dropped", null).use { k -> while (k.moveToNext()) dropped.add(k.getString(0)) }
            Triple(turns, points, days to dropped)
        }.get(120, java.util.concurrent.TimeUnit.SECONDS)

    /** रिस्टोर: सिर्फ़ जोड़ना (फ़ोन में जो है वह नहीं मिटता), एक transaction में. वापस: कितनी बातें + सार जुड़े */
    fun importBlocking(c: Context, turns: List<com.dk.assistant.backup.MemoryBackup.T>, points: List<com.dk.assistant.backup.MemoryBackup.P>, days: List<String>, dropped: List<String>): Pair<Int, Int> =
        io.submit<Pair<Int, Int>> {
            val d = db(c.applicationContext)
            var nt = 0; var np = 0
            d.beginTransaction()
            try {
                for (t in turns) {
                    val dup = d.rawQuery("SELECT 1 FROM turns WHERE at=? AND who=? AND text=? LIMIT 1", arrayOf(t.at.toString(), t.who, t.text)).use { it.moveToFirst() }
                    if (dup) continue
                    d.insert("turns", null, ContentValues().apply {
                        put("who", t.who); put("text", t.text); put("at", t.at); put("src", t.src); put("day", t.day.ifBlank { LocalRecall.dayOf(t.at) })
                    }); nt++
                }
                for (p in points) {
                    val dup = d.rawQuery("SELECT 1 FROM points WHERE text=? LIMIT 1", arrayOf(p.text)).use { it.moveToFirst() }
                    if (dup) continue
                    d.insert("points", null, ContentValues().apply { put("day", p.day.ifBlank { LocalRecall.dayOf(p.at) }); put("text", p.text); put("at", p.at) }); np++
                }
                for (day in days) d.insertWithOnConflict("days", null, ContentValues().apply { put("day", day); put("done", 1) }, SQLiteDatabase.CONFLICT_IGNORE)
                for (n in dropped) d.insertWithOnConflict("dropped", null, ContentValues().apply { put("norm", n) }, SQLiteDatabase.CONFLICT_IGNORE)
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
            blockCache = 0L to ""
            DKLog.d("याददाश्त: बैकअप से $nt बातें, $np सार जुड़े")
            nt to np
        }.get(300, java.util.concurrent.TimeUnit.SECONDS)

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

    // ------------------------------------------------------------------ सार (अब कोई अपने-आप सफ़ाई नहीं)
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
     * 1) आज से पहले के जिन दिनों का सार नहीं बना - बनाओ  2) आज का अब तक का सार.
     * नवंबर 2026 (मालिक): पहले 3) 30 दिन से पुरानी बातें मिटती थीं - अब कभी नहीं. पूरी बातचीत फ़ोन में हमेशा रहती है.
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
                // कोई बात अपने-आप नहीं मिटती (LocalRecall.cutoff = null)
                if (added > 0) blockCache = 0L to ""
                DKLog.d("याददाश्त ($why): $added नए सार - सारी बातें सुरक्षित, कुछ नहीं मिटाया")
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
