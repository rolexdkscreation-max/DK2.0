package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * 7-एजेंट कोडिंग सिस्टम के नियम (Android पर निर्भर नहीं - पूरी तरह unit test होते हैं).
 *
 *  1. मैनेजर   - काम समझे (नया फ़ीचर / बग / सवाल), क्रम से काम सौंपे, अटके तो रणनीति बदले, आख़िर में रिपोर्ट
 *  2. प्लानर   - फ़ाइलें पूरी पढ़े, छोटे कदम (एक कदम = एक फ़ाइल, एक बदलाव), पुराने फ़ीचर के ख़तरे पहले लिखे
 *  3. कोडर     - एक कदम: <<<FIND … FIND>>> + <<<REPLACE … REPLACE>>> (दोनों ज़रूरी)
 *  4. रिव्यूअर - लागू होने से पहले जाँच; गलत बदलाव कभी लागू नहीं
 *  5. टेस्टर   - सारे कदम के बाद test + build
 *  6. डिबगर    - user का बग या fail test: पहले असली जड़, फिर न्यूनतम सुधार
 *  7. गिट      - (सिर्फ़ GitHub मोड) backup branch, नई branch, PR, मंज़ूरी के बाद ही merge
 *  8. प्रॉम्प्ट इंजीनियर - user की हिंदी/Hinglish/आवाज़ वाली बात को साफ़ spec में बदले; समझ user को दिखाकर "हाँ" का इंतज़ार
 *  9. परफॉरमेंस - बदले code में UI जाम, leak, बेवजह बार-बार चलने वाला काम पकड़े
 * 10. QA       - build पास होने के बाद: हर शर्त सच में पूरी? अधूरा/नकली code नहीं? पुराने फ़ीचर सलामत?
 * 11. डिप्लॉयमेंट - user की "हाँ" के बाद: दोबारा जाँच → merge → APK build → release में APK पक्का
 */
object Seven {

    // ------------------------------------------------------------------ एजेंट
    enum class Agent(val hi: String, val icon: String) {
        MANAGER("मैनेजर", "👔"), PLANNER("प्लानर", "🗺"), CODER("कोडर", "⌨"), REVIEWER("रिव्यूअर", "🔍"),
        TESTER("टेस्टर", "🧪"), DEBUGGER("डिबगर", "🐞"), GIT("गिट", "🌿"),
        PROMPT("प्रॉम्प्ट इंजीनियर", "✍"), PERF("परफॉरमेंस", "⚡"), QA("QA", "✅"), DEPLOY("डिप्लॉयमेंट", "🚀")
    }

    /** हर एजेंट का अलग छोटा system prompt - उसका एक ही काम */
    object Prompts {
        val MANAGER = """You are the MANAGER of a 7-agent coding team. Your ONLY job now: understand the user's request.
Decide: "feature" (new feature or requested change), "bug" (something is broken / error / crash), or "question" (no code change needed).
Reply ONLY JSON: {"kind":"feature|bug|question","brief":"1-3 English lines: exactly what must be done","acceptance":["2-5 short Hindi checks that prove it is done"],"answer":"Hindi answer ONLY if kind=question"}
You also own the final report: never call the work "done" before the build/PR check has passed; the report always has the PR link and the check status."""

        val PLANNER = """You are the PLANNER. Your ONLY job: read the relevant files and split the work into small steps. Never write code.
To read files reply ONLY: {"read":["path", ...]} (max 8 paths, exactly as in the file list). You will get their full content.
To FIND where something is (a screen text, function, variable, id, setting) reply ONLY: {"search":["word", ...]} (max 6). You get every matching path:line in the whole repository.
WHERE: never guess the file from its name. Use the REPO MAP and search, then READ the file before you plan a change in it. The "anchor" must be text you have SEEN in that file - it is checked, and a plan with a wrong anchor is sent back.
When you have read enough reply ONLY:
{"steps":[{"file":"path","action":"edit|create|delete","change":"ONE precise change in English","anchor":"short exact text from the file near the change (edit only)","why":"which function/screen this is and why the change belongs exactly here","uses":["path: name"]}],"risks":["Hindi: old feature that could break and why"]}
Rules: one step = one file + one change. Max 10 steps. Change only what the task needs. Keep every old feature working.
NAMES: for every function, class, property or variable from ANOTHER file (or another class) that a step will use, put it in that step's "uses" as "path: name".
List ONLY names you have actually READ in that file. Never guess a name from memory - if you have not read the file that defines it, read it first.
Never delete or weaken existing tests. Never plan changes to .github/workflows, gradle wrapper, signing/keystore files unless the task says so."""

