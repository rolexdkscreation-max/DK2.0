package com.dk.assistant

import org.json.JSONObject

/**
 * डिबगर की मदद (11-एजेंट मोड) - Android पर निर्भर नहीं, पूरी तरह unit test होती है.
 *
 *  - पढ़ना: फ़ाइल लाइन नंबर के साथ ("123| code"), बड़ी फ़ाइल का कोई भी हिस्सा ("path:800-1100"),
 *           और कटी हुई बड़ी फ़ाइल के साथ उसकी रूपरेखा (हर class/fun किस लाइन पर) - ताकि सही हिस्सा माँग सके
 *  - देखा हुआ: डिबगर ने कौन-सी फ़ाइल की कौन-सी लाइनें सच में देखीं
 *  - जड़ की जाँच: बताई फ़ाइल रिपो में है? डिबगर ने पढ़ी? बताई लाइन फ़ाइल में है और पढ़े हिस्से में थी?
 */
object SevenDebug {

    /** एक बार में एक हिस्से की ज़्यादा से ज़्यादा लाइनें */
    const val RANGE_MAX_LINES = 600
    /** जड़ की जाँच फ़ेल होने पर डिबगर को कितनी बार वापस भेजें */
    const val VERIFY_ROUNDS = 2

    /** 11-एजेंट मोड में डिबगर के prompt में जुड़ने वाले नियम */
    const val EXTRA = """
HOW TO FIND THE ROOT CAUSE (follow this, do not guess):
1) FIND first: reply ONLY {"search":["word", ...]} (max 6: screen text, button text, setting name, function, variable, error message, log tag). You get every matching path:line in the WHOLE repository. Never pick a file only from its name.
2) TRACE the flow: start where the user meets the bug (screen / button / voice command / setting) and follow the calls (search the called function names) until the line where the behaviour goes wrong.
3) READ before deciding: {"read":["path"]} gives the file WITH LINE NUMBERS ("123| code"). A big file is cut and comes with an OUTLINE (every class/fun with its line). Read any part with {"read":["path:START-END"]} (max 600 lines per part).
4) Your "where" MUST be "path:line" of a line you have READ, and the root cause must quote / describe the code on that line. It is checked automatically; a root cause in a file or line you did not read is sent back.
5) If the DIFF of this task is given and a build/test failed, first check whether the new code caused it.
6) MISSING CODE IS A ROOT CAUSE TOO: often nothing is "broken" - the needed behaviour was simply never written (e.g. a search for the API/feature that would do it says NOT FOUND anywhere). Then reply with "kind":"missing": root_cause = "path:line - X is never done here, so <the user's problem>", where = the READ line where the new code must be connected (e.g. where the microphone / recording / screen / setting starts), fix = what must be added and where.
7) Rounds are limited - the number of rounds left is shown after every result. Do not keep searching: after a few searches/reads, decide. When rounds are over you must answer from what you have read.
FINAL JSON: {"kind":"bug|missing","root_cause":"path:line - what is wrong / missing and WHY","bug":"Hindi: बग क्या था","where":"path:line","fix":"minimal fix in English: which file, what change","files":["paths to change"]}"""

    /** 11-एजेंट मोड में प्लानर के prompt में जुड़ने वाले नियम (बड़ी फ़ाइलें) */
    const val PLANNER_EXTRA = """
BIG FILES: a big file is cut ("TRUNCATED") and comes with an OUTLINE (line: class/fun). If the place you need is not in the part you got, read that part: {"read":["path:START-END"]} (max 600 lines per part; the text comes WITHOUT line numbers so you can copy the anchor exactly).
In a big file the anchor must be text from a part you have READ (not only a search hit) - it is checked, and a step whose anchor you did not read is sent back."""

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /**
     * anchor फ़ाइल की किस लाइन पर है (1 से गिनती). पहले पूरी पहली लाइन, फिर spaces अनदेखे करके. null = नहीं मिला
     */
    fun anchorLine(src: String, anchor: String): Int? {
        val first = anchor.lines().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val lines = src.lines()
        lines.indexOfFirst { it.contains(first) }.takeIf { it >= 0 }?.let { return it + 1 }
        val nf = norm(first)
        lines.indexOfFirst { norm(it).contains(nf) }.takeIf { it >= 0 }?.let { return it + 1 }
        return null
    }

