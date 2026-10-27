package com.dk.assistant

import java.util.Locale

/**
 * रिपो डॉक्टर (Android पर निर्भर नहीं - पूरी तरह unit test होता है).
 *
 * पूरे रिपो की असली हालत: कौन-से PR खुले हैं, किसके checks चल रहे / फँसे / fail हैं, conflict, draft,
 * main का build, release बाकी है या नहीं, और कोई दूसरा एजेंट/व्यक्ति किसी branch पर काम कर रहा है या नहीं.
 * फिर हर चीज़ के लिए अगला कदम: इंतज़ार / दोबारा चलाओ / 11 एजेंट से ठीक करो / merge + APK release.
 * खुले PR का सुधार ([auto]: fail ठीक करना, फँसा run, check चलाना, चलते checks पर नज़र) अपने-आप - सिर्फ़ उसी PR की branch पर.
 * merge और release ([needYes]) हमेशा user की "हाँ" के बाद ही (RepoDoctorRunner).
 */
object RepoDoctor {

    data class Pr(
        val number: Int, val title: String, val url: String, val head: String, val base: String,
        val draft: Boolean, val headSha: String, val author: String, val updatedMs: Long = 0,
        val mergeable: Boolean? = null, val open: Boolean = true, val behind: Int = 0
    )

    data class RunI(
        val id: Long, val name: String, val status: String, val conclusion: String, val url: String,
        val event: String, val branch: String, val sha: String, val startedMs: Long, val file: String = ""
    ) {
        val running get() = status != "completed"
        val failed get() = status == "completed" && conclusion in BAD
        val passed get() = status == "completed" && conclusion in GOOD
    }

    /** दूसरी branch जिस पर काम है पर PR नहीं */
    data class Branch(val name: String, val ahead: Int)

    data class Snap(
        val repo: String, val base: String, val mainSha: String, val mainMs: Long,
        val prs: List<Pr>, val runs: List<RunI>, val release: SevenPlus.Deploy.Rel?,
        val branches: List<Branch> = emptyList(), val myPr: Int = 0, val busyHere: Boolean = false
    )

    enum class Act {
        /** checks चल रहे हैं - रुको */ WAIT,
        /** फँसा run: रोककर दोबारा */ RESTART,
        /** fail: log देखकर - GitHub की दिक्कत = दोबारा, code की गलती = 11 एजेंट उसी branch पर ठीक करें */ FIX,
        /** कोई check नहीं चला: branch पर check चलाओ */ CHECK,
        /** conflict: अपने-आप नहीं (main से ताज़ा branch पर काम दोबारा) */ CONFLICT,
        /** सब हरा: (draft हो तो ready) → merge → APK release */ MERGE,
        /** main आगे है, release पुरानी: release build चलाओ */ RELEASE,
        /** सिर्फ़ जानकारी */ INFO
    }

    data class Item(val act: Act, val pr: Int?, val title: String, val state: String, val run: RunI? = null, val branch: String = "")

    val BAD = setOf("failure", "timed_out", "startup_failure", "cancelled", "action_required")
    val GOOD = setOf("success", "skipped", "neutral")
    /** इतनी देर से चल/कतार में = फँसा */
    const val STUCK_MIN = 45L

    /** हर workflow का सबसे नया run (उसी commit पर) */
    fun latest(runs: List<RunI>): List<RunI> = runs.groupBy { it.name }.map { (_, r) -> r.maxByOrNull { it.startedMs }!! }

