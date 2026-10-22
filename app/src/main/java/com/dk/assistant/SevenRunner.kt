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

    /** कोडिंग एजेंट की अपनी API (Settings → AI दिमाग → कोडिंग एजेंट); limit पर उसी key के दूसरे मॉडल - CoderAi खुद */
    private class Ai(private val ai: CoderAi) : TeamAi {
        override suspend fun ask(agent: Seven.Agent, system: String, msgs: List<Pair<String, String>>): String =
            withContext(Dispatchers.IO) { ai.chat(system, msgs) }
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
            val hasCi = withContext(Dispatchers.IO) { a.teamList().any { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) } }
            if (!hasCi) return TeamTest(null, SevenTeam.NO_CI, a.pr?.url.orEmpty())
            val b = a.watchBuild(a.lastSha, onStep)
                ?: return TeamTest(null, "", a.pr?.url.orEmpty(), "GitHub Actions का नतीजा नहीं दिखा — token में Actions: Read की इजाज़त दें, या GitHub पर PR के checks देखें")
            return TeamTest(b.ok, b.log, b.url)
        }
        override suspend fun ready(): String {
            val why = a.teamReady()
            val url = a.pr?.url.orEmpty()
            return if (why.isBlank()) url else "$url (draft नहीं हटा: ${why.take(120)} — GitHub पर \"Ready for review\" दबाएँ)"
        }
        override val prUrl: String get() = a.pr?.url.orEmpty()
    }

    private fun say(role: String, t: String) = CoderSession.add(role, t)

    /** @return null = शुरू हो गया, वरना वजह */
    fun start(ctx: Context, task: String, fromVoice: Boolean = false, forceNormal: Boolean = false): String? {
        val c = ctx.applicationContext
        if (!Prefs.coderOn(c)) return "कोडिंग एजेंट बंद है — Settings में चालू करें"
        val ai = CoderAi.fromPrefs(c) ?: return "कोडिंग एजेंट की AI key नहीं — Settings → AI दिमाग → कोडिंग एजेंट में डालें"
        if (CoderSession.busy) return "अभी एक काम चल रहा है — पूरा होने दें या रोकें"
        CoderSession.offerNormal = null
        say("u", (if (fromVoice) "🎤 " else "") + task)
        CoderSession.launch(c) {
            var agent: CodingAgent? = null
            try {
                kotlinx.coroutines.withTimeout(CoderTeam.TASK_TIMEOUT_MIN * 60 * 1000L) {
                    // 0. मोड चुनो
                    val token = Prefs.ghToken(c).isNotBlank()
                    val repo = Prefs.ghRepo(c)
                    val wantNormal = forceNormal || Prefs.coderNormal(c)
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
                        a.safePr = true; a.draftPr = true
                        say("s", "🌿 GitHub मोड: $repo (${Prefs.ghBranch(c)}) — backup, नई branch, PR, और आपकी मंज़ूरी से merge")
                        a.teamLoad { say("s", it) }
                        team = SevenTeam(Ai(ai), RepoFiles(a), Git(a, task)) { ag, t -> say("s", "${ag.icon} ${ag.hi}: $t") }
                    } else {
                        say("s", "📝 नॉर्मल मोड" + when {
                            wantNormal -> " (आपने चुना)"
                            !token -> " (GitHub नहीं जुड़ा)"
                            else -> " (कोई रिपो नहीं चुना — ऊपर \"रिपो\" से चुनें तो GitHub मोड)"
                        } + " — गिट एजेंट बंद; रिव्यू, टेस्ट, डिबग वही। कोड फ़ाइल-नाम के साथ मिलेगा।")
                        val start = LinkedHashMap(CoderSession.normalFiles).apply { putAll(Seven.filesFromMessage(task)) }
                        if (start.isNotEmpty()) say("s", "फ़ाइलें: " + start.keys.joinToString())
                        team = SevenTeam(Ai(ai), MemoryFiles(start), null) { ag, t -> say("s", "${ag.icon} ${ag.hi}: $t") }
                    }
                    val out = team.run(task)
                    if (out.answerOnly) { say("d", out.report); return@withTimeout }
                    say(if (out.testOk == true) "d" else "e", out.report)
                    if (out.mode == Seven.Mode.NORMAL) out.files.forEach { (p, content) ->
                        say("f", p + "\n" + (content ?: "(यह फ़ाइल हटानी है)"))
                        if (content != null) CoderSession.normalFiles[p] = content else CoderSession.normalFiles.remove(p)
                    }
                    val ok = out.testOk == true
                    CoderTeam.announce(c, if (ok) "7-एजेंट टीम: काम पूरा" else "7-एजेंट टीम: रुकी", out.report.lines().drop(2).firstOrNull().orEmpty(),
                        if (ok && out.mode == Seven.Mode.GITHUB) "कोडिंग का काम पूरा हो गया, बॉस। टेस्ट पास हैं, PR आपकी मंज़ूरी का इंतज़ार कर रहा है।"
                        else if (ok) "कोडिंग का काम पूरा हो गया, बॉस। कोड स्क्रीन पर फ़ाइल-नाम के साथ है।"
                        else "कोडिंग का काम पूरा नहीं हो पाया। कोडिंग स्क्रीन पर वजह लिखी है।")
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                say("e", "7-एजेंट टीम: तय समय (${CoderTeam.TASK_TIMEOUT_MIN} मिनट) में काम पूरा नहीं हुआ। main अछूता है।")
            } catch (e: kotlinx.coroutines.CancellationException) {
                say("e", "7-एजेंट टीम का काम रोक दिया।")
            } catch (e: Exception) {
                say("e", "7-एजेंट टीम रुकी: " + (e.message ?: e.javaClass.simpleName).take(400) + "\n(API/नेटवर्क की गलती पर 3 बार अपने-आप कोशिश हो चुकी)")
            } finally {
                agent?.let { a ->
                    a.draftPr = false
                    withContext(NonCancellable) { try { a.dropEmptyBranch() } catch (_: Exception) {} }
                }
            }
        }
        CoderSession.listener?.invoke()
        return null
    }
}