    /**
     * बड़ी फ़ाइल (bigChars से बड़ी) में हर edit कदम की anchor उस हिस्से में है जो प्लानर ने सच में पढ़ा?
     * छोटी फ़ाइल पूरी पढ़ने को मिलती है - वहाँ पुरानी जगह-जाँच ही काफ़ी. @return समस्याएँ (खाली = ठीक)
     */
    fun anchorsSeen(plan: Seven.Plan, read: (String) -> String?, seen: Seen, bigChars: Int): List<String> {
        val out = mutableListOf<String>()
        for ((i, s) in plan.steps.withIndex()) {
            if (s.action != "edit" || s.anchor.isBlank()) continue
            val cur = read(s.file) ?: continue
            if (cur.length <= bigChars) continue
            val at = anchorLine(cur, s.anchor) ?: continue          // anchor ही गलत = पुरानी जगह-जाँच पकड़ती है
            if (!seen.covers(s.file, at)) out.add("step ${i + 1}: the anchor is at line $at of ${s.file} (a big file), but you did not READ that part - " +
                "read {\"read\":[\"${s.file}:${maxOf(1, at - 60)}-${at + 60}\"]} and check the change belongs exactly there")
        }
        return out
    }

    /** डिबगर एक बार में इतने अक्षर पढ़े (छोटे मॉडल का context न भरे) */
    const val DEBUG_READ_BUDGET = 90_000

    // ------------------------------------------------------------------ छोटा नाम -> असली path
    /**
     * AI अक्सर पूरा path नहीं, सिर्फ़ "Ear.kt" या "assistant/Ear.kt" लिखता है.
     * @return रिपो का असली path (सटीक, या एक ही फ़ाइल जिसका path इसी पर ख़त्म हो), नहीं तो null
     */
    fun resolve(path: String, keys: Collection<String>): String? {
        // v3.89: CI log के "file:///home/runner/work/DK2.0/DK2.0/app/..." जैसे पूरे path भी रिपो path बनें
        val p = BuildLog.clean(path.trim().trim('`')).removePrefix("file://").removePrefix("./").removePrefix("/")
        if (p.isBlank()) return null
        if (p in keys) return p
        keys.filter { p.endsWith("/$it") }.maxByOrNull { it.length }?.let { return it }
        val hits = keys.filter { it.endsWith("/$p") }
        return hits.singleOrNull() ?: hits.filterNot { it.contains("/test/") }.singleOrNull()
    }

    // ------------------------------------------------------------------ बातचीत छोटी रखना
    /**
     * पुराने बड़े नतीजे (पढ़ी फ़ाइलें / खोज) छोटे कर दो - वरना हर राउंड में सब दोबारा जाता है और छोटे मॉडल का context भर जाता है.
     * पहला संदेश (काम) और आख़िरी keepLast संदेश जैसे के तैसे. क्या पढ़ा था उसकी सूची बची रहती है.
     */
    fun compact(msgs: MutableList<Pair<String, String>>, keepLast: Int = 4, bigChars: Int = 3_000) {
        for (i in 1 until (msgs.size - keepLast)) {
            val (role, text) = msgs[i]
            if (role != "user" || text.length <= bigChars || text.startsWith("(EARLIER RESULT")) continue
            val heads = text.lines().filter { it.startsWith("----- BEGIN") || it.startsWith("=== SEARCH") || it.endsWith("NOT FOUND -----") }
                .map { it.removePrefix("----- BEGIN ").removeSuffix(" -----").take(160) }
            msgs[i] = role to ("(EARLIER RESULT - shortened to save space; you already saw: " +
                heads.joinToString("; ").ifBlank { "a long result" } + ". Read it again only if you really need it.)")
        }
    }

    /** बचे राउंड - हर नतीजे के बाद AI को */
    fun roundsLeft(left: Int) = "\n(ROUNDS LEFT: $left" + if (left <= 2) " - stop searching, decide now)" else ")"

    // ------------------------------------------------------------------ पढ़ने की माँग
    data class ReadReq(val path: String, val from: Int? = null, val to: Int? = null)