    fun analyze(s: Snap, now: Long): List<Item> {
        val out = mutableListOf<Item>()
        val prBranches = s.prs.map { it.head }.toSet()
        for (p in s.prs.sortedBy { it.number }) {
            val t = "PR #${p.number}: ${p.title.take(70)}"
            val runs = latest(s.runs.filter { it.sha == p.headSha && it.sha.isNotBlank() })
            val stuck = runs.firstOrNull { it.running && it.startedMs > 0 && now - it.startedMs > STUCK_MIN * 60_000 }
            val running = runs.firstOrNull { it.running }
            val failed = runs.firstOrNull { it.failed }
            out += when {
                p.number == s.myPr && s.busyHere -> Item(Act.WAIT, p.number, t, "मेरी टीम अभी इसी पर काम कर रही है", branch = p.head)
                stuck != null -> Item(Act.RESTART, p.number, t, "check \"${stuck.name}\" ${(now - stuck.startedMs) / 60_000} मिनट से अटका है (फँसा)", stuck, p.head)
                // एक check fail + दूसरा अभी चल रहा = गलती पहले से पता है → सुधार अभी (पहले "चल रहा" दिखाकर गलती छुप जाती थी)
                failed != null -> Item(Act.FIX, p.number, t, "check \"${failed.name}\" fail (${failed.conclusion})" +
                    (if (running != null) " — \"${running.name}\" अभी चल रहा है" else ""), failed, p.head)
                running != null -> Item(Act.WAIT, p.number, t, "check \"${running.name}\" अभी चल रहा है", running, p.head)
                p.mergeable == false -> Item(Act.CONFLICT, p.number, t, "main से conflict है — अपने-आप merge नहीं हो सकता", branch = p.head)
                runs.isEmpty() -> Item(Act.CHECK, p.number, t, "इस PR के आख़िरी commit पर कोई check नहीं चला", branch = p.head)
                else -> Item(Act.MERGE, p.number, t, "सारे checks पास ✓" + (if (p.draft) " (अभी draft)" else "") +
                    (if (p.behind > 0) " — main ${p.behind} commit आगे, merge से पहले update + tests दोबारा" else ""), branch = p.head)
            }
        }
        // main का build + release
        val mainRuns = latest(s.runs.filter { it.sha == s.mainSha && it.sha.isNotBlank() && it.event != "pull_request" })
        val mainRun = mainRuns.firstOrNull { it.running }
        val mainBad = mainRuns.firstOrNull { it.failed && it.conclusion != "cancelled" }
        when {
            mainRun != null && s.mainSha.isNotBlank() && mainRun.startedMs > 0 && now - mainRun.startedMs > STUCK_MIN * 60_000 ->
                out += Item(Act.RESTART, null, "${s.base} का build", "\"${mainRun.name}\" ${(now - mainRun.startedMs) / 60_000} मिनट से अटका है", mainRun, s.base)
            mainRun != null -> out += Item(Act.WAIT, null, "${s.base} का build", "\"${mainRun.name}\" चल रहा है (APK release बन रही होगी)", mainRun, s.base)
            mainBad != null -> out += Item(Act.FIX, null, "${s.base} का build", "\"${mainBad.name}\" ${s.base} पर fail है", mainBad, s.base)
            else -> {
                val r = s.release
                val behind = r == null || (s.mainMs > 0 && r.publishedMs > 0 && r.publishedMs < s.mainMs - 60_000)
                if (behind && s.mainSha.isNotBlank()) out += Item(Act.RELEASE, null, "APK release",
                    if (r == null) "अभी कोई release नहीं" else "सबसे नई release ${r.name.ifBlank { r.tag }} पुरानी है — ${s.base} के आख़िरी बदलाव उसमें नहीं", branch = s.base)
            }
        }
        // बिना PR के चल रहे काम (दूसरा एजेंट / व्यक्ति)
        for (b in s.branches) if (b.name !in prBranches && b.ahead > 0)
            out += Item(Act.INFO, null, "branch ${b.name}", "${b.ahead} commit का काम, पर PR नहीं (कोई दूसरा एजेंट/व्यक्ति काम कर रहा होगा)", branch = b.name)
        s.runs.filter { it.running && it.branch !in prBranches && it.branch != s.base }.distinctBy { it.branch }.forEach {
            out += Item(Act.INFO, null, "branch ${it.branch}", "\"${it.name}\" अभी चल रहा है (PR नहीं)", it, it.branch)
        }
        return out
    }

    /** जिन पर user की हाँ से कुछ करना है */
    fun actionable(items: List<Item>) = items.filter { it.act in setOf(Act.RESTART, Act.FIX, Act.CHECK, Act.MERGE, Act.RELEASE) }

    /**
     * खुले PR के काम जो रिपो डॉक्टर अपने-आप करता है (हाँ की ज़रूरत नहीं): fail ठीक करना, फँसा run दोबारा, check चलाना,
     * और चलते checks पर नज़र (पूरे होने तक). सब उसी PR की branch पर - merge कभी नहीं.
     * मेरी टीम जिस PR पर अभी काम कर रही है (WAIT बिना run) वह नहीं.
     */
    fun auto(items: List<Item>) = items.filter { it.pr != null &&
        (it.act in setOf(Act.RESTART, Act.FIX, Act.CHECK) || it.act == Act.WAIT && it.run != null) }

