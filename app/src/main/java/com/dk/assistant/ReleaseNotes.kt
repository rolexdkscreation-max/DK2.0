package com.dk.assistant

/**
 * v3.6 (owner): अपडेट स्क्रीन / नोटिफ़िकेशन में सिर्फ़ असली बदलाव - "Merge pull request…", टेस्ट, बैकअप,
 * फ़ाइलों के नाम, कोडिंग एजेंट को दिया पूरा काम (लंबा prompt) जैसी अंदर की बातें नहीं।
 * Pure (Android नहीं) - unit test से जाँचा जाता है। पुराने releases के notes भी ऐप में यहीं से साफ़ होते हैं।
 */
object ReleaseNotes {
    const val FALLBACK = "• छोटे सुधार और बेहतर स्थिरता"
    const val MAX_LINES = 12
    const val MAX_LEN = 140

    private val dropStarts = listOf("merge pull request", "merge branch", "merge remote", "revert \"merge", "co-authored-by",
        "signed-off-by", "tests:", "test:", "ci:", "chore", "bump", "backup", "wip")
    private val dropHas = listOf("backup/", ".kt", ".xml", ".yml", ".gradle", "unit test", "testdebug", "assembledebug",
        "github/workflows", "agents.md", "```", "http://", "https://", "pull/", "feature/", "रिपो", "branch")

    /** एक लाइन रखने लायक है? (नहीं = null) - साफ़ की हुई लाइन लौटाता है */
    fun line(raw: String): String? {
        var s = raw.trim().trimStart('•', '-', '*', '+', ' ', '\t').trim()
        if (s.isEmpty() || s.startsWith("#") || s == "---") return null
        s = s.replace(Regex("^\\[(software|security|सॉफ़्टवेयर|सिक्योरिटी)]\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(#\\d+\\)$"), "")
            .replace(Regex("\\s*[+&]\\s*tests?\\s*$", RegexOption.IGNORE_CASE), "")
            .replace("**", "").replace("`", "").trim()
        val low = s.lowercase()
        if (s.length < 4) return null
        if (dropStarts.any { low.startsWith(it) }) return null
        if (dropHas.any { low.contains(it) }) return null
        if (Regex("^(type|version):", RegexOption.IGNORE_CASE).containsMatchIn(s)) return null
        if (Regex("[{};]|\\(\\)|->|==|:=|\\w\\(\\w").containsMatchIn(s)) return null             // code जैसा
        if (s.length > MAX_LEN) {                                                   // लंबा काम-विवरण = पहला वाक्य ही
            val cut = s.take(MAX_LEN).let { t -> listOf("।", ". ", " — ", "; ").map { t.indexOf(it) }.filter { it > 20 }.minOrNull()?.let { t.take(it) } ?: t.substringBeforeLast(' ') }
            s = cut.trim().trimEnd('।', '.', ',') + "…"
        }
        return s
    }

    fun clean(notes: String): String {
        val seen = HashSet<String>()
        val out = notes.replace("\r", "").lines().mapNotNull { line(it) }
            .filter { seen.add(it.lowercase().filter { ch -> ch.isLetterOrDigit() }) }
            .take(MAX_LINES)
        return if (out.isEmpty()) FALLBACK else out.joinToString("\n") { "• $it" }
    }

    /** नोटिफ़िकेशन भेजें? नया release + इसी build का पहले नहीं बताया */
    fun shouldNotify(latest: Int, current: Int, lastNotified: Int): Boolean = latest > current && latest > lastNotified
}
