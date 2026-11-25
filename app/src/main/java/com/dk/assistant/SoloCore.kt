package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "एक ही एजेंट सब काम कर लेगा... बारीकी से, पूरा फिनिश करके ही दे, बार-बार खराब न करे"):
 * एक-दिमाग वाला कोडर.
 *
 * पुराने 7 एजेंट क्रम में हर एजेंट को काम का छोटा टुकड़ा मिलता था (प्लानर को काम, कोडर को एक कदम + एक फ़ाइल,
 * रिव्यूअर को diff, डिबगर को log). बात एक से दूसरे तक पहुँचते-पहुँचते छूट जाती थी, और कोई भी एक एजेंट अटका तो
 * पूरा काम "प्लानर प्लान नहीं बना पाया" / "डिबगर जड़ नहीं ढूँढ पाया" पर रुक जाता था (feature/dk-1112-1236:
 * 4 commit, फिर भी गायब तस्वीरें).
 *
 * अब एक ही एजेंट पूरी बातचीत याद रखकर खुद खोजता (LIST/GREP), पढ़ता (READ), कई फ़ाइलें बदलता (FILE + FIND/REPLACE),
 * खुद जाँचता (CHECK) और तभी DONE कहता है. CI की गलती, QA की कमी, user की नई बात - सब उसी बातचीत में लौटते हैं,
 * तो वह जानता है कि उसने क्या बदला और क्या पहले आज़माया जा चुका.
 *
 * यह फ़ाइल सिर्फ़ पक्का (AI के बिना) हिस्सा है: जवाब पढ़ना, बदलाव लागू करना, खोज, पंक्ति-नंबर वाली फ़ाइल. AI वाला चक्र
 * [SevenTeam] में है (वहीं की पक्की जाँचें, push, CI, PR, रिपोर्ट).
 */
object Solo {

    /** एक काम-राउंड में AI के अधिकतम जवाब (हर जवाब में कई कमांड हो सकती हैं) */
    const val MAX_TURNS = 45
    /** CI fail / QA कमी के बाद सुधार-राउंड में अधिकतम जवाब */
    const val FIX_TURNS = 30
    /** इतने जवाब बचें तो एजेंट को याद दिलाओ कि अब काम समेटे */
    const val WARN_LEFT = 4
    /** खोज के अधिकतम नतीजे */
    const val GREP_MAX = 80
    /** सूची में अधिकतम path */
    const val LIST_MAX = 400

    sealed class Act {
        data class Read(val path: String, val from: Int? = null, val to: Int? = null) : Act()
        data class Grep(val pattern: String, val under: String = "") : Act()
        data class ListFiles(val prefix: String = "") : Act()
        /** मौजूदा फ़ाइल में FIND -> REPLACE (कई जोड़े) */
        data class Edit(val path: String, val pairs: List<Pair<String, String>>) : Act()
        /** नई फ़ाइल / पूरी फ़ाइल दोबारा */
        data class Write(val path: String, val content: String) : Act()
        data class Delete(val path: String) : Act()
        object Check : Act()
        /** title = commit/PR का छोटा अंग्रेज़ी नाम, summary = user के लिए हिंदी में क्या किया */
        data class Done(val title: String, val summary: String) : Act()
        data class Ask(val question: String) : Act()
        /** जवाब का कोई हिस्सा ढाँचे में नहीं था */
        data class Bad(val why: String) : Act()
    }

    private val CMD = Regex("""^(READ|GREP|LIST|FILE|DELETE|CHECK|DONE|ASK)\b:?\s*(.*)$""")

    private fun cleanPath(p: String) = p.trim().trim('`', '"', '\'').removePrefix("./").removePrefix("/")

