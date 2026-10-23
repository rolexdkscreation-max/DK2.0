package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * रिपो डॉक्टर को ऐप से जोड़ना: "रिपो चेक करो" / "क्या चल रहा है" / "PR #42 ठीक करो" / "PR #42 merge करो".
 * 1. पूरे रिपो की असली हालत पढ़ो (सिर्फ़ पढ़ना - कुछ नहीं बदलता)
 * 2. रिपोर्ट + योजना दिखाओ, user की "हाँ" का इंतज़ार
 * 3. हाँ पर: फँसा run दोबारा, fail = GitHub की दिक्कत तो दोबारा / code की गलती तो 11 एजेंट उसी branch पर ठीक करें,
 *    सब हरा होते ही merge → APK release (पक्का)
 */
object RepoDoctorRunner {

    data class Plan(val snap: RepoDoctor.Snap, val items: List<RepoDoctor.Item>)

    /** हाँ का इंतज़ार कर रही योजना */
    @Volatile var pending: Plan? = null

    /** null = यह संदेश रिपो डॉक्टर का नहीं */
    class Res(val err: String?, val voice: String = "")

    private fun say(role: String, t: String) = CoderSession.add(role, t)

    /**
     * हर संदेश पहले यहाँ: रिपो डॉक्टर का हो तो संभालो.
     * @return null = आगे का सामान्य काम (11 एजेंट / पुरानी टीम)
     */
    fun intercept(ctx: Context, input: String, fromVoice: Boolean): Res? {
        val c = ctx.applicationContext
        val shown = (if (fromVoice) "🎤 " else "") + input
        pending?.let { p ->
            when (SevenPlus.reply(input)) {
                SevenPlus.Reply.YES -> { pending = null; say("u", shown); return Res(execute(c, p), "ठीक है, रिपो की योजना शुरू कर रही हूँ। हर कदम कोडिंग स्क्रीन पर दिखेगा।") }
                SevenPlus.Reply.NO -> { pending = null; say("u", shown); say("s", "ठीक है — रिपो में कुछ नहीं किया।"); return Res(null, "ठीक है, कुछ नहीं करूँगी।") }
                SevenPlus.Reply.OTHER -> pending = null
            }
        }
        val n = RepoDoctor.prNumber(input)
        if (n != null && SafePr.mergeIntent(input) == true && n != CoderSession.agent?.pr?.number) {
            say("u", shown)
            return Res(scan(c, focus = n, mergeNow = true), "PR #$n की जाँच करके merge और APK release कर रही हूँ।")
        }
        if (n != null && RepoDoctor.isFixAsk(input)) { say("u", shown); return Res(scan(c, focus = n, question = input), "PR #$n की जाँच कर रही हूँ, योजना बताती हूँ।") }
        if (RepoDoctor.isStatusAsk(input)) { say("u", shown); return Res(scan(c, focus = n, question = input), "रिपो की जाँच कर रही हूँ — खुले PR, builds, release। नतीजा अभी बताती हूँ।") }
        return null
    }