        val CODER = """You are the CODER. Your ONLY job: implement ONE plan step in ONE file. No explanations.
For an EXISTING file reply exactly:
FILE: <path>
<<<FIND
exact existing lines, copied character-for-character from the file, enough lines to be unique
FIND>>>
<<<REPLACE
the new lines
REPLACE>>>
(max 3 FIND/REPLACE pairs, each FIND followed by its REPLACE). NEVER send REPLACE without FIND. Keep indentation exactly.
Use ONLY functions, classes, properties and variables that you can SEE in the file content or in DEFINITIONS given to you (or that you add in this same change).
Never invent or guess a name (e.g. do not write svc.speak() unless speak really exists on that type). If what you need is missing, create it in this step.
For a NEW file reply: FILE: <path> then <<<FILE on its own line, the complete content, then FILE>>> on its own line.
To DELETE a file reply: DELETE: <path>"""

        const val CODER_REWRITE = """MANAGER CHANGED STRATEGY: the FIND approach failed. Rewrite the WHOLE file instead:
FILE: <path>
<<<FILE
complete new file content - keep EVERY other line exactly as it is, change only this step
FILE>>>"""

        val REVIEWER = """You are the REVIEWER. Your ONLY job: check ONE proposed change BEFORE it is applied.
Automatic checks already passed (FIND and REPLACE present, FIND matches the file exactly and once, brackets/syntax).
Check: 1) it does the step correctly and completely 2) it cannot break an existing feature (removed/renamed code still used elsewhere, changed behaviour, deleted tests)
3) compile errors (missing import, wrong types, undefined names, wrong API)
4) NAME CHECK: every function/class/variable the new code calls must really exist. If a NAME CHECK section lists a name that was not found, reject unless you are 100% sure it exists (e.g. a real Android/Java SDK method of the parent class).
Reply ONLY JSON: {"pass":true|false,"problems":["exact problem + how to fix"]}"""

        val TESTER = """You are the TESTER. Your ONLY job: decide whether the changed code works for the task.
You cannot run the code, so trace it carefully: list the important test cases (normal + edge) and walk through the code for each.
Reply ONLY JSON: {"pass":true|false,"cases":["Hindi: case → result"],"error":"if fail: the exact failing case, file and line, and the wrong result"}
Say pass only if EVERY case passes. (In GitHub mode the real build runs on the branch first; a PR is opened only after it passes.)"""

        val DEBUGGER = """You are the DEBUGGER. Your ONLY job: find the REAL root cause of a reported bug or a failed test/build. Never guess, never patch the symptom.
To read files reply ONLY: {"read":["path", ...]} (max 8). When you are sure reply ONLY:
{"root_cause":"file:line - what is wrong and WHY it happens","bug":"Hindi: बग क्या था","where":"file:line","fix":"the MINIMAL fix in English: which file, what change","files":["paths to change"]}
For a failed build/check: start from the EXACT error lines of the log (file:line + message); your root cause must explain THAT error. Read the files it points to before deciding."""

        val GIT = """You are the GIT agent. Your ONLY job: write a short commit message and PR title for the finished change.
Reply ONLY JSON: {"commit":"short English commit message","title":"short PR title (Hindi or English)"}
(The app opens the PR only after the build passes, and never merges without the user's approval.)"""

        val PROMPT = """You are the PROMPT ENGINEER. Your ONLY job: turn the user's request (often Hindi / Hinglish / spoken, short or messy) into a precise task that the team cannot misunderstand. Never write code.
Work out what the user REALLY means (the intent, not only the words). Find WHERE in this repository it belongs: use the REPO MAP, and if you are not sure reply ONLY {"search":["word", ...]} (names, screen texts, ids; max 6) or {"read":["path", ...]} (max 6) - you will get the results.
When you understand, reply ONLY JSON:
{"understood":"Hindi, 2-5 simple lines: what the user wants, in which screen/feature, and what will change for them","spec":"English: exact behaviour, the screen/feature and the files/functions where it belongs, edge cases, what must NOT change","acceptance":["2-6 short Hindi checks that prove it is done"],"not_touch":["Hindi: existing features that must stay exactly as they are"],"area":["path - why"],"questions":["Hindi question"]}
"questions": ONLY if something important is really unclear and you cannot decide it from the code (max 3, simple Hindi, each with the options you see). If it is clear, give an empty list - do not ask for the sake of asking."""