    /**
     * AI का जवाब -> कमांड की सूची (क्रम से). FIND/REPLACE/FILE के घेरे [Seven.parseEdit] वाले ही
     * (<<<FIND ... FIND>>>, <<<REPLACE ... REPLACE>>>, <<<FILE ... FILE>>>), ताकि कोड के अंदर कुछ भी हो, घेरा पक्का रहे.
     */
    fun parse(text: String): List<Act> {
        val out = mutableListOf<Act>()
        var file: String? = null
        val pairs = mutableListOf<Pair<String, String>>()
        var full: String? = null
        var pendingFind: String? = null
        var mode = ""
        val buf = StringBuilder()
        var first = true
        var done: Pair<String, StringBuilder>? = null

        fun flushFile() {
            val f = file
            if (f == null) { pairs.clear(); full = null; pendingFind = null; return }
            when {
                pendingFind != null -> out.add(Act.Bad("$f: FIND के बाद REPLACE नहीं आया (दोनों ज़रूरी हैं)"))
                full != null -> out.add(Act.Write(f, full!!))
                pairs.isNotEmpty() -> out.add(Act.Edit(f, pairs.toList()))
                else -> out.add(Act.Bad("$f: FILE के बाद कोई <<<FIND/<<<REPLACE या <<<FILE नहीं मिला"))
            }
            file = null; pairs.clear(); full = null; pendingFind = null
        }
        fun flushDone() {
            done?.let { (t, s) -> out.add(Act.Done(t.trim(), s.toString().trim())) }
            done = null
        }

        for (raw in text.replace("\r\n", "\n").split("\n")) {
            val t = raw.trim()
            if (mode.isNotEmpty()) {
                val end = when (mode) { "FIND" -> "FIND>>>"; "REPLACE" -> "REPLACE>>>"; else -> "FILE>>>" }
                if (t == end) {
                    val body = buf.toString()
                    when (mode) {
                        "FIND" -> pendingFind = body
                        "REPLACE" -> { pendingFind?.let { pairs.add(it to body) }; pendingFind = null }
                        else -> full = body
                    }
                    mode = ""
                } else {
                    if (!first) buf.append('\n')
                    buf.append(raw); first = false
                }
                continue
            }
            when {
                t == "<<<FIND" || t == "<<<REPLACE" || t.startsWith("<<<FILE") -> {
                    if (file == null) { out.add(Act.Bad("<<<${t.removePrefix("<<<").substringBefore(' ')} से पहले FILE: path नहीं लिखा")) }
                    if (t == "<<<REPLACE" && pendingFind == null) out.add(Act.Bad("${file ?: "?"}: REPLACE बिना FIND के"))
                    mode = when { t == "<<<FIND" -> "FIND"; t == "<<<REPLACE" -> "REPLACE"; else -> "FILE" }
                    buf.setLength(0); first = true
                }
                else -> {
                    val m = CMD.find(t)
                    if (m == null) {
                        // DONE के बाद की लाइनें = user के लिए सारांश
                        done?.second?.let { s -> if (t.isNotEmpty() && !t.startsWith("```")) s.append(t).append('\n') }
                        continue
                    }
                    val cmd = m.groupValues[1]; val arg = m.groupValues[2].trim()
                    flushFile(); flushDone()
                    when (cmd) {
                        "READ" -> arg.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { e ->
                            val r = SevenDebug.parseRead(cleanPath(e))
                            out.add(Act.Read(cleanPath(r.path), r.from, r.to))
                        }
                        "GREP" -> {
                            val inAt = arg.lastIndexOf(" IN:")
                            if (inAt > 0) out.add(Act.Grep(arg.substring(0, inAt).trim(), cleanPath(arg.substring(inAt + 4))))
                            else if (arg.isNotEmpty()) out.add(Act.Grep(arg))
                        }
                        "LIST" -> out.add(Act.ListFiles(cleanPath(arg)))
                        "FILE" -> if (arg.isNotEmpty()) file = cleanPath(arg) else out.add(Act.Bad("FILE: के बाद path नहीं"))
                        "DELETE" -> if (arg.isNotEmpty()) out.add(Act.Delete(cleanPath(arg)))
                        "CHECK" -> out.add(Act.Check)
                        "DONE" -> done = arg to StringBuilder()
                        "ASK" -> out.add(Act.Ask(arg))
                    }
                }
            }
        }
        if (mode.isNotEmpty()) out.add(Act.Bad("${file ?: "?"}: <<<$mode का घेरा बंद नहीं हुआ ($mode>>> नहीं मिला) — जवाब शायद बीच में कट गया, छोटे हिस्सों में भेजें"))
        else { flushFile(); flushDone() }
        return out
    }

    /** बदलाव लागू करने का नतीजा: content = नई फ़ाइल (null = कुछ लागू नहीं), errors = क्यों नहीं */
    data class Applied(val content: String?, val errors: List<String>)

