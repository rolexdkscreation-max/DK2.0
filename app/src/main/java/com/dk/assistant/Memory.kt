package com.dk.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DK ki yaaddasht (phone me save, app band/restart hone par bhi rehti hai):
 *  1. facts   - "याद रखो मेरी बाइक का नंबर MH12 AB 1234 है" jaisi pakki baatein (hamesha yaad)
 *  2. history - pichhli baatcheet ke aakhri hisse (AI ko context ke liye)
 *  3. chat    - chat screen par dikhne wale messages
 */
object Memory {
    private const val MAX_FACTS = 60
    private const val MAX_HISTORY = 30
    private const val MAX_CHAT = 200

    private fun file(c: Context, name: String) = File(c.applicationContext.filesDir, name)

    @Synchronized private fun readArr(c: Context, name: String): JSONArray = try {
        val f = file(c, name)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) { JSONArray() }

    @Synchronized private fun writeArr(c: Context, name: String, a: JSONArray) {
        try { file(c, name).writeText(a.toString()) } catch (e: Exception) { DKLog.d("Memory save: ${e.message}") }
    }

    // ------------------------------------------------------------ pakki baatein (facts)
    fun facts(c: Context): List<String> {
        val a = readArr(c, "dk_facts.json")
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    /** Nayi baat yaad karo. Milti-julti purani baat ho to use badal do. */
    fun addFact(c: Context, fact: String): Boolean {
        val f = fact.trim().trim('.', '।', ' ').take(300)
        if (f.length < 3) return false
        val list = facts(c).toMutableList()
        if (list.any { it.equals(f, true) }) return true
        // same vishay (pehle 3 shabd same) -> purani hatao, nayi rakho (jaise naya number)
        val key = f.lowercase().split(' ').take(3).joinToString(" ")
        if (key.length > 6) list.removeAll { it.lowercase().startsWith(key) }
        list.add(f)
        while (list.size > MAX_FACTS) list.removeAt(0)
        writeArr(c, "dk_facts.json", JSONArray(list))
        return true
    }

    /** "X भूल जाओ" - jisme yeh shabd ho woh baatein hatao. Wapas: kitni hatin. */
    fun forget(c: Context, about: String): Int {
        val words = about.lowercase().split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return 0
        val list = facts(c).toMutableList()
        val before = list.size
        list.removeAll { f -> val l = f.lowercase(); words.all { l.contains(it) } }
        writeArr(c, "dk_facts.json", JSONArray(list))
        return before - list.size
    }

    fun clearFacts(c: Context) = writeArr(c, "dk_facts.json", JSONArray())

    /** AI ke prompt me jodne ke liye. */
    fun factsBlock(c: Context): String {
        val f = facts(c)
        if (f.isEmpty()) return ""
        return "\nTHINGS THE USER ASKED YOU TO REMEMBER (use them when relevant, never say you don't know these):\n" +
            f.joinToString("\n") { "- $it" }
    }

    // ------------------------------------------------------------ baatcheet ka itihaas
    fun loadHistory(c: Context): MutableList<Turn> {
        val a = readArr(c, "dk_history.json")
        val out = mutableListOf<Turn>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Turn(o.optString("r"), o.optString("t")))
        }
        // 12 ghante se purani baatcheet ka context zaroori nahi (facts alag se yaad rehte hain)
        val saved = file(c, "dk_history.json").lastModified()
        if (saved > 0 && System.currentTimeMillis() - saved > 12 * 3600_000L) return mutableListOf()
        return out
    }

    fun saveHistory(c: Context, h: List<Turn>) {
        val a = JSONArray()
        h.takeLast(MAX_HISTORY).forEach { a.put(JSONObject().put("r", it.role).put("t", it.text)) }
        writeArr(c, "dk_history.json", a)
    }

    // ------------------------------------------------------------ chat screen
    fun loadChat(c: Context): List<DKState.Msg> {
        val a = readArr(c, "dk_chat.json")
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { DKState.Msg(it.optString("r"), it.optString("t"), it.optLong("w")) }
        }
    }

    fun saveChat(c: Context, msgs: List<DKState.Msg>) {
        val a = JSONArray()
        msgs.takeLast(MAX_CHAT).forEach { a.put(JSONObject().put("r", it.role).put("t", it.text).put("w", it.time)) }
        writeArr(c, "dk_chat.json", a)
    }

    // ------------------------------------------------------------ bolkar yaad karana / bhulana
    data class Cmd(val kind: String, val text: String)   // kind: remember | forget | forget_all | recall

    private val REMEMBER = Regex("^(?:(?:डीके|dk|दीके)[, ]+)?(?:ये |यह |यह बात |ये बात |एक बात )?(?:याद रखो|याद रखना|याद कर लो|याद कर लेना|remember)(?: कि| की|:|,)?\\s*(.*)$")
    private val REMEMBER_END = Regex("^(.*?)[, ]*(?:ये |यह )?(?:याद रखो|याद रखना|याद कर लो|याद कर लेना)$")

    fun parse(raw: String): Cmd? {
        val t = raw.trim().trim('.', '।', '!', '?').trim()
        val low = t.lowercase()
        if (low.contains("सारी यादें") || low.contains("सब यादें") || low.contains("सब कुछ भूल") || low.contains("सारी बातें भूल"))
            return Cmd("forget_all", "")
        if (low.contains("क्या याद है") || low.contains("क्या-क्या याद") || low.contains("तुम्हें क्या याद") || low.contains("what do you remember"))
            return Cmd("recall", "")
        REMEMBER.find(low)?.let { m ->
            val s = t.substring(t.length - m.groupValues[1].length).trim()
            if (s.length >= 3) return Cmd("remember", s)
        }
        REMEMBER_END.find(low)?.let { m ->
            val s = t.substring(0, m.groupValues[1].length).trim()
            if (s.length >= 3) return Cmd("remember", s)
        }
        Regex("^(.+?)\\s+(?:वाली बात |के बारे में |की बात )?(?:भूल जाओ|भूल जाना|भुला दो)$").find(low)?.let { m ->
            val s = m.groupValues[1].trim()
            if (s.length >= 2 && s != "सब" && s != "सारी बात") return Cmd("forget", s)
        }
        return null
    }
}