    /** जिन पर user की हाँ चाहिए: merge, release, और main का सुधार */
    fun needYes(items: List<Item>): List<Item> { val a = auto(items).toSet(); return actionable(items).filter { it !in a } }

    private fun icon(a: Act) = when (a) {
        Act.WAIT -> "⏳"; Act.RESTART -> "🧊"; Act.FIX -> "❌"; Act.CHECK -> "❔"; Act.CONFLICT -> "⚔"; Act.MERGE -> "✅"; Act.RELEASE -> "📦"; Act.INFO -> "🌿"
    }

    fun todo(i: Item): String = when (i.act) {
        Act.RESTART -> "अटका run रोककर दोबारा चलाऊँगा"
        Act.FIX -> if (i.pr != null) "log पढ़ूँगा: GitHub की दिक्कत हुई तो दोबारा चलाऊँगा, code की गलती हुई तो 11 एजेंट उसी PR की branch पर ठीक करेंगे"
            else "log पढ़ूँगा: GitHub की दिक्कत हुई तो दोबारा चलाऊँगा, code की गलती हुई तो 11 एजेंट नई branch + PR से ठीक करेंगे"
        Act.CHECK -> "उस branch पर check चलाऊँगा"
        Act.MERGE -> "दोबारा जाँच → merge → APK release (पक्का करके)"
        Act.WAIT -> "checks पूरे होने तक नज़र रखूँगा: पास = merge के लिए आपसे पूछूँगा, fail = log पढ़कर 11 एजेंट उसी PR की branch पर ठीक करेंगे"
        Act.RELEASE -> "release build चलाकर APK release पक्का करूँगा"
        else -> ""
    }

    fun report(s: Snap, items: List<Item>): String = buildString {
        append("🩺 रिपो डॉक्टर — ${s.repo} (${s.base})\n")
        val prs = items.filter { it.pr != null }
        append(if (s.prs.isEmpty()) "• कोई PR खुला नहीं है\n" else "• खुले PR: ${s.prs.size}\n")
        for (i in prs) append("${icon(i.act)} ${i.title}\n   ${i.state}\n")
        items.filter { it.pr == null && it.act != Act.INFO }.forEach { append("${icon(it.act)} ${it.title}: ${it.state}\n") }
        if (items.none { it.pr == null && it.act != Act.INFO }) append("✅ ${s.base} का build ठीक" +
            (s.release?.let { " • release ${it.name.ifBlank { it.tag }} ताज़ा है" } ?: "") + "\n")
        val info = items.filter { it.act == Act.INFO }
        if (info.isNotEmpty()) { append("\nदूसरे चल रहे काम:\n"); info.take(8).forEach { append("${icon(it.act)} ${it.title}: ${it.state}\n") } }
        val acts = actionable(items)
        val auto = auto(items)
        val yes = needYes(items)
        if (auto.isNotEmpty()) {
            append("\nमैं यह करूँगा (अपने-आप, अभी — सिर्फ़ PR की branch पर सुधार, merge नहीं):\n")
            auto.forEachIndexed { n, i -> append("${n + 1}. ${i.title} — ${todo(i)}\n") }
            append("   ठीक होकर सारे checks पास होते ही merge के लिए आपसे पूछूँगा।\n")
            if (yes.isNotEmpty()) {
                append("\nआपकी हाँ से:\n")
                yes.forEachIndexed { n, i -> append("${n + 1}. ${i.title} — ${todo(i)}\n") }
                append("\n\"हाँ\" बोलें/लिखें तो ये भी करूँ। \"नहीं\" = सिर्फ़ ऊपर वाला सुधार।")
            }
        } else if (acts.isEmpty()) {
            append(if (items.any { it.act == Act.WAIT }) "\nअभी कुछ करने की ज़रूरत नहीं — चल रहे checks पूरे होने दें, फिर \"रिपो चेक करो\" बोलें।"
                else "\nसब ठीक है — कुछ ठीक करने को नहीं।")
            if (items.any { it.act == Act.CONFLICT }) append("\nConflict वाले PR अपने-आप merge नहीं होंगे — बताइए तो वही काम main से नई branch पर दोबारा करवा दूँ।")
        } else {
            append("\nमैं यह करूँगा (क्रम से):\n")
            acts.forEachIndexed { n, i -> append("${n + 1}. ${i.title} — ${todo(i)}\n") }
            if (acts.any { it.act == Act.FIX || it.act == Act.RESTART || it.act == Act.CHECK } && acts.none { it.act == Act.MERGE && it.pr != null })
                append("   ठीक होकर सारे checks पास होते ही वह PR भी merge करके APK release करूँगा।\n")
            append("\n\"हाँ\" बोलें/लिखें तो शुरू करूँ। \"नहीं\" = कुछ नहीं होगा। सिर्फ़ एक PR के लिए: \"PR #N ठीक करो\" / \"PR #N merge करो\"।")
        }
    }

