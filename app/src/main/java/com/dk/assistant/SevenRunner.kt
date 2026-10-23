package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 7-एजेंट सिस्टम को ऐप से जोड़ना (Settings → कोडिंग एजेंट → "7 एजेंट वाला नया सिस्टम").
 * बंद हो तो पुराना कोडिंग एजेंट / टीम जैसी की तैसी चलती है.
 *
 * मोड: GitHub token + चुना रिपो = GitHub मोड (backup, नई branch, draft PR + टेस्ट, मंज़ूरी से merge);
 * token नहीं / रिपो नहीं चुना / user ने नॉर्मल चुना = नॉर्मल मोड (गिट बंद, कोड फ़ाइल-नाम के साथ स्क्रीन पर).
 * token गलत/expired या रिपो not found = रुककर बताओ + नॉर्मल मोड का विकल्प.
 */
object SevenRunner {

    /** प्रॉम्प्ट इंजीनियर की समझ जो user की "हाँ" का इंतज़ार कर रही है */
    data class Pending(val task: String, val u: SevenPlus.Understanding, val forceNormal: Boolean, val media: List<CoderMedia> = emptyList())

    /** null = कोई समझ इंतज़ार में नहीं */
    @Volatile var pending: Pending? = null

    /**
     * user का नया संदेश + इंतज़ार वाली समझ -> (असली काम, मंज़ूर समझ या null, नॉर्मल?), या null = रद्द.
     * "हाँ" = वही काम, मंज़ूर समझ के साथ. सवालों का जवाब / सुधार = काम + जवाब, फिर से समझना.
     */
    private fun resolve(input: String, p: Pending?, forceNormal: Boolean): Triple<String, SevenPlus.Understanding?, Boolean>? =
        SevenPlus.resolve(input, p?.task, p?.u, p?.forceNormal == true, forceNormal)

    /** कोडिंग एजेंट की अपनी API (Settings → AI दिमाग → कोडिंग एजेंट); limit पर उसी key के दूसरे मॉडल - CoderAi खुद */
    private class Ai(private val ai: CoderAi) : TeamAi {
        override suspend fun ask(agent: Seven.Agent, system: String, msgs: List<Pair<String, String>>): String =
            withContext(Dispatchers.IO) { ai.chat(system, msgs) }
        override suspend fun askMedia(agent: Seven.Agent, system: String, msgs: List<Pair<String, String>>, media: List<CoderMedia>): String =
            withContext(Dispatchers.IO) { ai.chat(system, msgs, media) }
        override val budget: Int get() = ai.budget
    }

    /** GitHub मोड की फ़ाइलें: चुना रिपो/branch (CodingAgent की snapshot) */
    private class RepoFiles(private val a: CodingAgent) : TeamFiles {
        private val mine = linkedMapOf<String, String?>()     // इस काम में बदली (push के बाद भी याद)
        private val before = HashMap<String, String?>()
        override suspend fun list() = withContext(Dispatchers.IO) {
            (a.teamList() + mine.filterValues { it != null }.keys).distinct().filter { !(it in mine && mine[it] == null) }
        }
        override suspend fun read(path: String): String? {
            if (path in mine) return mine[path]
            val r = withContext(Dispatchers.IO) { a.teamRead(path) }
            if (path !in before) before[path] = r
            return r
        }
        override suspend fun original(path: String) = if (path in before) before[path] else read(path).also { before[path] = it }
        override suspend fun stage(path: String, content: String?) {
            if (path !in before) before[path] = withContext(Dispatchers.IO) { try { a.teamRead(path) } catch (_: Exception) { null } }
            mine[path] = content
            withContext(Dispatchers.IO) { a.teamStage(path, content) }
        }
        override fun staged(): Map<String, String?> = LinkedHashMap(mine)
        override suspend fun all(): Map<String, String>? = a.teamSnapshot()
        override val whole: Boolean get() = a.teamSnapshot() != null
    }

