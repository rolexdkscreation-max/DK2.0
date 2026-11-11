package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Coding agent (v2): poora repo EK zip se phone me (tez padhna + asli grep line number ke saath),
 * ek baar me kai files padhna, kai edit ek saath, whitespace-sahansheel edit, diff se khud jaanch,
 * "done" se pehle syntax jaanch (bracket / XML / JSON), aur push ke baad GitHub Actions build dekhkar galti khud theek.
 */
class CodingAgent(internal val gh: GitHub, var repo: String, var branch: String, private val ai: CoderAi) {
    /** ऑटोनोमस मोड की key वाला agent (अलग key = नया agent बने) */
    val aiNote get() = ai.autoTag
    /** v4.4: यह मॉडल एक बार में कितना पढ़ सके (2 लाख / 5 लाख) */
    internal val budget: Int get() = ai.budget

    /** User ki chuni hui (main) branch. Surakshit PR mode me kaam hamesha alag feature branch par, PR isi me */
    val baseBranch = branch
    /** सुरक्षित PR मोड: main par seedha push kabhi nahi -> feature branch -> PR -> build/tests -> user Merge */
    var safePr = true
    /** User ने खुद पुराना फ़ीचर बदलने/हटाने को कहा (जानबूझकर बदलाव) - तब पहले backup, फिर बदलाव */
    var intentional = false
    /** पुराने फ़ीचर वाले हिस्से का backup (branch) - ज़रूरत पड़ने पर वापस लाने के लिए */
    var backupBranch = ""
        private set
    /** Bug/build की असली जड़ (root cause) - PR और रिपोर्ट में */
    var rootCause = ""
        private set
    /** इस feature branch पर अब तक कुछ push हुआ? (नहीं = खाली branch, काम के बाद हटा दो) */
    private var pushedToBranch = false
    /** Is kaam ka khula PR (null = abhi nahi bana) */
    var pr: GitHub.Pull? = null
        private set
    /** PR nahi ban paya to wajah (token permission etc.) */
    var prError = ""
        private set
    /** Aakhri merge ka commit sha (main par release APK build dekhne ke liye) */
    var lastMergeSha = ""
        private set
    /** checks चल रहे थे → इंतज़ार किया, सब पास → merge नहीं किया, user से हाँ दोबारा माँगी (गलती नहीं) */
    var lastMergeAsk = false
        private set
    /** PR dobara banane ke liye (pehli baar token ki wajah se na bana ho) */
    private var lastPushMsg = ""
    private var lastChangeText = ""
    private var lastTask = ""
    private var lastSummary = ""
    /** AGENTS.md + workflows - Reviewer ko bhi dikhate hain */
    internal var projectCtx = ""

    /** stopped = एजेंट खुद रुका (एक ही चेतावनी बार-बार) - बदलाव push नहीं करने, user को वजह बतानी */
    data class Outcome(val summary: String, val commit: String, val question: Boolean, val stopped: Boolean = false)
    data class Build(val ok: Boolean, val url: String, val log: String)
    data class Call(val o: JSONObject, val file: String?, val finds: List<String>, val replaces: List<String>)
    /** Reviewer ka faisla */
    data class Review(val pass: Boolean, val issues: List<String>, val tested: String, val complete: Boolean = true)

    /** Team mode: Builder kabhi user se nahi poochta (khud sahi andaza), aur extra cheez sirf "suggest" karta hai */
    var teamMode = false
    /** Builder/Reviewer ko koi zaroori/faydemand cheez dikhi jo user ne nahi maangi -> (title, kyun, kaam) */
    var onSuggest: ((String, String, String) -> Unit)? = null

    /** path -> naya content (null = mitana) - abhi push nahi hua */
    val staged = LinkedHashMap<String, String?>()
    internal val original = HashMap<String, String?>()
    internal val msgs = mutableListOf<Pair<String, String>>()
    internal var files: List<GitHub.Entry>? = null
    /** Poore repo ki copy (zip se) - null = nahi mili, tab har file API se */
    internal var snap: Map<String, String>? = null
    /** snapshot में न आई (binary/बड़ी) फ़ाइलों के path - resource जाँच के लिए (GitHub.TRUNCATED = रिपो पूरा नहीं आया) */
    internal var snapSkipped: Set<String> = emptySet()
    private var checkWarned = false
    /** इस काम में कौन-सी फ़ाइलें पढ़ीं - बिना पढ़े पूरी फ़ाइल दोबारा नहीं लिखनी */
    internal val readInTask = mutableSetOf<String>()
    /** Aakhri push ka commit sha (build dekhne ke liye) */
    var lastSha = ""
        private set

    /** एक काम में ज़्यादा से ज़्यादा कदम (Settings → कोडिंग एजेंट; default 5) */
    var maxSteps = DEFAULT_STEPS
    /** इस repo में लिखने की इजाज़त एक बार जाँच ली (हर push पर नहीं) */
    private var writeChecked = false

    fun reset() {
        staged.clear(); original.clear(); msgs.clear(); files = null; snap = null; snapSkipped = emptySet(); checkWarned = false
        branch = baseBranch; pr = null; prError = ""; lastTask = ""; lastSummary = ""; projectCtx = ""
        lastPushMsg = ""; lastChangeText = ""
        intentional = false; backupBranch = ""; rootCause = ""; pushedToBranch = false
        workNote = ""; lastMergeAsk = false
    }

    /**
     * "आगे करो": [reset] जैसा, पर उसी रिपो की फ़ोन में रखी फ़ाइलें (snapshot) बची रहें - पूरा रिपो दोबारा न उतरे.
     * snapshot तभी बचे जब वह उसी branch का हो जिस पर आगे बढ़ना है (या branch अभी बनी ही नहीं थी).
     */
    fun resetKeepFiles(resumeBranch: String) {
        val keep = snap != null && (resumeBranch.isBlank() || resumeBranch == branch)
        val s = snap; val f = files; val sk = snapSkipped
        reset()
        if (keep) { snap = s; files = f; snapSkipped = sk }
    }

    /** App restart ke baad pichhla khula PR wapas (Merge card ke liye). GitHub par band/merge mila to mergePr/push khud saaf karte hain */
    fun restorePr(p: GitHub.Pull) {
        if (!safePr || SafePr.isBlocked(p.head, baseBranch)) return
        pr = p; branch = p.head; prError = ""; pushedToBranch = true
    }

    /**
     * "वहीं से आगे" (ऐप बंद होने के बाद): पिछली feature branch वापस - GitHub पर हो तभी. उस पर push हुआ माना
     * (ताकि खाली समझकर मिटे नहीं). @return true = branch मिली
     */
    fun restoreBranch(name: String): Boolean {
        if (!safePr || SafePr.isBlocked(name, baseBranch)) return false
        val ok = try { gh.remoteBranchSha(repo, name).isNotBlank() } catch (_: Exception) { false }
        if (ok) { branch = name; pushedToBranch = true }
        return ok
    }

