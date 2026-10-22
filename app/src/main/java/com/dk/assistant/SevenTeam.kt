package com.dk.assistant

import com.dk.assistant.Seven.Agent
import org.json.JSONObject

/** एजेंटों की AI - हर कॉल में उस एजेंट का अपना छोटा system prompt (कोडिंग एजेंट की अपनी API key से) */
interface TeamAi {
    suspend fun ask(agent: Agent, system: String, msgs: List<Pair<String, String>>): String
}

/** काम की फ़ाइलें: GitHub मोड = चुना रिपो/branch, नॉर्मल मोड = user के भेजे कोड + नई फ़ाइलें (फ़ोन की memory में) */
interface TeamFiles {
    suspend fun list(): List<String>
    /** अभी की हालत (लागू हो चुके बदलाव समेत). null = फ़ाइल नहीं */
    suspend fun read(path: String): String?
    /** काम शुरू होने से पहले की हालत */
    suspend fun original(path: String): String?
    suspend fun stage(path: String, content: String?)
    fun staged(): Map<String, String?>
    /** पूरा workspace (नाम-जाँच के लिए): path -> content, बदलाव समेत. null = नहीं दे सकते */
    suspend fun all(): Map<String, String>? = null
    /** all() पूरा रिपो है (GitHub मोड)? false = user की भेजी कुछ फ़ाइलें */
    val whole: Boolean get() = false
}

/** टेस्ट/बिल्ड का नतीजा. ok = null: नतीजा देख नहीं पाए (जैसे token में Actions की इजाज़त नहीं) */
data class TeamTest(val ok: Boolean?, val log: String, val url: String = "", val note: String = "", val stage: String = "") {
    companion object {
        /** PR से पहले, सिर्फ़ branch पर चला build (पास हो तभी PR) */
        const val BRANCH = "branch"
        /** PR का अपना चेक */
        const val PR = "pr"
    }
}

/** गिट एजेंट के हाथ (सिर्फ़ GitHub मोड) */
interface TeamGit {
    /** काम शुरू होने से पहले: backup branch (backup/dk-तारीख-समय) */
    suspend fun backup(task: String): String
    /** main से नई काम की branch (main पर कभी सीधे नहीं) */
    suspend fun branch(task: String): String
    /** बदलाव उसी branch पर push + (टेस्ट चलाने के लिए) draft PR + GitHub Actions का नतीजा */
    suspend fun pushAndTest(commit: String, title: String, summary: String, rootCause: String, onStep: (String) -> Unit): TeamTest
    /** टेस्ट पास: PR को review के लिए तैयार (draft हटाओ). merge कभी नहीं - वह सिर्फ़ user की मंज़ूरी से */
    suspend fun ready(): String
    val prUrl: String
    /** branch पर build पास होने के बाद PR खोलो (पहले नहीं). @return PR url, "" = नहीं बना (वजह prNote में) */
    suspend fun openPr(): String = prUrl
    /** PR क्यों नहीं बना / किस हाल में है */
    val prNote: String get() = ""
}

/**
 * 7 एजेंट, तय क्रम में. नया फ़ीचर: मैनेजर → गिट → प्लानर → (कोडर → रिव्यूअर → लागू)×कदम → टेस्टर → (डिबगर → प्लानर → …)* → गिट PR → रिपोर्ट.
 * बग: मैनेजर → गिट → डिबगर (जड़) → प्लानर (न्यूनतम सुधार) → कोडर/रिव्यूअर → टेस्टर → …
 * git = null → नॉर्मल मोड (गिट एजेंट बंद; बाकी क्रम वही).
 */
