package com.dk.assistant.dkai

import java.io.File

/**
 * 13 नवंबर 2026 (मालिक): "DK का Terminal और एडवांस / पावरफुल बनाओ, DK लाइव बोलने पर खुद कमांड चलाए, और DK को
 * Terminal की पूरी जानकारी हो". यहाँ सिर्फ़ शुद्ध नियम (tests): alias, export, इतिहास (!! / !n), खतरनाक कमांड की पहचान,
 * Tab से नाम पूरा करना, tree, output छोटा करना, मदद, और DK लाइव को दी जाने वाली पूरी जानकारी.
 * चलाना [DkaiShell] में (Terminal स्क्रीन और DK लाइव - दोनों एक ही सत्र: वही फ़ोल्डर, वही इतिहास, वही alias).
 */
object DkaiTerm {
    /** DK की अपनी कमांड (Android के sh में नहीं होतीं / ऐप के अंदर की) */
    val BUILTINS = linkedSetOf("help", "clear", "cd", "open", "preview", "export", "unset", "env", "alias", "unalias", "history",
        "tree", "unzip", "zip", "wget", "curl", "sysinfo", "dkhelp")

    val DEFAULT_ALIASES = linkedMapOf("ll" to "ls -la", "la" to "ls -A", "l" to "ls -l", ".." to "cd ..", "h" to "history")

    /** पहला शब्द */
    fun head(cmd: String): String = cmd.trim().substringBefore(' ').substringBefore('\t')

    fun isBuiltin(cmd: String): Boolean = head(cmd) in BUILTINS

    /** alias बदलो (सिर्फ़ पहला शब्द; एक-दूसरे में फँसे alias पर 5 बार के बाद रुको) */
    fun expandAlias(cmd: String, aliases: Map<String, String>): String {
        var c = cmd.trim(); var n = 0
        while (n++ < 5) {
            val h = head(c); val a = aliases[h] ?: return c
            val next = a + c.removePrefix(h)
            if (next == c) return c
            c = next
        }
        return c
    }

    /** "!!" = पिछली कमांड, "!3" = इतिहास की तीसरी. null = वैसी कोई कमांड नहीं */
    fun expandHistory(cmd: String, history: List<String>): String? {
        val t = cmd.trim()
        if (t == "!!") return history.lastOrNull()
        Regex("""^!(\d+)$""").find(t)?.let { m -> return history.getOrNull(m.groupValues[1].toInt() - 1) }
        return t
    }

    private val PAIR = Regex("""([A-Za-z_][A-Za-z0-9_]*)=("([^"]*)"|'([^']*)'|(\S*))""")

    /** "export A=1 B='x y'" का हिस्सा "A=1 B='x y'" -> {A=1, B=x y} */
    fun pairs(arg: String): Map<String, String> = PAIR.findAll(arg).associate { m ->
        m.groupValues[1] to (m.groups[3]?.value ?: m.groups[4]?.value ?: m.groupValues[5])
    }

    /** "alias ll='ls -la'" का हिस्सा "ll='ls -la'" -> ll to "ls -la" */
    fun aliasDef(arg: String): Pair<String, String>? {
        val m = Regex("""^([^=\s]+)=(?:"([^"]*)"|'([^']*)'|(.*))$""").find(arg.trim()) ?: return null
        val v = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groupValues[4]
        return if (v.isBlank()) null else m.groupValues[1] to v.trim()
    }