    private val RANGE = Regex("""^(.+?)[:#]L?(\d+)\s*-\s*L?(\d+)$""")

    /** "path" / "path:120-260" / "path#L120-L260" */
    fun parseRead(entry: String): ReadReq {
        val t = entry.trim().removePrefix("./").removePrefix("/")
        val m = RANGE.find(t) ?: return ReadReq(t)
        val a = m.groupValues[2].toInt(); val b = m.groupValues[3].toInt()
        return ReadReq(m.groupValues[1].trim(), minOf(a, b).coerceAtLeast(1), maxOf(a, b))
    }

    /** लाइन नंबर के साथ (1 से गिनती). from..to दोनों शामिल */
    fun numbered(src: String, from: Int = 1, to: Int = Int.MAX_VALUE): String {
        val lines = src.lines()
        val a = from.coerceAtLeast(1); val b = to.coerceAtMost(lines.size)
        if (a > b) return ""
        val w = b.toString().length
        return (a..b).joinToString("\n") { n -> n.toString().padStart(w) + "| " + lines[n - 1] }
    }

    private val OUTLINE = Regex("""^\s*(?:(?:private|internal|public|protected|override|open|abstract|data|sealed|inline|suspend|enum|inner|companion|lateinit|const|operator)\s+)*(class|object|interface|fun)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)""")

    /** फ़ाइल की रूपरेखा: हर class / object / fun किस लाइन पर */
    fun outline(src: String, max: Int = 200): String = src.lines().withIndex().mapNotNull { (i, l) ->
        val t = l.trimStart()
        if (t.startsWith("//") || t.startsWith("*")) null
        else OUTLINE.find(l)?.let { "${i + 1}: ${it.groupValues[1]} ${it.groupValues[2]}" }
    }.take(max).joinToString("\n")

    /** एक पढ़ाई का नतीजा: AI को दिखने वाला text + कौन-सी लाइनें दिखीं */
    data class Block(val text: String, val path: String, val from: Int, val to: Int, val chars: Int)

    /**
     * @param content फ़ाइल (null = नहीं मिली)
     * @param budget बचे हुए अक्षर
     * @param maxChars पूरी फ़ाइल पढ़ने पर एक फ़ाइल की सीमा
     */
    fun block(req: ReadReq, content: String?, budget: Int, maxChars: Int, numbers: Boolean = true): Block {
        val p = req.path
        if (content == null) return Block("----- $p : FILE NOT FOUND (use {\"search\":[…]} to find the right path) -----", p, 0, 0, 0)
        if (budget <= 0) return Block("----- $p : (skipped, read budget used - ask for a smaller part \"$p:START-END\") -----", p, 0, 0, 0)
        val lines = content.lines()
        val total = lines.size
        if (req.from != null) {
            val a = req.from.coerceIn(1, maxOf(1, total))
            val b = minOf(req.to ?: (a + RANGE_MAX_LINES - 1), a + RANGE_MAX_LINES - 1, total)
            var body = if (numbers) numbered(content, a, b) else lines.subList(a - 1, b).joinToString("\n")
            var end = b
            if (body.length > budget) { body = body.take(budget).substringBeforeLast('\n'); end = a + body.lines().size - 1 }
            return Block("----- BEGIN $p lines $a-$end of $total -----\n$body\n----- END $p -----", p, a, end, body.length)
        }
        val limit = minOf(budget, maxChars)
        val full = if (numbers) numbered(content) else content
        if (full.length <= limit) return Block("----- BEGIN $p ($total lines) -----\n$full\n----- END $p -----", p, 1, total, full.length)
        val ol = outline(content).take(6_000)
        val room = (limit - ol.length - 300).coerceAtLeast(limit / 2)
        val part = full.take(room).substringBeforeLast('\n')
        val end = part.lines().size
        val text = "----- BEGIN $p lines 1-$end of $total (TRUNCATED - read other parts with {\"read\":[\"$p:START-END\"]}) -----\n$part\n" +
            "----- OUTLINE of $p (line: declaration) -----\n$ol\n----- END $p -----"
        return Block(text, p, 1, end, part.length + ol.length)
    }

