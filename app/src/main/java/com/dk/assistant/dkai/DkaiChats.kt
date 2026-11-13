package com.dk.assistant.dkai

import com.dk.assistant.DKState
import com.dk.assistant.GroundCite
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 13 नवंबर 2026 (मालिक): पुरानी चैट की सूची (Claude ऐप के "Recents" जैसी).
 * हर चैट अपनी फ़ाइल में: filesDir/dkai_chats/chat_<N>.json - ऐप बंद/खुलने पर भी रहती है.
 * अभी वाली चैट की बातचीत DKState (dk_chat.json) में ही चलती है; चैट बदलते / "+ New" दबाते समय यहाँ सेव होती है.
 */
object DkaiChats {
    const val DIR = "dkai_chats"
    private const val MAX_MSGS = 200

    data class Entry(val no: Int, val title: String, val time: Long, val count: Int)

    fun dir(c: android.content.Context): File = File(c.filesDir, DIR)
    fun file(dir: File, no: Int) = File(dir, "chat_$no.json")

    // ------------------------------------------------------------------ JSON (Memory.saveChat वाला ही ढाँचा)
    fun toJson(no: Int, msgs: List<DKState.Msg>): JSONObject {
        val a = JSONArray()
        msgs.takeLast(MAX_MSGS).forEach { m ->
            val o = JSONObject().put("r", m.role).put("t", m.text).put("w", m.time)
            if (m.src.isNotEmpty()) o.put("s", JSONArray().apply { m.src.forEach { put(JSONArray().put(it.first).put(it.second)) } })
            m.g?.let { o.put("g", GroundCite.toJson(it)) }
            a.put(o)
        }
        return JSONObject().put("n", no).put("m", a)
    }

    fun fromJson(o: JSONObject): List<DKState.Msg> {
        val a = o.optJSONArray("m") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { m ->
                val sa = m.optJSONArray("s")
                val src = (0 until (sa?.length() ?: 0)).mapNotNull { k -> sa?.optJSONArray(k)?.let { it.optString(0) to it.optString(1) } }
                DKState.Msg(m.optString("r"), m.optString("t"), m.optLong("w"), src, GroundCite.fromJson(m.optJSONObject("g")))
            }
        }
    }

    // ------------------------------------------------------------------ फ़ाइलें
    /** चैट सेव (खाली = कुछ नहीं लिखते; पहले से फ़ाइल हो तो वैसी ही रहती है) */
    fun save(dir: File, no: Int, msgs: List<DKState.Msg>) {
        if (msgs.isEmpty()) return
        try {
            dir.mkdirs()
            val f = file(dir, no); val tmp = File(dir, "chat_$no.json.tmp")
            tmp.writeText(toJson(no, msgs).toString())
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {}
    }

    fun load(dir: File, no: Int): List<DKState.Msg> = try {
        val f = file(dir, no)
        if (f.exists()) fromJson(JSONObject(f.readText())) else emptyList()
    } catch (_: Exception) { emptyList() }

    /** सेव की हुई चैट के नंबर */
    fun saved(dir: File): List<Int> = (dir.listFiles() ?: emptyArray())
        .mapNotNull { Regex("chat_(\\d+)\\.json").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }

    /** अगली नई चैट का नंबर: अब तक की सबसे बड़ी + 1 (पुरानी चैट खोलकर "+ New" दबाने पर भी नंबर नहीं टकराता) */
    fun nextNo(dir: File, cur: Int): Int = (saved(dir) + cur).maxOf { it }.coerceAtLeast(0) + 1

    // ------------------------------------------------------------------ सूची
    /** चैट का नाम = आपकी पहली बात की पहली लाइन (नहीं तो पहला संदेश) */
    fun title(msgs: List<DKState.Msg>): String {
        val first = (msgs.firstOrNull { it.role == "u" && it.text.isNotBlank() } ?: msgs.firstOrNull { it.text.isNotBlank() })?.text ?: return ""
        val line = first.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return if (line.length > 70) line.take(69).trimEnd() + "…" else line
    }

    fun entry(no: Int, msgs: List<DKState.Msg>, fallbackTime: Long = 0L) =
        Entry(no, title(msgs), msgs.lastOrNull()?.time ?: fallbackTime, msgs.size)

    /** सारी चैट - सबसे नई (बड़ा नंबर) सबसे ऊपर; अभी वाली चैट DKState से (सबसे ताज़ा) */
    fun entries(dir: File, cur: Int, curMsgs: List<DKState.Msg>, now: Long = System.currentTimeMillis()): List<Entry> {
        val out = HashMap<Int, Entry>()
        for (n in saved(dir)) if (n != cur) load(dir, n).takeIf { it.isNotEmpty() }?.let { out[n] = entry(n, it) }
        out[cur] = entry(cur, curMsgs, now)
        return out.values.sortedByDescending { it.no }
    }

    /** "आज, 4:05 PM" / "कल, 9:10 AM" / "8 Nov, 6:30 PM" / "2 Jan 2025, 1:00 PM" */
    fun whenText(t: Long, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): String {
        if (t <= 0L) return ""
        val cal = { x: Long -> Calendar.getInstance(tz).apply { timeInMillis = x } }
        val a = cal(t); val b = cal(now)
        fun fmt(p: String) = SimpleDateFormat(p, Locale.ENGLISH).apply { timeZone = tz }.format(Date(t))
        val sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
        val dayDiff = run {
            val x = cal(now); x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0)
            if (t >= x.timeInMillis) 0 else if (t >= x.timeInMillis - 86_400_000L) 1 else 2
        }
        return when {
            dayDiff == 0 -> "आज, " + fmt("h:mm a")
            dayDiff == 1 -> "कल, " + fmt("h:mm a")
            sameYear -> fmt("d MMM, h:mm a")
            else -> fmt("d MMM yyyy, h:mm a")
        }
    }
}