    /**
     * रिपो पढ़ो (कोई बदलाव नहीं) → रिपोर्ट → योजना pending.
     * mergeNow = user ने खुद "PR #N merge करो" कहा: PR हरा हो तो सीधे merge + release (वही उसकी हाँ है)
     */
    fun scan(c: Context, focus: Int? = null, mergeNow: Boolean = false, question: String? = null): String? {
        val p0 = CoderSession.problems(c)
        if (p0.isNotEmpty()) return "कोडिंग एजेंट तैयार नहीं: " + p0.joinToString(", ")
        val gh = GitHub.fromPrefs(c) ?: return "GitHub token नहीं — Settings → कोडिंग एजेंट में डालें"
        val repo = Prefs.ghRepo(c).takeIf { it.contains('/') } ?: return "कोई रिपो नहीं चुना — कोडिंग स्क्रीन पर ऊपर \"रिपो\" से चुनें"
        val base = Prefs.ghBranch(c).ifBlank { "main" }
        say("s", "🩺 रिपो डॉक्टर: $repo पढ़ रहा हूँ — खुले PR, उनके checks, $base का build, release, दूसरी branches…")
        CoderSession.scope.launch {
            try {
                val s = withContext(Dispatchers.IO) { collect(gh, repo, base) }
                var items = RepoDoctor.analyze(s, System.currentTimeMillis())
                if (focus != null) {
                    items = items.filter { it.pr == focus }
                    if (items.isEmpty()) {
                        val info = withContext(Dispatchers.IO) { try { gh.pullInfo(repo, focus) } catch (_: Exception) { null } }
                        // सवाल पूछा गया (जैसे "PR #50 merge हुआ? क्या बदला?") - बंद/merge PR भी जाँचकर्ता बताएगा
                        if (question != null && !mergeNow && info != null) { inspect(c, gh, s, question, focus, RepoDoctor.report(s, emptyList())); return@launch }
                        say("e", if (info == null) "PR #$focus नहीं मिला।" else if (!info.open) "PR #$focus खुला नहीं है (merge/बंद हो चुका)।"
                            else "PR #$focus का base ${info.base} है — मैं सिर्फ़ $base वाले PR संभालता हूँ।")
                        return@launch
                    }
                }
                val rep = RepoDoctor.report(s, items)
                val acts = RepoDoctor.actionable(items)
                if (mergeNow && focus != null) {
                    val it0 = items.first()
                    if (it0.act == RepoDoctor.Act.MERGE) { say("d", rep.substringBefore("\nमैं यह करूँगा")); execute(c, Plan(s, items))?.let { say("e", it) }; return@launch }
                    say("d", rep)
                    if (acts.isNotEmpty()) pending = Plan(s, items)
                    CoderTeam.announce(c, "रिपो डॉक्टर: PR #$focus अभी merge नहीं", it0.state, "PR $focus अभी merge नहीं हो सकता: ${it0.state}। " +
                        if (acts.isNotEmpty()) "पहले ठीक करके merge करूँ? हाँ बोलिए।" else "")
                    return@launch
                }
                say("d", rep)
                if (acts.isNotEmpty()) pending = Plan(s, items)
                if (question != null) inspect(c, gh, s, question, focus, rep)
                val bad = items.count { it.act == RepoDoctor.Act.FIX || it.act == RepoDoctor.Act.RESTART }
                val ready = items.count { it.act == RepoDoctor.Act.MERGE }
                val running = items.count { it.act == RepoDoctor.Act.WAIT }
                CoderTeam.announce(c, "रिपो डॉक्टर: ${s.prs.size} PR खुले", rep.lines().drop(1).take(2).joinToString(" "),
                    "रिपो की जाँच पूरी। ${s.prs.size} PR खुले हैं" +
                        (if (bad > 0) ", $bad में खराबी या फँसा काम" else "") + (if (ready > 0) ", $ready merge के लिए तैयार" else "") +
                        (if (running > 0) ", $running अभी चल रहे" else "") + "। " +
                        if (acts.isNotEmpty()) "योजना स्क्रीन पर है — हाँ बोलें तो ठीक करके merge और APK release कर दूँगी।" else "अभी कुछ करने की ज़रूरत नहीं।")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                say("e", "रिपो डॉक्टर रिपो नहीं पढ़ पाया: " + (e.message ?: e.javaClass.simpleName).take(300) +
                    "\n(token में Pull requests: Read और Actions: Read की इजाज़त चाहिए)")
            }
        }
        return null
    }

