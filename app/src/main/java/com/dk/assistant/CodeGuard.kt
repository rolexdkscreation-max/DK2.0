package com.dk.assistant

/**
 * v4.4 (मालिक): कोडिंग एजेंट ने "Listen only to my voice" हटाते समय पूरी Prefs.kt / SettingsActivity.kt / ui_en.tsv
 * delete कर दीं, फिर डिबगर 7 बार Prefs.kt में टुकड़े जोड़ता रहा (उसे दिखा ही नहीं कि फ़ाइलें इसी काम ने हटाई थीं).
 *
 * यह pure जाँच (Android import नहीं -> JVM test):
 *  1. [users]  - फ़ाइल हटाने से पहले: उसके नाम (class/fun/val) बाकी रिपो में कहाँ-कहाँ इस्तेमाल हैं (इस्तेमाल हो = हटाना मना)
 *  2. [shrunk] - फ़ाइल आधी से ज़्यादा छोटी हो गई (बड़ा हिस्सा गायब)
 *  3. [footprint] - base branch (main) से तुलना: इस काम ने कौन-सी फ़ाइल हटाई/छोटी की - डिबगर को सबसे ऊपर
 *  4. [damage] - build log के "Unresolved reference" उन्हीं हटाई/छोटी फ़ाइलों के नाम हैं = असली जड़ = फ़ाइल main से वापस
 *  5. [flips] - बदली हुई शर्त उल्टी तो नहीं हुई (`!x` -> `x`, `==` -> `!=`, `<` -> `>=`)
 */
object CodeGuard {
    /** इतनी लाइन से छोटी फ़ाइल का घटना "बड़ा नुकसान" नहीं */
    const val SHRINK_MIN_LINES = 60
    /** इतने % लाइन गायब = बड़ा नुकसान */
    const val SHRINK_RATIO = 0.5

    private val CODE_EXT = setOf("kt", "kts", "java")
    private val DECL = Regex("""\b(?:fun|val|var|class|object|interface|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w]*\.)?([A-Za-z_]\w*)""")
    private val STOP = setOf("it", "this", "value", "result", "name", "text", "data", "list", "map", "item", "index", "context", "view",
        "main", "Companion", "invoke", "toString", "equals", "hashCode", "onCreate", "onResume", "onPause", "onDestroy", "run", "get", "set")