        val PERF = """You are the PERFORMANCE agent. Your ONLY job: check the changed code for speed / battery / memory problems. Never rewrite features.
Look only at the NEW / CHANGED lines: network or disk work on the main (UI) thread, Thread.sleep / runBlocking on the UI thread, loops or timers that never stop (Handler / delay loop without stop on pause/destroy), listeners / callbacks / coroutines that are never removed or cancelled (leaks), work repeated without need (e.g. reading the same file or calling the network in a loop), very large in-memory copies.
Reply ONLY JSON: {"pass":true|false,"must":["Hindi: real problem that will hurt the user + file + English fix"],"should":["Hindi: small improvement (optional)"]}
Put something in "must" only if it is a real problem in the new code, not a style wish."""

        val QA = """You are the QA (quality assurance) agent. Your ONLY job: after the build/tests passed, check that the task is REALLY and COMPLETELY done the way the user meant.
You get: the user's request, the prompt engineer's spec, the acceptance checks, and the full diff. For EVERY acceptance check decide: done or not, with the file/line that proves it.
Reject if: a check is not met; the code only pretends (fake progress, hard-coded result, a function that is never called / never wired to the UI, a button with no action); something was left half-done (TODO, empty handler); an existing feature was changed or removed without the user asking.
Reply ONLY JSON: {"pass":true|false,"checks":["Hindi: शर्त → ✓/✗ + सबूत (file:line)"],"missing":["Hindi: what is missing / wrong + file"],"fix":"English: the minimal change that would make it complete"}"""

        val DEPLOY = """You are the DEPLOYMENT agent. Your ONLY job: release a new APK ONLY after the user said yes.
Checklist (done by the app, in this order): PR still open and without conflict -> if main moved, update the branch and run all tests again -> every check on the last commit green -> merge -> the release workflow (Build DK APK) runs on main (start it by hand if it did not start by itself) -> wait for it -> the newest GitHub release is from this merge and contains the APK -> tell the user the version and how to install.
Never merge or release without the user's yes. Never say "released" before the release with the APK really exists.
If asked, reply ONLY JSON: {"notes":"Hindi: 1-3 lines what is new for the user in this APK"}"""

        fun of(a: Agent) = when (a) {
            Agent.MANAGER -> MANAGER; Agent.PLANNER -> PLANNER; Agent.CODER -> CODER; Agent.REVIEWER -> REVIEWER
            Agent.TESTER -> TESTER; Agent.DEBUGGER -> DEBUGGER; Agent.GIT -> GIT
            Agent.PROMPT -> PROMPT; Agent.PERF -> PERF; Agent.QA -> QA; Agent.DEPLOY -> DEPLOY
        }
    }

    // ------------------------------------------------------------------ मोड
    enum class Mode { GITHUB, NORMAL }

    /** मोड का फ़ैसला: GITHUB / NORMAL, या BLOCKED (टोकन/रिपो की दिक्कत - user को बताओ, नॉर्मल मोड का विकल्प दो) */
    data class ModeDecision(val mode: Mode?, val problem: String = "") {
        val blocked get() = mode == null
    }

    /**
     * @param token GitHub token डला है?
     * @param tokenOk token जाँच (null = नहीं जाँचा / नेटवर्क, true = ठीक, false = गलत/expired)
     * @param repo चुना हुआ रिपो ("" = नहीं चुना)
     * @param repoOk रिपो जाँच (null = नहीं जाँचा, false = not found / इजाज़त नहीं)
     * @param wantNormal user ने खुद नॉर्मल मोड चुना
     */
    fun decideMode(token: Boolean, tokenOk: Boolean?, repo: String, repoOk: Boolean?, wantNormal: Boolean): ModeDecision = when {
        wantNormal || !token -> ModeDecision(Mode.NORMAL)
        tokenOk == false -> ModeDecision(null, "GitHub टोकन गलत है या expire हो गया (401)। Settings → कोडिंग एजेंट में नया token डालें — या नॉर्मल मोड में काम करें।")
        repo.isBlank() -> ModeDecision(Mode.NORMAL)
        repoOk == false -> ModeDecision(null, "रिपो \"$repo\" नहीं मिला (not found) या token को उसकी इजाज़त नहीं। दूसरा रिपो चुनें — या नॉर्मल मोड में काम करें।")
        else -> ModeDecision(Mode.GITHUB)
    }

    // ------------------------------------------------------------------ काम का प्रकार
    enum class Kind { FEATURE, BUG, QUESTION }

    fun kindOf(managerKind: String?, task: String): Kind = when (managerKind?.trim()?.lowercase()) {
        "bug" -> Kind.BUG
        "question" -> Kind.QUESTION
        "feature" -> if (SafePr.isFixTask(task) && !SafePr.changesOldFeature(task)) Kind.BUG else Kind.FEATURE
        else -> if (SafePr.isFixTask(task)) Kind.BUG else Kind.FEATURE
    }