    /** गिट एजेंट के हाथ = CodingAgent की सुरक्षित PR वाली सुविधाएँ */
    private class Git(private val a: CodingAgent, private val task: String) : TeamGit {
        override suspend fun backup(task: String) = a.backupOld(task)
        override suspend fun branch(task: String) = a.ensureBranch(task)
        override suspend fun pushAndTest(commit: String, title: String, summary: String, rootCause: String, onStep: (String) -> Unit): TeamTest {
            a.teamInfo(task, summary, rootCause)
            if (a.staged.isNotEmpty()) {
                a.push(commit)
                if (a.prError.isNotBlank()) onStep("PR: ${a.prError.take(200)}")
            }
            val wfs = withContext(Dispatchers.IO) { a.teamWorkflows() }
            if (wfs.isEmpty()) return TeamTest(null, SevenTeam.NO_CI, a.pr?.url.orEmpty())
            if (a.pr == null) {
                // नियम: PR बनाने से पहले build. फ़ोन पर compile नहीं -> GitHub पर इसी branch का build (PR के बिना)
                val wf = Seven.dispatchable(wfs)
                if (wf != null) {
                    val why = a.teamDispatch(wf)
                    if (why.isBlank()) {
                        onStep("PR से पहले इसी branch (${a.branch}) पर build/tests चला दिए ($wf) — पास होने पर ही PR बनेगा")
                        val b = a.watchBuildFor(a.lastSha, "workflow_dispatch", onStep)
                            ?: return TeamTest(null, "", "", "branch वाले build का नतीजा नहीं दिखा — token में Actions: Read की इजाज़त दें. code branch ${a.branch} पर है", TeamTest.BRANCH)
                        return TeamTest(b.ok, b.log, b.url, if (b.ok) "PR से पहले branch build ($wf) पास" else "", TeamTest.BRANCH)
                    }
                    onStep("branch पर build नहीं चला पाया (${why.take(120)}) — token में Actions: Read and write दें. अभी: draft PR पर build, पास होने पर ही असली PR")
                } else onStep("इस रिपो का build सिर्फ़ PR पर चलता है — draft PR पर build चलेगा, पास होने पर ही वह असली (ready) PR बनेगा")
                val e = a.teamOpenPr(draft = true)
                if (e.isNotBlank()) return TeamTest(null, "", "", "build के लिए draft PR नहीं बना: ${e.take(200)}", TeamTest.PR)
                onStep("draft PR (सिर्फ़ build के लिए): ${a.pr?.url.orEmpty()}")
            }
            // PR का अपना चेक (pull_request); न मिले तो इस commit के सारे checks
            val b = a.watchBuildFor(a.lastSha, "pull_request", onStep) ?: a.watchBuild(a.lastSha, onStep)
                ?: return TeamTest(null, "", a.pr?.url.orEmpty(), "PR के चेक का नतीजा नहीं दिखा — token में Actions: Read की इजाज़त दें, या GitHub पर PR के checks देखें", TeamTest.PR)
            return TeamTest(b.ok, b.log, b.url, if (b.ok) "PR का चेक पास" else "", TeamTest.PR)
        }
        override suspend fun openPr(): String {
            val e = a.teamOpenPr(draft = false)
            return if (e.isBlank()) a.pr?.url.orEmpty() else ""
        }
        override val prNote: String get() = a.prError.ifBlank { if (a.pr == null && a.branch.isNotBlank()) "code branch ${a.branch} पर है" else "" }
        override suspend fun ready(): String {
            val why = a.teamReady()
            val url = a.pr?.url.orEmpty()
            return if (why.isBlank()) url else "$url (draft नहीं हटा: ${why.take(120)} — GitHub पर \"Ready for review\" दबाएँ)"
        }
        override val prUrl: String get() = a.pr?.url.orEmpty()
    }

    private fun say(role: String, t: String) = CoderSession.add(role, t)