    /**
     * 🔎 जाँचकर्ता: PR की बदली फ़ाइलें + patch, checks + fail log, हाल के merge, टीम का हाल - सब पढ़कर AI step-by-step हिंदी में बताए.
     * सिर्फ़ पढ़ना - कुछ नहीं बदलता. AI key न हो / AI fail = रिपो डॉक्टर की रिपोर्ट ही काफ़ी (कोई गलती नहीं).
     */
    private suspend fun inspect(c: Context, gh: GitHub, s: RepoDoctor.Snap, question: String, focus: Int?, report: String) {
        val ai = CoderAi.fromPrefs(c) ?: return
        say("s", "${Seven.INSPECTOR_HI}: हर PR की बदली फ़ाइलें, checks और fail log ध्यान से पढ़ रहा हूँ…")
        try {
            val facts = withContext(Dispatchers.IO) {
                val merged = try { gh.closedPulls(s.repo, 8) } catch (_: Exception) { emptyList() }
                val nums = RepoInspector.pick(focus, s.prs.map { it.number }, merged.filter { it.merged }.map { it.number })
                val deep = nums.mapNotNull { n ->
                    val d = try { gh.pullDetail(s.repo, n) } catch (_: Exception) { null } ?: return@mapNotNull null
                    val files = try { gh.pullFiles(s.repo, n) } catch (_: Exception) { emptyList() }
                    val runs = if (d.headSha.isBlank()) emptyList() else
                        s.runs.filter { it.sha == d.headSha }.ifEmpty { try { gh.runsFor(s.repo, d.headSha).map { r ->
                            RepoDoctor.RunI(r.id, r.name, r.status, r.conclusion, r.url, r.event, d.head, d.headSha, 0L) } } catch (_: Exception) { emptyList() } }
                    val bad = RepoDoctor.latest(runs).firstOrNull { it.failed && it.conclusion != "cancelled" }
                    val log = bad?.let { try { gh.failureLog(s.repo, it.id) } catch (_: Exception) { "" } }.orEmpty()
                    RepoInspector.Deep(d, files, runs, log)
                }
                val mainBad = RepoDoctor.latest(s.runs.filter { it.sha == s.mainSha && it.sha.isNotBlank() }).firstOrNull { it.failed && it.conclusion != "cancelled" }
                val mainLog = mainBad?.let { try { gh.failureLog(s.repo, it.id) } catch (_: Exception) { "" } }.orEmpty()
                val team = RepoInspector.team(CoderSession.busy, CoderSession.task, CoderSession.lines.toList().map { it.role to it.text })
                RepoInspector.facts(question, s, report, deep, merged, mainLog, team, (ai.budget * 7 / 10).coerceAtLeast(40_000))
            }
            val ans = withContext(Dispatchers.IO) {
                ai.chat(Seven.Prompts.INSPECTOR + "\n\n" + Seven.Prompts.RULES, listOf("user" to facts + "\n\nNow answer the USER QUESTION step by step in Hindi."))
            }.trim()
            if (ans.isNotBlank()) say("d", "${Seven.INSPECTOR_HI}:\n$ans")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            say("s", "${Seven.INSPECTOR_HI}: गहरी जाँच नहीं हो पाई (${(e.message ?: e.javaClass.simpleName).take(160)}) — ऊपर रिपो डॉक्टर की रिपोर्ट देखें")
        }
    }

    /** पूरे रिपो की हालत (सिर्फ़ पढ़ना) */
    private suspend fun collect(gh: GitHub, repo: String, base: String): RepoDoctor.Snap {
        val prs = gh.openPulls(repo, 30).filter { it.base == base }.take(15).map { p ->
            var info = try { gh.pullInfo(repo, p.number) } catch (_: Exception) { null }
            if (info != null && info.mergeable == null) { kotlinx.coroutines.delay(1500); info = try { gh.pullInfo(repo, p.number) } catch (_: Exception) { info } }
            val behind = try { gh.behindBy(repo, base, p.head) } catch (_: Exception) { 0 }
            p.copy(mergeable = info?.mergeable, headSha = info?.headSha?.ifBlank { null } ?: p.headSha, behind = behind)
        }
        val runs = try { gh.recentRuns(repo, 60) } catch (_: Exception) { emptyList() }
        val mainSha = try { gh.remoteBranchSha(repo, base) } catch (_: Exception) { "" }
        val mainMs = if (mainSha.isBlank()) 0L else try { gh.commitTimeMs(repo, mainSha) } catch (_: Exception) { 0L }
        val rel = try { gh.latestRelease(repo) } catch (_: Exception) { null }
        val heads = prs.map { it.head }.toSet()
        val branches = (try { gh.branches(repo) } catch (_: Exception) { emptyList() })
            .filter { it != base && it !in heads && !it.startsWith("backup/") && !it.startsWith("archive/") }.take(15)
            .mapNotNull { b -> val n = try { gh.aheadBy(repo, base, b) } catch (_: Exception) { 0 }; if (n > 0) RepoDoctor.Branch(b, n) else null }
        return RepoDoctor.Snap(repo, base, mainSha, mainMs, prs, runs, rel, branches,
            CoderSession.agent?.pr?.number ?: 0, CoderSession.busy)
    }

    /** user की हाँ के बाद योजना चलाओ. @return null = शुरू, वरना वजह */
    private fun execute(c: Context, plan: Plan): String? {
        if (CoderSession.busy) return "अभी एक काम चल रहा है — पूरा होने दें, फिर \"रिपो चेक करो\" बोलें"
        val a = CoderSession.agentFor(c) ?: return "GitHub या AI सेटअप नहीं मिला"
        val gh = GitHub.fromPrefs(c) ?: return "GitHub token नहीं"
        val acts = RepoDoctor.actionable(plan.items)
        if (acts.isEmpty()) return "करने को कुछ नहीं"
        // पहले PR (छोटे नंबर पहले), फिर main का सुधार, आख़िर में release (merge खुद release बनाता है)
        val order = acts.sortedWith(compareBy({ if (it.pr != null) 0 else if (it.act == RepoDoctor.Act.RELEASE) 2 else 1 }, { it.pr ?: 0 }))
        CoderSession.launch(c, "रिपो डॉक्टर: " + order.joinToString { it.title.take(30) }) {
            val done = mutableListOf<String>()
            var released = false
            for (i in order) {
                say("s", "🩺 ${i.title} — ${RepoDoctor.todo(i)}")
                try {
                    val r = handle(c, a, gh, plan.snap, i, released)
                    if (r.second) released = true
                    done += "${if (r.first) "✅" else "⚠"} ${i.title}: ${r.third}"
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    done += "⚠ ${i.title}: " + (e.message ?: e.javaClass.simpleName).take(200)
                }
            }
            say("d", "🩺 रिपो डॉक्टर — नतीजा:\n" + done.joinToString("\n") +
                "\n\nफिर से जाँचना हो तो \"रिपो चेक करो\" बोलें।")
        }
        CoderSession.listener?.invoke()
        return null
    }

