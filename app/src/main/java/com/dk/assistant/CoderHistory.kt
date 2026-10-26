package com.dk.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * कोडिंग एजेंट का इतिहास (History) + "वहीं से आगे" (resume).
 *
 * - हर बातचीत = एक "धागा" (thread): कब शुरू हुई, आख़िरी बार कब, क्या काम, हाल (चल रहा / मंज़ूरी का इंतज़ार / पूरा / रुका), PR.
 *   सारे संदेश फ़ोन में (filesDir/coder_history) - ऐप बंद होकर खुले तो वही बातचीत, उसी में आगे लिखो.
 * - चलते काम की "चौकी" (checkpoint): काम, मंज़ूर समझ, branch, PR, अब तक के बदलाव (staged).
 *   ऐप/फ़ोन ने बीच में काम मार दिया = अगली बार ऐप खुलते ही उसी branch और उन्हीं बदलावों से आगे (नए सिरे से नहीं).
 *
 * JSON वाला हिस्सा ([Logic]) Android के बिना - unit test होता है.
 */
object CoderHistory {

    /** एक बातचीत का हिसाब (index में) */
    data class Thread(
        val id: String,
        val title: String,
        val created: Long,
        val updated: Long,
        /** chat / wait (मंज़ूरी) / run / done / fail / stop / pause */
        val status: String,
        val repo: String = "",
        val prNum: Int = 0,
        val prUrl: String = "",
        val prHead: String = "",
        val prBase: String = "",
        val lines: Int = 0
    )

    /** बीच में रुके काम की चौकी. stage: pending = समझ मंज़ूरी के इंतज़ार में, run = चलते-चलते ऐप बंद, pause = गलती/limit/समय से रुका */
    data class Checkpoint(
        val thread: String,
        val task: String,
        val u: SevenPlus.Understanding,
        val stage: String,
        val normal: Boolean = false,
        val repo: String = "",
        val base: String = "",
        val branch: String = "",
        val backup: String = "",
        val prNum: Int = 0,
        val prUrl: String = "",
        val staged: Map<String, String?> = emptyMap(),
        val last: String = "",
        val at: Long = 0L,
        /** अपने-आप कितनी बार आगे बढ़ाया (बार-बार crash हो तो रुको) */
        val tries: Int = 0,
        /** टीम कहाँ तक पहुँची (TeamProgress का JSON) - "आगे करो" पर पूरे हुए हिस्से दोबारा नहीं */
        val team: String = ""
    )

    const val MAX_THREADS = 100
    /** ऐप बंद होने के बाद अपने-आप ज़्यादा से ज़्यादा इतनी बार आगे बढ़ाओ (फिर बटन से) */
    const val AUTO_RESUME_MAX = 3

    // ------------------------------------------------------------------ JSON (unit test)
    object Logic {
        /** पहला संदेश -> छोटा शीर्षक (60 अक्षर) */
        fun title(firstUserMsg: String): String {
            val t = firstUserMsg.removePrefix("🎤").trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            return if (t.isEmpty()) "नई बातचीत" else if (t.length <= 60) t else t.take(57).trimEnd() + "…"
        }

        fun statusHi(s: String): String = when (s) {
            "run" -> "⏳ चल रहा"
            "wait" -> "✋ मंज़ूरी का इंतज़ार"
            "done" -> "✅ पूरा"
            "fail" -> "⚠ रुका (वजह लिखी है)"
            "stop" -> "⏹ आपने रोका"
            "pause" -> "⏸ बीच में रुका — आगे बढ़ा सकते हैं"
            else -> "💬 बातचीत"
        }

        fun threadJson(t: Thread): JSONObject = JSONObject().put("id", t.id).put("title", t.title).put("created", t.created)
            .put("updated", t.updated).put("status", t.status).put("repo", t.repo).put("prNum", t.prNum).put("prUrl", t.prUrl)
            .put("prHead", t.prHead).put("prBase", t.prBase).put("lines", t.lines)

        fun thread(o: JSONObject): Thread = Thread(o.optString("id"), o.optString("title"), o.optLong("created"), o.optLong("updated"),
            o.optString("status", "chat"), o.optString("repo"), o.optInt("prNum"), o.optString("prUrl"), o.optString("prHead"),
            o.optString("prBase"), o.optInt("lines"))