    /**
     * रिपो डॉक्टर: fail हुए check को 11 एजेंट से ठीक करवाना (user की हाँ रिपो डॉक्टर में मिल चुकी).
     * pr != null = उसी PR की branch पर सुधार (नया PR नहीं); null = main से नई branch + PR.
     * यह पहले से चल रहे काम (CoderSession.launch) के अंदर बुलाया जाता है.
     * @return टीम का नतीजा (testOk = true तभी जब सारे checks पास)
     */
    suspend fun fixWithTeam(c: Context, a: CodingAgent, pr: GitHub.Pull?, task: String, title: String): SevenTeam.Outcome? {
        val ai = CoderAi.fromPrefs(c) ?: run { say("e", "कोडिंग एजेंट की AI key नहीं — Settings में डालें"); return null }
        a.reset()
        a.safePr = true; a.draftPr = true; a.prLater = true
        if (pr != null) {
            a.restorePr(pr)
            if (a.pr?.number != pr.number) { say("e", "PR #${pr.number} को अपना नहीं पाया (branch ${pr.head} सुरक्षित नहीं/base अलग)"); return null }
            say("s", "🌿 PR #${pr.number} की branch (${pr.head}) पर ही सुधार होगा — नया PR नहीं")
        }
        try {
            a.teamLoad { say("s", it) }
            val team = SevenTeam(Ai(ai), RepoFiles(a), Git(a, task), full = true) { ag, t -> say("s", "${ag.icon} ${ag.hi}: $t") }
            val u = SevenPlus.Understanding(Seven.Kind.BUG, title, "रिपो डॉक्टर: $title — जड़ ढूँढकर ठीक करना, ताकि सारे checks पास हों", task,
                listOf("जो check fail था वह पास हो", "बाकी सारे checks और पुराने tests पास हों", "कोई पुराना फ़ीचर न बदले"),
                listOf("सारे मौजूदा फ़ीचर और tests"), emptyList(), emptyList())
            val out = team.run(task, u)
            say(if (out.testOk == true) "d" else "e", out.report)
            return out
        } finally {
            a.draftPr = false; a.prLater = false
            withContext(NonCancellable) { try { a.dropEmptyBranch() } catch (_: Exception) {} }
        }
    }

    /** 🔎 जाँचकर्ता को सौंपो: रिपो डॉक्टर की पढ़ाई + गहरी जाँच + step-by-step जवाब */
    private fun inspect(c: Context, task: String) {
        RepoDoctorRunner.scan(c, focus = RepoDoctor.prNumber(task), question = task)?.let { say("e", "${Seven.INSPECTOR_HI}: $it") }
    }