    /**
     * FIND/REPLACE क्रम से. हर FIND फ़ाइल में ठीक एक जगह होना चाहिए. हूबहू न मिले तो लाइनों के आगे-पीछे की खाली जगह
     * छोड़कर मिलाओ (AI अक्सर indentation बदल देता है) - वह भी ठीक एक जगह हो तभी.
     * कोई भी जोड़ा न लगे तो पूरी फ़ाइल वैसी ही (आधा बदलाव कभी नहीं).
     */
    fun apply(cur: String, pairs: List<Pair<String, String>>): Applied {
        var now = cur
        val errs = mutableListOf<String>()
        pairs.forEachIndexed { i, (find, rep) ->
            if (find.isBlank()) { errs.add("बदलाव ${i + 1}: FIND खाली है"); return@forEachIndexed }
            val n = count(now, find)
            when {
                n == 1 -> now = now.replaceFirst(find, rep)
                n > 1 -> errs.add("बदलाव ${i + 1}: FIND फ़ाइल में $n जगह मिला — ऊपर-नीचे की 2-3 लाइनें और जोड़कर उसे एक जगह का बनाएँ")
                else -> {
                    val loose = looseReplace(now, find, rep)
                    if (loose != null) now = loose
                    else errs.add("बदलाव ${i + 1}: FIND फ़ाइल में नहीं मिला (फ़ाइल दोबारा READ करके हूबहू कॉपी करें). FIND की पहली लाइन: " +
                        find.lines().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(120))
                }
            }
        }
        return if (errs.isEmpty()) Applied(now, emptyList()) else Applied(null, errs)
    }

    private fun count(hay: String, needle: String): Int {
        var n = 0; var i = hay.indexOf(needle)
        while (i >= 0) { n++; if (n > 1) return n; i = hay.indexOf(needle, i + 1) }
        return n
    }

    /** लाइनों की आगे-पीछे की खाली जगह छोड़कर FIND ढूँढो; ठीक एक जगह मिले तो वे लाइनें REPLACE से बदलो */
    internal fun looseReplace(cur: String, find: String, rep: String): String? {
        val lines = cur.split("\n")
        val want = find.split("\n").map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (want.isEmpty()) return null
        var at = -1
        for (s in 0..(lines.size - want.size)) {
            var ok = true
            for (k in want.indices) if (lines[s + k].trim() != want[k]) { ok = false; break }
            if (ok) { if (at >= 0) return null; at = s }
        }
        if (at < 0) return null
        val out = ArrayList<String>(lines.size)
        out.addAll(lines.subList(0, at))
        out.addAll(rep.split("\n"))
        out.addAll(lines.subList(at + want.size, lines.size))
        return out.joinToString("\n")
    }

    /** पंक्ति-नंबर वाली फ़ाइल (या उसका हिस्सा). maxChars से बड़ी हो तो आगे का हिस्सा कैसे पढ़ें, यह भी लिखो */
    fun numbered(path: String, content: String, from: Int? = null, to: Int? = null, maxChars: Int = 60_000): String {
        val lines = content.split("\n")
        val a = (from ?: 1).coerceIn(1, maxOf(1, lines.size))
        val b = (to ?: lines.size).coerceIn(a, maxOf(a, lines.size))
        val sb = StringBuilder("----- BEGIN $path (lines $a-$b of ${lines.size}) -----\n")
        var last = a - 1
        for (i in a..b) {
            val l = "$i| ${lines[i - 1]}\n"
            if (sb.length + l.length > maxChars) break
            sb.append(l); last = i
        }
        if (last < b) sb.append("(cut at line $last to save space — read the rest with READ: $path:${last + 1}-$b)\n")
        sb.append("----- END $path -----")
        return sb.toString()
    }

    /**
     * रिपो में खोज (rg जैसी): pattern regex हो तो regex, वरना सीधा शब्द. under = path का शुरुआती हिस्सा.
     * @return "path:line: text" लाइनें
     */
    fun grep(ws: Map<String, String>, pattern: String, under: String = "", max: Int = GREP_MAX): List<String> {
        if (pattern.isBlank()) return emptyList()
        val rx = try { Regex(pattern) } catch (_: Exception) { Regex(Regex.escape(pattern)) }
        val out = mutableListOf<String>()
        for (p in ws.keys.sorted()) {
            if (under.isNotEmpty() && !p.startsWith(under)) continue
            val src = ws[p] ?: continue
            if (src.length > 2_000_000) continue
            src.split("\n").forEachIndexed { i, l ->
                if (out.size < max && rx.containsMatchIn(l)) out.add("$p:${i + 1}: ${l.trim().take(220)}")
            }
            if (out.size >= max) break
        }
        return out
    }