        fun indexJson(list: List<Thread>): String = JSONArray().apply { list.forEach { put(threadJson(it)) } }.toString()
        fun index(s: String?): List<Thread> = try {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { thread(a.getJSONObject(it)) }.filter { it.id.isNotBlank() }
        } catch (_: Exception) { emptyList() }

        fun linesJson(lines: List<CoderSession.Line>): String =
            JSONArray().apply { lines.forEach { put(JSONArray().put(it.role).put(it.text)) } }.toString()
        fun lines(s: String?): List<CoderSession.Line> = try {
            val a = JSONArray(s ?: "[]")
            (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { CoderSession.Line(it.optString(0), it.optString(1)) } }
        } catch (_: Exception) { emptyList() }

        /** सबसे नया ऊपर, ज़्यादा से ज़्यादा [MAX_THREADS] - पुराने हटें */
        fun upsert(list: List<Thread>, t: Thread): List<Thread> =
            (listOf(t) + list.filter { it.id != t.id }).sortedByDescending { it.updated }.take(MAX_THREADS)

        private fun arr(l: List<String>) = JSONArray().apply { l.forEach { put(it) } }
        private fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

        fun uJson(u: SevenPlus.Understanding): JSONObject = JSONObject().put("kind", u.kind.name).put("brief", u.brief)
            .put("understood", u.understood).put("spec", u.spec).put("acceptance", arr(u.acceptance)).put("notTouch", arr(u.notTouch))
            .put("questions", arr(u.questions)).put("area", arr(u.area)).put("answer", u.answer).put("changes", arr(u.changes))
            .put("steps", arr(u.steps)).put("mediaNotes", u.mediaNotes).put("wants", arr(u.wants)).put("now", u.now)

        fun u(o: JSONObject): SevenPlus.Understanding = SevenPlus.Understanding(
            try { Seven.Kind.valueOf(o.optString("kind")) } catch (_: Exception) { Seven.Kind.FEATURE },
            o.optString("brief"), o.optString("understood"), o.optString("spec"), strs(o.optJSONArray("acceptance")),
            strs(o.optJSONArray("notTouch")), strs(o.optJSONArray("questions")), strs(o.optJSONArray("area")), o.optString("answer"),
            strs(o.optJSONArray("changes")), strs(o.optJSONArray("steps")), o.optString("mediaNotes"),
            strs(o.optJSONArray("wants")), o.optString("now"))

        fun ckJson(k: Checkpoint): String = JSONObject().put("thread", k.thread).put("task", k.task).put("u", uJson(k.u))
            .put("stage", k.stage).put("normal", k.normal).put("repo", k.repo).put("base", k.base).put("branch", k.branch)
            .put("backup", k.backup).put("prNum", k.prNum).put("prUrl", k.prUrl)
            .put("staged", JSONObject().apply { k.staged.forEach { (p, c) -> put(p, c ?: JSONObject.NULL) } })
            .put("last", k.last).put("at", k.at).put("tries", k.tries).put("team", k.team).toString()

        fun ck(s: String?): Checkpoint? = if (s.isNullOrBlank()) null else try {
            val o = JSONObject(s)
            val st = o.optJSONObject("staged") ?: JSONObject()
            val staged = LinkedHashMap<String, String?>()
            st.keys().forEach { p -> staged[p] = if (st.isNull(p)) null else st.optString(p) }
            Checkpoint(o.optString("thread"), o.optString("task"), u(o.getJSONObject("u")), o.optString("stage"), o.optBoolean("normal"),
                o.optString("repo"), o.optString("base"), o.optString("branch"), o.optString("backup"), o.optInt("prNum"),
                o.optString("prUrl"), staged, o.optString("last"), o.optLong("at"), o.optInt("tries"), o.optString("team"))
        } catch (_: Exception) { null }

        /** काम का नतीजा (आख़िरी संदेश) -> धागे का हाल */
        fun statusAfter(lastRole: String?, cancelled: Boolean, waiting: Boolean): String = when {
            waiting -> "wait"
            cancelled -> "stop"
            lastRole == "d" -> "done"
            lastRole == "e" -> "fail"
            else -> "chat"
        }