    /** @return null = शुरू हो गया, वरना वजह */
    fun start(ctx: Context, input: String, fromVoice: Boolean = false, forceNormal: Boolean = false, media: List<CoderMedia> = emptyList()): String? {
        val c = ctx.applicationContext
        if (!Prefs.coderOn(c)) return "कोडिंग एजेंट बंद है — Settings में चालू करें"
        val ai = CoderAi.fromPrefs(c) ?: return "कोडिंग एजेंट की AI key नहीं — Settings → AI दिमाग → कोडिंग एजेंट में डालें"
        if (CoderSession.busy) return "अभी एक काम चल रहा है — पूरा होने दें या रोकें"
        CoderSession.offerNormal = null
        // PR तैयार है और user ने "हाँ" / "merge करो" कहा = डिप्लॉयमेंट (दोबारा जाँच → merge → APK release)
        if (pending == null && CoderSession.agent?.pr != null) {
            val m = SafePr.mergeIntent(input)
            if (m == false) { say("u", (if (fromVoice) "🎤 " else "") + input); say("s", "ठीक है, PR अभी merge नहीं करूँगा।"); return null }
            if (m == true || SevenPlus.reply(input) == SevenPlus.Reply.YES) {
                if (!fromVoice) say("u", input)
                return CoderTeam.merge(c, fromVoice)
            }
        }
        say("u", (if (fromVoice) "🎤 " else "") + input + CoderMedia.chip(media).let { if (it.isEmpty()) "" else "\n$it" })
        // फ़ोटो/वीडियो: पिछली समझ के साथ आई थीं तो वे भी (सुधार/हाँ के बाद भी टीम उन्हें देखे)
        val allMedia = (pending?.media.orEmpty() + media).take(CoderMedia.MAX_ITEMS)
        // पहले समझ → user की "हाँ" → तभी काम. जवाब/सुधार आया तो दोबारा समझो
        val r = resolve(input, pending, forceNormal)
        pending = null
        if (r == null) { say("s", "ठीक है — यह काम रद्द। कुछ भी नहीं बदला।"); CoderSession.listener?.invoke(); return null }
        val (task, approved, normal) = r
        if (approved != null) say("s", "✅ आपकी मंज़ूरी मिली — अब 11 एजेंट काम शुरू कर रहे हैं")
        CoderSession.launch(c, task) {
            var agent: CodingAgent? = null
            try {
                kotlinx.coroutines.withTimeout(CoderTeam.TASK_TIMEOUT_MIN * 60 * 1000L) {
                    // 0. मोड चुनो
                    val token = Prefs.ghToken(c).isNotBlank()
                    val repo = Prefs.ghRepo(c)
                    val wantNormal = normal || Prefs.coderNormal(c)
                    val gh = GitHub.fromPrefs(c)
                    var tokOk: Boolean? = null; var repoOk: Boolean? = null
                    if (token && !wantNormal && gh != null) withContext(Dispatchers.IO) {
                        tokOk = try { gh.me(); true } catch (e: GitHub.Err) { if (e.code == 401) false else null } catch (_: Exception) { null }
                        if (tokOk != false && repo.contains('/'))
                            repoOk = try { gh.defaultBranch(repo); true } catch (e: GitHub.Err) { if (e.code == 404 || e.code == 403) false else null } catch (_: Exception) { null }
                    }
                    val d = Seven.decideMode(token, tokOk, if (repo.contains('/')) repo else "", repoOk, wantNormal)
                    if (d.blocked) {
                        say("e", "⚠ ${d.problem}")
                        CoderSession.offerNormal = task
                        return@withTimeout
                    }
                    val team: SevenTeam
                    if (d.mode == Seven.Mode.GITHUB) {
                        val a = CoderSession.agentFor(c) ?: run { say("e", "GitHub सेटअप नहीं मिला"); return@withTimeout }
                        agent = a
                        // नया काम = साफ़ शुरुआत (नया backup + नई branch). पिछला PR अभी खुला (merge नहीं) हो तो यह काम उसी में जुड़ता है
                        val open = a.pr
                        if (open == null) a.reset() else say("s", "🌿 पिछला PR #${open.number} अभी खुला है (merge नहीं हुआ) — यह काम उसी branch (${a.branch}) में जुड़ेगा")
                        a.safePr = true; a.draftPr = true; a.prLater = true
                        say("s", "🌿 GitHub मोड: $repo (${Prefs.ghBranch(c)}) — backup, नई branch, PR, और आपकी मंज़ूरी से merge")
                        a.teamLoad { say("s", it) }
                        team = SevenTeam(Ai(ai), RepoFiles(a), Git(a, task), full = true) { ag, t -> say("s", "${ag.icon} ${ag.hi}: $t") }
                    } else {
                        say("s", "📝 नॉर्मल मोड" + when {
                            wantNormal -> " (आपने चुना)"
                            !token -> " (GitHub नहीं जुड़ा)"
                            else -> " (कोई रिपो नहीं चुना — ऊपर \"रिपो\" से चुनें तो GitHub मोड)"
                        } + " — गिट एजेंट बंद; रिव्यू, टेस्ट, डिबग वही। कोड फ़ाइल-नाम के साथ मिलेगा।")
                        val start = LinkedHashMap(CoderSession.normalFiles).apply { putAll(Seven.filesFromMessage(task)) }
                        if (start.isNotEmpty()) say("s", "फ़ाइलें: " + start.keys.joinToString())
                        team = SevenTeam(Ai(ai), MemoryFiles(start), null, full = true) { ag, t -> say("s", "${ag.icon} ${ag.hi}: $t") }
                    }
                    team.media = allMedia
                    if (approved == null) {
                        // चरण 1: सिर्फ़ समझ (कोई branch / बदलाव नहीं) - user को दिखाकर "हाँ" का इंतज़ार
                        val u = team.understand(task)
                        if (u.answer.isNotBlank()) { say("d", u.answer); return@withTimeout }
                        // मैनेजर: रिपो / PR / काम की हालत का सवाल → जाँचकर्ता (कोड नहीं छूता, मंज़ूरी की ज़रूरत नहीं)
                        if (u.kind == Seven.Kind.STATUS) { inspect(c, task); return@withTimeout }
                        pending = Pending(task, u, normal, allMedia)
                        say("d", u.hindi())
                        CoderTeam.announce(c, if (u.needsAnswer) "कोडिंग टीम: एक सवाल है" else "कोडिंग टीम: आपकी मंज़ूरी चाहिए",
                            u.understood.ifBlank { u.brief }.take(120),
                            if (u.needsAnswer) "बॉस, काम शुरू करने से पहले एक बात साफ़ कीजिए: " + u.questions.first().take(200)
                            else "बॉस, मैंने काम ऐसे समझा: " + u.understood.ifBlank { u.brief }.take(220) + "। सही है तो हाँ बोलिए, वरना बताइए क्या बदलना है।")
                        return@withTimeout
                    }
                    val out = team.run(task, approved)
                    if (out.answerOnly && out.report == SevenTeam.STATUS_ASK) { inspect(c, task); return@withTimeout }
                    if (out.answerOnly) { say("d", out.report); return@withTimeout }
                    say(if (out.testOk == true) "d" else "e", out.report)
                    if (out.mode == Seven.Mode.NORMAL) out.files.forEach { (p, content) ->
                        say("f", p + "\n" + (content ?: "(यह फ़ाइल हटानी है)"))
                        if (content != null) CoderSession.normalFiles[p] = content else CoderSession.normalFiles.remove(p)
                    }
                    val ok = out.testOk == true
                    CoderTeam.announce(c, if (ok) "11-एजेंट टीम: काम पूरा" else "11-एजेंट टीम: रुकी", out.report.lines().getOrNull(1).orEmpty(),
                        if (ok && out.mode == Seven.Mode.GITHUB) "कोडिंग का काम पूरा हो गया, बॉस। टेस्ट पास हैं, PR आपकी मंज़ूरी का इंतज़ार कर रहा है। हाँ बोलें तो merge करके APK release कर दूँगा।"
                        else if (ok) "कोडिंग का काम पूरा हो गया, बॉस। कोड स्क्रीन पर फ़ाइल-नाम के साथ है।"
                        else "कोडिंग का काम पूरा नहीं हो पाया। कोडिंग स्क्रीन पर वजह लिखी है।")
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                say("e", "11-एजेंट टीम: तय समय (${CoderTeam.TASK_TIMEOUT_MIN} मिनट) में काम पूरा नहीं हुआ। main अछूता है।")
            } catch (e: kotlinx.coroutines.CancellationException) {
                say("e", "11-एजेंट टीम का काम रोक दिया।")
            } catch (e: Exception) {
                say("e", "11-एजेंट टीम रुकी: " + (e.message ?: e.javaClass.simpleName).take(400) + "\n(API/नेटवर्क की गलती पर 3 बार अपने-आप कोशिश हो चुकी)")
            } finally {
                agent?.let { a ->
                    a.draftPr = false; a.prLater = false
                    withContext(NonCancellable) { try { a.dropEmptyBranch() } catch (_: Exception) {} }
                }
            }
        }
        CoderSession.listener?.invoke()
        return null
    }
}