    /** पहली बार का काम: user के शब्द, मंज़ूर समझ, रिपो का नक्शा. एजेंट को पूरी तस्वीर एक ही बार में */
    fun firstOrder(task: String, brief: String, spec: String, acceptance: List<String>, notTouch: List<String>,
                   github: Boolean, fileCount: Int, map: String): String = buildString {
        append("USER TASK (the user's own words - do ALL of it, exactly as asked):\n").append(task.trim()).append("\n\n")
        if (brief.isNotBlank()) append("UNDERSTANDING: ").append(brief.trim()).append('\n')
        if (spec.isNotBlank() && spec.trim() != brief.trim()) append("SPEC: ").append(spec.trim()).append('\n')
        if (acceptance.isNotEmpty()) append("DONE MEANS (every point must be true at the end):\n").append(acceptance.joinToString("\n") { "- $it" }).append('\n')
        if (notTouch.isNotEmpty()) append("DO NOT CHANGE: ").append(notTouch.joinToString("; ")).append('\n')
        append("\nMODE: ").append(if (github) "GitHub repo - your changes are pushed to a feature branch and real CI (compile + unit tests + build) runs after DONE" else "normal - the user's files only, no CI")
        append("\nFILES IN WORKSPACE: ").append(fileCount).append('\n')
        if (map.isNotBlank()) append("\nREPO MAP (where things are):\n").append(map.trim()).append('\n')
        append("\nStart now: find and READ the relevant code first (LIST / GREP / READ), then make the complete change, CHECK, and DONE.")
    }

    /** CI / पक्की जाँच fail: असली गलती उसी बातचीत में, जड़ से ठीक करने को */
    fun failOrder(round: Int, errs: String, log: String, url: String, extra: String, tried: List<String>, repeat: Boolean): String = buildString {
        append(if (log.startsWith("STATIC CHECK FAILED")) "PRE-PUSH CHECK FAILED" else "BUILD / TESTS FAILED on GitHub Actions")
        append(" (fix round $round). Your work is NOT finished yet.\n")
        if (url.isNotBlank()) append("Run: $url\n")
        if (errs.isNotBlank()) append("\nEXACT ERROR LINES:\n").append(errs).append('\n')
        if (extra.isNotBlank()) append('\n').append(extra.trim()).append('\n')
        if (repeat && tried.isNotEmpty()) append("\nTHE SAME PLACE FAILED AGAIN. These were already tried and did NOT work - find a different, real root cause:\n")
            .append(tried.takeLast(4).joinToString("\n") { "- $it" }).append('\n')
        append("\nLOG (important part):\n").append(log.trim()).append('\n')
        append("\nREAD the exact failing lines first, find the ROOT CAUSE, fix it in the code (never weaken or delete a test to make it pass), CHECK, then DONE.")
    }

    /**
     * 25 नवंबर 2026 (मालिक: "काम एकदम स्टेप बाय स्टेप... खुद ही काम करे, फिर चेक करे, फिर रिव्यू करे, डीबग करके पूरा फिनिश करे"):
     * एक ही एजेंट के कदम - स्क्रीन पर पहले ही दिखते हैं, ताकि user को पता रहे अभी कौन-सा कदम चल रहा है.
     */
    fun phases(github: Boolean): List<String> = listOfNotNull(
        "काम समझना और रिपो में सिर्फ़ इसी काम से जुड़ा कोड खोजकर पढ़ना (कहाँ-कहाँ क्या है)",
        "कदम-दर-कदम पूरा बदलाव (हर ज़रूरी फ़ाइल में)",
        "खुद जाँच (CHECK: नाम, import, resource, पुराने tests)",
        "खुद रिव्यू: अपना पूरा diff दोबारा पढ़कर हर माँगी बात और हर हटाई लाइन जाँचना, गलती मिली तो वहीं ठीक",
        if (github) "GitHub पर असली build + सारे unit tests; fail = log पढ़कर जड़ से ठीक (debug), पास होने तक" else null,
        "आख़िरी रिपोर्ट: कौन-सी फ़ाइल बदली, क्या बदला, कैसे इस्तेमाल/जाँचें" + if (github) ", और screenshot" else ""
    )

