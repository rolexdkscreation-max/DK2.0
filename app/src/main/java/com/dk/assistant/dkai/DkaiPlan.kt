package com.dk.assistant.dkai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 13 नवंबर 2026 (मालिक - Pipeline अपग्रेड): Pipeline का pure logic (Android View नहीं -> JVM test).
 *
 *  1. Blueprint: बड़ा लक्ष्य -> पहले छोटे, क्रम वाले कदम (कोड नहीं) - स्क्रीन पर पूरा प्लान.
 *  2. क्रम से काम: एक कदम पूरा + सेव + जाँच (syntax) हो तभी अगला; पिछले कदमों का सार + बदली फ़ाइलें अगले कदम को.
 *  3. हर कदम की स्थिति: Pending / In Progress / Completed / Failed / Skipped.
 *  4. आख़िर में पूरी जाँच: फ़ाइलें आपस में जुड़ीं (src/href/import मौजूद), कोष्ठक/टैग बराबर, JSON सही.
 */
object DkaiPlan {

    enum class St(val label: String) { PENDING("Pending"), RUNNING("In Progress"), DONE("Completed"), FAILED("Failed"), SKIPPED("Skipped") }

    data class Step(val title: String, val goal: String, val status: St = St.PENDING, val note: String = "",
                    val files: List<String> = emptyList(), val summary: String = "")

    const val MAX_STEPS = 8

    const val PLAN_SYSTEM = "You are the DKAI Pipeline planner. Do NOT write any code. " +
        "Break the user's goal into 3 to 8 small, logical, ordered steps for building or changing the given project. " +
        "A typical order is: 1) file structure, 2) UI layout, 3) functions and logic, 4) error checking and polish - adapt it to the goal. " +
        "Each step must be small enough to finish in one go and must say which files it creates or edits. " +
        "Reply with ONLY JSON, no markdown: {\"steps\":[{\"title\":\"short title\",\"goal\":\"exactly what to do in this step and which files\"}]}"