    // ------------------------------------------------------------------ user की बात
    /** अपने-आप में साफ़: रिपो/PR की हालत पूछना */
    private val askWords = listOf("रिपो चेक", "रिपोजिटरी चेक", "रिपॉजिटरी चेक", "रिपोज़िटरी चेक", "repo check", "check repo", "repo status", "रिपो की हालत", "रिपो का हाल",
        "रिपो में क्या", "repo me kya", "रिपो देखो", "रिपो में देखो", "repo dekho", "रिपो डॉक्टर", "repo doctor",
        "क्या चल रहा", "क्या काम चल", "कोई काम चल", "kya chal raha", "koi kaam chal",
        "कौन सा pr", "कौन-सा pr", "कौन से pr", "कितने pr", "pr की हालत", "pr का हाल", "pr चेक", "pr check", "pr status", "खुले pr", "open pr")
    /** सिर्फ़ तब जब साथ में रिपो/PR/build की बात हो ("login fail हुआ" = नया बग, रिपो डॉक्टर नहीं) */
    private val troubleWords = listOf("फँसा", "फंसा", "अटका", "अटक", "stuck", "fail", "फेल", "खराबी", "गड़बड़", "error", "एरर", "रुका")
    private val repoWords = listOf("pr", "build", "बिल्ड", "check", "चेक", "workflow", "action", "रिपो", "repo", "apk", "release", "रिलीज़", "रिलीज", "merge", "मर्ज")

    /** "रिपो चेक करो", "क्या चल रहा है", "कोई PR फँसा है?" … */
    fun isStatusAsk(text: String): Boolean {
        val low = text.lowercase(Locale.ROOT).replace("पीआर", "pr").replace("p.r.", "pr")
        if (askWords.any { low.contains(it) }) return true
        val words = low.split(Regex("[\\s,.!?।:;\"'()#-]+")).filter { it.isNotBlank() }
        return troubleWords.any { low.contains(it) } && words.any { w -> repoWords.any { w == it || w.startsWith(it) && it.length >= 4 } } &&
            prNumber(text) == null
    }

    /** "PR #42", "pr 42", "पीआर 42" → 42 */
    fun prNumber(text: String): Int? {
        val low = text.lowercase(Locale.ROOT).replace("पीआर", "pr")
        return Regex("""\bpr\s*(?:नंबर|number|no\.?)?\s*#?\s*(\d{1,6})""").find(low)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""#(\d{1,6})""").find(low)?.groupValues?.get(1)?.toIntOrNull()
    }

    private val fixWords = listOf("ठीक", "सही कर", "fix", "theek", "thik", "सुधार")
    fun isFixAsk(text: String) = fixWords.any { text.lowercase(Locale.ROOT).contains(it) }

    /** 11 एजेंट के लिए साफ़ काम (fail check) - user की हाँ पहले ही मिल चुकी */
    fun fixTask(i: Item, base: String, errors: String): String =
        (if (i.pr != null) "The check \"${i.run?.name}\" FAILS on PR #${i.pr} (branch ${i.branch}). Fix the root cause ON THIS BRANCH so that all checks pass."
        else "The build \"${i.run?.name}\" FAILS on $base. Fix the root cause on a new branch + PR so that all checks pass.") +
            " Keep every existing feature and test exactly as it is; change only what the error needs.\n" +
            "ERROR (from the GitHub Actions log):\n" + errors.take(6_000)
}