    // ------------------------------------------------------------------ JSON
    /** AI के जवाब में पहला पूरा JSON object (```json घेरा या आगे-पीछे का text हो तो भी) */
    fun json(text: String): JSONObject? {
        val s = text.indexOf('{'); if (s < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in s until text.length) {
            val c = text[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return try { JSONObject(text.substring(s, i + 1)) } catch (_: Exception) { null } } }
        }
        return null
    }

    fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optString(it).trim().takeIf { s -> s.isNotBlank() } }

    // ------------------------------------------------------------------ प्लान
    data class Step(val file: String, val action: String, val change: String, val anchor: String = "", val uses: List<String> = emptyList(), val why: String = "")
    data class Plan(val steps: List<Step>, val risks: List<String>)

    const val MAX_STEPS = 10

    /** प्लानर का जवाब: पढ़ने की list (read) या प्लान (steps). दोनों नहीं = null */
    fun planReads(text: String): List<String>? = json(text)?.optJSONArray("read")?.let { strings(it).take(8) }?.takeIf { it.isNotEmpty() }

    fun parsePlan(text: String): Plan? {
        val o = json(text) ?: return null
        val arr = o.optJSONArray("steps") ?: return null
        val steps = (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val f = s.optString("file").trim().removePrefix("/").removePrefix("./")
            val act = s.optString("action", "edit").trim().lowercase().let { if (it in listOf("edit", "create", "delete")) it else "edit" }
            val ch = s.optString("change").trim()
            if (f.isBlank() || ch.isBlank() && act != "delete") null else Step(f, act, ch, s.optString("anchor").trim(), strings(s.optJSONArray("uses")).take(12), s.optString("why").trim())
        }
        if (steps.isEmpty()) return null
        return Plan(steps.take(MAX_STEPS), strings(o.optJSONArray("risks")))
    }

    // ------------------------------------------------------------------ कोडर का बदलाव
    data class Edit(val file: String, val pairs: List<Pair<String, String>>, val full: String?, val delete: Boolean, val errors: List<String>)

    /**
     * कोडर का जवाब पढ़ो: FILE: path + (<<<FIND…FIND>>> <<<REPLACE…REPLACE>>>)* या <<<FILE…FILE>>> या DELETE: path.
     * गलत ढाँचा (REPLACE बिना FIND, FIND बिना REPLACE, बंद न हुआ घेरा) = errors.
     */
    fun parseEdit(text: String, expectedFile: String): Edit {
        val errs = mutableListOf<String>()
        var file = ""
        var delete = false
        val pairs = mutableListOf<Pair<String, String>>()
        var full: String? = null
        var pendingFind: String? = null
        var mode = ""            // "", FIND, REPLACE, FILE
        val buf = StringBuilder()
        var first = true
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            val t = raw.trim()
            if (mode.isEmpty()) {
                when {
                    t.startsWith("FILE:") -> file = t.removePrefix("FILE:").trim().trim('`').removePrefix("./")
                    t.startsWith("DELETE:") -> { file = t.removePrefix("DELETE:").trim().trim('`').removePrefix("./"); delete = true }
                    t == "<<<FIND" -> { if (pendingFind != null) errs.add("FIND के बाद REPLACE नहीं आया"); mode = "FIND" }
                    t == "<<<REPLACE" -> { if (pendingFind == null) errs.add("REPLACE बिना FIND के भेजा — FIND (फ़ाइल का सटीक पुराना कोड) ज़रूरी है"); mode = "REPLACE" }
                    t.startsWith("<<<FILE") -> { mode = "FILE"; t.removePrefix("<<<FILE").trim().takeIf { it.isNotBlank() && file.isBlank() }?.let { file = it } }
                }
                buf.setLength(0); first = true
                continue
            }
            val end = when (mode) { "FIND" -> "FIND>>>"; "REPLACE" -> "REPLACE>>>"; else -> "FILE>>>" }
            if (t == end) {
                val body = buf.toString()
                when (mode) {
                    "FIND" -> pendingFind = body
                    "REPLACE" -> { pendingFind?.let { pairs.add(it to body) }; pendingFind = null }
                    "FILE" -> full = body
                }
                mode = ""; continue
            }
            if (!first) buf.append('\n')
            buf.append(raw); first = false
        }
        if (mode.isNotEmpty()) errs.add("<<<$mode का घेरा बंद नहीं हुआ ($mode>>> नहीं मिला)")
        if (pendingFind != null) errs.add("FIND के बाद REPLACE नहीं आया — दोनों हिस्से ज़रूरी हैं")
        if (file.isBlank()) file = expectedFile
        if (!delete && pairs.isEmpty() && full == null && errs.isEmpty()) errs.add("कोई बदलाव नहीं मिला — FILE: path और <<<FIND/<<<REPLACE (या <<<FILE) भेजें")
        return Edit(file, pairs, full, delete, errs)
    }

    // ------------------------------------------------------------------ रिव्यूअर की अपनी (बिना AI) जाँच
    data class Check(val ok: Boolean, val content: String?, val problems: List<String>, val findMiss: Boolean = false)

    const val MAX_PAIRS = 3

    /**
     * लागू होने से पहले की पक्की जाँच. current = फ़ाइल अभी (null = मौजूद नहीं).
     * allowRewrite = मैनेजर ने "पूरी फ़ाइल दोबारा लिखो" रणनीति दी है.
     */
    fun check(e: Edit, step: Step, current: String?, allowRewrite: Boolean): Check {
        if (e.errors.isNotEmpty()) return Check(false, null, e.errors)
        if (e.file != step.file) return Check(false, null, listOf("गलत फ़ाइल: कदम ${step.file} का है, बदलाव ${e.file} में भेजा"))
        if (e.delete) return if (current == null) Check(false, null, listOf("${e.file} मौजूद ही नहीं")) else
            if (step.action != "delete") Check(false, null, listOf("प्लान में यह फ़ाइल हटाने का कदम नहीं है")) else Check(true, null, emptyList())
        val full = e.full
        if (full != null) {
            if (current != null && !allowRewrite) return Check(false, null, listOf("मौजूदा फ़ाइल के लिए <<<FIND/<<<REPLACE भेजें (पूरी फ़ाइल सिर्फ़ मैनेजर के कहने पर)"))
            if (full.isBlank()) return Check(false, null, listOf("फ़ाइल खाली है"))
            if (current != null && full.lines().size < current.lines().size / 2 && step.action != "delete")
                return Check(false, null, listOf("पूरी फ़ाइल दोबारा लिखने में आधी से ज़्यादा लाइनें गायब (${current.lines().size} → ${full.lines().size}) — पुराना कोड वैसा ही रखें"))
            return syntaxGate(e.file, current, full)
        }
        if (current == null) return Check(false, null, listOf("${e.file} मौजूद नहीं — नई फ़ाइल के लिए <<<FILE … FILE>>> भेजें"))
        if (e.pairs.size > MAX_PAIRS) return Check(false, null, listOf("एक कदम में ज़्यादा से ज़्यादा $MAX_PAIRS FIND/REPLACE — बाकी अगला कदम"))
        var work: String = current
        val probs = mutableListOf<String>()
        var miss = false
        e.pairs.forEachIndexed { i, (find, rep) ->
            val n = i + 1
            if (find.isBlank()) { probs.add("FIND #$n खाली है"); return@forEachIndexed }
            if (find == rep) { probs.add("FIND #$n और REPLACE #$n एक जैसे — कोई बदलाव नहीं"); return@forEachIndexed }
            val count = occurrences(work, find)
            when {
                count == 0 -> { miss = true; probs.add("FIND #$n फ़ाइल में हू-ब-हू नहीं मिला" + nearHint(work, find)) }
                count > 1 -> probs.add("FIND #$n फ़ाइल में $count जगह मिला — ऊपर/नीचे की 1-2 लाइनें और जोड़ें ताकि एक ही जगह मिले")
                else -> work = work.replaceFirst(find, rep)
            }
        }
        if (probs.isNotEmpty()) return Check(false, null, probs, miss)
        return syntaxGate(e.file, current, work)
    }

    private fun syntaxGate(path: String, before: String?, after: String): Check {
        val err = Syntax.check(path, after) ?: return Check(true, after, emptyList())
        // पहले से वही गड़बड़ (हमारी जाँच की सीमा) हो तो कोडर को दोष नहीं
        if (before != null && Syntax.check(path, before) != null) return Check(true, after, emptyList())
        return Check(false, null, listOf("$path: syntax गलती — $err"))
    }

    fun occurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var c = 0; var i = hay.indexOf(needle)
        while (i >= 0) { c++; i = hay.indexOf(needle, i + 1) }
        return c
    }

    /** FIND न मिले तो मदद: पहली लाइन फ़ाइल में कहाँ है (spaces/बाकी लाइनें अलग) */
    private fun nearHint(src: String, find: String): String {
        val firstLine = find.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        val lines = src.lines()
        val at = lines.indexOfFirst { it.trim() == firstLine }
        return if (at < 0) " (पहली लाइन भी फ़ाइल में नहीं है — फ़ाइल दोबारा ध्यान से पढ़कर सटीक कॉपी करें)"
        else " (पहली लाइन line ${at + 1} पर है, पर आगे की लाइनें/spaces अलग हैं — वहीं से हू-ब-हू कॉपी करें)"
    }

    // ------------------------------------------------------------------ रणनीति (मैनेजर)
    enum class Strategy { FIND, REWRITE }

    /** एक फ़ाइल इतनी बड़ी हो तो पूरी दोबारा लिखवाना ख़तरनाक (AI बीच में काट देता है) */
    const val REWRITE_MAX_CHARS = 30_000
    /** एक कदम पर ज़्यादा से ज़्यादा कोशिशें (कोडर ⇄ रिव्यूअर) */
    const val STEP_ATTEMPTS = 5

    /**
     * FIND दो बार न बने, या एक ही गलती लगातार 2 बार आए = वही तरीका दोबारा नहीं: पूरी फ़ाइल पढ़कर पूरी दोबारा लिखो
     * (backup के साथ). बहुत बड़ी फ़ाइल में rewrite नहीं - FIND ही, पर बड़े हिस्से के साथ.
     */
    fun strategy(errors: List<String>, findMisses: Int, fileChars: Int, fileExists: Boolean): Strategy {
        if (!fileExists || fileChars > REWRITE_MAX_CHARS) return Strategy.FIND
        val sameTwice = errors.size >= 2 && errors[errors.size - 1] == errors[errors.size - 2]
        return if (findMisses >= 2 || sameTwice) Strategy.REWRITE else Strategy.FIND
    }

    /** बड़ी फ़ाइल: anchor के आस-पास का हिस्सा (line नंबर बिना - FIND सीधे कॉपी हो सके) */
    fun window(src: String, anchor: String, radius: Int = 120): String {
        val lines = src.lines()
        // पहले पूरी anchor एक लाइन में; नहीं तो उसकी पहली लाइन (कई लाइन वाली anchor / spaces का फ़र्क)
        val at = if (anchor.isBlank()) -1 else lines.indexOfFirst { it.contains(anchor.trim()) }.takeIf { it >= 0 }
            ?: SevenDebug.anchorLine(src, anchor)?.minus(1) ?: -1
        if (at < 0) return lines.take(radius * 2).joinToString("\n")
        val a = (at - radius).coerceAtLeast(0); val b = (at + radius).coerceAtMost(lines.size)
        return lines.subList(a, b).joinToString("\n")
    }

    // ------------------------------------------------------------------ नॉर्मल मोड: user के भेजे कोड से फ़ाइलें
    private val langExt = mapOf("kotlin" to "kt", "kt" to "kt", "java" to "java", "javascript" to "js", "js" to "js", "typescript" to "ts", "ts" to "ts",
        "python" to "py", "py" to "py", "html" to "html", "css" to "css", "json" to "json", "xml" to "xml", "jsx" to "jsx", "tsx" to "tsx",
        "c" to "c", "cpp" to "cpp", "c++" to "cpp", "go" to "go", "rust" to "rs", "php" to "php", "dart" to "dart", "swift" to "swift", "sh" to "sh", "bash" to "sh", "sql" to "sql")

    /** ```lang [नाम] … ``` घेरे → (नाम, कोड). नाम: घेरे की पहली लाइन, या ठीक ऊपर की लाइन (File: x.js / 📄 x.js / `x.js`) */
    fun filesFromMessage(msg: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val lines = msg.replace("\r\n", "\n").split("\n")
        var i = 0; var n = 0
        val nameRx = Regex("""([\w./-]+\.[A-Za-z0-9]{1,6})""")
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("```")) {
                val info = l.removePrefix("```").trim()
                val body = StringBuilder(); var j = i + 1
                while (j < lines.size && !lines[j].trim().startsWith("```")) { if (body.isNotEmpty()) body.append('\n'); body.append(lines[j]); j++ }
                val lang = info.split(' ', ':').firstOrNull()?.lowercase().orEmpty()
                val prev = (i - 1 downTo maxOf(0, i - 2)).map { lines[it].trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                val name = nameRx.find(info.substringAfter(' ', ""))?.value
                    ?: nameRx.find(info)?.value?.takeIf { it.contains('.') && langExt[lang] == null }
                    ?: prev.takeIf { it.length < 120 }?.let { nameRx.find(it)?.value }
                    ?: "code${++n}.${langExt[lang] ?: "txt"}"
                out[name] = body.toString()
                i = j + 1; continue
            }
            i++
        }
        return out
    }

    /** नॉर्मल मोड का आउटपुट: हर फ़ाइल नाम के साथ अलग (सीधे कॉपी करने लायक) */
    fun formatFiles(files: Map<String, String?>): String = files.entries.joinToString("\n\n") { (p, c) ->
        if (c == null) "🗑 $p — यह फ़ाइल हटानी है"
        else "📄 $p\n```${langExt.entries.firstOrNull { it.value == p.substringAfterLast('.', "") }?.key ?: ""}\n$c\n```"
    }

    // ------------------------------------------------------------------ रिपोर्ट
    data class Bug(val what: String, val where: String, val rootCause: String, val fix: String)

    /**
     * PR से पहले branch पर चलाने लायक workflow: जो PR पर चलता है (pull_request) और हाथ से भी चल सकता है (workflow_dispatch).
     * @return workflow फ़ाइल का नाम, या null (तब draft PR पर build, पास होने पर ही असली PR)
     */
    fun dispatchable(workflows: List<Pair<String, String>>): String? = workflows.firstOrNull { (_, y) ->
        val t = y.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        Regex("\\bworkflow_dispatch\\b").containsMatchIn(t) && Regex("\\bpull_request\\b").containsMatchIn(t)
    }?.first

    /** चेक (GitHub Actions) की हालत - रिपोर्ट में हमेशा */
    enum class CheckState(val hi: String) { PASS("✅ पास"), FAIL("❌ फ़ेल"), RUNNING("⏳ नतीजा अभी नहीं मिला"), NONE("— नहीं चला") }

    /**
     * आख़िरी रिपोर्ट - user के माँगे ढाँचे में.
     * नियम (user): चेक पास होने से पहले "काम पूरा हुआ" नहीं; GitHub मोड में PR का लिंक और चेक की हालत हमेशा.
     */
    fun report(task: String, mode: Mode, changes: List<Pair<String, String>>, bugs: List<Bug>, testOk: Boolean?, testNote: String,
               prUrl: String, backup: String, check: CheckState? = null, checkUrl: String = "", prNote: String = ""): String = buildString {
        val gh = mode == Mode.GITHUB
        val chk = check ?: when (testOk) { true -> if (gh) CheckState.PASS else CheckState.NONE; false -> CheckState.FAIL; null -> CheckState.RUNNING }
        val done = testOk == true && (!gh || (chk == CheckState.PASS && prUrl.isNotBlank()))
        append("📋 रिपोर्ट (${if (gh) "GitHub मोड" else "नॉर्मल मोड"})\n")
        append(when {
            done -> "✅ काम पूरा हुआ"
            testOk == false || chk == CheckState.FAIL -> "❌ काम अभी पूरा नहीं हुआ (टेस्ट/चेक फ़ेल)"
            else -> "⏳ काम अभी पूरा नहीं हुआ (चेक का नतीजा नहीं मिला)"
        }).append("\n\n")
        append("• काम क्या था: ").append(task.trim().take(300)).append("\n\n")
        append("• क्या हुआ:\n")
        if (changes.isEmpty()) append("  - कोई फ़ाइल नहीं बदली\n") else changes.forEach { (f, w) -> append("  - $f — $w\n") }
        append("\n• बग / एरर: ")
        if (bugs.isEmpty()) append("कोई नहीं\n") else {
            append("\n"); bugs.forEach { b ->
                append("  - क्या था: ${b.what}\n  - जड़ कहाँ थी: ${b.where.ifBlank { "-" }} — ${b.rootCause}\n  - कैसे ठीक किया: ${b.fix}\n")
            }
        }
        append("\n• टेस्ट का नतीजा: ").append(when (testOk) { true -> "✅ पास"; false -> "❌ फ़ेल"; null -> "—" })
        if (testNote.isNotBlank()) append(" ($testNote)")
        append("\n")
        if (gh) {
            append("• PR: ").append(prUrl.ifBlank { "अभी नहीं बना" + if (prNote.isNotBlank()) " — $prNote" else " — build पास होने पर ही बनता है" }).append("\n")
            append("• चेक की हालत: ${chk.hi}").append(if (checkUrl.isNotBlank()) " — $checkUrl" else "").append("\n")
        }
        if (backup.isNotBlank()) append("\n• बैकअप: $backup\n")
        if (gh) {
            if (done) append("\n• मंज़ूरी: क्या मैं PR को merge कर दूँ?")
            else append("\n• main को हाथ नहीं लगाया — PR merge नहीं होगा जब तक चेक पास न हो")
        }
    }
}