    /** @return (ठीक हुआ?, नई release बनी?, Hindi नतीजा) */
    private suspend fun handle(c: Context, a: CodingAgent, gh: GitHub, s: RepoDoctor.Snap, i: RepoDoctor.Item, released: Boolean): Triple<Boolean, Boolean, String> {
        val repo = s.repo
        val pr = i.pr?.let { n -> s.prs.firstOrNull { it.number == n } }
        val pull = pr?.let { GitHub.Pull(it.number, it.url, it.head, it.base) }
        val step: (String) -> Unit = { t -> CoderSession.scope.launch { say("s", t) } }

        suspend fun mergeIt(): Triple<Boolean, Boolean, String> {
            val p = pull ?: return Triple(true, false, "ठीक हो गया")
            a.reset(); a.safePr = true; a.restorePr(p)
            if (a.pr?.number != p.number) return Triple(false, false, "PR अपनाया नहीं जा सका (branch ${p.head})")
            if (withContext(Dispatchers.IO) { try { gh.isDraft(repo, p.number) } catch (_: Exception) { false } }) {
                val why = a.teamReady(); if (why.isNotBlank()) return Triple(false, false, "draft नहीं हटा: ${why.take(120)}")
            }
            val ok = CoderTeam.mergeAndRelease(c, a, p)
            return Triple(ok, ok, if (ok) "merge + नई APK release ✓" else "merge/release पूरा नहीं — ऊपर वजह")
        }

        suspend fun watch(sha: String, event: String? = null): Boolean? {
            val b = (if (event == null) a.watchBuild(sha, step) else a.watchBuildFor(sha, event, step)) ?: return null
            if (!b.ok) say("e", "check फिर fail: ${b.url}")
            return b.ok
        }

        // पहले agent को सही repo/branch पर (CodingAgent.repo Prefs से ही बनता है)
        if (a.repo != repo) return Triple(false, false, "चुना रिपो बदल गया — दोबारा \"रिपो चेक करो\"")

        return when (i.act) {
            RepoDoctor.Act.RESTART -> {
                val run = i.run ?: return Triple(false, false, "run नहीं मिला")
                withContext(Dispatchers.IO) { try { gh.cancelRun(repo, run.id) } catch (_: Exception) {}; kotlinx.coroutines.delay(8000); gh.rerunAll(repo, run.id) }
                say("s", "🧊 अटका run रोककर दोबारा चलाया: ${run.url}")
                when (watch(run.sha)) {
                    true -> if (pull != null) mergeIt() else Triple(true, false, "दोबारा चलाने पर पास ✓")
                    false -> if (pull != null) fixCode(c, a, gh, s, i, pull)?.let { if (it) mergeIt() else Triple(false, false, "11 एजेंट ठीक नहीं कर पाए") }
                        ?: Triple(false, false, "fail का log नहीं मिला") else Triple(false, false, "दोबारा चलाने पर भी fail")
                    null -> Triple(false, false, "नतीजा नहीं दिखा (Actions: Read इजाज़त?)")
                }
            }
            RepoDoctor.Act.CHECK -> {
                val p = pull ?: return Triple(false, false, "PR नहीं")
                val wfs = workflows(gh, repo, p.head)
                val wf = Seven.dispatchable(wfs) ?: return Triple(false, false, "इस रिपो में हाथ से चलने वाला check नहीं (workflow_dispatch)")
                withContext(Dispatchers.IO) { gh.dispatchWorkflow(repo, wf, p.head) }
                say("s", "❔ ${p.head} पर check ($wf) चलाया")
                when (watch(pr?.headSha.orEmpty(), "workflow_dispatch")) {
                    true -> mergeIt()
                    false -> fixCode(c, a, gh, s, i, p)?.let { if (it) mergeIt() else Triple(false, false, "11 एजेंट ठीक नहीं कर पाए") } ?: Triple(false, false, "fail")
                    null -> Triple(false, false, "check का नतीजा नहीं दिखा")
                }
            }
            RepoDoctor.Act.FIX -> {
                val run = i.run ?: return Triple(false, false, "run नहीं मिला")
                val log = withContext(Dispatchers.IO) { try { gh.failureLog(repo, run.id) } catch (_: Exception) { "" } }
                if (run.conclusion == "cancelled" || Retry.isInfraFailure(log)) {
                    say("s", "🔁 यह GitHub की दिक्कत/रुका हुआ run है (code की गलती नहीं) — दोबारा चला रहा हूँ")
                    withContext(Dispatchers.IO) { if (run.conclusion == "cancelled") gh.rerunAll(repo, run.id) else gh.rerunFailed(repo, run.id) }
                    when (watch(run.sha)) {
                        true -> return if (pull != null) mergeIt() else Triple(true, false, "दोबारा चलाने पर पास ✓")
                        null -> return Triple(false, false, "नतीजा नहीं दिखा")
                        false -> {}
                    }
                }
                val ok = fixCode(c, a, gh, s, i, pull) ?: return Triple(false, false, "11 एजेंट शुरू नहीं हो पाए")
                if (!ok) Triple(false, false, "11 एजेंट ठीक नहीं कर पाए — ऊपर रिपोर्ट")
                else if (pull != null) mergeIt()
                else {
                    // main की गलती: टीम ने नया PR बनाया - वही merge + release
                    val np = a.pr ?: return Triple(true, false, "ठीक हुआ, पर PR नहीं मिला")
                    val ok2 = CoderTeam.mergeAndRelease(c, a, np)
                    Triple(ok2, ok2, if (ok2) "नया PR #${np.number} merge + APK release ✓" else "PR #${np.number} merge/release पूरा नहीं")
                }
            }
            RepoDoctor.Act.MERGE -> mergeIt()
            RepoDoctor.Act.RELEASE -> {
                if (released) return Triple(true, false, "ऊपर merge के साथ नई release पहले ही बन गई")
                val sha = withContext(Dispatchers.IO) { gh.remoteBranchSha(repo, s.base) }
                a.reset()
                val (b, rl, why) = a.deployRelease(sha, s.mainMs, step)
                if (b != null && !b.ok) Triple(false, false, "release build fail: ${b.url}")
                else if (why.isBlank() && rl != null) Triple(true, true, "नई APK ${rl.name.ifBlank { rl.tag }} release ✓ ${rl.url}")
                else Triple(false, false, "release पक्की नहीं: $why")
            }
            else -> Triple(true, false, "कुछ नहीं करना था")
        }
    }