class SevenTeam(
    private val ai: TeamAi,
    private val files: TeamFiles,
    private val git: TeamGit?,
    private val ev: (Agent, String) -> Unit
) {
    data class Outcome(val report: String, val files: Map<String, String?>, val testOk: Boolean?, val mode: Seven.Mode, val answerOnly: Boolean = false)

    val mode get() = if (git != null) Seven.Mode.GITHUB else Seven.Mode.NORMAL
    private val changes = linkedMapOf<String, String>()
    private val bugs = mutableListOf<Seven.Bug>()
    private var backup = ""
    private var lastBlock = ""
    private var task = ""
    private var brief = ""
    private var acceptance = listOf<String>()
    private var passNote = ""
    private var lastTest: TeamTest? = null
    private var noCi = false
    private var commitMsg: Pair<String, String>? = null

    companion object {
        const val FILE_CHARS = 40_000          // एक फ़ाइल इससे बड़ी = anchor के आस-पास का हिस्सा
        const val READ_BUDGET = 150_000        // प्लानर/डिबगर एक बार में इतना पढ़ें
        const val LIST_MAX = 800
        /** गिट: रिपो में GitHub Actions का कोई workflow नहीं */
        const val NO_CI = "NO_CI"
    }

    suspend fun run(userTask: String): Outcome {
        task = userTask
        // 1. मैनेजर: काम समझो
        ev(Agent.MANAGER, "काम समझ रहा हूँ…")
        val fileList = files.list()
        val m = askJson(Agent.MANAGER, "USER REQUEST:\n$task\n\nMODE: $mode\nFILES IN WORKSPACE (${fileList.size}):\n" + fileList.take(150).joinToString("\n"))
        val kind = Seven.kindOf(m?.optString("kind"), task)
        if (kind == Seven.Kind.QUESTION) {
            val ans = m?.optString("answer").orEmpty().trim()
            if (ans.isNotBlank()) {
                ev(Agent.MANAGER, "यह सवाल है — कोड बदलने की ज़रूरत नहीं")
                return Outcome(ans, emptyMap(), null, mode, answerOnly = true)
            }
        }
        brief = m?.optString("brief").orEmpty().trim()
        acceptance = Seven.strings(m?.optJSONArray("acceptance"))
        val bug = kind == Seven.Kind.BUG
        ev(Agent.MANAGER, (if (bug) "यह बग ठीक करने का काम है → डिबगर पहले जड़ ढूँढेगा" else "यह नया फ़ीचर/बदलाव है → प्लानर पहले फ़ाइलें पढ़ेगा") +
            if (acceptance.isNotEmpty()) "\nपूरा होने की शर्तें:\n" + acceptance.joinToString("\n") { "• $it" } else "")

        // 2. गिट: backup + नई branch (सिर्फ़ GitHub मोड)
        git?.let { g ->
            backup = g.backup(task)
            ev(Agent.GIT, if (backup.isNotBlank()) "backup branch बनाई: $backup" else "backup branch नहीं बन पाई — फिर भी main को हाथ नहीं लगेगा")
            ev(Agent.GIT, "नई branch: ${g.branch(task)} — सारा काम इसी में, main अछूता")
        }

        // 3. प्लान (बग: पहले डिबगर)
        var rootCause = ""
        val plan = if (bug) {
            val d = debug("USER BUG REPORT:\n$task", repeat = false)
                ?: return finish(false, "डिबगर बग की जड़ नहीं ढूँढ पाया — थोड़ी और जानकारी दें (कब होता है, क्या एरर दिखता है)")
            rootCause = d.second
            makePlan("MINIMAL FIX for this bug.\nROOT CAUSE: ${d.second}\nFIX: ${d.third}", d.first.files)
        } else makePlan(task + if (brief.isNotBlank()) "\n(Manager brief: $brief)" else "", emptyList())
        plan ?: return finish(false, "प्लानर प्लान नहीं बना पाया: $lastBlock")
        if (!execute(plan, emptySet())) return finish(false, lastBlock)

        // 4. टेस्टर ⇄ डिबगर चक्र
        val sigs = mutableListOf<String>()
        while (true) {
            val t = test(rootCause)
            lastTest = t
            if (t.ok == true) {
                passNote = t.note
                ev(Agent.TESTER, (if (t.stage == TeamTest.BRANCH) "✓ PR से पहले branch पर build + tests पास" else if (t.stage == TeamTest.PR) "✓ PR का चेक पास" else "✓ टेस्ट + बिल्ड पास") +
                    if (t.url.isNotBlank()) "\n${t.url}" else "")
                // गिट: build पास होने के बाद ही PR (पहले नहीं); फिर उसी PR का अपना चेक भी देखो
                val g = git
                if (g != null && g.prUrl.isBlank()) {
                    val url = g.openPr()
                    if (url.isBlank()) return finish(null, "build पास है पर PR नहीं बन पाया" + if (g.prNote.isNotBlank()) ": ${g.prNote}" else "", "")
                    ev(Agent.GIT, "build पास होने के बाद PR बनाया: $url")
                    if (t.stage == TeamTest.BRANCH) { ev(Agent.TESTER, "अब PR का अपना चेक देख रहा हूँ…"); continue }
                }
                break
            }
            if (t.ok == null) return finish(null, t.note.ifBlank { "टेस्ट का नतीजा नहीं मिला" })
            val errs = errorLines(t.log)
            ev(Agent.TESTER, "✗ फ़ेल — log पढ़कर असली एरर डिबगर को दिया" + (if (errs.isNotBlank()) "\nअसली एरर:\n$errs" else "") + if (t.url.isNotBlank()) "\n${t.url}" else "")
            sigs.add(FixLoop.signature(t.log))
            when (FixLoop.next(sigs)) {
                FixLoop.Next.STOP_CAP -> return finish(false, "${sigs.size} बार जड़ ढूँढकर ठीक करने के बाद भी टेस्ट फ़ेल: ${sigs.last().take(200)}")
                FixLoop.Next.STOP_STUCK -> return finish(false, "वही गलती ${FixLoop.SAME_LIMIT} बार लौटी, रणनीति बदलकर भी नहीं गई: ${sigs.last().take(200)}")
                FixLoop.Next.FIX_AGAIN -> {}
            }
            val repeat = sigs.size >= 2 && sigs[sigs.size - 1] == sigs[sigs.size - 2]
            if (repeat) ev(Agent.MANAGER, "वही गलती दोबारा आई — रणनीति बदल रहा हूँ: डिबगर दूसरी जड़ देखेगा, कोडर पूरी फ़ाइल दोबारा लिखेगा")
            val d = debug("TEST/BUILD FAILED.\n" + (if (errs.isNotBlank()) "EXACT ERROR LINES FROM THE LOG:\n$errs\n\n" else "") + "FULL LOG (important part):\n${t.log.take(12_000)}", repeat)
                ?: return finish(false, "डिबगर टेस्ट फ़ेल होने की जड़ नहीं ढूँढ पाया")
            rootCause = d.second
            val fix = makePlan("MINIMAL FIX for the failed test/build.\nROOT CAUSE: ${d.second}\nFIX: ${d.third}", d.first.files)
                ?: return finish(false, "प्लानर सुधार का प्लान नहीं बना पाया: $lastBlock")
            if (!execute(fix, if (repeat) fix.steps.map { it.file }.toSet() else emptySet())) return finish(false, lastBlock)
        }

        // 5. गिट: PR तैयार (merge नहीं)
        var pr = ""
        git?.let { g -> pr = g.ready(); ev(Agent.GIT, "PR तैयार: $pr — merge सिर्फ़ आपकी मंज़ूरी से") }
        return finish(true, "", pr)
    }

    // ------------------------------------------------------------------ प्लानर
    private suspend fun makePlan(what: String, hint: List<String>): Seven.Plan? {
        for (tryNo in 0..1) {
            val p = planOnce(what + if (tryNo == 1) "\nMANAGER: the previous planning failed ($lastBlock). Make the SMALLEST possible plan (1-3 steps)." else "", hint)
            if (p != null) return p
            if (tryNo == 0) ev(Agent.MANAGER, "प्लानर अटका — रणनीति बदलकर छोटा प्लान माँग रहा हूँ")
        }
        return null
    }

    private suspend fun planOnce(what: String, hint: List<String>): Seven.Plan? {
        ev(Agent.PLANNER, "फ़ाइलें देखकर प्लान बना रहा हूँ…")
        val list = files.list()
        val msgs = mutableListOf("user" to ("TASK:\n$what\n" +
            (if (acceptance.isNotEmpty()) "ACCEPTANCE:\n" + acceptance.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (hint.isNotEmpty()) "DEBUGGER SAYS THESE FILES: ${hint.joinToString()}\n" else "") +
            (if (changes.isNotEmpty()) "ALREADY CHANGED IN THIS TASK: ${changes.keys.joinToString()}\n" else "") +
            "FILE LIST (${list.size}):\n" + list.take(LIST_MAX).joinToString("\n") + if (list.isEmpty()) "(empty - create new files)" else ""))
        var usesFixed = false
        for (round in 0..3) {
            val reply = ai.ask(Agent.PLANNER, Seven.Prompts.PLANNER, msgs)
            val reads = Seven.planReads(reply)
            if (reads != null && round < 3) {
                ev(Agent.PLANNER, "पूरी फ़ाइलें पढ़ रहा हूँ: " + reads.joinToString())
                msgs.add("model" to reply); msgs.add("user" to readFiles(reads) + "\nNow reply with the JSON plan, or read more files.")
                continue
            }
            val plan = Seven.parsePlan(reply)
            // प्लानर ने जो नाम (uses) बताए - उनकी फ़ाइल खोलकर पक्का: सच में हैं?
            if (plan != null && round < 3 && !usesFixed) {
                val bad = badUses(plan)
                if (bad.isNotEmpty()) {
                    usesFixed = true
                    ev(Agent.PLANNER, "प्लान के ये नाम फ़ाइल में नहीं मिले — फ़ाइल पढ़कर प्लान ठीक कर रहा हूँ:\n" + bad.joinToString("\n") { "• $it" })
                    msgs.add("model" to reply)
                    msgs.add("user" to "These \"uses\" entries are WRONG (the name is not in that file):\n" + bad.joinToString("\n") { "- $it" } +
                        "\nRead the right files ({\"read\":[…]}) and send the corrected JSON plan with only names that really exist.")
                    continue
                }
            }
            if (plan != null) {
                ev(Agent.PLANNER, "प्लान: ${plan.steps.size} कदम\n" + plan.steps.mapIndexed { i, s -> "${i + 1}. ${s.file} — ${s.action}: ${s.change.take(120)}" }.joinToString("\n") +
                    if (plan.risks.isNotEmpty()) "\n⚠ पुराने फ़ीचर का ख़तरा:\n" + plan.risks.joinToString("\n") { "• $it" } else "")
                return plan
            }
            msgs.add("model" to reply); msgs.add("user" to "Reply ONLY the JSON plan: {\"steps\":[{\"file\":…,\"action\":…,\"change\":…,\"anchor\":…}],\"risks\":[…]}")
        }
        lastBlock = "प्लानर का जवाब JSON प्लान नहीं था"
        return null
    }

    private suspend fun readFiles(paths: List<String>): String {
        var budget = READ_BUDGET
        val out = mutableListOf<String>()
        for (p in paths) {
            val c = files.read(p)
            out.add(when {
                c == null -> "----- $p : FILE NOT FOUND -----"
                budget <= 0 -> "----- $p : (skipped, read budget used) -----"
                else -> { val part = c.take(minOf(budget, FILE_CHARS)); budget -= part.length
                    "----- BEGIN $p (${c.lines().size} lines)${if (part.length < c.length) " TRUNCATED" else ""} -----\n$part\n----- END $p -----" }
            })
        }
        return out.joinToString("\n\n")
    }

    // ------------------------------------------------------------------ डिबगर
    data class Diag(val files: List<String>)

    /** @return (फ़ाइलें, root cause, fix) */
    private suspend fun debug(problem: String, repeat: Boolean): Triple<Diag, String, String>? {
        ev(Agent.DEBUGGER, if (repeat) "पिछला सुधार असली जड़ नहीं था — दूसरी वजह ढूँढ रहा हूँ…" else "असली जड़ ढूँढ रहा हूँ…")
        val list = files.list()
        val msgs = mutableListOf("user" to ("TASK: $task\n$problem\n" +
            (if (repeat) "NOTE FROM MANAGER: the SAME error came back after the previous fix. That fix did NOT address the real cause. Look for a different cause.\n" else "") +
            (if (changes.isNotEmpty()) "FILES CHANGED IN THIS TASK: ${changes.keys.joinToString()}\n" else "") +
            "FILE LIST (${list.size}):\n" + list.take(LIST_MAX).joinToString("\n")))
        var warned = false
        for (round in 0..4) {
            val reply = ai.ask(Agent.DEBUGGER, Seven.Prompts.DEBUGGER, msgs)
            val reads = Seven.planReads(reply)
            if (reads != null && round < 4) {
                ev(Agent.DEBUGGER, "पढ़ रहा हूँ: " + reads.joinToString())
                msgs.add("model" to reply); msgs.add("user" to readFiles(reads) + "\nReply with the root cause JSON, or read more files.")
                continue
            }
            val o = Seven.json(reply)
            val root = o?.optString("root_cause").orEmpty().trim()
            if (o != null && root.length >= 8) {
                val where = o.optString("where").trim()
                val fix = o.optString("fix").trim()
                bugs.add(Seven.Bug(o.optString("bug").trim().ifBlank { root.take(150) }, where, root, fix.ifBlank { "-" }))
                ev(Agent.DEBUGGER, "जड़ मिली: ${where.ifBlank { "-" }}\n$root\nसुधार: ${fix.take(200)}")
                return Triple(Diag(Seven.strings(o.optJSONArray("files"))), root, fix)
            }
            if (warned && round >= 3) break
            warned = true
            msgs.add("model" to reply); msgs.add("user" to "ROOT CAUSE REQUIRED. Reply ONLY JSON {\"root_cause\":\"file:line - what and why\",\"bug\":…,\"where\":…,\"fix\":…,\"files\":[…]} or {\"read\":[…]}.")
        }
        return null
    }

    // ------------------------------------------------------------------ कोडर ⇄ रिव्यूअर, कदम-दर-कदम
    private suspend fun execute(plan: Seven.Plan, rewrite: Set<String>): Boolean {
        plan.steps.forEachIndexed { i, s ->
            ev(Agent.CODER, "कदम ${i + 1}/${plan.steps.size}: ${s.file} — ${s.change.take(140)}")
            if (step(s, s.file in rewrite)) return@forEachIndexed
            // मैनेजर: अटके कदम पर दूसरा रास्ता - प्लानर से इसी हिस्से का नया प्लान
            ev(Agent.MANAGER, "कदम ${i + 1} अटका (${lastBlock.take(160)}) — रोककर दूसरा रास्ता: प्लानर से इस हिस्से का नया प्लान")
            val alt = planOnce("ONLY THIS PART of the task: ${s.change}\nFILE: ${s.file}\nThe previous attempt failed: $lastBlock\n" +
                "Plan a DIFFERENT approach (e.g. smaller steps, different location, or rewriting a small file).", listOf(s.file))
                ?: return false
            for ((j, a) in alt.steps.withIndex()) {
                ev(Agent.CODER, "नया रास्ता ${j + 1}/${alt.steps.size}: ${a.file} — ${a.change.take(140)}")
                if (!step(a, forceRewrite = false)) {
                    lastBlock = "कदम \"${s.change.take(100)}\" दो तरीक़ों से भी लागू नहीं हुआ: $lastBlock"
                    return false
                }
            }
        }
        return true
    }

    private suspend fun step(s: Seven.Step, forceRewrite: Boolean): Boolean {
        val cur = files.read(s.file)
        if (s.action == "edit" && cur == null) { lastBlock = "${s.file} मौजूद नहीं"; return false }
        if (s.action == "delete") return deleteStep(s, cur)
        val errors = mutableListOf<String>()
        var misses = 0
        var strat = if (forceRewrite && cur != null && cur.length <= Seven.REWRITE_MAX_CHARS) Seven.Strategy.REWRITE else Seven.Strategy.FIND
        if (strat == Seven.Strategy.REWRITE) ev(Agent.MANAGER, "रणनीति: ${s.file} पूरी पढ़कर पूरी दोबारा लिखवा रहा हूँ" + if (backup.isNotBlank()) " (backup: $backup)" else " (पुरानी फ़ाइल याद रखी है)")
        val defs = definitions(s)
        var msgs = mutableListOf("user" to coderBrief(s, cur, strat, defs))
        for (attempt in 1..Seven.STEP_ATTEMPTS) {
            val sys = Seven.Prompts.CODER + if (strat == Seven.Strategy.REWRITE) "\n\n" + Seven.Prompts.CODER_REWRITE else ""
            val reply = ai.ask(Agent.CODER, sys, msgs)
            val edit = Seven.parseEdit(reply, s.file)
            val chk = Seven.check(edit, s, cur, allowRewrite = strat == Seven.Strategy.REWRITE || cur == null)
            var problems = chk.problems
            var forCoder = ""
            // रिव्यूअर की पक्की नाम-जाँच: नया code जो function/class/variable बुलाता है, वह सच में है?
            val miss = if (chk.ok && chk.content != null) names(s.file, cur, chk.content) else emptyList()
            val sure = miss.filter { it.sure }
            if (sure.isNotEmpty()) {
                problems = sure.map { "नाम मौजूद नहीं: " + it.hindi() }
                forCoder = "\nNAME CHECK FAILED (these names do not exist - read the definitions and use the real names):\n" + sure.joinToString("\n") { "- " + it.english() }
            } else if (chk.ok) {
                // रिव्यूअर (AI): पुराना फ़ीचर / compile / कदम पूरा? (+ जो नाम पक्के नहीं, उनका सबूत)
                val r = review(s, cur, edit, chk.content, miss)
                if (r.isEmpty()) {
                    files.stage(s.file, chk.content)
                    changes[s.file] = (changes[s.file]?.let { "$it; " } ?: "") + s.change.take(160)
                    ev(Agent.REVIEWER, "✓ ${s.file}: जाँच पास — बदलाव लागू")
                    return true
                }
                problems = r
            }
            if (chk.findMiss) misses++
            errors.add(problems.joinToString(" | "))
            ev(Agent.REVIEWER, "✗ ${s.file}: वापस कोडर को (कोशिश $attempt/${Seven.STEP_ATTEMPTS})\n" + problems.joinToString("\n") { "• ${it.take(200)}" })
            lastBlock = problems.joinToString("; ").take(300)
            val next = Seven.strategy(errors, misses, cur?.length ?: 0, cur != null)
            if (next != strat) {
                strat = next
                ev(Agent.MANAGER, "एक ही गलती बार-बार / FIND नहीं बन रहा — तरीका बदला: ${s.file} पूरी पढ़कर पूरी दोबारा लिखवा रहा हूँ" +
                    if (backup.isNotBlank()) " (backup: $backup)" else "")
                msgs = mutableListOf("user" to coderBrief(s, cur, strat, defs) + forCoder)
            } else {
                msgs.add("model" to reply)
                msgs.add("user" to "REVIEWER REJECTED (the change was NOT applied):\n" + problems.joinToString("\n") { "- $it" } + forCoder +
                    (if (chk.findMiss && cur != null) "\nHere is the exact current file again - copy FIND character-for-character:\n" + fileBlock(s, cur) else "") +
                    "\nSend the corrected change in the same format.")
            }
        }
        return false
    }

    private suspend fun deleteStep(s: Seven.Step, cur: String?): Boolean {
        if (cur == null) { lastBlock = "${s.file} मौजूद नहीं"; return false }
        val o = askJson(Agent.REVIEWER, "TASK: $task\nSTEP: delete file ${s.file} — ${s.change}\nFILE LIST:\n" + files.list().take(300).joinToString("\n") +
            "\nCould deleting it break an existing feature (is it still used)?")
        val probs = Seven.strings(o?.optJSONArray("problems"))
        if (o != null && !o.optBoolean("pass", false) && probs.isNotEmpty()) { lastBlock = probs.joinToString("; "); ev(Agent.REVIEWER, "✗ ${s.file} हटाना रोका: $lastBlock"); return false }
        files.stage(s.file, null); changes[s.file] = "हटाई: ${s.change.take(120)}"
        ev(Agent.REVIEWER, "✓ ${s.file} हटाने की जाँच पास")
        return true
    }

    private fun fileBlock(s: Seven.Step, cur: String): String {
        val body = if (cur.length <= FILE_CHARS) cur else Seven.window(cur, s.anchor)
        return "----- BEGIN ${s.file}${if (body.length < cur.length) " (PART around the change)" else ""} -----\n$body\n----- END ${s.file} -----"
    }

    private fun coderBrief(s: Seven.Step, cur: String?, strat: Seven.Strategy, defs: String = "") = "TASK: $task\nSTEP (${s.action}): ${s.change}\nFILE: ${s.file}\n" +
        (if (defs.isNotBlank()) "DEFINITIONS (real, read from the files - use exactly these names/signatures):\n$defs\n" else "") +
        (if (cur == null) "(new file - send FILE: ${s.file} and <<<FILE … FILE>>>)" else if (strat == Seven.Strategy.REWRITE)
            "FULL CURRENT FILE (rewrite it completely, keep everything else exactly):\n----- BEGIN ${s.file} -----\n$cur\n----- END ${s.file} -----"
        else fileBlock(s, cur))

    /** @return समस्याएँ (खाली = पास) */
    private suspend fun review(s: Seven.Step, cur: String?, e: Seven.Edit, after: String?, unsure: List<SevenSymbols.Miss> = emptyList()): List<String> {
        val change = when {
            e.full != null && cur == null -> "NEW FILE ${s.file}:\n" + e.full.take(20_000)
            e.full != null -> "FULL REWRITE of ${s.file}. Lines before: ${cur!!.lines().size}, after: ${after?.lines()?.size}.\nREMOVED LINES:\n" +
                (cur.lines().toSet() - (after ?: "").lines().toSet()).take(80).joinToString("\n") + "\nADDED LINES:\n" +
                ((after ?: "").lines().toSet() - cur.lines().toSet()).take(120).joinToString("\n")
            else -> e.pairs.mapIndexed { i, (f, r) -> "--- FIND #${i + 1}\n$f\n+++ REPLACE #${i + 1}\n$r" }.joinToString("\n")
        }
        val ctx = if (cur != null && e.full == null) "\nFILE CONTEXT:\n" + (if (cur.length <= 12_000) cur else Seven.window(cur, e.pairs.first().first.lines().first(), 60)) else ""
        for (t in 0..1) {
            val nameCheck = if (unsure.isEmpty()) "" else "\nNAME CHECK (not found in this repository - only valid if it really exists in the external parent class / SDK):\n" +
                unsure.joinToString("\n") { "- " + it.english() }
            val o = askJson(Agent.REVIEWER, "TASK: $task\nSTEP: ${s.change}\nFILE: ${s.file}\nCHANGE:\n$change$ctx$nameCheck") ?: continue
            if (o.optBoolean("pass", false)) return emptyList()
            return Seven.strings(o.optJSONArray("problems")).ifEmpty { listOf("रिव्यूअर ने बदलाव ठीक नहीं माना (वजह नहीं बताई)") }
        }
        // रिव्यूअर का जवाब पढ़ा नहीं जा सका: पक्की (बिना AI) जाँच पास हो चुकी है - वही मान्य
        ev(Agent.REVIEWER, "AI रिव्यू का जवाब नहीं मिला — पक्की जाँच (FIND/REPLACE + syntax) पास है, उसी पर लागू")
        return emptyList()
    }

    // ------------------------------------------------------------------ टेस्टर
    private suspend fun test(rootCause: String): TeamTest {
        ev(Agent.TESTER, "टेस्ट चला रहा हूँ…")
        val staged = files.staged()
        // पहले पक्की जाँच: हर बदली फ़ाइल की syntax + पुराने tests हटे/कमज़ोर तो नहीं
        val stat = mutableListOf<String>()
        staged.forEach { (p, c) -> if (c != null) Syntax.check(p, c)?.let { stat.add("$p: $it") } }
        val orig = staged.keys.associateWith { files.original(it) }
        stat.addAll(SafePr.testGuard(staged, orig, SafePr.removesOldFeature(task)))
        if (stat.isNotEmpty()) return TeamTest(false, "STATIC CHECK FAILED:\n" + stat.joinToString("\n"))
        if (staged.isEmpty()) return TeamTest(false, "NOTHING CHANGED: no file was changed for the task")
        val g = git
        if (g != null) {
            val cm = commitMsg ?: run {
                val o = askJson(Agent.GIT, "TASK: $task\nCHANGES:\n" + changes.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                (o?.optString("commit").orEmpty().ifBlank { "DK: " + task.take(60) } to o?.optString("title").orEmpty().ifBlank { task.take(80) })
                    .also { commitMsg = it }
            }
            val commit = cm.first; val title = cm.second
            ev(Agent.GIT, if (g.prUrl.isBlank()) "branch पर push — PR अभी नहीं, पहले build/tests" else "उसी PR की branch पर push, PR का चेक…")
            val r = g.pushAndTest(commit, title, changes.entries.joinToString("\n") { "• ${it.key} — ${it.value}" }, rootCause) { ev(Agent.TESTER, it) }
            if (r.ok == null && r.log == NO_CI) {
                noCi = true
                ev(Agent.TESTER, "इस रिपो में GitHub Actions का कोई test/build नहीं — syntax जाँच + ड्राई-रन से जाँच रहा हूँ")
                return dryRun(staged, "रिपो में CI नहीं: syntax जाँच + ड्राई-रन")
            }
            return r
        }
        return dryRun(staged, "syntax जाँच + ड्राई-रन (फ़ोन पर build नहीं चलता)")
    }

    /** फ़ोन पर build नहीं चलता: पक्की जाँच (हो चुकी) + टेस्टर का कोड को मन में चलाकर देखना */
    private suspend fun dryRun(staged: Map<String, String?>, note: String): TeamTest {
        val body = staged.entries.joinToString("\n\n") { (p, c) -> "----- $p -----\n" + (c ?: "(deleted)").take(30_000) }
        val o = askJson(Agent.TESTER, "TASK: $task\nACCEPTANCE:\n" + acceptance.joinToString("\n") + "\nCHANGED FILES:\n$body")
            ?: return TeamTest(true, "", note = "syntax जाँच पास; AI ड्राई-रन का जवाब नहीं मिला")
        val cases = Seven.strings(o.optJSONArray("cases"))
        if (cases.isNotEmpty()) ev(Agent.TESTER, "जाँचे गए हालात:\n" + cases.take(6).joinToString("\n") { "• $it" })
        return if (o.optBoolean("pass", false)) TeamTest(true, "", note = note)
        else TeamTest(false, "DRY-RUN TEST FAILED: " + o.optString("error").ifBlank { "tester found a failing case" })
    }

    // ------------------------------------------------------------------ नाम-जाँच (फ़ाइल पढ़कर, अंदाज़े से नहीं)
    private suspend fun workspace(): Map<String, String> = files.all() ?: run {
        val m = LinkedHashMap<String, String>()
        for (p in files.list().take(400)) files.read(p)?.let { m[p] = it }
        m
    }

    private suspend fun names(path: String, before: String?, after: String): List<SevenSymbols.Miss> = try {
        val ws = HashMap(workspace()); ws[path] = after
        SevenSymbols.check(path, before, after, ws, files.whole)
    } catch (e: Exception) { emptyList() }

    private suspend fun readMap(paths: Collection<String>): Map<String, String?> {
        val m = HashMap<String, String?>()
        for (p in paths) m[p] = try { files.read(p) } catch (_: Exception) { null }
        return m
    }

    private suspend fun badUses(plan: Seven.Plan): List<String> {
        val uses = plan.steps.flatMap { it.uses }.distinct()
        if (uses.isEmpty()) return emptyList()
        val m = readMap(uses.map { it.substringBefore(':').trim() }.distinct())
        return SevenSymbols.badUses(uses) { m[it] }
    }

    private suspend fun definitions(s: Seven.Step): String {
        if (s.uses.isEmpty()) return ""
        val m = readMap(s.uses.map { it.substringBefore(':').trim() }.distinct())
        return SevenSymbols.signatures(s.uses) { m[it] }
    }

    /** log से असली एरर लाइनें (Kotlin "e: file:line", error:, FAILED, Exception) */
    private fun errorLines(log: String): String = log.lines().map { it.trim() }.filter { l ->
        l.startsWith("e: ") || l.contains("error:", true) || l.contains("Unresolved reference") || l.contains("FAILED") ||
            l.contains("AssertionError") || l.contains("Exception:") || l.startsWith("STATIC CHECK") || l.startsWith("DRY-RUN")
    }.distinct().take(8).joinToString("\n") { it.take(240) }

    // ------------------------------------------------------------------ मदद
    private suspend fun askJson(a: Agent, user: String): JSONObject? {
        val msgs = mutableListOf("user" to user)
        for (t in 0..1) {
            val reply = ai.ask(a, Seven.Prompts.of(a), msgs)
            Seven.json(reply)?.let { return it }
            msgs.add("model" to reply); msgs.add("user" to "Reply ONLY valid JSON in the required format.")
        }
        return null
    }

    private fun finish(testOk: Boolean?, problem: String, pr: String = git?.prUrl.orEmpty()): Outcome {
        if (problem.isNotBlank()) ev(Agent.MANAGER, (if (testOk == null) "⚠ " else "✗ ") + problem)
        val lt = lastTest
        val chk = when {
            git == null -> null
            noCi -> Seven.CheckState.NONE
            testOk == true -> Seven.CheckState.PASS
            lt == null -> Seven.CheckState.NONE
            lt.ok == false -> Seven.CheckState.FAIL
            else -> Seven.CheckState.RUNNING
        }
        var rep = Seven.report(task, mode, changes.entries.map { it.key to it.value }, bugs, testOk,
            if (testOk == true) passNote else problem.take(300), pr, backup, chk, lt?.url.orEmpty(), git?.prNote.orEmpty())
        if (testOk != true && problem.isNotBlank()) rep += "\n\n• रुकने की वजह: $problem" +
            if (mode == Seven.Mode.GITHUB) "\n• main अछूता है${if (backup.isNotBlank()) ", backup: $backup" else ""}। आगे क्या करूँ, बताइए।" else ""
        return Outcome(rep, files.staged(), testOk, mode)
    }
}

/** नॉर्मल मोड की फ़ाइलें: user के भेजे कोड (मूल) + इस काम में बदली/नई (फ़ोन की memory में) */
class MemoryFiles(initial: Map<String, String>) : TeamFiles {
    private val orig = LinkedHashMap(initial)
    private val stagedMap = linkedMapOf<String, String?>()
    override suspend fun list() = (orig.keys + stagedMap.filterValues { it != null }.keys).distinct().filter { stagedMap[it] != null || it !in stagedMap }
    override suspend fun read(path: String) = if (path in stagedMap) stagedMap[path] else orig[path]
    override suspend fun original(path: String) = orig[path]
    override suspend fun stage(path: String, content: String?) { stagedMap[path] = content }
    override fun staged(): Map<String, String?> = LinkedHashMap(stagedMap)
    override suspend fun all(): Map<String, String> = LinkedHashMap<String, String>().apply {
        putAll(orig); stagedMap.forEach { (p, c) -> if (c == null) remove(p) else put(p, c) }
    }
}
