package com.dk.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * नवंबर 2026 (मालिक: "Claude / ChatGPT से कुछ सीखो, उसके काम करने का तरीका देखो, वह तरीका अपने अंदर जोड़ो ताकि तुम भी
 * उसके जैसे काम कर सको - और सीखते रहो हमेशा"):
 * DK ने दूसरे AI से जो तरीके / सबक सीखे, वे यहाँ - सिर्फ़ इसी फ़ोन में, हमेशा (अपने-आप कभी नहीं मिटते; मालिक "भूल जाओ" कहे तभी).
 * हर prompt में ([Memory.factsBlock]) सबसे नए तरीके जुड़ते हैं -> DK लाइव, DK चैट, DK कंप्यूटर सब इन्हें काम में लेते हैं.
 * हर सबक लोकल याददाश्त में भी ([LocalMemory.keepPoint]) - "तुमने Claude से क्या सीखा था" पूछने पर मिलता है.
 */
object LearnedWays {
    data class Way(val text: String, val from: String, val topic: String, val at: Long)

    private const val FILE = "dk_learned.json"
    private val lock = Any()

    // ------------------------------------------------------------------ JVM test वाला हिस्सा
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    /** पुराने + नए (वही बात दोबारा नहीं). कुछ भी नहीं हटता */
    fun merge(old: List<Way>, new: List<Way>): List<Way> {
        val seen = old.map { norm(it.text) }.toMutableSet()
        val out = old.toMutableList()
        for (w in new) {
            val t = w.text.trim()
            if (t.length < 4 || Sensitive.isSensitive(t)) continue
            if (seen.add(norm(t))) out += w.copy(text = t.take(300))
        }
        return out
    }

    /** prompt के लिए - सबसे नए, [maxChars] तक */
    fun block(all: List<Way>, maxChars: Int = 2200): String {
        if (all.isEmpty()) return ""
        val picked = ArrayList<String>()
        var n = 0
        for (w in all.asReversed()) {
            val line = "- ${w.text}" + (if (w.from.isNotBlank()) " (${w.from} से)" else "")
            if (n + line.length > maxChars) break
            picked += line; n += line.length + 1
        }
        return "\nWAYS OF WORKING DK LEARNED FROM OTHER AIs (apply them yourself when they fit the task; never say you cannot do what you learned):\n" +
            picked.asReversed().joinToString("\n")
    }

    fun toJson(list: List<Way>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("t", it.text).put("f", it.from).put("p", it.topic).put("a", it.at)) }
    }.toString()

    fun fromJson(s: String?): List<Way> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Way(it.optString("t"), it.optString("f"), it.optString("p"), it.optLong("a")) }?.takeIf { it.text.isNotBlank() } }
    } catch (_: Exception) { emptyList() }

    // ------------------------------------------------------------------ फ़ोन पर
    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE)

    fun all(c: Context): List<Way> = synchronized(lock) { try { fromJson(file(c).takeIf { it.exists() }?.readText()) } catch (_: Exception) { emptyList() } }

    /** नए सबक जोड़ो - कितने सच में नए थे */
    fun add(c: Context, lessons: List<String>, from: String, topic: String): Int = synchronized(lock) {
        val old = all(c)
        val now = System.currentTimeMillis()
        val merged = merge(old, lessons.map { Way(it, from, topic.take(120), now) })
        val added = merged.size - old.size
        if (added > 0) {
            try { file(c).writeText(toJson(merged)) } catch (e: Exception) { DKLog.d("सीखा हुआ सेव नहीं हुआ: ${e.message}"); return 0 }
            merged.takeLast(added).forEach { LocalMemory.keepPoint(c, "DK ने $from से सीखा: ${it.text}") }
        }
        added
    }

    /** "X भूल जाओ" - जिन सबक में ये सारे शब्द हों */
    fun forget(c: Context, about: String): Int = synchronized(lock) {
        val words = norm(about).split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return 0
        val old = all(c)
        val keep = old.filterNot { w -> val n = norm(w.text + " " + w.from); words.all { n.contains(it) } }
        if (keep.size != old.size) try { file(c).writeText(toJson(keep)) } catch (_: Exception) { return 0 }
        old.size - keep.size
    }

    /** नवंबर 2026: Drive बैकअप - फ़ाइल जैसी है वैसी / रिस्टोर पर जोड़ी हुई पूरी सूची */
    fun rawJson(c: Context): String = synchronized(lock) { toJson(all(c)) }
    fun replaceAll(c: Context, json: String) = synchronized(lock) {
        try { file(c).writeText(toJson(fromJson(json))) } catch (e: Exception) { DKLog.d("सीखा हुआ रिस्टोर नहीं हुआ: ${e.message}") }
    }

    fun promptBlock(c: Context): String = try { block(all(c)) } catch (_: Exception) { "" }
}