    /** कदमों की सूची, स्क्रीन के लिए */
    fun phasesText(github: Boolean): String = "🪜 मेरे कदम (एक-एक करके):\n" + phases(github).mapIndexed { i, x -> "${i + 1}. $x" }.joinToString("\n")

    /**
     * एक फ़ाइल का छोटा diff (AI के खुद-रिव्यू के लिए): शुरू/आख़िर की एक जैसी लाइनें छोड़कर बीच का बदला हिस्सा,
     * "- " = हटी लाइन, "+ " = नई लाइन. old null = नई फ़ाइल, new null = फ़ाइल हटाई.
     */
    fun miniDiff(path: String, old: String?, new: String?, max: Int = 6_000): String {
        if (new == null) return "=== $path (DELETED) ==="
        val a = old?.split("\n").orEmpty()
        val b = new.split("\n")
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val gone = a.subList(pre, a.size - suf)
        val added = b.subList(pre, b.size - suf)
        val sb = StringBuilder("=== $path " + (if (old == null) "(NEW FILE)" else "(changed around line ${pre + 1})") + " ===\n")
        for (l in gone) { if (sb.length > max) break; sb.append("- ").append(l).append('\n') }
        for (l in added) { if (sb.length > max) break; sb.append("+ ").append(l).append('\n') }
        if (sb.length > max) sb.append("(diff cut - READ the file to see the rest)\n")
        return sb.toString().trimEnd()
    }

    /** बदली हुई लाइनों की गिनती (बड़ा बदलाव = DONE से पहले अलग से खुद-रिव्यू ज़रूरी) */
    fun changedLines(diff: String): Int = diff.lines().count { it.startsWith("- ") || it.startsWith("+ ") }

    /** इतना बड़ा बदलाव हो तो DONE से पहले एक पूरा खुद-रिव्यू राउंड (छोटे बदलाव में CHECK के साथ ही diff दिखता है) */
    fun needsOwnReview(files: Int, lines: Int): Boolean = files >= 2 || lines >= 30

    /** खुद-रिव्यू का आदेश: अपना पूरा diff + जाँचने की सूची. वही एजेंट, वही बातचीत */
    fun reviewOrder(diffs: String, acceptance: List<String>): String = buildString {
        append("SELF-REVIEW (you are the reviewer and debugger of your own work now). Your full change so far:\n\n")
        append(diffs.trim()).append("\n\n")
        append("Check it line by line before finishing:\n")
        append("1. Every point of the USER TASK")
        if (acceptance.isNotEmpty()) append(" and DONE MEANS (").append(acceptance.joinToString("; ").take(600)).append(")")
        append(" is really implemented AND wired (reachable from the app UI / called from real code), not just written.\n")
        append("2. Every '- ' line: was removing it really needed? No old feature, setting, string or test may break.\n")
        append("3. Bugs: wrong names/imports, null cases, missing strings/resources/manifest entries, main-thread work, missing unit test for new logic.\n")
        append("4. Nothing extra that the user did not ask for.\n")
        append("If anything is wrong: fix it now (READ / FILE + FIND/REPLACE), CHECK, then DONE. If everything is correct: reply DONE again.")
    }

    /** CHECK साफ़ होने पर उसी नतीजे में जुड़ने वाली छोटी याद (बिना अलग AI कॉल) */
    const val REVIEW_HINT = "Before DONE: review this diff yourself (every task point wired end to end, every '- ' line intended, no old feature broken). Fix anything wrong, otherwise DONE."

    /** AI ने कोई कमांड नहीं भेजी */
    const val NO_ACTION = "Your reply had no command. Reply ONLY with commands (READ / GREP / LIST / FILE + <<<FIND/<<<REPLACE or <<<FILE / DELETE / CHECK / DONE / ASK)."

    fun turnsLeft(left: Int) = "\n(REPLIES LEFT IN THIS ROUND: $left - finish the change now, then CHECK and DONE.)"