    // ------------------------------------------------------------------ डिबगर ने क्या देखा
    class Seen {
        private val ranges = HashMap<String, MutableList<IntRange>>()
        fun add(path: String, from: Int, to: Int) { if (from in 1..to) ranges.getOrPut(path) { mutableListOf() }.add(from..to) }
        fun read(path: String) = ranges[path].orEmpty().isNotEmpty()
        fun covers(path: String, line: Int) = ranges[path].orEmpty().any { line in it }
        fun paths(): Set<String> = ranges.keys
    }

    // ------------------------------------------------------------------ जड़ की जाँच
    private val LOC = Regex("""([\w./-]+\.[A-Za-z0-9]{1,6})(?::(\d+))?""")

    /** "path:line - …" / "path:line" / "path" -> (path, line?) */
    fun location(text: String): Pair<String, Int?>? {
        val m = LOC.find(text.trim()) ?: return null
        return m.groupValues[1].removePrefix("./").removePrefix("/") to m.groupValues[2].toIntOrNull()
    }

    /**
     * डिबगर का जवाब पक्का है? @return समस्याएँ (खाली = पास)
     * @param exists path -> फ़ाइल की लाइनें (null = रिपो में नहीं)
     */
    fun verify(o: JSONObject, seen: Seen, resolve: (String) -> String? = { it }, exists: (String) -> Int?): List<String> {
        val root = o.optString("root_cause").trim()
        val where = o.optString("where").trim()
        val loc = location(where) ?: location(root)
            ?: return listOf("\"where\" must be \"path:line\" of the wrong line (no file path found in where/root_cause)")
        val path = resolve(loc.first) ?: loc.first
        val line = loc.second
        val lines = exists(path)
            ?: return listOf("$path does not exist in this repository - search for the real file ({\"search\":[…]}) and read it")
        val out = mutableListOf<String>()
        if (!seen.read(path)) out.add("You did not READ $path - read it ({\"read\":[\"$path\"]}) and confirm the root cause in the real code")
        else if (line == null) out.add("Give the exact line: \"where\":\"$path:LINE\" (line numbers are shown as \"123| code\")")
        else if (line < 1 || line > lines) out.add("$path has only $lines lines - line $line does not exist")
        else if (!seen.covers(path, line)) out.add("Line $line of $path was not in the part you read - read it ({\"read\":[\"$path:${maxOf(1, line - 40)}-${line + 40}\"]})")
        val bad = Seven.strings(o.optJSONArray("files")).filter { f -> exists(resolve(f) ?: f.removePrefix("./").removePrefix("/")) == null && !f.endsWith("/") }
        val fix = o.optString("fix")
        if (bad.isNotEmpty() && !fix.contains("create", true) && !fix.contains("new file", true))
            out.add("These \"files\" do not exist: ${bad.joinToString()} - use real paths (or say in \"fix\" that a new file must be created)")
        return out
    }
}

/** v3.89: compiler/test जिन फ़ाइलों में एरर बता रहा है, डिबगर का इलाज उन्हें छूता भी नहीं? (PR #105: 4 राउंड ग़लत फ़ाइल) */
object ErrorCover {
    /** @return डिबगर को लौटाने लायक बात, या null = ठीक */
    fun miss(o: JSONObject, sites: List<BuildLog.Site>, resolve: (String) -> String?): String? {
        if (sites.isEmpty()) return null
        val errFiles = sites.mapNotNull { resolve(it.path) ?: it.path }.distinct()
        val named = (Seven.strings(o.optJSONArray("files")) + listOfNotNull(SevenDebug.location(o.optString("where"))?.first))
            .map { resolve(it) ?: it.removePrefix("./").removePrefix("/") }
        fun same(a: String, b: String) = a == b || a.endsWith("/$b") || b.endsWith("/$a")
        if (named.any { n -> errFiles.any { same(n, it) } }) return null
        return "The build log reports the errors IN: ${errFiles.take(6).joinToString()} (e.g. ${sites.first().path}:${sites.first().line} - ${sites.first().msg.take(160)}). " +
            "Your diagnosis does not touch any of these files. Read the error lines shown above; either fix those files (add them to \"files\") " +
            "or explain exactly why the error in that file is caused by another file and include both."
    }
}
