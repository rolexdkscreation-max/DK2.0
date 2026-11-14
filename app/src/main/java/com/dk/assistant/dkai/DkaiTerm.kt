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
        "tree", "unzip", "zip", "wget", "curl", "sysinfo", "dkhelp", "apk")

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
        // ऑडिट 13 नवंबर 2026: पहले "toybox rm", "sh -c 'rm …'", "env rm", "busybox rm", "cp /dev/null f", "tee f" बच निकलते थे.
        // अब खतरनाक शब्द कमांड में कहीं भी हो (आगे कोई भी wrapper लगा हो) = पहले पूछो
        Regex("""(?<![\w.-])(rm|rmdir|mv|cp|dd|truncate|chmod|chown|kill|pkill|killall|shred|tee|ln|unlink|rsync|mkfs[\w.]*|wipe|reboot|setprop)(?![\w-])"""),
        // दूसरी कमांड चलाने वाले (इनके अंदर कुछ भी छिप सकता है)
        Regex("""(?<![\w.-])(sh|bash|zsh|ash|mksh|eval|source|exec|xargs|nohup|su|sudo)(?![\w-])"""),
        Regex("""(^|[\s;&|(`{])\.\s+\S"""),           // ". script.sh"
        // फ़ोन / सिस्टम की सेटिंग और ऐप बदलना
        Regex("""\b(pm\s+(uninstall|clear|disable|hide|grant|revoke|install)|am\s+(force-stop|kill)|settings\s+(put|delete)|svc\s+\w+|cmd\s+\w+)"""),
        // ऐप के workspace से बाहर की जगहें (पूरी फ़ोन storage, ऐप का निजी डेटा, सिस्टम)
        Regex("""(^|[\s=:'"(])(/sdcard|/storage|/data|/mnt|/system|/vendor|/proc|/apex|~)(/|\s|$|'|")"""),
        Regex("""\.\./\.\."""),                          // दो या ज़्यादा "../" = workspace से बाहर
    )

    /** कुछ मिटा / बदल / ऊपर लिख सकती है, या workspace से बाहर जाती है? (DK लाइव पहले पूछे) */
    fun risky(cmd: String): Boolean {
        val c = " " + cmd.trim().replace('\n', ';').replace('\r', ';')
        return RISKY.any { it.containsMatchIn(c) }
    }

    private val LOOK = setOf("ls", "ll", "la", "cat", "pwd", "echo", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "stat", "du", "df", "date",
        "whoami", "id", "tree", "which", "type", "file", "uname", "uptime", "less", "more", "sort", "uniq", "basename", "dirname", "realpath", "help", "history", "clear", "md5sum", "sha1sum", "sha256sum")
    private val GIT_LOOK = Regex("""^git\s+(status|log|diff|show|remote(\s+-v)?|branch(\s+-[av]+)?)(\s|$)""")

    /** सिर्फ़ देखने वाली कमांड? (कुछ बनाती / बदलती / चलाती नहीं) - हर हिस्सा (; && || |) देखने वाला हो */
    fun readOnly(cmd: String): Boolean {
        val t = cmd.trim()
        if (t.isEmpty() || t.contains('`') || t.contains("$(") || Regex("""(^|[^>])>""").containsMatchIn(t)) return false
        return t.split(Regex("""\s*(;|&&|\|\||\||\n)\s*""")).filter { it.isNotBlank() }.all { part ->
            val p = part.trim()
            val w = p.split(Regex("\\s+")).first()
            when {
                w == "git" -> GIT_LOOK.containsMatchIn(p)
                w == "find" -> !Regex("""\s-(delete|exec|execdir|ok|fprint)""").containsMatchIn(p)
                else -> w in LOOK
            }
        }
    }

    /** DK लाइव की खतरनाक कमांड जो मालिक की "हाँ" का इंतज़ार कर रही है */
    data class Pending(val cmd: String, val askedAt: Long)

    sealed class Gate {
        /** चलाओ */
        object Run : Gate()
        /** पहले मालिक से पूछो (नई pending बनाओ) */
        object Ask : Gate()
        /** AI ने confirmed=true भेजा, पर ऐप ने मालिक की अपनी "हाँ" नहीं सुनी */
        object NotHeard : Gate()
    }

    const val CONFIRM_WINDOW_MS = 2 * 60_000L

    /**
     * ऑडिट 13 नवंबर 2026: "हाँ" की जाँच अब ऐप करता है, AI नहीं. पहले AI पहली बार में ही confirmed=true भेज दे तो
     * खतरनाक कमांड बिना पूछे चल जाती थी. अब: वही कमांड पहले पूछी गई हो, 2 मिनट के अंदर, और पूछने के बाद मालिक की
     * अपनी आवाज़/लिखी बात (DKState.lastUser) में साफ़ "हाँ" हो ([com.dk.assistant.agent.core.ConfirmGate]).
     */
    fun liveGate(cmd: String, aiConfirmed: Boolean, pending: Pending?, userAt: Long, userText: String, now: Long): Gate {
        // 14 नवंबर 2026 (मालिक: "टर्मिनल में कोई भी काम बिना इजाज़त के ना करेगा"): सिर्फ़ देखने वाली कमांड (ls / cat / pwd …)
        // बिना पूछे; बनाने / बदलने / चलाने वाली हर कमांड (git clone, apk, npm, mkdir, touch …) पहले मालिक की "हाँ"
        if (!risky(cmd) && readOnly(cmd)) return Gate.Run
        if (!aiConfirmed || pending == null || pending.cmd != cmd.trim() || now - pending.askedAt > CONFIRM_WINDOW_MS) return Gate.Ask
        return if (com.dk.assistant.agent.core.ConfirmGate.accept(pending.askedAt, userAt, userText)) Gate.Run else Gate.NotHeard
    }

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
        "  apk make <क्या बनाना है>     असली ऐप (APK) बनाओ + इंस्टॉल का बटन  (जैसे: apk make कैलकुलेटर ऐप)\n" +
        "  apk build [फ़ोल्डर] [नाम]    index.html वाले फ़ोल्डर से APK;  apk install <file.apk>;  apk list\n" +
        "  export A=1 / unset A / env  अपने variable (याद रहते हैं)\n" +
        "  alias ll='ls -la' / unalias  छोटे नाम (ll, la, l, .., h पहले से)\n" +
        "  history / history -c / !! / !3   पिछली कमांड\n" +
        "  clear                       स्क्रीन साफ़\n\n" +
        "बटन: Tab = नाम पूरा करें, ↑ ↓ = पिछली कमांड, ■ = चलती कमांड रोकें (Ctrl+C), कोई कमांड जवाब माँगे तो नीचे लिखकर भेजें.\n" +
        "DK लाइव से बोलें: \"टर्मिनल में ls चलाओ\", \"Workspace में test फ़ोल्डर बनाओ\" - DK खुद चलाकर बताएगी (मिटाने वाली कमांड से पहले पूछेगी).\n" +
        "नहीं होता: git, python, node, npm, apt, gradle, javac, root (Android इजाज़त नहीं देता); vi / top जैसी पूरी स्क्रीन वाली कमांड.\n" +
        "  (ऐप बनाने के लिए gradle की ज़रूरत नहीं - 'apk' कमांड फ़ोन पर ही APK बनाती है)\n\n"

    /** 14 नवंबर 2026: `apk` कमांड की मदद */
    const val APK_HELP = "apk - फ़ोन पर ही असली Android ऐप (APK), बिना कंप्यूटर / gradle के\n" +
        "  apk make <क्या बनाना है>    AI ऐप लिखता है -> APK बनता है -> इंस्टॉल की स्क्रीन अपने-आप खुलती है\n" +
        "                              जैसे: apk make कैलकुलेटर ऐप   /   apk make todo list ऐप\n" +
        "  apk build [फ़ोल्डर] [नाम]   जिस फ़ोल्डर में index.html हो उससे APK (खाली = अभी वाला फ़ोल्डर)\n" +
        "  apk install <file.apk>      बना हुआ APK इंस्टॉल करें (apks/ फ़ोल्डर में रहते हैं)\n" +
        "  apk list                    बने हुए APK\n" +
        "ऐप HTML + CSS + JavaScript से बनता है (बिना इंटरनेट चलता है). वही नाम दोबारा बनाओ = पुराने ऐप के ऊपर अपडेट.\n"

    /** "build my app 'Calc Pro'" -> [build, my, app, Calc Pro] (उद्धरण के अंदर की जगह एक शब्द) */
    fun words(s: String): List<String> = Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)").findAll(s.trim())
        .map { m -> m.groups[1]?.value ?: m.groups[2]?.value ?: m.value }.toList()

    /** DK लाइव को: Terminal की पूरी जानकारी (tool के description + prompt में) */
    const val KNOWLEDGE = "DKAI Terminal (the DKAI home -> Terminal tab; same session for you and the user: same folder, history, aliases, variables). " +
        "It is the app's own real Android shell (sh + toybox, no root) that runs inside the app's folders only: Workspace (start, ~), App Studio, DK Computer. " +
        "Normal commands: ls, cat, echo, mkdir, cp, mv, rm, grep, sed, find, wc, head, tail, sort, du, df, ps, date, sh script.sh, pipes | > >> && ; and \$VARS. " +
        "DK built-ins: cd (also cd -, cd ~), tree, wget URL (download a file), curl URL (show a page / API reply, -o saves), unzip / zip, sysinfo (model, Android, battery, RAM, storage), " +
        "export / unset / env, alias / unalias (ll, la, l, .., h ready), history, !!, !N, help, open FILE (opens the Editor) and preview FILE.html (Preview tab) - open/preview/clear work only on the Terminal screen. " +
        "BUILD A REAL INSTALLABLE ANDROID APP (APK) right on the phone, no gradle needed: `apk make <what app, in the user's words>` (e.g. apk make कैलकुलेटर ऐप) - " +
        "the App Studio AI writes the app (HTML/CSS/JS), then the APK is built and signed automatically and the Android install screen opens by itself (it takes about a minute; tell the user it has started, the install button comes when it is ready). " +
        "`apk build [folder] [name]` turns a folder that has index.html into an APK and opens install; `apk install apks/NAME.apk`; `apk list`. " +
        "Whenever the user asks to make / build an app or APK with the terminal (टर्मिनल से ऐप बनाओ, इंस्टॉल करने लायक ऐप), use `apk make ...` - never try to write the files with echo or run gradle. " +
        "The screen also has Tab completion, up/down history, a stop button (Ctrl+C), quick chips, copy output, and you can type input for a command that asks for it. " +
        "NOT available: git, python, node, npm, apt, gradle, javac, root, full-screen programs like vi or top; GitHub work is the GitHub tab. Outside the app's folders is not allowed."
}
