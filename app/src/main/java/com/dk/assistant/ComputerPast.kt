package com.dk.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v5 मालिक: DK कंप्यूटर को पिछले काम याद रहें (जैसे Perplexity Computer की memory) -
 * "पिछली बार जो रिपोर्ट बनाई थी उसमें…", "कल वाले फ़ोन की लिस्ट" - past_tasks टूल से खोजता है.
 * फ़ोन में ही सेव (आख़िरी [MAX] काम), कहीं भेजा नहीं जाता.
 */
object ComputerPast {
    data class Item(val at: Long, val task: String, val answer: String, val files: List<String>)

    const val MAX = 80
    const val FILE = "computer_past.json"

    // ------------------------------------------------------------ pure (tests)
    fun words(s: String): List<String> = s.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").split(' ')
        .filter { it.length >= 2 }.distinct()

    /** शब्द मिलें तो ऊपर; नया काम थोड़ा ऊपर. खाली query = सबसे नए */
    fun search(items: List<Item>, query: String, n: Int = 5): List<Item> {
        val q = words(query)
        if (q.isEmpty()) return items.sortedByDescending { it.at }.take(n)
        return items.map { it to (words(it.task + " " + it.answer + " " + it.files.joinToString(" ")).toSet()) }
            .map { (it, w) ->
                val hit = q.count { qq -> w.any { ww -> ww == qq || (qq.length >= 4 && (ww.startsWith(qq) || qq.startsWith(ww) && ww.length >= 4)) } }
                Triple(it, hit, it.at)
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<Item, Int, Long>> { it.second }.thenByDescending { it.third })
            .take(n).map { it.first }
    }

    fun encode(items: List<Item>): String = JSONArray().also { a ->
        items.forEach { a.put(JSONObject().put("at", it.at).put("task", it.task).put("answer", it.answer).put("files", JSONArray(it.files))) }
    }.toString()

    fun decode(raw: String?): List<Item> = try {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val f = o.optJSONArray("files") ?: JSONArray()
            Item(o.optLong("at"), o.optString("task"), o.optString("answer"), (0 until f.length()).map { f.optString(it) })
        }
    } catch (_: Exception) { emptyList() }

    fun show(items: List<Item>): String = if (items.isEmpty()) "(पिछले कामों में कुछ नहीं मिला)" else items.joinToString("\n\n") {
        val d = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("hi", "IN")).format(java.util.Date(it.at))
        "[$d] काम: ${it.task.take(300)}\n" + (if (it.files.isNotEmpty()) "फ़ाइलें: ${it.files.joinToString()}\n" else "") + "जवाब: ${it.answer.take(1500)}"
    }

    // ------------------------------------------------------------ फ़ोन में सेव
    private fun file(c: Context) = File(c.filesDir, FILE)
    @Synchronized fun all(c: Context): List<Item> = decode(file(c).takeIf { it.exists() }?.readText())

    @Synchronized fun add(c: Context, task: String, answer: String, files: List<String>) {
        if (task.isBlank() || answer.isBlank()) return
        try {
            val l = (all(c) + Item(System.currentTimeMillis(), task.take(1200), answer.take(4000), files.take(20))).takeLast(MAX)
            file(c).writeText(encode(l))
        } catch (_: Exception) {}
    }

    @Synchronized fun clear(c: Context) { file(c).delete() }
}
