package com.dk.assistant

/**
 * v3.89: GitHub Actions के build/test log से डिबगर के काम की असली बातें (Android पर निर्भर नहीं - unit test).
 *
 * पहले की दिक्कतें (PR #105 में दिखीं):
 *  - log में path "file:///home/runner/work/DK2.0/DK2.0/app/src/..." आते थे - डिबगर वही path माँगता तो "FILE NOT FOUND"
 *  - "> Task :app:..." जैसी सैकड़ों बेकार लाइनें भी "ज़रूरी" मानी जाती थीं - बड़े log में असली एरर 200 की गिनती से बाहर रह जाते
 *  - एरर कहाँ है (फ़ाइल:लाइन) डिबगर को खुद ढूँढना पड़ता था - अब सीधे वहीं का कोड पहले से साथ
 */
object BuildLog {
    data class Site(val path: String, val line: Int, val msg: String, val kind: String)

    // GitHub runner का पूरा path -> रिपो के अंदर का path
    private val RUNNER = Regex("""(?:file://)?/home/runner/work/[^/\s]+/[^/\s]+/""")
    /**
     * नवंबर 2026 (feature/dk-1112-1236): Codespace sandbox के log में path "file:///home/codespace/dk-run/app/..." आता है -
     * वह रिपो का path नहीं, तो डिबगर को गलती वाली लाइन का code मिलता ही नहीं था और वह अंदाज़े से दूसरी लाइनें बदलता रहा.
     * sandbox (dk-run) / codespace (/workspaces/<repo>/) के path भी रिपो वाले करो.
     */
    private val SANDBOX = Regex("""(?:file://)?(?:/home/[^/\s]+/dk-run/|/workspaces/[^/\s]+/)""")
    private val TS = Regex("""^\d{4}-\d\d-\d\dT[\d:.]+Z\s""")
    private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

    fun clean(line: String): String = line.replace(TS, "").replace(ANSI, "").replace(RUNNER, "").replace(SANDBOX, "").trimEnd()

    private val KOTLIN_ERR = Regex("""^e:\s+(?:file://)?([\w./-]+\.(?:kt|kts|java)):(\d+)(?::\d+)?\s+(.*)$""")
    private val JAVA_ERR = Regex("""^([\w./-]+\.java):(\d+):\s*error:\s*(.*)$""")
    private val AT_FILE = Regex("""\bat\s+(?:[\w.$]+\()?([\w$]+\.(?:kt|java)):(\d+)\)?""")
    private val TEST_FAIL = Regex("""^([\w.$]+)\s+>\s+(.+?)\s+FAILED$""")
    // test fail के नीचे का कारण: "java.lang.AssertionError: ..." / "org.junit.ComparisonFailure: ..." / "...Exception: ..."
    private val REASON = Regex("""^((?:[\w$]+\.)*[\w$]*(?:Error|Exception|Failure)[\w$]*)(?::\s*(.*))?""")
    private val AAPT = Regex("""^(?:ERROR:\s*)?([\w./-]+\.xml):(\d+):\s*(?:AAPT:\s*)?error:\s*(.*)$""", RegexOption.IGNORE_CASE)