    /** 11 एजेंट से code की गलती ठीक करवाओ. @return null = शुरू नहीं हुआ */
    private suspend fun fixCode(c: Context, a: CodingAgent, gh: GitHub, s: RepoDoctor.Snap, i: RepoDoctor.Item, pull: GitHub.Pull?): Boolean? {
        // ताज़ा fail run का log (दोबारा चलने के बाद वाला)
        val run = withContext(Dispatchers.IO) {
            val sha = if (pull != null) (try { gh.pullInfo(s.repo, pull.number)?.headSha } catch (_: Exception) { null } ?: i.run?.sha.orEmpty()) else i.run?.sha.orEmpty()
            try { gh.runsFor(s.repo, sha).firstOrNull { it.status == "completed" && it.conclusion in RepoDoctor.BAD } } catch (_: Exception) { null }
        }
        val log = withContext(Dispatchers.IO) { try { gh.failureLog(s.repo, run?.id ?: i.run?.id ?: 0) } catch (_: Exception) { "" } }
        say("s", "🐞 code की गलती — 11 एजेंट जड़ ढूँढकर " + (if (pull != null) "PR #${pull.number} की branch पर" else "नई branch पर") + " ठीक करेंगे")
        val out = SevenRunner.fixWithTeam(c, a, pull, RepoDoctor.fixTask(i, s.base, log), i.title) ?: return null
        return out.testOk == true
    }

    private suspend fun workflows(gh: GitHub, repo: String, ref: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            gh.tree(repo, ref).map { it.path }.filter { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) }
                .mapNotNull { p -> gh.read(repo, p, ref)?.let { p.substringAfterLast('/') to it } }
        } catch (_: Exception) { emptyList() }
    }
}
