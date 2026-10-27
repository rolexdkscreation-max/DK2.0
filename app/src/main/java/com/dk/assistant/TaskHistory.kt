package com.dk.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.88: हर काम (DK कंप्यूटर + कोडिंग टीम) का इतिहास - फ़ोन में सेव, ऐप बंद होने के बाद भी.
 * [TaskStatus] की असली हालत से ही (कोई नकली % नहीं): काम, किस हालत में है, आख़िरी असली कदम, कितने कदम, समय.
 */
object TaskHistory {
    private const val KEY = "task_history_v1"
    private const val MAX = 60
    data class Entry(val id: String, val who: String, val task: String, val state: String, val last: String,
                     val steps: Int, val started: Long, val ended: Long)

    @Volatile private var app: Context? = null
    private val listener = TaskStatus.Listener { j, _ -> app?.let { save(it, j) } }

    fun install(c: Context) { app = c.applicationContext; TaskStatus.listen(listener) }

    private fun sp(c: Context) = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)

    @Synchronized private fun save(c: Context, j: TaskStatus.Job) {
        val id = "${j.key}:${j.started}"
        val l = all(c).filter { it.id != id }.toMutableList()
        l.add(0, Entry(id, j.who, j.task, j.state.hi, j.last.take(200), j.steps, j.started, j.ended))
        val a = JSONArray()
        l.take(MAX).forEach { e ->
            a.put(JSONObject().put("id", e.id).put("who", e.who).put("task", e.task).put("state", e.state).put("last", e.last)
                .put("steps", e.steps).put("started", e.started).put("ended", e.ended))
        }
        sp(c).edit().putString(KEY, a.toString()).apply()
    }

    fun all(c: Context): List<Entry> = try {
        val a = JSONArray(sp(c).getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { o ->
                Entry(o.optString("id"), o.optString("who"), o.optString("task"), o.optString("state"), o.optString("last"),
                    o.optInt("steps"), o.optLong("started"), o.optLong("ended"))
            }
        }
    } catch (_: Exception) { emptyList() }

    fun clear(c: Context) { sp(c).edit().remove(KEY).apply() }

    fun line(e: Entry): String {
        val t = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("hi", "IN")).format(java.util.Date(e.started))
        return "${e.who} • ${e.state} • $t\n${e.task}" + (if (e.last.isNotBlank()) "\nआख़िरी कदम: ${e.last}" else "") +
            (if (e.steps > 0) " (${e.steps} कदम)" else "")
    }
}
