package com.dk.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * v3.88: DK कंप्यूटर के काम की हालत फ़ोन में (filesDir/computer_session.json):
 * काम, चल रहा था या नहीं, नेटवर्क की वजह से रुका, स्क्रीन की लाइनें, AI की बातचीत.
 * ऐप बंद हो जाए / फ़ोन ने ऐप हटा दिया / नेटवर्क टूटा = लौटने पर वहीं से आगे ([ComputerSession.resume]).
 * लिखना 1 सेकंड में एक बार (हर कदम पर नहीं), अलग thread पर.
 */
object ComputerSaved {
    data class State(val task: String, val running: Boolean, val netPaused: Boolean, val at: Long,
                     val lines: List<Pair<String, String>>, val msgs: List<Pair<String, String>>)

    private const val FILE = "computer_session.json"
    private const val MAX_LINES = 200
    private const val MAX_MSG_CHARS = 400_000
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    @Volatile private var running = false
    @Volatile private var netPaused = false
    @Volatile private var pending = false

    private fun file(c: Context) = File(c.filesDir, FILE)

    fun mark(c: Context, running: Boolean, netPaused: Boolean) {
        app = c.applicationContext; this.running = running; this.netPaused = netPaused
        flushSoon(0)
    }

    /** कुछ बदला - थोड़ी देर में सेव */
    fun dirty() { if (app != null) flushSoon(1_000) }

    private fun flushSoon(ms: Long) {
        if (pending && ms > 0) return
        pending = true
        main.postDelayed({ pending = false; snapshot() }, ms)
    }

    /** Main thread पर ही copy (session की list वहीं बदलती है), लिखना IO पर */
    private fun snapshot() {
        val c = app ?: return
        val task = ComputerSession.runningTask ?: return
        val lines = ComputerSession.lines.takeLast(MAX_LINES).map { it.role to it.text }
        var msgs = ComputerSession.agentMsgs()
        while (msgs.size > 4 && msgs.sumOf { it.second.length } > MAX_MSG_CHARS) msgs = msgs.drop(2)
        val r = running; val np = netPaused
        io.execute {
            try {
                val o = JSONObject().put("task", task).put("running", r).put("net", np).put("at", System.currentTimeMillis())
                    .put("lines", pairs(lines)).put("msgs", pairs(msgs))
                val f = file(c); val tmp = File(f.path + ".tmp")
                tmp.writeText(o.toString()); tmp.renameTo(f)
            } catch (e: Exception) { DKLog.d("DK कंप्यूटर की हालत सेव नहीं हुई: ${e.javaClass.simpleName}") }
        }
    }

    private fun pairs(l: List<Pair<String, String>>) = JSONArray().also { a -> l.forEach { a.put(JSONArray().put(it.first).put(it.second)) } }
    private fun unpairs(a: JSONArray?): List<Pair<String, String>> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optString(0) to it.optString(1) } }

    fun load(c: Context): State? = try {
        val f = file(c)
        if (!f.exists()) null else JSONObject(f.readText()).let { o ->
            State(o.optString("task"), o.optBoolean("running"), o.optBoolean("net"), o.optLong("at"),
                unpairs(o.optJSONArray("lines")), unpairs(o.optJSONArray("msgs")))
        }
    } catch (_: Exception) { null }

    fun clear() {
        val c = app
        running = false; netPaused = false
        if (c != null) io.execute { try { file(c).delete() } catch (_: Exception) {} }
    }
}