    /** एरर की जगहें (क्रम से, दोहराव नहीं). test की जगह "FaceMatchTest.kt" जैसा छोटा नाम हो सकता है - resolve से पूरा path */
    fun sites(log: String): List<Site> {
        val out = LinkedHashMap<String, Site>()
        var testName = ""
        var reason = ""
        for (raw in log.lines()) {
            val l = clean(raw).trim()
            KOTLIN_ERR.find(l)?.let { m -> add(out, Site(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].take(240), "compile")) ; return@let }
                ?: JAVA_ERR.find(l)?.let { m -> add(out, Site(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].take(240), "compile")) }
                ?: AAPT.find(l)?.let { m -> add(out, Site(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].take(240), "resource")) }
            val failLine = TEST_FAIL.find(l)?.also { m -> testName = m.groupValues[1].substringAfterLast('.') + "." + m.groupValues[2]; reason = "" } != null
            // PR #252: पहले site में सिर्फ़ "at com.dk...Test.kt:57" जाता था - डिबगर को पता ही नहीं चलता था कि क्या टूटा
            if (!failLine && testName.isNotEmpty() && reason.isEmpty() && !l.startsWith("at ")) REASON.find(l)?.let { m ->
                reason = (if (m.groupValues[2].isNotBlank()) m.groupValues[2] else m.groupValues[1].substringAfterLast('.')).trim()
            }
            if (testName.isNotEmpty()) AT_FILE.find(l)?.let { m ->
                val f = m.groupValues[1]
                // सिर्फ़ अपनी (रिपो की) फ़ाइल; junit/kotlin की अंदर की नहीं
                if (!f.startsWith("Assert") && !f.startsWith("Junit") && !f.startsWith("Kotlin")) {
                    add(out, Site(f, m.groupValues[2].toInt(), "test $testName failed: " + (reason.ifBlank { l }).take(400), "test"))
                    testName = ""; reason = ""
                }
            }
        }
        return out.values.toList()
    }

    private fun add(m: LinkedHashMap<String, Site>, s: Site) { m.putIfAbsent("${s.path}:${s.line}", s) }

    private fun noise(l: String): Boolean =
        (l.startsWith("> Task ") && !l.endsWith("FAILED")) || l.startsWith("w: ") || l.startsWith("Download") ||
            l.startsWith("Cache ") || l.startsWith("##[group]") || l.startsWith("##[endgroup]") || l.isBlank()

    private fun key(l: String): Boolean =
        l.startsWith("e: ") || l.contains("error:", true) || l.contains("What went wrong") || l.contains("FAILURE:") ||
            l.contains("Unresolved reference") || l.contains("Exception") || l.contains("##[error]") || l.endsWith("FAILED") ||
            l.contains("AssertionError") || l.contains("expected:") || l.contains("tests completed") || l.startsWith("Caused by")

    /**
     * डिबगर को देने लायक log: सबसे ऊपर असली एरर (सारे "e:" + fail tests के पूरे कारण), फिर उनके आस-पास की लाइनें.
     * बेकार लाइनें (Task सूची, warnings, downloads) हटाकर. path रिपो वाले.
     */
    fun digest(raw: String, maxChars: Int = 30_000): String {
        val lines = raw.lines().map { clean(it) }
        val keep = sortedSetOf<Int>()
        for ((i, l) in lines.withIndex()) {
            val t = l.trim()
            if (noise(t) || !key(t)) continue
            keep.add(i)
            // fail test / exception: नीचे का पूरा कारण (expected/actual, stack की पहली लाइनें)
            val after = if (t.endsWith("FAILED") || t.contains("Exception") || t.contains("AssertionError") || t.contains("What went wrong")) 14 else 2
            for (x in (i - 1)..(i + after)) if (x in lines.indices && !noise(lines[x].trim())) keep.add(x)
        }
        val body = if (keep.isEmpty()) lines.filterNot { noise(it.trim()) }.takeLast(200) else keep.map { lines[it] }
        val s = sites(raw)
        val head = if (s.isEmpty()) "" else "ERROR LOCATIONS (file:line - message):\n" + s.take(20).joinToString("\n") { "- ${it.path}:${it.line} - ${it.msg}" } + "\n\n"
        return (head + body.joinToString("\n")).take(maxChars)
    }

    /** छोटा नाम / runner path -> रिपो का असली path (एक ही मिले तो) */
    fun resolve(path: String, keys: Collection<String>): String? {
        val p = clean(path.trim().trim('`')).removePrefix("file://").removePrefix("./").removePrefix("/")
        if (p.isBlank()) return null
        if (p in keys) return p
        keys.filter { p.endsWith("/$it") }.maxByOrNull { it.length }?.let { return it }
        val hits = keys.filter { it.endsWith("/$p") }
        return hits.singleOrNull() ?: hits.filterNot { it.contains("/test/") }.singleOrNull()
    }

    /** एक फ़ाइल की कई एरर-लाइनों के आस-पास के हिस्से (मिला कर), 1 से गिनती */
    fun windows(lines: List<Int>, total: Int, pad: Int = 30): List<IntRange> {
        val rs = lines.sorted().map { maxOf(1, it - pad)..minOf(total, it + pad) }
        val out = mutableListOf<IntRange>()
        for (r in rs) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out.add(r)
        }
        return out
    }
}