/** फ़ाइल की syntax जाँच (बिना compiler): brackets / XML / JSON. null = ठीक */
object Syntax {
    private val cLike = setOf("kt", "kts", "java", "js", "mjs", "ts", "jsx", "tsx", "c", "h", "cpp", "hpp", "cs", "go", "swift", "dart", "gradle", "scala", "php", "css", "scss")

    fun check(path: String, src: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (src.contains("<<<FIND") || src.contains("FIND>>>") || src.contains("<<<REPLACE") || src.contains("REPLACE>>>") || src.contains("<<<FILE"))
            return "फ़ाइल में <<<FIND/REPLACE/FILE के निशान रह गए"
        return when (ext) {
            in cLike -> brackets(src, hashComments = ext == "php", templates = ext in setOf("kt", "kts"), backtick = ext in setOf("js", "mjs", "ts", "jsx", "tsx"))
            "py" -> brackets(src, hashComments = true, templates = false, backtick = false, cComments = false)
            "json" -> try { val t = src.trim(); if (t.startsWith("[")) JSONArray(t) else JSONObject(t); null } catch (e: Exception) { "JSON गलत: ${e.message?.take(150)}" }
            "xml" -> try {
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(src.byteInputStream()); null
            } catch (e: Exception) { "XML गलत: ${e.message?.take(150)}" }
            else -> null
        }
    }