        /**
         * ऐप दोबारा खुली: चौकी का क्या करें. "auto" = अपने-आप आगे बढ़ाओ (काम चलते-चलते ऐप बंद हुआ था),
         * "ask" = "वहीं से आगे" बटन दिखाओ, "wait" = मंज़ूरी का इंतज़ार बहाल, "" = कुछ नहीं
         */
        fun onReopen(k: Checkpoint?, busy: Boolean): String = when {
            k == null || busy -> ""
            k.stage == "pending" -> "wait"
            k.stage == "run" && k.tries < AUTO_RESUME_MAX -> "auto"
            k.stage == "run" || k.stage == "pause" -> "ask"
            else -> ""
        }

        /** "आगे बढ़ो", "continue", "वहीं से शुरू करो" जैसा छोटा संदेश = रुका काम आगे (नया काम नहीं) */
        fun isContinue(input: String): Boolean {
            val t = input.removePrefix("🎤").trim().lowercase().trimEnd('.', '।', '!', '?')
            if (t.isEmpty() || t.length > 40) return false
            val words = listOf("आगे बढ़ो", "आगे बढ़ाओ", "आगे करो", "आगे चलो", "जारी रखो", "जारी करो", "वहीं से", "वहाँ से", "continue", "resume", "aage badho", "jari rakho")
            return words.any { t.contains(it) }
        }

        /** आगे बढ़ाते समय टीम को बताना कि पहले क्या हो चुका (वही काम दोबारा न करे) */
        fun resumeNote(k: Checkpoint): String = buildString {
            append("\n\n(RESUME: this work was interrupted (the app was closed or stopped). Continue from where it stopped - do NOT redo finished parts.")
            if (k.branch.isNotBlank()) append(" Working branch: ${k.branch} (already created).")
            if (k.prNum > 0) append(" PR #${k.prNum} already exists.")
            if (k.staged.isNotEmpty()) append(" Files already changed in this work (their current content is what you read): ${k.staged.keys.take(30).joinToString()}.")
            if (k.last.isNotBlank()) append(" Last step before stop: ${k.last.take(300)}")
            TeamProgress.parse(k.team)?.let { p ->
                if (p.rootCause.isNotBlank()) append(" Root cause found earlier: ${p.rootCause.take(300)}.")
                if (p.phase >= TeamProgress.CODED) append(" The code change is already done - only checking/fixing is left.")
            }
            append(")")
        }
    }

    // ------------------------------------------------------------------ फ़ोन में रखना
    private val io = Executors.newSingleThreadScheduledExecutor { r -> java.lang.Thread(r, "coder-history").apply { isDaemon = true } }
    @Volatile private var pending: ScheduledFuture<*>? = null
    @Volatile private var ctx: Context? = null
    @Volatile var current: String = ""
        private set
    private val lock = Any()

    private fun dir(c: Context) = File(c.filesDir, "coder_history").apply { mkdirs() }
    private fun idxFile(c: Context) = File(dir(c), "index.json")
    private fun linesFile(c: Context, id: String) = File(dir(c), "t_$id.json")
    private fun ckFile(c: Context) = File(dir(c), "checkpoint.json")

    fun list(c: Context): List<Thread> = synchronized(lock) { Logic.index(try { idxFile(c).readText() } catch (_: Exception) { null }) }
    fun thread(c: Context, id: String): Thread? = list(c).firstOrNull { it.id == id }

    /** ऐप खुलते ही: पिछली बातचीत वापस (CoderSession.lines खाली हो तो) */
    fun init(c: Context) {
        val app = c.applicationContext
        if (ctx != null) return
        ctx = app
        current = Prefs.coderThread(app)
        if (current.isNotBlank() && CoderSession.lines.isEmpty()) {
            val l = load(app, current)
            if (l.isNotEmpty()) CoderSession.lines.addAll(l)
        }
    }

    fun load(c: Context, id: String): List<CoderSession.Line> =
        Logic.lines(try { linesFile(c, id).readText() } catch (_: Exception) { null })

    /** नया धागा (पहला संदेश आने पर) */
    private fun ensureThread(c: Context, firstUser: String) {
        if (current.isNotBlank() && thread(c, current) != null) return
        val now = System.currentTimeMillis()
        current = "c" + now.toString(36)
        Prefs.edit(c) { putString("coder_thread", current) }
        put(c, Thread(current, Logic.title(firstUser), now, now, "chat", Prefs.ghRepo(c)))
    }