    val SYSTEM = """You are DK's senior software engineer. You do the WHOLE coding task ALONE, end to end, like an expert who searches and reads the code before touching it. Nobody else plans, reviews or fixes for you - you own the result. The user hates half work and repeated breakage: finish the task completely and correctly in one go.

HOW YOU WORK
1. Understand exactly what the user asked (their own words win over any summary). Do ALL of it - every point - nothing extra that was not asked.
2. Explore first: LIST / GREP / READ to find every place involved (UI, logic, settings, strings, wiring, tests). Read whole functions and their callers. NEVER guess a name, signature, import, id or resource - look it up.
3. Change the code completely: every file that needs it, wired end to end (the feature must be reachable and work, not just compile). Follow the existing style of the file.
4. CHECK, fix whatever it reports. CHECK also shows YOUR OWN DIFF: review it like a strict reviewer (every task point done and wired, every removed line intended, no old feature broken) and debug anything wrong. Only then DONE. After DONE the system runs strict checks and (in GitHub mode) the real CI build + unit tests; any failure comes back to you in this same conversation - then fix the ROOT CAUSE.

COMMANDS (reply with ONLY commands; you may send many in one reply, e.g. several READs at once; results come in the next message)
READ: path                      whole file with line numbers
READ: path:120-260              only those lines (use for big files)
GREP: regex                     search the whole repo  (GREP: regex IN: app/src/main)
LIST: path/prefix               list files
FILE: path                      then one or more exact FIND/REPLACE pairs:
<<<FIND
exact current lines copied from READ (without the line numbers), unique in the file
FIND>>>
<<<REPLACE
new lines
REPLACE>>>
FILE: path                      new file or full rewrite (only for new files or small files you READ fully):
<<<FILE
whole content
FILE>>>
DELETE: path
CHECK                           strict checks now (syntax, names that do not exist, missing resources, protected files, old tests)
DONE: Short English title for the commit and PR
Then 3-10 lines in simple Hindi for the user, in this order:
  बदली फ़ाइलें: each changed file and what changed in it (one short line each)
  कैसे इस्तेमाल करें: where it is in the app and how to use / see it, step by step
  कैसे जाँचा: what you checked (CHECK, self-review, test you added)
ASK: question                   ONLY if it is truly impossible to continue (a secret, a file only the user has). Otherwise decide yourself, sensibly.

RULES
- FIND must match the CURRENT file exactly once. Prefer several small FIND/REPLACE pairs over rewriting a big file. After an edit fails, READ the file again before retrying.
- Images, icons, wallpapers, backgrounds: you CANNOT create png/webp/jpg/mp3/font files. Make Android vector drawables (res/drawable/name.xml with <vector>), <shape>/<layer-list>/<gradient> XML, or draw them in code (Canvas/GradientDrawable). Never reference a resource (R.drawable.x, @string/x ...) that does not exist - create it in the same work.
- Never touch .github/workflows, AGENTS.md, gradle.properties, settings.gradle*, gradlew or signing files unless the user asked for exactly that file.
- Never delete or weaken an existing test (only tests of a feature the user asked to remove). When behaviour changes or is new, ADD a unit test for it.
- Keep every old feature working. Do not rename or remove public functions others use without updating every caller (GREP for them).
- Kotlin: every name you use must exist (GREP it), imports must be right, braces balanced, never write a slash-star inside a KDoc comment, string templates need a dollar sign only when meant.
- User-visible text in the app: simple Hindi (Devanagari), like the rest of the app. No raw exception text in toasts.
- Screenshots: when you add or change a screen, also write .github/ui/extra_shots.sh (bash lines run on the emulator by .github/ui/shots.sh with ${'$'}tag set) that open the new screen and take a picture, using the existing helpers: go .SomeActivity [--es section x] ; tap "visible text" ; shot ${'$'}{tag}_20_name 4 . Keep it to a few lines; replace the old content of that file.
- Do not stop early. DONE only when every point of the task is really implemented and CHECK is clean."""
}

/**
 * नवंबर 2026 (मालिक: "जो काम दूँ, तुरंत करे"): एक-दिमाग मोड में साफ़ काम (कोई सवाल नहीं) = "हाँ" का इंतज़ार किए बिना
 * सीधे शुरू. main फिर भी अछूता (नई branch + PR) और merge सिर्फ़ user की हाँ से. सवाल हो = पहले पूछो.
 * पुराना 7 एजेंट मोड = पहले जैसा, हर बार मंज़ूरी.
 */
object SoloStart {
    fun now(solo: Boolean, needsAnswer: Boolean): Boolean = solo && !needsAnswer
}