    fun ext(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    fun base(path: String) = path.substringAfterLast('/')
    fun stem(path: String) = base(path).substringBeforeLast('.')
    fun lines(t: String?) = t?.lines()?.count { it.isNotBlank() } ?: 0

    /** फ़ाइल जो नाम दूसरों को देती है: Kotlin/Java में class/object/fun/val (सदस्य भी), बाकी में फ़ाइल का नाम */
    fun decls(path: String, content: String): Set<String> {
        val out = LinkedHashSet<String>()
        if (ext(path) in CODE_EXT) {
            out.add(stem(path))
            DECL.findAll(content).forEach { m -> m.groupValues[1].takeIf { it.length >= 3 && it !in STOP }?.let { out.add(it) } }
        }
        return out
    }

    /** दूसरी फ़ाइलें कैसे इसे बुलाती हैं: code = class/object का नाम (Prefs.x); बाकी = फ़ाइल का नाम ("ui_en.tsv", "@layout/x") */
    private fun handles(path: String, content: String): List<Regex> {
        val e = ext(path)
        val r = ArrayList<Regex>()
        if (e in CODE_EXT) {
            val types = LinkedHashSet<String>().apply {
                add(stem(path))
                Regex("""\b(?:class|object|interface|enum class|annotation class)\s+([A-Z]\w*)""").findAll(content).forEach { add(it.groupValues[1]) }
                // सबसे ऊपर (बिना indent) की fun/val भी (top-level - import से इस्तेमाल)
                Regex("""(?m)^(?:public |internal )?(?:fun|val|const val)\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)""").findAll(content).forEach { m ->
                    m.groupValues[1].takeIf { it.length >= 4 && it !in STOP }?.let { add(it) } }
            }
            types.forEach { r.add(Regex("""\b${Regex.escape(it)}\b""")) }
        } else {
            r.add(Regex(Regex.escape(base(path))))
            if (path.contains("/res/")) r.add(Regex("""(?:R\.\w+\.|@\w+/)${Regex.escape(stem(path))}\b"""))
            else if (stem(path).length >= 4) r.add(Regex("""["'/]${Regex.escape(stem(path))}["'.]"""))
        }
        return r
    }

    /**
     * यह फ़ाइल हटाई तो कौन-सी दूसरी फ़ाइलें टूटेंगी (उनमें इसका नाम इस्तेमाल है).
     * @param ws पूरा रिपो (path -> content); [gone] = इसी काम में साथ में हट रहीं (उन्हें गिनना नहीं)
     */
    fun users(path: String, content: String, ws: Map<String, String>, gone: Set<String> = emptySet(), max: Int = 12): List<String> {
        val hs = handles(path, content)
        if (hs.isEmpty()) return emptyList()
        return ws.asSequence().filter { (p, _) -> p != path && p !in gone && !p.startsWith(".git/") }
            .filter { (_, c) -> hs.any { it.containsMatchIn(c) } }.map { it.key }.take(max).toList()
    }

    /** फ़ाइल का बड़ा हिस्सा गायब (हटाने को नहीं कहा था) */
    fun shrunk(before: String?, after: String?): Boolean {
        val b = lines(before); if (b < SHRINK_MIN_LINES) return false
        val a = lines(after)
        return a < b * (1 - SHRINK_RATIO)
    }

    /** हटाने/बड़ा घटाने से पहले की रोक का संदेश (null = ठीक) */
    fun deleteProblem(path: String, content: String?, ws: Map<String, String>, gone: Set<String> = emptySet()): String? {
        if (content == null) return null
        val u = users(path, content, ws, gone)
        if (u.isEmpty()) return null
        return "$path is still used by ${u.size}${if (u.size >= 12) "+" else ""} file(s): ${u.take(6).joinToString()}" +
            " - deleting it breaks the build. Remove ONLY the code the task asks for (small FIND/REPLACE edits), keep the file."
    }

    // ------------------------------------------------------------------ base branch से तुलना
    /** एक फ़ाइल का बदलाव (main से तुलना) */
    data class Change(val path: String, val status: String, val additions: Int, val deletions: Int, val baseLines: Int = 0) {
        val removed get() = status == "removed"
        /** बड़ा नुकसान: फ़ाइल हटी, या उसकी आधी से ज़्यादा लाइनें गईं */
        val damaged get() = removed || (baseLines >= SHRINK_MIN_LINES && deletions - additions > baseLines * SHRINK_RATIO)
    }

    /** डिबगर/रिव्यूअर के लिए: इस काम ने (main से) क्या-क्या बदला - हटाई/छोटी फ़ाइलें सबसे ऊपर, कभी कटे नहीं */
    fun footprint(changes: List<Change>, base: String): String {
        if (changes.isEmpty()) return ""
        val bad = changes.filter { it.damaged }
        return buildString {
            append("WHOLE-TASK FOOTPRINT vs base branch \"$base\" (every file this task changed, including earlier pushes and earlier runs):\n")
            if (bad.isNotEmpty()) {
                append("!! FILES THIS TASK DELETED OR MOSTLY EMPTIED (check these FIRST - a build error about missing names usually comes from here):\n")
                bad.forEach { append("!! ${it.path}: ").append(if (it.removed) "DELETED (${it.baseLines} lines on $base)" else "-${it.deletions} +${it.additions} lines (was ${it.baseLines})").append('\n') }
            }
            changes.filterNot { it.damaged }.take(200).forEach { append("- ${it.path}: ${it.status} -${it.deletions} +${it.additions}\n") }
        }
    }

    // ------------------------------------------------------------------ build log -> असली जड़
    private val UNRESOLVED = listOf(
        Regex("""Unresolved reference[:]?\s*'([A-Za-z_]\w*)'"""),
        Regex("""Unresolved reference:\s*([A-Za-z_]\w*)"""),
        Regex("""cannot find symbol\s*\n?\s*symbol:\s*(?:class|method|variable)\s+([A-Za-z_]\w*)"""),
        Regex("""Unresolved reference\.?\s+None of the following candidates.*?'([A-Za-z_]\w*)'"""),
    )

    /** log में जो नाम नहीं मिले */
    fun unresolved(log: String): Set<String> = UNRESOLVED.flatMap { r -> r.findAll(log).map { it.groupValues[1] }.toList() }.toSet()

    /** एक टूटी फ़ाइल: main पर उसका पूरा content + log के कौन-से नाम उसी में थे */
    data class Damage(val path: String, val baseContent: String, val names: List<String>, val removed: Boolean)

    /**
     * build fail: log के "Unresolved reference" वाले नाम उन फ़ाइलों में थे जिन्हें इसी काम ने हटाया/आधा किया = असली जड़.
     * @param baseOf main पर फ़ाइल का content
     */
    fun damage(log: String, changes: List<Change>, baseOf: (String) -> String?): List<Damage> {
        val missing = unresolved(log)
        val out = ArrayList<Damage>()
        for (ch in changes.filter { it.damaged }) {
            val b = baseOf(ch.path) ?: continue
            val names = if (ext(ch.path) in CODE_EXT) decls(ch.path, b).filter { it in missing } else emptyList()
            val mentioned = log.contains(base(ch.path))
            if (names.isNotEmpty() || mentioned) out.add(Damage(ch.path, b, names.take(12), ch.removed))
        }
        return out
    }

    /** डिबगर/कोडर के लिए साफ़ जड़ */
    fun damageNote(d: List<Damage>, base: String): String = if (d.isEmpty()) "" else buildString {
        append("ROOT CAUSE FOUND AUTOMATICALLY: this task ")
        append(d.joinToString("; ") { (if (it.removed) "DELETED " else "mostly emptied ") + it.path + if (it.names.isNotEmpty()) " (which declared ${it.names.take(8).joinToString()})" else "" })
        append(". The build errors come from that. These files were RESTORED exactly from \"$base\". ")
        append("Do NOT re-create pieces of them. If the task still needs a change in them, make ONLY that small change with FIND/REPLACE edits.")
    }

    // ------------------------------------------------------------------ उल्टी शर्त
    private val COND = Regex("""\b(?:if|while|when|takeIf|takeUnless|filter|any|all|none)\b""")

    private fun norm(l: String) = l.trim().replace(Regex("""\s+"""), " ")

    /** शर्त की "उल्टी" शक्ल: ! हटाना/जोड़ना, == <-> !=, < <-> >=, > <-> <=, && <-> || */
    private fun flipped(a: String, b: String): Boolean {
        if (a == b) return false
        fun strip(s: String) = s.replace(" ", "")
        val x = strip(a); val y = strip(b)
        if (x.replace("!", "") == y.replace("!", "") && x.count { it == '!' } != y.count { it == '!' }) return true
        val swaps = listOf("==" to "!=", "<=" to ">", ">=" to "<", "&&" to "||")
        return swaps.any { (p, q) -> (x.replace(p, "§") == y.replace(q, "§") || x.replace(q, "§") == y.replace(p, "§")) && x.contains(Regex("[=<>&|]")) }
    }

    /**
     * हटाई और जोड़ी गई लाइनों में से वे जोड़े जिनमें सिर्फ़ शर्त उल्टी हुई (जैसे `if (!inSeg)` -> `if (inSeg)`).
     * रिव्यूअर को दिखाते हैं: काम में यह उलटना माँगा न हो तो यह bug है.
     */
    fun flips(before: String?, after: String?, max: Int = 20): List<String> {
        if (before == null || after == null) return emptyList()
        val bs = before.lines().map(::norm).toHashSet(); val asSet = after.lines().map(::norm).toHashSet()
        val gone = before.lines().map(::norm).filter { it !in asSet && COND.containsMatchIn(it) }
        val added = after.lines().map(::norm).filter { it !in bs && COND.containsMatchIn(it) }
        val out = ArrayList<String>()
        for (g in gone) for (a in added) if (flipped(g, a)) { out.add("\"$g\"  ->  \"$a\""); if (out.size >= max) return out }
        return out
    }
}