    /** AI का जवाब -> कदम (JSON; न हो तो \"1. शीर्षक - काम\" वाली सूची) */
    fun parsePlan(raw: String?): List<Step> {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return emptyList()
        val arr: JSONArray? = try {
            val a = t.indexOf('{'); val b = t.lastIndexOf('}')
            if (a >= 0 && b > a) JSONObject(t.substring(a, b + 1)).optJSONArray("steps") else null
        } catch (_: Exception) { null } ?: try {
            val a = t.indexOf('['); val b = t.lastIndexOf(']')
            if (a >= 0 && b > a) JSONArray(t.substring(a, b + 1)) else null
        } catch (_: Exception) { null }
        if (arr != null) {
            val out = ArrayList<Step>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                val title = (o?.optString("title") ?: arr.optString(i)).trim()
                val goal = (o?.optString("goal")?.ifBlank { o.optString("description") } ?: "").trim()
                if (title.isNotEmpty() || goal.isNotEmpty()) out.add(Step(title.ifEmpty { goal.take(60) }, goal.ifEmpty { title }))
            }
            if (out.isNotEmpty()) return out.take(MAX_STEPS)
        }
        val re = Regex("""^\s*(?:step\s*)?(\d{1,2})\s*[.):\-]\s*(.+)$""", RegexOption.IGNORE_CASE)
        val out = t.lines().mapNotNull { re.find(it)?.groupValues?.get(2)?.trim()?.trim('*')?.trim() }.filter { it.isNotEmpty() }.map { line ->
            val parts = line.split(Regex("""\s+[-–—:]\s+|:\s+"""), limit = 2)
            Step(parts[0].trim().trim('*').take(80), (parts.getOrNull(1) ?: parts[0]).trim())
        }
        return out.take(MAX_STEPS)
    }

    /** AI प्लान न दे पाए = मालिक का बताया ढाँचा */
    fun defaultPlan(goal: String): List<Step> = listOf(
        Step("File structure", "Create or update the folders and files needed for: $goal"),
        Step("UI layout", "Build the screen layout and styles (HTML/CSS) for: $goal"),
        Step("Functions & logic", "Write the functions and logic (JavaScript / code) that make it work: $goal"),
        Step("Error checking", "Review every file of the project, fix syntax errors, broken links between files and logic bugs."),
    )

    fun planPrompt(goal: String, files: List<String>): String = "GOAL: ${goal.trim()}\n\nPROJECT FILES NOW:\n" +
        (if (files.isEmpty()) "(empty project)" else files.take(200).joinToString("\n"))

    /** एक कदम का निर्देश: पूरा प्लान + अब तक के कदमों का सार + मौजूदा फ़ाइलें (यानी पिछले कदम का कोड) */
    fun stepSystem(): String = DkaiKit.COPILOT_SYSTEM +
        "\nYou are executing ONE step of a larger plan. Do ONLY the current step (do not jump ahead), but keep everything consistent " +
        "with the files written by earlier steps (same names, ids, functions). If this step needs no file change, reply with a one-line summary starting with NO CHANGE."

    fun stepPrompt(goal: String, steps: List<Step>, i: Int, notes: List<String>, projectCtx: String, problems: List<String> = emptyList()): String = buildString {
        append("OVERALL GOAL: ").append(goal.trim()).append("\n\nPLAN:\n")
        steps.forEachIndexed { k, s -> append("${k + 1}. ${s.title} - ${s.goal}").append(if (k == i) "   <== CURRENT STEP" else if (k < i) "   [${s.status.label}]" else "").append('\n') }
        val done = steps.take(i).filter { it.status == St.DONE }
        if (done.isNotEmpty()) {
            append("\nWHAT EARLIER STEPS DID:\n")
            done.forEach { s -> append("- ${s.title}: ${s.summary.ifBlank { "done" }}").append(if (s.files.isNotEmpty()) " (files: ${s.files.joinToString(", ")})" else "").append('\n') }
        }
        if (notes.isNotEmpty()) { append("\nUSER INSTRUCTIONS (follow them):\n"); notes.forEach { append("- ").append(it.trim()).append('\n') } }
        if (problems.isNotEmpty()) { append("\nYOUR LAST ATTEMPT AT THIS STEP HAD THESE ERRORS - FIX THEM:\n"); problems.take(20).forEach { append("- ").append(it).append('\n') } }
        append("\nCURRENT PROJECT FILES:\n").append(projectCtx.ifBlank { "(project is empty)\n" })
        append("\nNOW DO STEP ${i + 1}: ${steps[i].title} - ${steps[i].goal}")
    }

    /** बचे कदम उपयोगकर्ता के नए निर्देश से दोबारा (पूरे हो चुके वही रहते हैं) */
    fun replanPrompt(goal: String, steps: List<Step>, instruction: String): String = buildString {
        append("GOAL: ").append(goal.trim()).append("\n\nCURRENT PLAN:\n")
        steps.forEachIndexed { k, s -> append("${k + 1}. [${s.status.label}] ${s.title} - ${s.goal}\n") }
        append("\nUSER WANTS THIS CHANGE TO THE PLAN: ").append(instruction.trim())
        append("\n\nReturn ONLY the steps that are still to be done (not the Completed ones), as JSON {\"steps\":[...]}")
    }

    /** पूरे हो चुके कदम + नए बचे कदम */
    fun merge(steps: List<Step>, fresh: List<Step>): List<Step> =
        (steps.filter { it.status == St.DONE || it.status == St.SKIPPED } + fresh.map { it.copy(status = St.PENDING) }).take(MAX_STEPS + 4)

    /** अगला चलने वाला कदम (Pending / Failed) - नहीं = -1 */
    fun nextIndex(steps: List<Step>): Int = steps.indexOfFirst { it.status == St.PENDING || it.status == St.FAILED || it.status == St.RUNNING }

    fun progress(steps: List<Step>): String {
        val d = steps.count { it.status == St.DONE || it.status == St.SKIPPED }
        return "$d/${steps.size}"
    }

    // ------------------------------------------------------------------ जाँच (AI के बिना)
    /** एक फ़ाइल में syntax की साफ़ दिखने वाली गलती - null = ठीक */
    fun syntax(path: String, text: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "json" -> try { val t = text.trim(); if (t.startsWith("[")) JSONArray(t) else JSONObject(t); null } catch (e: Exception) { "JSON सही नहीं: ${e.message?.take(80)}" }
            "js", "mjs", "cjs", "ts", "jsx", "tsx", "kt", "kts", "java", "c", "cpp", "h", "cs", "go", "rs", "swift", "dart", "php" -> brackets(text, true)
            "css", "scss", "less" -> brackets(text, false)
            "html", "htm" -> html(text)
            else -> null
        }
    }

    /** कोष्ठक बराबर? (string / comment / regex के अंदर वाले नहीं गिनते). [code] = () [] भी, नहीं तो सिर्फ़ {} */
    fun brackets(text: String, code: Boolean): String? {
        val stack = ArrayList<Pair<Char, Int>>()
        var i = 0; var line = 1; var lastSig = '\u0000'
        val n = text.length
        fun closeOf(c: Char) = when (c) { '(' -> ')'; '[' -> ']'; else -> '}' }
        while (i < n) {
            val ch = text[i]
            if (ch == '\n') { line++; i++; continue }
            if (ch == '/' && i + 1 < n && text[i + 1] == '/' && code) { while (i < n && text[i] != '\n') i++; continue }
            if (ch == '/' && i + 1 < n && text[i + 1] == '*') { i += 2; while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) { if (text[i] == '\n') line++; i++ }; i += 2; continue }
            if (ch == '"' || ch == '\'' || ch == '`') {
                val q = ch; i++
                while (i < n && text[i] != q) { if (text[i] == '\\') i++ else if (text[i] == '\n') { line++; if (q != '`') break }; i++ }
                i++; lastSig = 'a'; continue
            }
            if (code && ch == '/' && (lastSig == '\u0000' || lastSig in "(,=:[!&|?{};+-*%<>~^")) {
                // regex literal
                var j = i + 1; var cls = false; var ok = false
                while (j < n && text[j] != '\n') {
                    val c = text[j]
                    if (c == '\\') { j += 2; continue }
                    if (c == '[') cls = true else if (c == ']') cls = false else if (c == '/' && !cls) { ok = true; break }
                    j++
                }
                if (ok) { i = j + 1; lastSig = 'a'; continue }
            }
            val open = if (code) "([{" else "{"; val close = if (code) ")]}" else "}"
            if (ch in open) stack.add(ch to line)
            else if (ch in close) {
                val top = stack.lastOrNull() ?: return "लाइन $line पर फ़ालतू '$ch'"
                if (closeOf(top.first) != ch) return "लाइन $line पर '$ch' गलत - लाइन ${top.second} का '${top.first}' बंद नहीं हुआ"
                stack.removeAt(stack.size - 1)
            }
            if (!ch.isWhitespace()) lastSig = ch
            i++
        }
        val left = stack.lastOrNull() ?: return null
        return "लाइन ${left.second} का '${left.first}' कभी बंद नहीं हुआ"
    }

    private val PAIRED = listOf("html", "head", "body", "div", "section", "main", "header", "footer", "nav", "ul", "ol", "table", "form", "script", "style", "button", "select", "textarea")

    /** HTML: ज़रूरी टैग खुले-बंद बराबर + <script>/<style> के अंदर का कोड */
    fun html(text: String): String? {
        val noComments = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val scripts = Regex("<script\\b[^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(noComments).toList()
        val body = noComments.replace(Regex("(<script\\b[^>]*>).*?(</script>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "$1$2")
            .replace(Regex("(<style\\b[^>]*>).*?(</style>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "$1$2")
        for (t in PAIRED) {
            val o = Regex("<$t(\\s[^>]*)?>", RegexOption.IGNORE_CASE).findAll(body).count()
            val c = Regex("</$t\\s*>", RegexOption.IGNORE_CASE).findAll(body).count()
            if (o != c) return "<$t> $o बार खुला पर $c बार बंद"
        }
        for (s in scripts) brackets(s.groupValues[1], true)?.let { return "<script> में: $it" }
        return null
    }

    /** फ़ाइल किन लोकल फ़ाइलों को बुलाती है (http/data/# नहीं) */
    fun refs(path: String, text: String): List<String> {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val raw = when (ext) {
            "html", "htm" -> Regex("""(?:src|href)\s*=\s*["']([^"'#?]+)""", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1] }.toList()
            "js", "mjs", "jsx", "ts", "tsx" -> Regex("""(?:import\s[^'"]*?from\s*|import\s*\(\s*|import\s+|require\(\s*)["'](\.{1,2}/[^"']+)["']""").findAll(text).map { it.groupValues[1] }.toList()
            "css" -> Regex("""url\(\s*["']?([^"')#?]+)""").findAll(text).map { it.groupValues[1] }.toList() +
                Regex("""@import\s+["']([^"']+)["']""").findAll(text).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
        return raw.map { it.trim() }.filter { r ->
            r.isNotEmpty() && !r.contains("://") && !r.startsWith("//") && !r.startsWith("data:") && !r.startsWith("mailto:") &&
                !r.startsWith("tel:") && !r.startsWith("javascript:") && !r.startsWith("{") && !r.contains("\${") && !r.startsWith("/")
        }.distinct()
    }

    /** \"js/app.js\" + \"../css/a.css\" -> \"css/a.css\" (बाहर निकले = null) */
    fun resolve(from: String, ref: String): String? {
        val base = from.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (p in ref.split('/')) when (p) { "", "." -> {}; ".." -> { if (base.isEmpty()) return null; base.removeAt(base.size - 1) }; else -> base.add(p) }
        return base.joinToString("/")
    }

    data class Problem(val path: String, val msg: String) { override fun toString() = "$path: $msg" }

    /**
     * आख़िरी जाँच: [files] = path -> text (पूरा प्रोजेक्ट), [check] = किन फ़ाइलों की syntax देखनी (null = सब).
     * JS import वाली फ़ाइल बिना .js के भी चलती है (bundler) - दोनों देखते हैं.
     */
    fun integrate(files: Map<String, String>, check: Set<String>? = null, exists: (String) -> Boolean = { it in files }): List<Problem> {
        val out = ArrayList<Problem>()
        for ((p, t) in files) {
            if (check != null && p !in check) continue
            syntax(p, t)?.let { out.add(Problem(p, it)) }
            for (r in refs(p, t)) {
                val full = resolve(p, r) ?: continue
                val ok = exists(full) || exists("$full.js") || exists("$full.ts") || exists("$full/index.js") || exists("$full/index.html")
                if (!ok) out.add(Problem(p, "'$r' फ़ाइल नहीं मिली"))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ सेव (ऐप बंद-खोलने पर भी प्लान दिखे)
    fun toJson(goal: String, steps: List<Step>, notes: List<String>): String = JSONObject().put("goal", goal)
        .put("notes", JSONArray().apply { notes.forEach { put(it) } })
        .put("steps", JSONArray().apply {
            steps.forEach { s -> put(JSONObject().put("title", s.title).put("goal", s.goal).put("status", s.status.name).put("note", s.note)
                .put("summary", s.summary).put("files", JSONArray().apply { s.files.forEach { put(it) } })) }
        }).toString()

    data class Saved(val goal: String, val steps: List<Step>, val notes: List<String>)

    fun fromJson(raw: String?): Saved? = try {
        val o = JSONObject(raw ?: "")
        val a = o.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until a.length()).mapNotNull { i ->
            val s = a.optJSONObject(i) ?: return@mapNotNull null
            val st = try { St.valueOf(s.optString("status")) } catch (_: Exception) { St.PENDING }
            Step(s.optString("title"), s.optString("goal"), if (st == St.RUNNING) St.PENDING else st, s.optString("note"),
                s.optJSONArray("files")?.let { f -> (0 until f.length()).map { f.optString(it) } } ?: emptyList(), s.optString("summary"))
        }
        val n = o.optJSONArray("notes")
        Saved(o.optString("goal"), steps, n?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()).takeIf { it.goal.isNotBlank() }
    } catch (_: Exception) { null }
}