    /** रंग वाले कोड (ESC[..m) हटाओ - स्क्रीन पर कचरा न दिखे */
    fun stripAnsi(s: String): String = s.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "").replace("\r\n", "\n")

    private val RISKY = listOf(
        Regex("""(^|[;&|(`]|\bxargs|\bexec)\s*(rm|rmdir|mv|dd|truncate|chmod|chown|kill|pkill|killall|shred)\b"""),
        Regex("""(^|[^>])>[^>&]"""),                 // फ़ाइल के ऊपर लिखना (">" ; ">>" जोड़ना ठीक)
        Regex("""\bsed\b[^|;&]*\s-i"""),
        Regex("""\bunzip\b[^|;&]*\s-o\b"""),
        Regex("""\bfind\b[^|;&]*\s-delete\b"""),
    )

    /** कुछ मिटा / बदल / ऊपर लिख सकती है? (DK लाइव पहले पूछे) */
    fun risky(cmd: String): Boolean = RISKY.any { it.containsMatchIn(" " + cmd.trim()) }

    /** लंबा output: शुरू + आख़िर, बीच का "… N अक्षर छोड़े …" */
    fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val keepHead = max * 2 / 5; val keepTail = max - keepHead
        return s.take(keepHead) + "\n… (${s.length - max} अक्षर छोड़े) …\n" + s.takeLast(keepTail)
    }

    /**
     * Tab: आख़िरी शब्द का नाम पूरा करो. names = उस फ़ोल्डर की फ़ाइलें (फ़ोल्डर के आगे "/").
     * एक मिला = पूरा; कई = सबका साझा शुरू; कुछ नहीं = वैसा ही. second = कई मिले तो उनकी सूची.
     */
    fun complete(line: String, names: List<String>): Pair<String, List<String>> {
        val cut = line.lastIndexOf(' ') + 1
        val word = line.substring(cut)
        val dirPart = word.substringBeforeLast('/', "").let { if (word.contains('/')) "$it/" else "" }
        val stem = word.removePrefix(dirPart)
        val hits = names.filter { it.startsWith(stem) }.sorted()
        if (hits.isEmpty()) return line to emptyList()
        val common = hits.reduce { a, b -> a.commonPrefixWith(b) }
        val done = if (hits.size == 1) hits[0].let { if (it.endsWith("/")) it else "$it " } else common
        return line.substring(0, cut) + dirPart + done to (if (hits.size > 1) hits else emptyList())
    }

    /** फ़ोल्डर का पेड़ (tree) - .git / node_modules अंदर नहीं; ज़्यादा से ज़्यादा [limit] लाइन */
    fun tree(dir: File, maxDepth: Int = 3, limit: Int = 300): String {
        val sb = StringBuilder(dir.name.ifBlank { "." }).append('\n')
        var lines = 0; var dirs = 0; var files = 0
        fun walk(d: File, prefix: String, depth: Int) {
            val kids = (d.listFiles()?.toList() ?: emptyList()).filter { it.name != DkaiCore.REPO_META }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            kids.forEachIndexed { i, f ->
                if (lines >= limit) return
                val last = i == kids.lastIndex
                sb.append(prefix).append(if (last) "└── " else "├── ").append(f.name).append(if (f.isDirectory) "/" else "").append('\n')
                lines++
                if (f.isDirectory) {
                    dirs++
                    if (depth < maxDepth && f.name != ".git" && f.name != "node_modules") walk(f, prefix + if (last) "    " else "│   ", depth + 1)
                } else files++
            }
        }
        walk(dir, "", 1)
        if (lines >= limit) sb.append("… (और भी हैं)\n")
        sb.append("\n$dirs फ़ोल्डर, $files फ़ाइल\n")
        return sb.toString()
    }

    /** "1.5 MB" जैसा */
    fun size(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
        else -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    }

    /** URL से फ़ाइल का नाम (wget) */
    fun fileFromUrl(url: String): String =
        url.substringAfter("://").substringBefore('?').substringBefore('#').substringAfter('/', "").trimEnd('/').substringAfterLast('/').ifBlank { "index.html" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    /** zip के अंदर का रास्ता बाहर न निकले (zip-slip) */
    fun safeEntry(dest: File, name: String): File? {
        val f = File(dest, name)
        return if (DkaiCore.inside(dest, f)) f else null
    }

    /** wget / curl के शब्द: URL और -o / -O फ़ाइल */
    data class Fetch(val url: String, val out: String?, val save: Boolean)
    fun parseFetch(cmd: String): Fetch? {
        val w = cmd.trim().split(Regex("\\s+"))
        val tool = w.firstOrNull() ?: return null
        val url = w.drop(1).firstOrNull { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        val oi = w.indexOfFirst { it == "-o" || it == "-O" || it == "--output" }
        val named = if (oi > 0) w.getOrNull(oi + 1)?.takeIf { !it.startsWith("http") } else null
        return when (tool) {
            "wget" -> Fetch(url, named ?: fileFromUrl(url), true)
            "curl" -> if (oi > 0) Fetch(url, named ?: fileFromUrl(url), true) else Fetch(url, null, false)
            else -> null
        }
    }

    const val HELP = "DKAI Terminal - ऐप का अपना shell (Android sh + toybox) + DK की अपनी कमांड\n\n" +
        "आम कमांड: ls, cat, echo, mkdir, cp, mv, rm, grep, sed, find, wc, head, tail, sort, uniq, date, du, df, ps, sh script.sh…\n" +
        "  pipe (|), >, >>, &&, ; , \$VAR सब चलते हैं\n\n" +
        "DK की अपनी:\n" +
        "  cd <फ़ोल्डर> / cd - / cd ~   Workspace, App Studio, DK Computer के अंदर\n" +
        "  tree [फ़ोल्डर]              फ़ोल्डर का पेड़\n" +
        "  open <फ़ाइल>                Editor में खोलें\n" +
        "  preview <फ़ाइल.html>        Preview में चलाएँ\n" +
        "  wget <url> [-O नाम]         इंटरनेट से फ़ाइल डाउनलोड\n" +
        "  curl <url> [-o नाम]         पेज / API का जवाब दिखाएँ (या सेव)\n" +
        "  unzip <file.zip> [फ़ोल्डर]   zip खोलें;  zip <out.zip> <फ़ाइल/फ़ोल्डर…>  zip बनाएँ\n" +
        "  sysinfo                     फ़ोन: मॉडल, Android, बैटरी, RAM, जगह\n" +
        "  export A=1 / unset A / env  अपने variable (याद रहते हैं)\n" +
        "  alias ll='ls -la' / unalias  छोटे नाम (ll, la, l, .., h पहले से)\n" +
        "  history / history -c / !! / !3   पिछली कमांड\n" +
        "  clear                       स्क्रीन साफ़\n\n" +
        "बटन: Tab = नाम पूरा करें, ↑ ↓ = पिछली कमांड, ■ = चलती कमांड रोकें (Ctrl+C), कोई कमांड जवाब माँगे तो नीचे लिखकर भेजें.\n" +
        "DK लाइव से बोलें: \"टर्मिनल में ls चलाओ\", \"Workspace में test फ़ोल्डर बनाओ\" - DK खुद चलाकर बताएगी (मिटाने वाली कमांड से पहले पूछेगी).\n" +
        "नहीं होता: git, python, node, npm, apt, root (Android इजाज़त नहीं देता); vi / top जैसी पूरी स्क्रीन वाली कमांड.\n\n"

    /** DK लाइव को: Terminal की पूरी जानकारी (tool के description + prompt में) */
    const val KNOWLEDGE = "DKAI Terminal (the DKAI home -> Terminal tab; same session for you and the user: same folder, history, aliases, variables). " +
        "It is the app's own real Android shell (sh + toybox, no root) that runs inside the app's folders only: Workspace (start, ~), App Studio, DK Computer. " +
        "Normal commands: ls, cat, echo, mkdir, cp, mv, rm, grep, sed, find, wc, head, tail, sort, du, df, ps, date, sh script.sh, pipes | > >> && ; and \$VARS. " +
        "DK built-ins: cd (also cd -, cd ~), tree, wget URL (download a file), curl URL (show a page / API reply, -o saves), unzip / zip, sysinfo (model, Android, battery, RAM, storage), " +
        "export / unset / env, alias / unalias (ll, la, l, .., h ready), history, !!, !N, help, open FILE (opens the Editor) and preview FILE.html (Preview tab) - open/preview/clear work only on the Terminal screen. " +
        "The screen also has Tab completion, up/down history, a stop button (Ctrl+C), quick chips, copy output, and you can type input for a command that asks for it. " +
        "NOT available: git, python, node, npm, apt, root, full-screen programs like vi or top; GitHub work is the GitHub tab. Outside the app's folders is not allowed."
}