    /** {} () [] का हिसाब - string / comment के अंदर वाले छोड़कर. null = ठीक */
    fun brackets(src: String, hashComments: Boolean = false, templates: Boolean = true, backtick: Boolean = false, cComments: Boolean = true): String? {
        val stack = ArrayDeque<Pair<Char, Int>>()
        val pairs = mapOf('}' to '{', ')' to '(', ']' to '[')
        var i = 0; var line = 1; val n = src.length
        while (i < n) {
            val ch = src[i]
            when {
                ch == '\n' -> line++
                cComments && src.startsWith("//", i) -> { while (i < n && src[i] != '\n') i++; continue }
                hashComments && ch == '#' -> { while (i < n && src[i] != '\n') i++; continue }
                cComments && src.startsWith("/*", i) -> { val e = src.indexOf("*/", i + 2); if (e < 0) return "/* comment बंद नहीं (line $line)"; line += src.substring(i, e).count { it == '\n' }; i = e + 2; continue }
                src.startsWith("\"\"\"", i) || (!cComments && src.startsWith("'''", i)) -> {
                    val q = src.substring(i, i + 3)
                    val e = src.indexOf(q, i + 3); if (e < 0) return "$q string बंद नहीं (line $line)"
                    line += src.substring(i, e).count { it == '\n' }; i = e + 3; while (i < n && src[i] == q[0]) i++; continue
                }
                backtick && ch == '`' -> { val e = src.indexOf('`', i + 1); if (e < 0) return "` string बंद नहीं (line $line)"; line += src.substring(i, e).count { it == '\n' }; i = e + 1; continue }
                ch == '"' || (ch == '\'' && !templates) -> {
                    val q = ch; i++; var depth = 0
                    while (i < n) {
                        val c = src[i]
                        if (c == '\\') { i += 2; continue }
                        if (c == '\n' && depth == 0) return "string line $line पर बंद नहीं हुई"
                        if (templates && c == '$' && i + 1 < n && src[i + 1] == '{') { depth++; i += 2; continue }
                        if (depth > 0 && c == '}') { depth--; i++; continue }
                        if (depth > 0 && c == '"') { val e = src.indexOf('"', i + 1); if (e < 0) break; i = e + 1; continue }
                        if (c == q && depth == 0) break
                        i++
                    }
                }
                ch == '\'' -> {   // Kotlin/Java char literal
                    val e = if (i + 1 < n && src[i + 1] == '\\') src.indexOf('\'', i + 2) else i + 2
                    if (e in (i + 1)..(i + 8) && e < n && src[e] == '\'') { i = e + 1; continue }
                }
                ch == '{' || ch == '(' || ch == '[' -> stack.addLast(ch to line)
                ch == '}' || ch == ')' || ch == ']' -> {
                    val top = stack.removeLastOrNull() ?: return "line $line पर फ़ालतू '$ch'"
                    if (top.first != pairs[ch]) return "line ${top.second} का '${top.first}' line $line पर '$ch' से बंद हुआ"
                }
            }
            i++
        }
        return stack.lastOrNull()?.let { "line ${it.second} का '${it.first}' कभी बंद नहीं हुआ" }
    }
}
