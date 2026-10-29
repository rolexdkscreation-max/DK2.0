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
    private class Ai(private val ai: CoderAi, lightModel: String = GeminiModels.LIGHT) : TeamAi {
        // v3.6: हर एजेंट का अपना मॉडल - कोडर/डिबगर/प्लानर/रिव्यूअर = तेज़ (कोडिंग एजेंट का चुना, 3.8 Flash), बाकी हल्का (3.5 Flash Lite)
        private val light = ai.withModel(lightModel)
        private fun pick(agent: Seven.Agent) = if (TeamModels.fast(agent)) ai else light
        // runInterruptible: "रोकें" दबाते ही limit वाला इंतज़ार (Thread.sleep) भी रुके
        override suspend fun ask(agent: Seven.Agent, system: String, msgs: List<Pair<String, String>>): String =
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) { pick(agent).chat(system, msgs) }
        override suspend fun askMedia(agent: Seven.Agent, system: String, msgs: List<Pair<String, String>>, media: List<CoderMedia>): String =
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) { pick(agent).chat(system, msgs, media) }
        override val budget: Int get() = ai.budget
    }

    /** GitHub मोड की फ़ाइलें: चुना रिपो/branch (CodingAgent की snapshot) */
    private class RepoFiles(private val a: CodingAgent, private val c: Context? = null) : TeamFiles {
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
            // ऐप बीच में बंद हो तो ये बदलाव बचें (अगली बार वहीं से)
            c?.let { ctx -> val st = LinkedHashMap(a.staged); val br = a.branch; CoderHistory.updateCheckpoint(ctx) { it.copy(staged = st, branch = br) } }
        }
        override fun staged(): Map<String, String?> = LinkedHashMap(mine)
        override suspend fun all(): Map<String, String>? = a.teamSnapshot()
        override val whole: Boolean get() = a.teamSnapshot() != null
    }

    /** गिट एजेंट के हाथ = CodingAgent की सुरक्षित PR वाली सुविधाएँ */
    private class Git(private val a: CodingAgent, private val task: String, private val c: Context? = null, private val resumeBackup: String = "") : TeamGit {
        // "वहीं से आगे": backup पहली बार बन चुका - दोबारा नहीं
        override suspend fun backup(task: String) = resumeBackup.ifBlank { a.backupOld(task) }.also { b ->
            c?.let { ctx -> CoderHistory.updateCheckpoint(ctx) { it.copy(backup = b) } }
        }
        override suspend fun branch(task: String) = a.ensureBranch(task).also { br ->
            c?.let { ctx -> CoderHistory.updateCheckpoint(ctx) { it.copy(branch = br) } }
        }
        /** build पूरा होने पर emulator के screenshot ऐप में (अलग से - टीम नहीं रुकती) */
        private fun shots() {
            val ctx = c ?: return
            val gh = GitHub.fromPrefs(ctx) ?: return
            CoderShots.fetchLater(ctx, gh, a.repo, a.lastSha)
        }
        private fun saveCk() {
            val ctx = c ?: return
            val st = LinkedHashMap(a.staged); val br = a.branch; val p = a.pr
            CoderHistory.updateCheckpoint(ctx) { it.copy(staged = st, branch = br, prNum = p?.number ?: it.prNum, prUrl = p?.url ?: it.prUrl) }
        }
        override suspend fun pushAndTest(commit: String, title: String, summary: String, rootCause: String, onStep: (String) -> Unit): TeamTest {
            a.teamInfo(task, summary, rootCause)
            if (a.staged.isNotEmpty()) {
                a.push(commit)
                if (a.prError.isNotBlank()) onStep("PR: ${a.prError.take(200)}")
                saveCk()
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
                        shots()
                        val b = a.watchBuildFor(a.lastSha, "workflow_dispatch", onStep)
                            ?: return TeamTest(null, "", "", "branch वाले build का नतीजा नहीं दिखा — token में Actions: Read की इजाज़त दें. code branch ${a.branch} पर है", TeamTest.BRANCH)
                        return TeamTest(b.ok, b.log, b.url, if (b.ok) "PR से पहले branch build ($wf) पास" else "", TeamTest.BRANCH)
                    }
                    onStep("branch पर build नहीं चला पाया (${why.take(120)}) — token में Actions: Read and write दें. अभी: draft PR पर build, पास होने पर ही असली PR")
                } else onStep("इस रिपो का build सिर्फ़ PR पर चलता है — draft PR पर build चलेगा, पास होने पर ही वह असली (ready) PR बनेगा")
                val e = a.teamOpenPr(draft = true)
                if (e.isNotBlank()) return TeamTest(null, "", "", "build के लिए draft PR नहीं बना: ${e.take(200)}", TeamTest.PR)
                onStep("draft PR (सिर्फ़ build के लिए): ${a.pr?.url.orEmpty()}")
                saveCk()
            }
            shots()
            // PR का अपना चेक (pull_request); न मिले तो इस commit के सारे checks
            val b = a.watchBuildFor(a.lastSha, "pull_request", onStep) ?: a.watchBuild(a.lastSha, onStep)
                ?: return TeamTest(null, "", a.pr?.url.orEmpty(), "PR के चेक का नतीजा नहीं दिखा — token में Actions: Read की इजाज़त दें, या GitHub पर PR के checks देखें", TeamTest.PR)
            return TeamTest(b.ok, b.log, b.url, if (b.ok) "PR का चेक पास" else "", TeamTest.PR)
        }
        override suspend fun openPr(): String {
            val e = a.teamOpenPr(draft = false)
            saveCk(); shots()
            return if (e.isBlank()) a.pr?.url.orEmpty() else ""
        }
        override val prNote: String get() = a.prError.ifBlank { if (a.pr == null && a.branch.isNotBlank()) "code branch ${a.branch} पर है" else "" }
        // v3.89: काम-कॉपी पहले फ़ोन में (तुरंत, बिना API); PR वाली कॉपी सिर्फ़ हालत बदलने पर / कुछ मिनट में एक बार
        private var prNoteMd: String? = null
        private var prNoteAt = 0L
        override suspend fun saveNote(markdown: String) {
            c?.let { ctx -> withContext(Dispatchers.IO) { LocalNotes.save(ctx.filesDir, a.repo, a.branch, markdown) } }
            val now = System.currentTimeMillis()
            if (c == null || a.pr == null || LocalNotes.prDue(markdown, prNoteMd, prNoteAt, now)) {
                a.teamSaveNote(markdown)
                if (a.pr != null) { prNoteMd = markdown; prNoteAt = now }
            } else a.teamKeepNote(markdown)
        }
        override suspend fun loadNote(): String {
            val local = c?.let { ctx -> withContext(Dispatchers.IO) { LocalNotes.load(ctx.filesDir, a.repo, a.branch) } }
            // फ़ोन वाली कॉपी में वही छुपा data है जो WorkNote.fromBody पढ़ता है; न मिले/टूटी हो तो PR से (पुराना तरीका)
            return if (local != null && WorkNote.fromBody(local) != null) local else a.teamPrBody()
        }
        override suspend fun ready(): String {
            val why = a.teamReady()
            val url = a.pr?.url.orEmpty()
            return if (why.isBlank()) url else "$url (draft नहीं हटा: ${why.take(120)} — GitHub पर \"Ready for review\" दबाएँ)"
        }
        override val prUrl: String get() = a.pr?.url.orEmpty()
    }

    private fun say(role: String, t: String) = CoderSession.add(role, t)

    /** टीम की हर बात स्क्रीन पर; v3.89: टेस्टर का "✗ फ़ेल" = बग वाली अलग आवाज़ (काम बीच में, सुधार अपने-आप चलता रहता है) */
    private fun teamEvents(c: Context): (Seven.Agent, String) -> Unit = { ag, t ->
        say("s", "${ag.icon} ${ag.hi}: $t")
        if (ag == Seven.Agent.TESTER && t.startsWith("✗")) CoderSound.notify(c, CoderSound.BUG, "कोडिंग टीम: बग मिला — ठीक कर रहा हूँ", t.lines().first().take(120))
    }

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
            val team = SevenTeam(Ai(ai, Prefs.coderLightModel(c)), RepoFiles(a), Git(a, task), full = true, ev = teamEvents(c))
            val u = SevenPlus.Understanding(Seven.Kind.BUG, title, "रिपो डॉक्टर: $title — जड़ ढूँढकर ठीक करना, ताकि सारे checks पास हों", task,
                listOf("जो check fail था वह पास हो", "बाकी सारे checks और पुराने tests पास हों", "कोई पुराना फ़ीचर न बदले"),
                listOf("सारे मौजूदा फ़ीचर और tests"), emptyList(), emptyList())
            val out = team.run(task, u)
            DKLog.d("रिपो डॉक्टर - 11-एजेंट पूरी रिपोर्ट:\n" + out.report)
            say(if (out.testOk == true) "d" else "e", out.short.ifBlank { out.report })
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
        CoderHistory.init(c)
        OneKeyGate.check(c, OneKeyGate.Work.CODING)?.let { return it }
        if (!Prefs.coderOn(c)) return "कोडिंग एजेंट बंद है — Settings में चालू करें"
        val ai = CoderAi.fromPrefs(c) ?: return "कोडिंग एजेंट की AI key नहीं — Settings → AI दिमाग → कोडिंग एजेंट में डालें"
        if (CoderSession.busy) return "अभी एक काम चल रहा है — पूरा होने दें या रोकें"
        CoderSession.offerNormal = null
        // रुका काम है और user ने "आगे बढ़ो / continue" लिखा = वहीं से आगे (नया काम नहीं)
        val ck = CoderHistory.checkpoint(c)
        if (pending == null && ck != null && ck.stage != "pending" && CoderHistory.Logic.isContinue(input)) {
            say("u", (if (fromVoice) "🎤 " else "") + input)
            return resume(c, auto = false)
        }
        // "आगे करो" पर रुका काम ही नहीं - इसे नया काम मानकर शुरू से न समझने बैठो
        if (pending == null && ck == null && CoderHistory.Logic.isContinue(input)) {
            say("u", (if (fromVoice) "🎤 " else "") + input)
            say("s", "कोई रुका हुआ काम नहीं मिला — पिछला काम पूरा हो चुका है। नया काम बताइए।")
            CoderSession.listener?.invoke(); return null
        }
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
        if (r == null) {
            say("s", "ठीक है — यह काम रद्द। कुछ भी नहीं बदला।")
            CoderHistory.clearCheckpoint(c); CoderHistory.setStatus(c, "chat")
            CoderSession.listener?.invoke(); return null
        }
        val (task, approved, normal) = r
        if (approved != null) say("s", "✅ आपकी मंज़ूरी मिली — अब 11 एजेंट काम शुरू कर रहे हैं")
        launchTeam(c, ai, task, approved, normal, allMedia, null)
        CoderSession.listener?.invoke()
        return null
    }

    /** खुले PR और उनकी फ़ाइलें पढ़कर छोटा नोट; GitHub न मिले तो साफ़ बताओ (अंदाज़ा नहीं) */
    private suspend fun preCheck(gh: GitHub, repo: String, u: SevenPlus.Understanding, ownPr: Int?, base: String): String = try {
        withContext(Dispatchers.IO) {
            val prs = gh.openPulls(repo)
            val paths = RepoPreCheck.areaPaths(u.area)
            val touching = if (paths.isEmpty()) emptyMap() else prs.take(6).associate { p ->
                p.number to (try { RepoPreCheck.overlap(paths, gh.pullFiles(repo, p.number).map { it.path }) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; emptyList() })
            }
            RepoPreCheck.text(prs, touching, ownPr, base)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        "🔎 रिपो जाँच नहीं हो पाई (${e.message?.take(80)}) — खुले PR की जानकारी अभी पक्की नहीं।"
    }

    /** मंज़ूर काम (या समझना) 11 एजेंट से. [resume] = ऐप बंद होने/रुकने के बाद "वहीं से आगे" */
    private fun launchTeam(c: Context, ai: CoderAi, task: String, approved: SevenPlus.Understanding?, normal: Boolean,
                           allMedia: List<CoderMedia>, resume: CoderHistory.Checkpoint?) {
        // चौकी: मंज़ूर काम शुरू = "run" (ऐप बीच में बंद हो तो अगली बार वहीं से)
        if (approved != null && resume == null) CoderHistory.saveCheckpoint(c, CoderHistory.Checkpoint(CoderHistory.current, task, approved, "run", normal,
            Prefs.ghRepo(c), Prefs.ghBranch(c)))
        val workTask = if (resume != null) task + CoderHistory.Logic.resumeNote(resume) else task
        CoderSession.launch(c, task) {
            var agent: CodingAgent? = null
            var finished = false
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
                        if (resume != null) {
                            // "वहीं से आगे": पिछली branch / PR / बदलाव वापस - नए सिरे से नहीं
                            // पूरा रिपो दोबारा न उतरे: उसी branch की फ़ोन में रखी फ़ाइलें बची रहें
                            a.resetKeepFiles(resume.branch)
                            a.safePr = true
                            if (resume.prNum > 0 && resume.branch.isNotBlank()) a.restorePr(GitHub.Pull(resume.prNum, resume.prUrl, resume.branch, a.baseBranch))
                            else if (resume.branch.isNotBlank()) withContext(Dispatchers.IO) { a.restoreBranch(resume.branch) }
                            say("s", "↩ वहीं से आगे: " + (if (a.branch != a.baseBranch) "branch ${a.branch}" else "branch अभी नहीं बनी थी") +
                                (if (a.pr != null) " • PR #${a.pr?.number}" else "") + if (resume.staged.isNotEmpty()) " • ${resume.staged.size} फ़ाइल के बदलाव बचे थे" else "")
                        } else if (open == null) a.reset() else say("s", "🌿 पिछला PR #${open.number} अभी खुला है (merge नहीं हुआ) — यह काम उसी branch (${a.branch}) में जुड़ेगा")
                        a.safePr = true; a.draftPr = true; a.prLater = true
                        say("s", "🌿 GitHub मोड: $repo (${Prefs.ghBranch(c)}) — backup, नई branch, PR, और आपकी मंज़ूरी से merge")
                        a.teamLoad { say("s", it) }
                        val files = RepoFiles(a, c)
                        resume?.staged?.forEach { (p, content) -> files.stage(p, content) }
                        team = SevenTeam(Ai(ai, Prefs.coderLightModel(c)), files, Git(a, task, c, resume?.backup.orEmpty()), full = true, ev = teamEvents(c))
                        // "आगे करो": टीम कहाँ तक पहुँची थी - वहीं से (पूरे हुए हिस्से दोबारा नहीं)
                        team.resumeFrom = TeamProgress.parse(resume?.team)
                        if (approved != null) team.onProgress = { p -> val j = p.json(); CoderHistory.updateCheckpoint(c) { it.copy(team = j) } }
                    } else {
                        say("s", "📝 नॉर्मल मोड" + when {
                            wantNormal -> " (आपने चुना)"
                            !token -> " (GitHub नहीं जुड़ा)"
                            else -> " (कोई रिपो नहीं चुना — ऊपर \"रिपो\" से चुनें तो GitHub मोड)"
                        } + " — गिट एजेंट बंद; रिव्यू, टेस्ट, डिबग वही। कोड फ़ाइल-नाम के साथ मिलेगा।")
                        val start = LinkedHashMap(CoderSession.normalFiles).apply { putAll(Seven.filesFromMessage(task)) }
                        if (start.isNotEmpty()) say("s", "फ़ाइलें: " + start.keys.joinToString())
                        team = SevenTeam(Ai(ai, Prefs.coderLightModel(c)), MemoryFiles(start), null, full = true) { ag, t -> say("s", "${ag.icon} ${ag.hi}: $t") }
                    }
                    team.media = allMedia
                    if (approved == null) {
                        // चरण 1: सिर्फ़ समझ (कोई branch / बदलाव नहीं) - user को दिखाकर "हाँ" का इंतज़ार
                        val u = team.understand(task)
                        if (u.answer.isNotBlank()) { say("d", u.answer); return@withTimeout }
                        // मैनेजर: रिपो / PR / काम की हालत का सवाल → जाँचकर्ता (कोड नहीं छूता, मंज़ूरी की ज़रूरत नहीं)
                        if (u.kind == Seven.Kind.STATUS) { inspect(c, task); return@withTimeout }
                        // मैनेजर: "हाँ" माँगने से पहले रिपो जाँच (खुले PR + इसी जगह का टकराव) - सिर्फ़ पढ़ना
                        if (d.mode == Seven.Mode.GITHUB && gh != null) say("s", preCheck(gh, repo, u, agent?.pr?.number, Prefs.ghBranch(c)))
                        pending = Pending(task, u, normal, allMedia)
                        // ऐप बंद होकर खुले तो भी यही समझ "हाँ" का इंतज़ार करे
                        CoderHistory.saveCheckpoint(c, CoderHistory.Checkpoint(CoderHistory.current, task, u, "pending", normal, repo, Prefs.ghBranch(c)))
                        say("d", u.hindi())
                        CoderTeam.announce(c, if (u.needsAnswer) "कोडिंग टीम: एक सवाल है" else "कोडिंग टीम: आपकी मंज़ूरी चाहिए",
                            u.understood.ifBlank { u.brief }.take(120),
                            if (u.needsAnswer) "बॉस, काम शुरू करने से पहले एक बात साफ़ कीजिए: " + u.questions.first().take(200)
                            else "बॉस, मैंने काम ऐसे समझा: " + u.understood.ifBlank { u.brief }.take(220) + "। सही है तो हाँ बोलिए, वरना बताइए क्या बदलना है।", kind = CoderSound.WAIT)
                        return@withTimeout
                    }
                    val out = team.run(workTask, approved)
                    // काम पूरा (या सिर्फ़ जवाब) = चौकी साफ़. नहीं हुआ = चौकी बची रहे, ताकि "आगे करो" वहीं से चले
                    finished = out.testOk == true || out.answerOnly
                    if (out.answerOnly && out.report == SevenTeam.STATUS_ASK) { inspect(c, task); return@withTimeout }
                    if (out.answerOnly) { say("d", out.report); return@withTimeout }
                    // यूज़र को सिर्फ़ छोटी रिपोर्ट (हुआ/नहीं, API, वजह, हल). पूरी तकनीकी रिपोर्ट डिबग लॉग में
                    DKLog.d("11-एजेंट पूरी रिपोर्ट:\n" + out.report)
                    say(if (out.testOk == true) "d" else "e", out.short.ifBlank { out.report })
                    if (out.mode == Seven.Mode.NORMAL) out.files.forEach { (p, content) ->
                        say("f", p + "\n" + (content ?: "(यह फ़ाइल हटानी है)"))
                        if (content != null) CoderSession.normalFiles[p] = content else CoderSession.normalFiles.remove(p)
                    }
                    val ok = out.testOk == true
                    CoderTeam.announce(c, if (ok) "11-एजेंट टीम: काम पूरा" else "11-एजेंट टीम: रुकी",
                        out.short.lines().firstOrNull { it.startsWith("वजह:") }?.removePrefix("वजह:")?.trim() ?: out.report.lines().getOrNull(1).orEmpty(),
                        if (ok && out.mode == Seven.Mode.GITHUB) "कोडिंग का काम पूरा हो गया, बॉस। टेस्ट पास हैं, PR आपकी मंज़ूरी का इंतज़ार कर रहा है। हाँ बोलें तो merge करके APK release कर दूँगा।"
                        else if (ok) "कोडिंग का काम पूरा हो गया, बॉस। कोड स्क्रीन पर फ़ाइल-नाम के साथ है।"
                        else "कोडिंग का काम पूरा नहीं हो पाया। कोडिंग स्क्रीन पर वजह और हल लिखा है। ठीक करके आगे करो बोलिए, वहीं से शुरू होगा।",
                        kind = if (ok) CoderSound.DONE else CoderSound.WAIT)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                say("e", ShortReport.text(null, "तय समय (${CoderTeam.TASK_TIMEOUT_MIN} मिनट) में पूरा नहीं हुआ"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                say("e", ShortReport.text(null, "काम रोक दिया"))
            } catch (e: Exception) {
                val raw = e.message ?: e.javaClass.simpleName
                DKLog.d("11-एजेंट टीम रुकी: $raw")
                say("e", ShortReport.text(false, raw))
            } finally {
                // पूरा हुआ (पास/फ़ेल की रिपोर्ट) = चौकी साफ़. बीच में रुका = "pause" (बटन से आगे). मंज़ूरी का इंतज़ार = वही रहे
                withContext(NonCancellable) {
                    val k = if (approved != null) CoderHistory.checkpoint(c) else null
                    if (k != null && k.stage != "pending") {
                        if (finished) CoderHistory.clearCheckpoint(c)
                        else {
                            // टीम की याद (team) वाले बाकी बदलाव पहले लिखे जा चुके हों, उसके बाद ही "pause" (क्रम से, पुरानी चौकी से न ढके)
                            val last = CoderSession.lines.lastOrNull { it.role == "s" }?.text.orEmpty().take(300)
                            CoderHistory.updateCheckpoint(c) { if (it.stage == "pending") it else it.copy(stage = "pause", last = last) }
                        }
                    }
                }
                agent?.let { a ->
                    a.draftPr = false; a.prLater = false
                    withContext(NonCancellable) { try { a.dropEmptyBranch() } catch (_: Exception) {} }
                }
            }
        }
    }

    /**
     * ऐप दोबारा खुली / "वहीं से आगे" दबाया: चौकी वाला काम उसी branch, PR और बचे बदलावों से आगे.
     * @return null = शुरू हो गया, वरना वजह
     */
    fun resume(ctx: Context, auto: Boolean): String? {
        val c = ctx.applicationContext
        CoderHistory.init(c)
        val k = CoderHistory.checkpoint(c) ?: return "कोई रुका हुआ काम नहीं"
        if (CoderSession.busy) return "अभी एक काम चल रहा है"
        if (!Prefs.coderOn(c)) return "कोडिंग एजेंट बंद है — Settings में चालू करें"
        val ai = CoderAi.fromPrefs(c) ?: return "कोडिंग एजेंट की AI key नहीं — Settings → AI दिमाग → कोडिंग एजेंट में डालें"
        if (k.stage == "pending") {
            // मंज़ूरी का इंतज़ार वापस (फ़ोटो/वीडियो नहीं बचते, उनका लिखा विवरण समझ में है)
            pending = Pending(k.task, k.u, k.normal)
            return null
        }
        // उसी बातचीत (धागे) में आगे
        if (k.thread.isNotBlank() && k.thread != CoderHistory.current) {
            val l = CoderHistory.open(c, k.thread)
            CoderSession.lines.clear(); CoderSession.lines.addAll(l)
        }
        CoderHistory.saveCheckpoint(c, k.copy(stage = "run", tries = k.tries + if (auto) 1 else 0))
        say("s", if (auto) "↩ ऐप बंद होने से काम बीच में रुका था — वहीं से अपने-आप आगे बढ़ा रहा हूँ" else "↩ रुका हुआ काम वहीं से आगे बढ़ा रहा हूँ")
        launchTeam(c, ai, k.task, k.u, k.normal, emptyList(), k)
        CoderSession.listener?.invoke()
        return null
    }

    /**
     * ऐप/स्क्रीन खुली: चौकी देखो. मंज़ूरी वाली समझ = इंतज़ार वापस; चलते-चलते ऐप बंद हुई थी = अपने-आप आगे (3 बार तक);
     * गलती/limit/रोकने से रुका = कुछ नहीं (स्क्रीन पर "वहीं से आगे" बटन)
     */
    fun reopen(ctx: Context) {
        val c = ctx.applicationContext
        CoderHistory.init(c)
        val k = CoderHistory.checkpoint(c)
        when (CoderHistory.Logic.onReopen(k, CoderSession.busy)) {
            "wait" -> if (pending == null && k != null) pending = Pending(k.task, k.u, k.normal)
            "auto" -> resume(c, auto = true)
        }
    }

    /** रुका काम छोड़ दें (branch/PR GitHub पर जैसे हैं वैसे रहते हैं) */
    fun dropResume(ctx: Context) {
        CoderHistory.clearCheckpoint(ctx.applicationContext)
        pending = null
    }

}
