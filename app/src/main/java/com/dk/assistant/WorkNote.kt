package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * 📒 एजेंट की काम-कॉपी - PR के विवरण (description) में रखी जाती है.
 *
 * एजेंट एक बार कोड पढ़ता है तो यहाँ लिख लेता है: कौन-सी फ़ाइल में क्या है (कौन-सी लाइनें पढ़ीं),
 * क्या काम हो गया, क्या बाकी है, PR/checks/merge की हालत और आगे क्या करना है.
 * अगला राउंड / "आगे करो" / PR डॉक्टर यही पढ़कर आगे बढ़ता है - वही कोड बार-बार शुरू से नहीं पढ़ता.
 * जो फ़ाइल नोट के बाद बदली (sig अलग) उसे "बदली" बताया जाता है - सिर्फ़ वही दोबारा पढ़नी है.
 *
 * PR के बाकी विवरण को कभी नहीं छेड़ता: सिर्फ़ BEGIN…END के बीच का हिस्सा बदलता/जुड़ता है.
 * Pure Kotlin (Android पर निर्भर नहीं) - JUnit से जाँच होती है.
 */
class WorkNote(
    var task: String = "",
    val files: LinkedHashMap<String, FileNote> = linkedMapOf(),
    val done: MutableList<String> = mutableListOf(),
    var todo: List<String> = emptyList(),
    var status: String = "",
    var next: String = ""
) {
    /** एक फ़ाइल का नोट. sig = पढ़ते समय फ़ाइल की पहचान (बदली या नहीं), ranges = पढ़ी गई लाइनें */
    data class FileNote(val sig: String, val lines: Int, val ranges: List<Pair<Int, Int>>, val about: String = "")

    val isEmpty get() = files.isEmpty() && done.isEmpty() && todo.isEmpty() && status.isBlank()

    /** फ़ाइल (या उसका हिस्सा from..to) पढ़ी गई. फ़ाइल नोट के बाद बदल गई थी = पुरानी पढ़ाई बेकार, नए सिरे से */
    fun read(path: String, content: String?, from: Int = 1, to: Int = Int.MAX_VALUE) {
        if (path.isBlank() || content == null) return
        val n = content.lines().size
        val s = sig(content)
        val old = files[path]
        val a = from.coerceIn(1, maxOf(1, n)); val b = to.coerceIn(a, maxOf(a, n))
        val ranges = if (old != null && old.sig == s) merge(old.ranges + (a to b)) else listOf(a to b)
        val about = if (old != null && old.sig == s) old.about else old?.about?.let { if (it.isBlank()) "" else "(पुराना नोट, फ़ाइल बदली) $it" }.orEmpty()
        files.remove(path)
        files[path] = FileNote(s, n, ranges, about.take(MAX_ABOUT))
        while (files.size > MAX_FILES) files.remove(files.keys.first())
    }

    /** AI ने फ़ाइलों के बारे में जो एक-लाइन नोट दिए ("notes":{"path":"…"}) */
    fun about(notes: Map<String, String>) {
        notes.forEach { (p, t) ->
            val f = files[p] ?: return@forEach      // जो पढ़ी ही नहीं, उसका नोट नहीं (अंदाज़ा नहीं)
            val txt = t.trim().replace(Regex("\\s+"), " ").take(MAX_ABOUT)
            if (txt.isNotBlank()) files[p] = f.copy(about = txt)
        }
    }

    fun plan(steps: List<String>) { todo = steps.map { it.take(MAX_LINE) }.distinct().take(MAX_LIST) }

    fun did(what: String) {
        val w = what.trim().take(MAX_LINE)
        if (w.isBlank()) return
        todo = todo.filter { it != w }
        done.remove(w); done.add(w)
        while (done.size > MAX_LIST) done.removeAt(0)
    }

    fun state(status: String, next: String = this.next) { this.status = status.trim().take(MAX_LINE); this.next = next.trim().take(MAX_LINE) }

    /** फ़ाइल नोट के बाद बदली? (current = अभी की content) */
    fun changed(path: String, current: String?): Boolean = files[path]?.let { current == null || it.sig != sig(current) } ?: false

    /**
     * AI (प्लानर/डिबगर) के लिए: पहले से पढ़ी फ़ाइलें + हुआ/बाकी. current = नोट वाली फ़ाइलों की अभी की content.
     * "" = नोट खाली.
     */
    fun forPrompt(current: Map<String, String?>): String {
        if (isEmpty) return ""
        val sb = StringBuilder("WORK NOTE (saved by earlier rounds / agents on this PR - trust it, do NOT re-read whole files you already studied; " +
            "read only the exact line ranges you still need, e.g. {\"read\":[\"path:120-160\"]}, and re-read files marked CHANGED):\n")
        if (task.isNotBlank()) sb.append("TASK SO FAR: ").append(task.take(300)).append('\n')
        if (files.isNotEmpty()) {
            sb.append("FILES ALREADY STUDIED:\n")
            for ((p, f) in files.entries.reversed()) {
                val line = "- $p (${f.lines} lines, read ${rangesText(f.ranges)})" +
                    (if (p in current && changed(p, current[p])) " CHANGED since noted" else "") +
                    (if (f.about.isNotBlank()) ": ${f.about}" else "") + "\n"
                if (sb.length + line.length > MAX_PROMPT) { sb.append("- …\n"); break }
                sb.append(line)
            }
        }
        if (done.isNotEmpty()) sb.append("DONE:\n").append(done.takeLast(15).joinToString("\n") { "- $it" }).append('\n')
        if (todo.isNotEmpty()) sb.append("NOT DONE YET:\n").append(todo.take(15).joinToString("\n") { "- $it" }).append('\n')
        if (status.isNotBlank()) sb.append("STATUS: ").append(status).append('\n')
        if (next.isNotBlank()) sb.append("NEXT: ").append(next).append('\n')
        return sb.toString().take(MAX_PROMPT + 1500)
    }

    fun json(): JSONObject = JSONObject().put("v", 1).put("task", task).put("status", status).put("next", next)
        .put("done", JSONArray(done)).put("todo", JSONArray(todo))
        .put("files", JSONArray().apply {
            files.forEach { (p, f) ->
                put(JSONObject().put("p", p).put("s", f.sig).put("n", f.lines).put("a", f.about)
                    .put("r", JSONArray().apply { f.ranges.forEach { (a, b) -> put(JSONArray().put(a).put(b)) } }))
            }
        })

    /** PR के विवरण में जाने वाला हिस्सा (इंसान के पढ़ने लायक + अंदर छुपा data) */
    fun markdown(): String = buildString {
        append(BEGIN).append('\n')
        append("## 📒 DK एजेंट की काम-कॉपी\n")
        append("अपने-आप बनती है। अगला एजेंट/राउंड पहले यही पढ़ता है, कोड शुरू से दोबारा नहीं पढ़ता।\n\n")
        if (task.isNotBlank()) append("**काम:** ").append(task.take(300).replace('\n', ' ')).append('\n')
        if (status.isNotBlank()) append("**हालत:** ").append(status).append('\n')
        if (next.isNotBlank()) append("**आगे:** ").append(next).append('\n')
        if (done.isNotEmpty()) { append("\n### ✅ हो चुका\n"); done.forEach { append("- ").append(it).append('\n') } }
        if (todo.isNotEmpty()) { append("\n### ⏳ बाकी\n"); todo.forEach { append("- ").append(it).append('\n') } }
        if (files.isNotEmpty()) {
            append("\n### 📂 पढ़ी गई फ़ाइलें\n")
            files.forEach { (p, f) -> append("- `").append(p).append("` (").append(f.lines).append(" लाइन, पढ़ी: ").append(rangesText(f.ranges)).append(")")
                if (f.about.isNotBlank()) append(" — ").append(f.about)
                append('\n') }
        }
        append('\n').append(DATA).append(java.util.Base64.getEncoder().encodeToString(json().toString().toByteArray(Charsets.UTF_8))).append(" -->\n")
        append(END)
    }

    companion object {
        const val BEGIN = "<!-- DK-NOTE:BEGIN -->"
        const val END = "<!-- DK-NOTE:END -->"
        private const val DATA = "<!-- DK-NOTE-DATA:"
        const val MAX_FILES = 60
        const val MAX_LIST = 40
        const val MAX_ABOUT = 240
        const val MAX_LINE = 200
        const val MAX_PROMPT = 6_000

        /** प्लानर/डिबगर के लिए नियम: अंतिम JSON में फ़ाइलों का छोटा नोट भी दें */
        const val PROMPT_RULE = "\nWORK NOTE RULE: in your FINAL JSON also add \"notes\":{\"<path you read>\":\"one line: what in this file matters for the task " +
            "(key functions/ids and their line numbers)\"}. It is saved in the PR, so later rounds do not need to read the same code again."

        fun sig(content: String): String = "${content.length}-" + Integer.toHexString(content.hashCode())

        fun merge(r: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
            val out = mutableListOf<Pair<Int, Int>>()
            for (x in r.sortedBy { it.first }) {
                val l = out.lastOrNull()
                if (l != null && x.first <= l.second + 1) out[out.size - 1] = l.first to maxOf(l.second, x.second) else out.add(x)
            }
            return out.take(12)
        }

        fun rangesText(r: List<Pair<Int, Int>>) = r.joinToString(", ") { (a, b) -> if (a == b) "$a" else "$a-$b" }

        fun parse(o: JSONObject?): WorkNote? {
            o ?: return null
            val n = WorkNote(o.optString("task"), status = o.optString("status"), next = o.optString("next"))
            o.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { n.done.add(it) } }
            n.todo = o.optJSONArray("todo")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
            o.optJSONArray("files")?.let { a ->
                for (i in 0 until a.length()) {
                    val f = a.optJSONObject(i) ?: continue
                    val p = f.optString("p").ifBlank { null } ?: continue
                    val r = f.optJSONArray("r")?.let { ra -> (0 until ra.length()).mapNotNull { j -> ra.optJSONArray(j)?.let { it.optInt(0) to it.optInt(1) } } }.orEmpty()
                    n.files[p] = FileNote(f.optString("s"), f.optInt("n"), r, f.optString("a"))
                }
            }
            return n
        }

        /** PR के विवरण से काम-कॉपी. null = विवरण में नहीं / टूटी */
        fun fromBody(body: String?): WorkNote? {
            val b = body ?: return null
            val i = b.indexOf(DATA); if (i < 0) return null
            val j = b.indexOf("-->", i); if (j < 0) return null
            return try {
                parse(JSONObject(String(java.util.Base64.getDecoder().decode(b.substring(i + DATA.length, j).trim()), Charsets.UTF_8)))
            } catch (_: Exception) { null }
        }

        /** विवरण में काम-कॉपी रखो: पुरानी हो तो सिर्फ़ वही हिस्सा बदलो, न हो तो आख़िर में जोड़ो. बाकी विवरण अछूता */
        fun inBody(body: String?, md: String): String {
            val b = body.orEmpty()
            if (md.isBlank()) return b
            val s = b.indexOf(BEGIN); val e = b.indexOf(END)
            if (s >= 0 && e > s) return b.substring(0, s) + md + b.substring(e + END.length)
            return if (b.isBlank()) md else b.trimEnd() + "\n\n" + md
        }
    }
}