    private fun put(c: Context, t: Thread) = synchronized(lock) {
        val all = Logic.upsert(list(c), t)
        try { idxFile(c).writeText(Logic.indexJson(all)) } catch (_: Exception) {}
        // 100 से पुराने धागों की फ़ाइलें हटाओ
        val keep = all.map { "t_${it.id}.json" }.toSet()
        dir(c).listFiles()?.filter { it.name.startsWith("t_") && it.name !in keep }?.forEach { it.delete() }
    }

    /** हर नया संदेश: धागा बनाओ (ज़रूरत हो तो) + थोड़ी देर में फ़ाइल में (हर संदेश पर तुरंत नहीं) */
    fun onLine(role: String, text: String) {
        val c = ctx ?: return
        if (role == "u" && current.isBlank()) ensureThread(c, text)
        if (current.isBlank()) return
        pending?.cancel(false)
        pending = io.schedule({ flush(c) }, 600, TimeUnit.MILLISECONDS)
    }

    /** अभी के सारे संदेश + हिसाब फ़ाइल में */
    fun flush(c: Context) {
        val id = current.ifBlank { return }
        val snap = try { CoderSession.lines.toList() } catch (_: Exception) { return }
        try { linesFile(c, id).writeText(Logic.linesJson(snap)) } catch (_: Exception) {}
        val a = CoderSession.agent
        val pr = a?.pr
        val busy = CoderSession.busy
        modify(c, id) { t -> t.copy(updated = System.currentTimeMillis(), lines = snap.size,
            status = if (busy) "run" else t.status,
            repo = a?.repo ?: t.repo,
            prNum = pr?.number ?: t.prNum, prUrl = pr?.url ?: t.prUrl, prHead = pr?.head ?: t.prHead, prBase = pr?.base ?: t.prBase) }
    }

    /** पढ़ना + बदलना + लिखना एक साथ (दो जगह से एक साथ बदलें तो एक का बदलाव न खोए) */
    private fun modify(c: Context, id: String, f: (Thread) -> Thread) = synchronized(lock) {
        val t = list(c).firstOrNull { it.id == id } ?: return@synchronized
        put(c, f(t))
    }

    fun setStatus(c: Context, status: String) {
        val id = current.ifBlank { return }
        modify(c, id) { it.copy(status = status, updated = System.currentTimeMillis()) }
    }

    /** "नया" बटन: अगला संदेश नया धागा बनाएगा */
    fun newThread(c: Context) {
        flush(c)
        current = ""
        Prefs.edit(c) { putString("coder_thread", "") }
    }

    /** इतिहास से कोई बातचीत खोलो - उसी में आगे लिखना */
    fun open(c: Context, id: String): List<CoderSession.Line> {
        flush(c)
        current = id
        Prefs.edit(c) { putString("coder_thread", id) }
        return load(c, id)
    }

    fun delete(c: Context, id: String) = synchronized(lock) {
        val all = list(c).filter { it.id != id }
        try { idxFile(c).writeText(Logic.indexJson(all)) } catch (_: Exception) {}
        linesFile(c, id).delete()
        if (current == id) { current = ""; Prefs.edit(c) { putString("coder_thread", "") } }
    }

    // ------------------------------------------------------------------ चौकी (resume)
    fun checkpoint(c: Context): Checkpoint? = synchronized(lock) { Logic.ck(try { ckFile(c).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }) }

    fun saveCheckpoint(c: Context, k: Checkpoint) = synchronized(lock) {
        try { ckFile(c).writeText(Logic.ckJson(k.copy(at = System.currentTimeMillis()))) } catch (_: Exception) {}
    }

    /** चौकी में बदलाव (staged, branch, आख़िरी कदम) - background में */
    fun updateCheckpoint(c: Context, f: (Checkpoint) -> Checkpoint) {
        io.execute { checkpoint(c)?.let { saveCheckpoint(c, f(it)) } }
    }

    fun clearCheckpoint(c: Context) = synchronized(lock) { ckFile(c).delete(); Unit }
}