    /**
     * काम शुरू होते ही (कोड बदलने से पहले): main से नई feature branch. सारा काम, push और टेस्ट इसी में;
     * main को merge तक हाथ नहीं लगता. पहले से खुला PR/feature branch हो तो वही.
     * @return branch का नाम
     */
    suspend fun ensureBranch(task: String): String = withContext(Dispatchers.IO) {
        if (!safePr) return@withContext branch
        // PR GitHub पर ही merge/बंद हो गया हो -> पुरानी branch नहीं, नई
        pr?.let { old ->
            val open = try { gh.pullState(repo, old.number).open } catch (_: Exception) { true }
            if (!open) { pr = null; branch = baseBranch; pushedToBranch = false }
        }
        if (!SafePr.isBlocked(branch, baseBranch)) return@withContext branch
        var name = SafePr.branchName(task)
        try { gh.createBranch(repo, name, baseBranch) } catch (e: GitHub.Err) {
            // isi minute me isi naam ki branch pehle se (422) -> naam ke aage chhota number
            if (e.code != 422) throw e
            name = "$name-" + (System.currentTimeMillis() / 1000 % 100000)
            gh.createBranch(repo, name, baseBranch)
        }
        branch = name; pushedToBranch = false
        // पहली बार (msgs खाली) system prompt में ही branch है; बाद में बताओ
        if (msgs.isNotEmpty()) {
            msgs.add("user" to "NOTE: working branch is now \"$name\" (created from $baseBranch). All work and pushes go here; a PR goes to $baseBranch.")
            msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"ठीक है\"}")
        }
        name
    }

    // ---------------------------------------------------------------- 7-एजेंट सिस्टम के हाथ (पुराना एजेंट इन्हें इस्तेमाल नहीं करता)
    /** true = नया PR draft बने (7-एजेंट: टेस्ट के लिए draft, पास होने पर ही review के लिए तैयार). पुराना एजेंट: false */
    @Volatile var draftPr = false
    /** true = push पर PR नहीं (7-एजेंट: पहले branch पर build, पास हो तभी teamOpenPr). पुराना एजेंट: false */
    @Volatile var prLater = false

    /** रिपो की फ़ाइलें फ़ोन में (पहले से हों तो कुछ नहीं) */
    suspend fun teamLoad(onStep: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (files != null || snap != null) return@withContext
        onStep("$repo ($branch) की फ़ाइलें ला रहा हूँ…")
        val skip = HashSet<String>()
        snap = try { gh.snapshot(repo, branch, skip).also { onStep("पूरा रिपो फ़ोन में आ गया (${it.size} फ़ाइलें)") } } catch (e: Exception) { null }
        snapSkipped = skip
        files = snap?.let { m -> m.map { GitHub.Entry(it.key, it.value.length) } } ?: gh.tree(repo, branch)
    }
    fun teamList(): List<String> = allPaths()
    fun teamRead(path: String): String? = try { current(path) } catch (e: GitHub.Err) { if (e.code == 404) null else throw e }
    fun teamStage(path: String, content: String?) {
        // नवंबर 2026: binary (तस्वीर/font) text से लिखी तो टूटती है (dk_theme_bg.webp) - कभी stage नहीं
        if (content != null && BinaryGuard.isBinary(path)) { DKLog.d("Coder: " + BinaryGuard.refusal(path)); return }
        remember(path); staged[path] = content
    }
    /** PR के विवरण के लिए: काम, क्या बदला, जड़ */
    fun teamInfo(task: String, summary: String, root: String) { lastTask = task; lastSummary = summary; rootCause = root }

    /** 📒 एजेंट की काम-कॉपी (markdown) - PR के विवरण में. PR न बना हो तो यहीं रहती है और PR बनते ही उसमें जाती है */
    var workNote = ""
        private set

    /** काम-कॉपी PR के विवरण में: सिर्फ़ काम-कॉपी वाला हिस्सा बदलता है, बाकी विवरण (किसी और का लिखा भी) अछूता */
    /** v3.89: काम-कॉपी सिर्फ़ याद रखो (फ़ोन में सहेजी जा चुकी) - अगली बार PR खुलने/बदलने पर उसी में जाएगी, अलग API call नहीं */
    fun teamKeepNote(md: String) { workNote = md }

    suspend fun teamSaveNote(md: String) = withContext(Dispatchers.IO) {
        workNote = md
        val p = pr ?: return@withContext
        try {
            val body = gh.pullDetail(repo, p.number)?.body.orEmpty()
            val nb = WorkNote.inBody(body, md)
            if (nb != body) gh.editPullBody(repo, p.number, nb)
        } catch (e: Exception) { DKLog.d("Coder: काम-कॉपी PR में नहीं लिखी: ${e.message?.take(120)}") }
    }

    /** खुले PR का विवरण (पिछली काम-कॉपी पढ़ने के लिए). "" = PR नहीं / पढ़ नहीं पाया */
    suspend fun teamPrBody(): String = withContext(Dispatchers.IO) {
        val p = pr ?: return@withContext ""
        try { gh.pullDetail(repo, p.number)?.body.orEmpty() } catch (_: Exception) { "" }
    }
    /** पूरा रिपो (बदलाव समेत) - नाम-जाँच के लिए. null = snapshot नहीं (बड़ा रिपो) */
    fun teamSnapshot(): Map<String, String>? = snap?.let { m ->
        HashMap(m).apply { staged.forEach { (p, c) -> if (c == null) remove(p) else put(p, c) } }
    }

    // ---------------------------------------------------------------- v4.4: पूरे काम का हिसाब (main से) + टूटी फ़ाइल वापस
    private val baseCache = HashMap<String, String?>()

    /** base branch (main) पर फ़ाइल = इस काम से पहले की असली शक्ल (दोबारा चले काम में भी सही) */
    fun baseRead(path: String): String? = synchronized(baseCache) {
        if (baseCache.containsKey(path)) return baseCache[path]
        val r = try { gh.read(repo, path, baseBranch) } catch (_: Exception) { null }
        baseCache[path] = r; r
    }

    /** इस काम ने main से क्या बदला: पहले के push (GitHub compare) + अभी के staged. हटाई/आधी फ़ाइलें पहचान के साथ */
    fun taskChanges(): List<CodeGuard.Change> {
        val m = LinkedHashMap<String, CodeGuard.Change>()
        if (branch.isNotBlank() && branch != baseBranch) try {
            gh.compareFiles(repo, baseBranch, branch).forEach { m[it.path] = it }
        } catch (e: Exception) { DKLog.d("Coder: main से तुलना नहीं मिली: ${e.message}") }
        for ((p, c) in staged.toMap()) {
            val b = baseRead(p)
            if (b == null && c == null) { m.remove(p); continue }
            val bl = CodeGuard.lines(b); val al = CodeGuard.lines(c)
            m[p] = CodeGuard.Change(p, if (c == null) "removed" else if (b == null) "added" else "modified", maxOf(0, al - bl), maxOf(0, bl - al))
        }
        return m.values.map { ch -> if (ch.status == "added" || ch.deletions < 30) ch else ch.copy(baseLines = CodeGuard.lines(baseRead(ch.path))) }
    }

    /** डिबगर के लिए: पूरे काम का हिसाब (हटाई/आधी फ़ाइलें सबसे ऊपर) */
    fun footprintText(): String = try { CodeGuard.footprint(taskChanges(), baseBranch) } catch (_: Exception) { "" }

    /** v4.4: build fail की जड़ = इसी काम ने फ़ाइल हटाई/आधी की -> main से हूबहू वापस (staged). लौटाता है क्या वापस आया */
    fun restoreDamaged(log: String): List<CodeGuard.Damage> {
        val d = try { CodeGuard.damage(log, taskChanges()) { baseRead(it) } } catch (e: Exception) { DKLog.d("Coder: नुकसान जाँच नहीं हुई: ${e.message}"); emptyList() }
        d.forEach { teamStage(it.path, it.baseContent) }
        if (d.isNotEmpty()) DKLog.d("Coder: main से वापस लाई: " + d.joinToString { it.path })
        return d
    }

    /**
     * नवंबर 2026 (feature/dk-1110-2225): test fail + उसी test/log में इसी काम की बदली binary फ़ाइल का नाम =
     * फ़ाइल main से हूबहू वापस (blob sha से, सीधा commit). @return वापस आई फ़ाइलें
     */
    fun restoreBinaries(log: String): List<String> {
        if (branch.isBlank() || branch == baseBranch) return emptyList()
        val changed = try { gh.compareFiles(repo, baseBranch, branch).map { it.path }.filter { BinaryGuard.isBinary(it) } } catch (_: Exception) { emptyList() }
        if (changed.isEmpty()) return emptyList()
        val bad = BinaryGuard.culprits(log, changed, { p -> try { teamRead(p) } catch (_: Exception) { null } }, allPaths())
        if (bad.isEmpty()) return emptyList()
        val url = gh.restoreFromBase(repo, branch, baseBranch, bad, "DK: binary फ़ाइल $baseBranch से हूबहू वापस (" + bad.joinToString { it.substringAfterLast('/') } + ")")
        if (url.isNotBlank()) { lastSha = url.substringAfterLast('/'); pushedToBranch = true }
        DKLog.d("Coder: binary main से वापस: " + bad.joinToString())
        return bad
    }

    /** v4.4: फ़ाइल हटाने से पहले - बाकी रिपो में इस्तेमाल है? (रिपो की copy न हो तो GitHub खोज) */
    fun deleteBlock(path: String, content: String?): String? {
        if (content == null) return null
        val gone = staged.filterValues { it == null }.keys + path
        teamSnapshot()?.let { return CodeGuard.deleteProblem(path, content, it, gone) }
        val users = try { gh.search(repo, CodeGuard.stem(path)).filter { it != path && it !in gone } } catch (_: Exception) { emptyList() }
        return if (users.isEmpty()) null else "$path is still used by: ${users.take(6).joinToString()} - deleting it breaks the build. Remove ONLY the code the task asks for (small FIND/REPLACE edits), keep the file."
    }

    /** रिपो के GitHub Actions workflows: (फ़ाइल का नाम, content) */
    fun teamWorkflows(): List<Pair<String, String>> = allPaths()
        .filter { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) }
        .mapNotNull { p -> (try { current(p) } catch (_: Exception) { null })?.let { p.substringAfterLast('/') to it } }

    /** इस branch पर workflow चलाओ (PR से पहले build). @return "" = चल गया, वरना वजह */
    suspend fun teamDispatch(workflowFile: String): String = withContext(Dispatchers.IO) {
        try { gh.dispatchWorkflow(repo, workflowFile, branch); "" } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
    }

    /** build पास होने के बाद PR खोलो. @return "" = बना (pr में), वरना वजह */
    suspend fun teamOpenPr(draft: Boolean): String = withContext(Dispatchers.IO) {
        if (pr != null) return@withContext ""
        val prot = SafePr.protectedIn(staged.keys)
        val body = SafePr.prBody(lastSummary, lastChangeText, prot, backupBranch, rootCause, workNote)
        val title = lastPushMsg.ifBlank { lastTask.take(80) }
        try {
            pr = gh.openPullFor(repo, branch) ?: if (draft) gh.createDraftPull(repo, branch, baseBranch, title, body)
                else gh.createPull(repo, branch, baseBranch, title, body)
            prError = ""; ""
        } catch (e: Exception) {
            prError = (e.message ?: e.javaClass.simpleName).take(200) + " — token में Pull requests: Read and write दें, या GitHub पर $branch से PR खुद बनाएँ"
            prError
        }
    }

    /** टेस्ट पास: draft PR → review के लिए तैयार. @return "" = ठीक, वरना वजह */
    suspend fun teamReady(): String = withContext(Dispatchers.IO) {
        val p = pr ?: return@withContext "PR नहीं है"
        try { if (gh.isDraft(repo, p.number)) gh.markReady(repo, p.number); "" } catch (e: Exception) { e.message ?: "draft नहीं हटा" }
    }

    /** काम में कुछ push नहीं हुआ (सिर्फ़ सवाल/fail) -> बनी हुई खाली feature branch हटा दो, GitHub साफ़ रहे */
    suspend fun dropEmptyBranch() = withContext(Dispatchers.IO) {
        if (!safePr || pushedToBranch || pr != null || staged.isNotEmpty() || SafePr.isBlocked(branch, baseBranch)) return@withContext
        val br = branch
        try { gh.deleteBranch(repo, br); DKLog.d("Coder: खाली branch $br हटाई") } catch (e: Exception) { DKLog.d("Coder: खाली branch नहीं हटी: ${e.message}") }
        branch = baseBranch
    }

    /**
     * पुराना फ़ीचर जानबूझकर बदलना/हटाना: बदलाव push होने से पहले main की अभी की हालत backup branch में
     * (backup/dk-…). ज़रूरत हो तो उसी से पुराना फ़ीचर वापस. एक काम में एक बार.
     * @return backup branch, या "" (नहीं बना - वजह log में; काम फिर भी PR में ही रहता है, main सुरक्षित)
     */
    suspend fun backupOld(task: String): String = withContext(Dispatchers.IO) {
        if (backupBranch.isNotBlank()) return@withContext backupBranch
        var name = SafePr.backupName(task)
        try {
            try { gh.createBranch(repo, name, baseBranch) } catch (e: GitHub.Err) {
                if (e.code != 422) throw e
                name = "$name-" + (System.currentTimeMillis() / 1000 % 100000)
                gh.createBranch(repo, name, baseBranch)
            }
            backupBranch = name
            pr?.let { p -> try { gh.editPullBody(repo, p.number, SafePr.prBody(lastSummary, changeList().ifBlank { lastChangeText }, SafePr.protectedIn(staged.keys), backupBranch, rootCause, workNote)) } catch (_: Exception) {} }
            name
        } catch (e: Exception) { DKLog.d("Coder: backup branch नहीं बनी: ${e.message}"); "" }
    }

    /** Push ho gaya (feature branch) par PR nahi bana -> screen par "PR फिर बनाएँ" */
    val canRetryPr get() = safePr && pr == null && prError.isNotBlank() && !SafePr.isBlocked(branch, baseBranch)

    /** PR dobara banao (token ki ijazat theek karne ke baad) */
    suspend fun retryPr(): String = withContext(Dispatchers.IO) {
        pr?.let { return@withContext "PR #${it.number} पहले से बना है: ${it.url}" }
        if (!safePr || SafePr.isBlocked(branch, baseBranch)) return@withContext "अभी कोई feature branch push नहीं हुई"
        prError = ""
        pr = try {
            gh.openPullFor(repo, branch) ?: gh.createPull(repo, branch, baseBranch, lastPushMsg.ifBlank { "DK: code changes" },
                SafePr.prBody(lastSummary, lastChangeText, emptyList(), backupBranch, rootCause, workNote))
        } catch (e: Exception) {
            prError = (e.message ?: e.javaClass.simpleName).take(200) +
                " — token में Pull requests: Read and write दें, या GitHub पर $branch से PR खुद बनाएँ"
            null
        }
        pr?.let { "✓ PR #${it.number} बन गया — tests + build पूरे होने पर \"Merge करें\" दबाएँ\n${it.url}" } ?: "PR फिर नहीं बना: $prError"
    }

    /** Ek kaam chalao (ya pichhle "ask" ka jawab). onStep = UI ke liye chhoti line */
    suspend fun run(task: String, onStep: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        if (msgs.isEmpty()) {
            onStep("$repo ($branch) की फ़ाइलें ला रहा हूँ…")
            val skip = HashSet<String>()
            snap = try { gh.snapshot(repo, branch, skip).also { onStep("पूरा repo फ़ोन में आ गया (${it.size} फ़ाइलें)") } }
                   catch (e: Exception) { DKLog.d("Coder: zip nahi mila (${e.message}) - har file API se"); null }
            snapSkipped = skip
            files = snap?.let { m -> m.map { GitHub.Entry(it.key, it.value.length) } } ?: gh.tree(repo, branch)
            // पूरी list नहीं - सिर्फ़ ज़रूरी (code/res/build) फ़ाइलें; बाकी list/grep से
            val list = importantPaths(files!!.map { it.path }, 250).joinToString("\n")
            msgs.add("user" to "Repository files (${files!!.size}, showing the important ones):\n$list" + (if (files!!.size > 250) "\n…(more: use list with a folder, or grep)" else ""))
            msgs.add("model" to "{\"tool\":\"list\",\"path\":\"\"}")
            projectCtx = loadProjectContext()
            if (projectCtx.isNotBlank()) {
                onStep("repo के नियम, agents और GitHub Actions समझ लिए")
                msgs.add("user" to "PROJECT CONTEXT (read and follow before any change):\n" + projectCtx.take(maxOf(6000, budget / 20)))
                msgs.add("model" to "{\"tool\":\"diff\"}")
                msgs.add("user" to "RESULT (diff):\n(no staged changes)")
            }
            msgs.add("user" to "OK. Waiting for the task.")
            msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"क्या करना है?\"}")
        }
        checkWarned = false
        readInTask.clear()
        var planShown = false
        if (!task.startsWith("BUILD FAILED") && !task.startsWith("REVIEWER") && !task.startsWith("Coordinator:")) lastTask = task
        msgs.add("user" to "TASK FROM USER: $task" +
            if (staged.isNotEmpty()) "\n(Already staged, not pushed: ${staged.keys.joinToString()})" else "")
        var step = 0
        val guard = WarnGuard(2)
        /** एक ही चेतावनी लगातार 2 बार -> रुककर वजह बताओ (बार-बार का चक्र नहीं) */
        fun repeated(w: String): Outcome? = if (!guard.hit(w)) null else
            Outcome("एजेंट रुक गया: एक ही चेतावनी लगातार 2 बार आई —\n\"${w.lineSequence().first().take(200)}\"\n" +
                "आगे: काम को छोटे और साफ़ हिस्से में लिखकर फिर भेजें (कौन-सी फ़ाइल, क्या बदलना है)।", "", false, stopped = true)
        var looks = 0          // बदलाव से पहले के पढ़ने वाले कदम (समझने का हिस्सा)
        var changed = false
        var unusedWarned = false
        val fixTask = SafePr.isFixTask(task)
        var rootWarned = false
        val changedFiles = mutableSetOf<String>()   // हर बदली फ़ाइल = 1 अतिरिक्त कदम (कई फ़ाइलों वाला काम आधा न रहे)
        while (true) {
            ensureActive()
            // पहले समझना: बदलाव से पहले के 2 पढ़ने वाले कदम सीमा में नहीं गिने जाते
            if (++step > stepLimit(maxSteps, looks, changedFiles.size)) return@withContext Outcome("$maxSteps कदम हो गए, यहीं रोका। अब तक के बदलाव नीचे हैं। " +
                "(ज़्यादा कदम चाहिए तो Settings → कोडिंग एजेंट → \"एक काम में ज़्यादा से ज़्यादा कदम\")", "DK: partial changes", false)
            // हर कदम के बीच थोड़ा रुको (free API की प्रति-मिनट limit जल्दी खत्म न हो)
            if (step > 1) delay(STEP_DELAY_MS)
            val reply = try { ai.chat(system(), view()) } catch (e: AiException) {
                throw AiException(e.code, "${ai.label}: ${e.message}")
            }
            msgs.add("model" to reply)
            val call = parse(reply)
            if (call == null) {
                val w = "ERROR: your reply had no valid JSON tool call on the first line. Reply with exactly one tool call."
                repeated(w)?.let { onStep("चेतावनी: AI ने दो बार गलत जवाब दिया — रुक गया"); return@withContext it }
                onStep("…")
                msgs.add("user" to w)
                continue
            }
            val o = call.o
            val tool = o.optString("tool"); val path = o.optString("path").trim().trimStart('/')
            // पहले बताओ क्या करने वाला है (एक बार): AI की "plan" लाइन, न हो तो पहले कदम से बनी
            if (!planShown && tool != "ask") {
                planShown = true
                val pl = o.optString("plan").trim().take(300).ifBlank { autoPlan(tool, path, o.optJSONArray("paths")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(), o.optString("pattern")) }
                if (pl.isNotBlank()) { onStep("योजना: $pl"); if (CoderReport.plan().isBlank()) CoderReport.plan(pl) }
            }
            if (tool == "ask") return@withContext Outcome(o.optString("question").ifBlank { "थोड़ा और बताइए?" }, "", true)
            if (tool == "done") {
                // staged बदलाव यहाँ push नहीं होते - "done" के बाद ऐप GitHub API से एक commit में भेजता है.
                // (पहले यहाँ "staged हैं पर push नहीं हुए" वाली जाँच थी: done हर बार लौट जाता था = अंतहीन चक्र)
                val problems = check()
                if (problems.isNotEmpty() && !checkWarned) {
                    checkWarned = true
                    onStep("जाँच में दिक्कत मिली, ठीक करवा रहा हूँ…")
                    msgs.add("user" to "CHECK FAILED before done (these would likely break the build):\n$problems\n" +
                        "Fix them (read the file again), or call done again if you are sure they are false alarms.")
                    continue
                }
                // नई फ़ाइल बनी पर कहीं इस्तेमाल नहीं = काम अधूरा (एक बार याद दिलाओ)
                val unused = unusedNewFiles(staged.filter { (p, c) -> c != null && original.containsKey(p) && original[p] == null }.mapValues { it.value!! },
                    staged.filterValues { it != null }.mapValues { it.value!! })
                if (unused.isNotEmpty() && !unusedWarned) {
                    unusedWarned = true
                    onStep("नई फ़ाइल अभी कहीं इस्तेमाल नहीं हुई — बाकी जगह भी जोड़ने को कहा")
                    msgs.add("user" to "INCOMPLETE before done: you created ${unused.joinToString { "${it.first} (${it.second})" }} but no other changed file uses it. " +
                        "Grep for the places the task mentions and update them to use it now. Call done again only if the task really asked for just this file.")
                    continue
                }
                // bug / reviewer / build की गलती: पहले असली जड़ (root cause), तभी fix माना जाए
                val rc = o.optString("root_cause").trim()
                if (fixTask && staged.isNotEmpty() && rc.length < 8 && !rootWarned) {
                    rootWarned = true
                    onStep("पहले समस्या की जड़ (root cause) बताने को कहा")
                    msgs.add("user" to "ROOT CAUSE REQUIRED before done: this task fixes a bug/failure. First make sure you found the REAL cause " +
                        "(read the exact code/log lines). Then call done again with \"root_cause\": \"file:line - what was wrong and why\". " +
                        "If your change only hides the symptom, fix the real cause first.")
                    continue
                }
                if (rc.isNotBlank()) { rootCause = rc.take(400); CoderReport.add("जड़ (root cause): ${rc.take(200)}", "बिल्डर ने यही असली वजह ठीक की") }
                if (staged.isNotEmpty()) lastSummary = o.optString("summary")
                return@withContext Outcome(o.optString("summary").ifBlank { "हो गया।" },
                    o.optString("commit").ifBlank { if (staged.isEmpty()) "" else "DK: code changes" }, false)
            }
            val result = try { runTool(tool, path, call, onStep) } catch (e: GitHub.Err) { "GITHUB ERROR: ${e.message}" }
            if (result.startsWith("ERROR") || result.startsWith("NOT FOUND") || result.startsWith("GITHUB ERROR")) {
                repeated("$tool: $result")?.let { onStep("चेतावनी दोबारा आई — रुक गया"); return@withContext it }
            } else {
                guard.ok()
                if (tool in listOf("write", "edit", "delete")) { changed = true; changedFiles.add(path) }
                else if (!changed && tool in listOf("list", "read", "grep", "search")) looks++
            }
            msgs.add("user" to "RESULT ($tool${if (path.isNotBlank()) " $path" else ""}):\n$result")
        }
        Outcome("", "", false)
    }

    // ---------------------------------------------------------------- jaanch
    /** Push se pehle: bracket balance (Kotlin/Java), XML aur JSON sahi hai? */
    fun check(): String {
        val sb = StringBuilder()
        staged.forEach { (p, c) ->
            if (c == null) return@forEach
            val ext = p.substringAfterLast('.', "").lowercase()
            val err = when (ext) {
                "kt", "kts", "java", "gradle" -> brackets(c)
                "xml" -> try {
                    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(c.byteInputStream()); null
                } catch (e: Exception) { "XML error: ${e.message?.take(200)}" }
                "json" -> try { if (c.trimStart().startsWith("[")) JSONArray(c) else JSONObject(c); null } catch (e: Exception) { "JSON error: ${e.message?.take(200)}" }
                else -> null
            }
            if (err != null) sb.append("- $p: $err\n")
            if (c.contains("<<<FIND") || c.contains("FIND>>>") || c.contains("<<<FILE") || c.contains("REPLACE>>>"))
                sb.append("- $p: contains leftover <<<FIND/FILE markers\n")
        }
        // पुराने features के tests चुपके से हटाए/कम नहीं (user ने खुद फ़ीचर हटाने को न कहा हो)
        SafePr.testGuard(staged, staged.keys.associateWith { original[it] ?: snap?.get(it) }, intentional && SafePr.removesOldFeature(lastTask))
            .forEach { sb.append("- $it\n") }
        return sb.toString()
    }

    // ---------------------------------------------------------------- diff
    /** Saare staged badlaav unified diff jaise (2 line context) */
    fun diffAll(): String = staged.entries.joinToString("\n") { (p, c) ->
        val o = original[p]
        when {
            c == null -> "--- $p (DELETED, ${o?.lines()?.size ?: 0} lines)"
            o == null -> "+++ $p (NEW FILE, ${c.lines().size} lines)\n" + c.lines().take(400).joinToString("\n") { "+$it" }
            else -> "=== $p\n" + diff(o, c)
        }
    }

    /** Badlaav ki chhoti list: "+ naya", "- hataya", "~ badla (+a -b)" */
    fun changeList(): String = staged.entries.joinToString("\n") { (p, c) ->
        val o = original[p]
        when {
            c == null -> "−  $p (हटाया)"
            o == null -> "+  $p (नई, ${c.lines().size} लाइन)"
            else -> {
                val d = diff(o, c).lines()
                "~  $p (+${d.count { it.startsWith("+") }} −${d.count { it.startsWith("-") }})"
            }
        }
    }

    /**
     * Reviewer: staged badlaav ko bina user se pooche jaanchta hai (padhta, grep, raasta trace) aur faisla deta hai.
     * Static jaanch (bracket/XML/JSON) pehle se jod di jaati hai.
     */
    suspend fun review(task: String, acceptance: List<String>, round: Int, onStep: (String) -> Unit): Review = withContext(Dispatchers.IO) {
        if (staged.isEmpty()) return@withContext Review(true, emptyList(), "कोई बदलाव नहीं था", true)
        val rmsgs = mutableListOf<Pair<String, String>>()
        val static = check()
        val d = diffAll().let { val m = maxOf(20_000, budget / 3); if (it.length > m) it.take(m) + "\n…(diff cut - use read)" else it }   // v4.4: पूरा diff
        rmsgs.add("user" to "REVIEW ROUND $round\nUSER TASK: $task\n" +
            (if (acceptance.isNotEmpty()) "ACCEPTANCE CRITERIA:\n" + acceptance.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (projectCtx.isNotBlank()) "PROJECT RULES:\n${projectCtx.take(maxOf(5000, budget / 20))}\n" else "") +
            SafePr.protectedIn(staged.keys).let { if (it.isEmpty()) "" else "PROTECTED FILES CHANGED: ${it.joinToString()} (issue unless the task explicitly asks)\n" } +
            "STATIC CHECK: " + static.ifBlank { "OK (brackets/XML/JSON fine)" } + "\n" + guardNotes() + "\nSTAGED DIFF:\n$d")
        var bad = 0
        for (step in 1..(maxSteps + 2)) {
            ensureActive()
            if (step > 1) delay(STEP_DELAY_MS)
            val reply = try { ai.chat(reviewerSystem(), if (CoderContext.total(rmsgs) > budget) CoderContext.compact(rmsgs, budget) else rmsgs) } catch (e: AiException) { throw AiException(e.code, "Reviewer ${ai.label}: ${e.message}") }
            rmsgs.add("model" to reply)
            val call = parse(reply)
            if (call == null) {
                if (++bad >= 3) break
                rmsgs.add("user" to "ERROR: reply with exactly one JSON tool call."); continue
            }
            bad = 0
            val o = call.o; val tool = o.optString("tool"); val path = o.optString("path").trim().trimStart('/')
            if (tool == "verdict") {
                val arr = o.optJSONArray("issues") ?: JSONArray()
                val issues = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                val pass = o.optBoolean("pass", issues.isEmpty()) && issues.isEmpty() && static.isBlank()
                val allIssues = if (static.isNotBlank()) issues + static.lines().filter { it.isNotBlank() }.map { it.removePrefix("- ") } else issues
                return@withContext Review(pass, allIssues, o.optString("tested"), true)
            }
            val result = when (tool) {
                "list", "read", "grep", "search", "diff", "suggest" -> try { runTool(tool, path, call) { st -> onStep("रिव्यूअर: $st") } } catch (e: GitHub.Err) { "GITHUB ERROR: ${e.message}" }
                else -> "ERROR: reviewer is READ-ONLY. Use list/read/grep/diff, then verdict."
            }
            rmsgs.add("user" to "RESULT ($tool${if (path.isNotBlank()) " $path" else ""}):\n" + result.take(CoderContext.readChars(budget)))
        }
        // Reviewer faisla nahi de paya: static jaanch hi aadhaar
        Review(static.isBlank(), static.lines().filter { it.isNotBlank() }.map { it.removePrefix("- ") }, "रिव्यूअर पूरा जवाब नहीं दे पाया — सिर्फ़ static जाँच हुई", false)
    }

    // ---------------------------------------------------------------- push + build
    /**
     * Push: saare staged ek commit me.
     * Surakshit PR mode: main par kabhi nahi -> pehli baar nayi feature branch banti hai, phir PR (ya pehle wala PR update).
     */
    suspend fun push(message: String): String = withContext(Dispatchers.IO) {
        val msg = message.ifBlank { "DK: code changes" }
        val changeText = changeList()
        val prot = SafePr.protectedIn(staged.keys)
        // काम की feature branch (आमतौर पर काम शुरू होते ही बन चुकी; न बनी हो तो अब). PR बंद/merge हो गया हो तो नई
        ensureBranch(lastTask.ifBlank { msg })
        if (safePr && SafePr.isBlocked(branch, baseBranch)) throw IllegalStateException("सुरक्षित PR मोड: $branch पर सीधे push मना है")
        // token में Contents: Read and write है? (एक बार) - न हो तो साफ़ बताओ क्या बदलना है
        if (!writeChecked) {
            gh.checkWrite(repo)?.let { throw IllegalStateException(it) }
            writeChecked = true
        }
        // v4.4: आख़िरी पहरा - इस्तेमाल हो रही फ़ाइल हटाने वाला commit कभी नहीं (किसी भी रास्ते से आया हो)
        staged.filterValues { it == null }.keys.forEach { p -> deleteBlock(p, original[p] ?: baseRead(p))?.let { throw IllegalStateException("push रोका: $it") } }
        // नवंबर 2026: binary फ़ाइल text से लिखी हो तो commit से बाहर (रिपो की copy भी साफ़ रहे)
        BinaryGuard.blockedWrites(staged).forEach { staged.remove(it) }
        if (staged.isEmpty()) throw GitHub.NoChange("सिर्फ़ binary फ़ाइलें लिखनी थीं — रोकीं, कोई commit नहीं")
        // GitHub API से सीधा commit (git push नहीं); commit() खुद पक्का करता है कि branch नए commit पर पहुँची
        val url = gh.commit(repo, branch, staged.toMap(), msg)
        pushedToBranch = true
        lastSha = url.substringAfterLast('/')
        lastPushMsg = msg; lastChangeText = changeText
        // ab GitHub par yahi naya code hai (repo ki copy bhi)
        val m = snap?.toMutableMap()
        staged.forEach { (p, c) -> original[p] = c; if (m != null) { if (c == null) m.remove(p) else m[p] = c } }
        if (m != null) { snap = m; files = m.map { GitHub.Entry(it.key, it.value.length) } } else files = null
        staged.clear()
        msgs.add("user" to "PUSHED to $branch: $url. The repository now contains these changes.")
        msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"और कुछ?\"}")
        if (safePr && pr == null && !prLater) {
            prError = ""
            pr = try {
                gh.openPullFor(repo, branch) ?: if (draftPr) gh.createDraftPull(repo, branch, baseBranch, msg, SafePr.prBody(lastSummary, changeText, prot, backupBranch, rootCause, workNote))
                else gh.createPull(repo, branch, baseBranch, msg, SafePr.prBody(lastSummary, changeText, prot, backupBranch, rootCause, workNote))
            } catch (e: Exception) {
                prError = (e.message ?: e.javaClass.simpleName).take(200) +
                    " — token में Pull requests: Read and write दें, या GitHub पर $branch से PR खुद बनाएँ"
                DKLog.d("Coder: PR nahi bana: $prError"); null
            }
        }
        url
    }

    /**
     * Review ke baad user ka "Merge": sirf tab jab PR khula ho, takraav na ho, aur aakhri commit ke
     * saare GitHub Actions (unit tests + build) safal hon. Merge ke baad branch hatao aur agent main par wapas.
     * @return Hindi nateeja
     */
    suspend fun mergePr(onStep: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        lastMergeSha = ""; lastMergeAsk = false
        val p = pr ?: return@withContext "कोई PR नहीं है"
        var st = gh.pullState(repo, p.number)
        // PR के tests के बाद main आगे बढ़ गया? तो पुराने tests पर merge नहीं: branch को नए main से update करो,
        // सारे tests फिर चलाओ (पुराने + नए features साथ), पास हों तभी merge
        if (st.open && !st.merged && st.mergeable != false) {
            val behind = try { gh.behindBy(repo, baseBranch, p.head.ifBlank { branch }) } catch (_: Exception) { 0 }
            if (behind > 0) {
                onStep("$baseBranch $behind commit आगे बढ़ गया — PR branch update करके tests दोबारा चला रहा हूँ…")
                try { gh.updateBranch(repo, p.number, st.headSha) } catch (e: GitHub.Err) {
                    return@withContext "$baseBranch आगे बढ़ गया है और PR branch update नहीं हो पाई (${e.message}) — merge नहीं किया"
                }
                // GitHub नया head commit बनाता है - उसका इंतज़ार, फिर उसी के tests
                var newSha = st.headSha
                for (i in 0 until 20) { delay(3_000); newSha = try { gh.pullState(repo, p.number).headSha } catch (_: Exception) { newSha }; if (newSha != st.headSha) break }
                if (newSha == st.headSha) return@withContext "PR branch update हो रही है — थोड़ी देर बाद फिर \"merge कर दो\" कहें"
                val b = watchBuild(newSha, onStep) ?: return@withContext "नए $baseBranch के साथ tests का नतीजा नहीं मिला — merge नहीं किया, थोड़ी देर बाद फिर कहें"
                if (!b.ok) return@withContext "नए $baseBranch के साथ tests fail हुए — merge नहीं किया (पुराना और नया code साथ नहीं चल रहा)\n${b.url}"
                st = gh.pullState(repo, p.number)
            }
        }
        if (st.merged) { reset(); return@withContext "PR #${p.number} पहले ही merge हो चुका है" }
        if (!st.open) { reset(); return@withContext "PR #${p.number} GitHub पर बंद कर दिया गया है — अगला काम नई branch और नए PR में होगा" }
        if (st.mergeable == false) return@withContext "PR में $baseBranch से टकराव (conflict) है — पहले उसे ठीक करें"
        val runs = try { gh.runsFor(repo, st.headSha) } catch (e: GitHub.Err) {
            return@withContext "Build/tests नहीं देख पाया (${e.message}) — token में Actions: Read दें"
        }
        if (runs.isEmpty()) return@withContext "इस PR पर अभी कोई build/tests नहीं चला — थोड़ी देर बाद फिर कोशिश करें"
        if (runs.any { it.status != "completed" }) {
            // checks अभी चल रहे: यह गलती नहीं - पूरे होने तक इंतज़ार, फिर नतीजा बताकर merge की हाँ दोबारा माँगो
            onStep("⏳ PR #${p.number} के checks अभी चल रहे हैं — पूरे होने तक इंतज़ार कर रहा हूँ (merge अभी नहीं)…")
            val b = watchBuild(st.headSha, onStep)
                ?: return@withContext "⏳ PR #${p.number} के checks अभी चल रहे हैं (20 मिनट में पूरे नहीं हुए) — merge नहीं किया। पूरे होने पर फिर \"merge करो\" बोलें"
            if (!b.ok) return@withContext "Build/tests fail हैं — merge नहीं किया\n${b.url}"
            lastMergeAsk = true
            return@withContext "✓ PR #${p.number} के सारे checks अब पास हो गए (build + tests)।\nMerge करूँ? \"हाँ, merge करो\" बोलें — तभी merge होकर नई APK बनेगी।\n${p.url}"
        }
        runs.firstOrNull { it.conclusion !in listOf("success", "skipped", "neutral") }?.let {
            return@withContext "Build/tests fail हैं (${it.name}) — merge नहीं किया\n${it.url}"
        }
        val sha = gh.mergePull(repo, p.number, "${lastTask.ifBlank { p.head.ifBlank { "DK changes" } }.take(90)} (#${p.number})")
        lastMergeSha = sha
        val br = branch
        try { gh.deleteBranch(repo, br) } catch (_: Exception) {}
        reset()
        "✓ PR #${p.number} $baseBranch में merge हो गया (${sha.take(7)}), branch $br हटा दी"
    }

    /**
     * Push ke baad GitHub Actions build ka intezaar. null = is repo me build (workflow) nahi / dekh nahi paya.
     */
    suspend fun watchBuild(sha: String, onStep: (String) -> Unit): Build? = watchBuildFor(sha, null, onStep)

    /**
     * डिप्लॉयमेंट एजेंट: merge (sha) के बाद APK release.
     * push से build चला और नई release बनी = ठीक. नहीं चला / release पुरानी = release workflow खुद चलाओ (एक बार) और फिर जाँचो.
     * @return (build नतीजा या null, release या null, "" = release पक्की वरना कमी)
     */
    suspend fun deployRelease(sha: String, mergedMs: Long, onStep: (String) -> Unit): Triple<Build?, SevenPlus.Deploy.Rel?, String> {
        var b = watchBuild(sha, onStep)
        if (b != null && !b.ok) return Triple(b, null, "release build fail")
        var rel = withContext(Dispatchers.IO) { try { gh.latestRelease(repo) } catch (_: Exception) { null } }
        var why = SevenPlus.Deploy.verify(rel, sha, mergedMs)
        if (why.isBlank()) return Triple(b, rel, "")
        val wfs = withContext(Dispatchers.IO) {
            try {
                gh.tree(repo, baseBranch).map { it.path }
                    .filter { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) }
                    .mapNotNull { p -> gh.read(repo, p, baseBranch)?.let { p.substringAfterLast('/') to it } }
            } catch (_: Exception) { emptyList() }
        }
        val wf = SevenPlus.Deploy.releaseWorkflow(wfs) ?: return Triple(b, rel, "$why — release बनाने वाला workflow नहीं मिला")
        onStep("🚀 डिप्लॉयमेंट: $why — APK build अपने-आप नहीं चला, $wf खुद चला रहा हूँ ($baseBranch)")
        val e = withContext(Dispatchers.IO) { try { gh.dispatchWorkflow(repo, wf, baseBranch); "" } catch (x: Exception) { x.message ?: x.javaClass.simpleName } }
        if (e.isNotBlank()) return Triple(b, rel, "$why — $wf नहीं चला पाया: ${e.take(150)} (token में Actions: Read and write दें)")
        b = watchBuildFor(sha, "workflow_dispatch", onStep) ?: return Triple(null, rel, "$why — हाथ से चलाए build का नतीजा नहीं दिखा")
        if (!b.ok) return Triple(b, null, "release build fail")
        rel = withContext(Dispatchers.IO) { try { gh.latestRelease(repo) } catch (_: Exception) { null } }
        why = SevenPlus.Deploy.verify(rel, sha, mergedMs)
        return Triple(b, rel, why)
    }

    /** event = सिर्फ़ इस तरह से चले runs ("pull_request" / "workflow_dispatch"); null = सब */
    suspend fun watchBuildFor(sha: String, event: String?, onStep: (String) -> Unit): Build? = withContext(Dispatchers.IO) {
        if (sha.isBlank()) return@withContext null
        val t0 = System.currentTimeMillis()
        var said = ""
        var reruns = 0
        delay(8_000)
        while (System.currentTimeMillis() - t0 < 20 * 60_000L) {
            ensureActive()
            val runs = try { gh.runsFor(repo, sha).filter { event == null || it.event == event } } catch (e: GitHub.Err) {
                onStep(if (e.code == 403 || e.code == 404) "Build नहीं देख पाया — token में Actions: Read की इजाज़त दें" else "Build नहीं देख पाया: ${e.message}")
                return@withContext null
            } catch (e: Exception) { delay(10_000); continue }
            if (runs.isEmpty()) {
                if (System.currentTimeMillis() - t0 > 90_000) return@withContext null
                delay(8_000); continue
            }
            // v3.89: कोई भी एक जाँच फ़ेल हो चुकी तो बाक़ी (जैसे 8-10 मिनट वाली emulator जाँच) का इंतज़ार नहीं -
            // नतीजा वैसे भी फ़ेल ही है; तुरंत log लेकर डिबगर को (PR #105 में हर राउंड ~8 मिनट बेकार जाते थे)
            val failedEarly = runs.firstOrNull { it.status == "completed" && it.conclusion !in listOf("success", "skipped", "neutral") }
            if (failedEarly != null || runs.all { it.status == "completed" }) {
                val bad = failedEarly
                    ?: return@withContext Build(true, runs.first().url, "")
                val log = try { gh.failureLog(repo, bad.id) } catch (e: Exception) { "(log नहीं मिला: ${e.message})" }
                // GitHub की अपनी दिक्कत (नेटवर्क/runner) - code नहीं बदलना; वही job अपने-आप दोबारा (2 बार तक)
                if (Retry.isInfraFailure(log) && reruns < Retry.ATTEMPTS - 1) {
                    reruns++
                    val ok = try { gh.rerunFailed(repo, bad.id); true } catch (e: Exception) { DKLog.d("Coder: rerun नहीं हुआ: ${e.message}"); false }
                    if (ok) {
                        onStep("Build GitHub की नेटवर्क/runner दिक्कत से fail — अपने-आप दोबारा चला रहा हूँ ($reruns/${Retry.ATTEMPTS - 1})")
                        CoderReport.add("GitHub Actions नेटवर्क/runner दिक्कत से fail (code की गलती नहीं)", "वही job अपने-आप दोबारा चलाई")
                        said = ""; delay(15_000); continue
                    }
                }
                return@withContext Build(false, bad.url, log)
            }
            val st = runs.joinToString { "${it.name}: " + if (it.status == "in_progress") "चल रहा" else "कतार में" }
            if (st != said) { said = st; onStep("Build: $st (${(System.currentTimeMillis() - t0) / 1000}s)") }
            delay(12_000)
        }
        null
    }

    fun discard() {
        staged.clear()
        msgs.add("user" to "User DISCARDED all staged changes. Repository is unchanged.")
        msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"ठीक है, क्या बदलूँ?\"}")
    }

    companion object {
        /** एक काम में default कदम (Settings में 3-30) */
        const val DEFAULT_STEPS = 15      // नवंबर 2026 (मालिक): 5 कदम में बड़ा काम अधूरा रह जाता था
        /** हर कदम के बीच रुकना (ms) */
        const val STEP_DELAY_MS = 1500L
        /** बदलाव से पहले समझने (पढ़ने) के लिए अलग कदम */
        const val READ_BONUS = 2

        /** कुल कदम: Settings वाले + समझने के (ज़्यादा से ज़्यादा 2) + हर बदली फ़ाइल का 1 (ज़्यादा से ज़्यादा 8) */
        fun stepLimit(maxSteps: Int, looks: Int, changedFiles: Int): Int = maxSteps + minOf(looks, READ_BONUS) + minOf(changedFiles, 8)

        /**
         * नई Kotlin/Java फ़ाइलें जिनका मुख्य नाम (class/object/interface) किसी और बदली फ़ाइल में नहीं आता.
         * @return (path, नाम)
         */
        fun unusedNewFiles(newFiles: Map<String, String>, allChanged: Map<String, String>): List<Pair<String, String>> =
            newFiles.mapNotNull { (p, c) ->
                if (!(p.endsWith(".kt") || p.endsWith(".java")) || p.contains("/test/")) return@mapNotNull null
                val name = Regex("""(?m)^\s*(?:(?:public|internal|private|data|enum|sealed|abstract|open|final)\s+)*(?:class|object|interface)\s+([A-Za-z_]\w*)""")
                    .find(c)?.groupValues?.get(1) ?: return@mapNotNull null
                val used = allChanged.any { (op, oc) -> op != p && Regex("\\b" + Regex.escape(name) + "\\b").containsMatchIn(oc) }
                if (used) null else p to name
            }

        /** AI ने "plan" नहीं दी तो पहले कदम से हिंदी योजना: "पहले … पढ़ रहा हूँ, फिर …" */
        fun autoPlan(tool: String, path: String, paths: List<String>, pattern: String): String {
            val names = (paths.ifEmpty { listOf(path) }).map { it.substringAfterLast('/') }.filter { it.isNotBlank() }.distinct()
            val what = when (tool) {
                "read" -> if (names.isEmpty()) "" else "पहले ${names.take(4).joinToString(", ")} का कोड पढ़ रहा हूँ"
                "grep", "search" -> if (pattern.isBlank()) "" else "पहले कोड में \"${pattern.take(40)}\" कहाँ है, ढूँढ रहा हूँ"
                "list" -> "पहले repo की फ़ाइलें देख रहा हूँ"
                "edit", "write", "delete" -> if (names.isEmpty()) "" else "${names.first()} में बदलाव कर रहा हूँ"
                else -> ""
            }
            if (what.isBlank()) return ""
            return if (tool in listOf("edit", "write", "delete")) "$what, फिर जाँचकर काम पूरा करूँगा" else "$what, फिर समझकर पूरा काम ठीक करूँगा"
        }

        /** शुरू में भेजी जाने वाली फ़ाइलें: पहले code/res/build की, फिर बाकी; [max] तक */
        fun importantPaths(all: List<String>, max: Int): List<String> {
            val skip = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".jar", ".zip", ".apk", ".so", ".ttf", ".otf", ".mp3", ".wav")
            val code = listOf(".kt", ".java", ".kts", ".gradle", ".xml", ".md", ".yml", ".yaml", ".json", ".toml", ".properties", ".txt")
            val ok = all.filter { p -> skip.none { p.endsWith(it, true) } }
            val first = ok.filter { p -> code.any { p.endsWith(it, true) } }
            return (first + ok.filter { it !in first }).take(max)
        }

        /** Pehli line ka JSON + <<<FILE / FIND / REPLACE>>> blocks (kai FIND/REPLACE jode ho sakte hain) */
        fun parse(reply: String): Call? {
            val txt = reply.trim().replace(Regex("^```(?:json)?\\s*"), "")
            val head = txt.substringBefore("<<<")
            val s = head.indexOf('{'); if (s < 0) return null
            var depth = 0; var inStr = false; var esc = false; var end = -1
            for (i in s until head.length) {
                val ch = head[i]
                if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
                when (ch) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) { end = i; break } } }
            }
            if (end < 0) return null
            val o = try { JSONObject(head.substring(s, end + 1)) } catch (_: Exception) { return null }
            fun all(tag: String) = Regex("(?s)<<<$tag\\r?\\n(.*?)\\r?\\n?$tag>>>").findAll(txt).map { it.groupValues[1] }.toList()
            return Call(o, all("FILE").firstOrNull(), all("FIND"), all("REPLACE"))
        }
    }
}
