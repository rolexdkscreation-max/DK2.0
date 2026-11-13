package com.dk.assistant.dkai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dk.assistant.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 13 नवंबर 2026 (मालिक - M6/M7): DKAI Copilot - प्रोजेक्ट की फ़ाइलें पढ़कर AI से बदलाव, सीधे फ़ाइलों में.
 * हर काम के बाद "Review Changes": Undo Task (सब पुराना वापस) / Keep all changes.
 * सिर्फ़ ऐप के अपने user-space में (कोई root नहीं).
 */
object DkaiCopilot {
    class Change(val path: String, val old: String?, val new: String)
    class Review(val project: File, val changes: List<Change>, val summary: String, val command: String)

    @Volatile var review: Review? = null; private set
    @Volatile var busy = false; private set
    val log = CopyOnWriteArrayList<String>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val ui = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    private fun changed() = ui.post { for (l in listeners) try { l() } catch (_: Throwable) {} }

    fun say(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        log.add("[$t] $s"); while (log.size > 200) log.removeAt(0)
        changed()
    }

    /** AI को भेजने लायक फ़ाइलें (text, छोटी) - index.html पहले */
    fun context(project: File, maxEach: Int = 40_000, maxTotal: Int = 140_000): String {
        val files = project.walkTopDown().maxDepth(6)
            .onEnter { it.name != ".git" && it.name != "node_modules" && it.name != DkaiKit.CONFIG_DIR && it.name != "build" && it.name != "dist" }
            .filter { it.isFile && DkaiCore.isTextName(it.name) && it.length() <= maxEach }
            .sortedWith(compareBy<File> { !it.name.equals("index.html", true) }.thenBy { it.path.count { ch -> ch == '/' } }.thenBy { it.path })
            .toList()
        val sb = StringBuilder(); var skipped = 0
        for (f in files) {
            val rel = f.relativeTo(project).path
            val t = try { f.readText() } catch (_: Exception) { continue }
            if (sb.length + t.length > maxTotal) { skipped++; continue }
            sb.append("=== FILE: ").append(rel).append(" ===\n").append(t).append("\n=== END ===\n")
        }
        if (skipped > 0) sb.append("(+$skipped more files not shown because of size)\n")
        return sb.toString()
    }

    /** कमांड चलाओ: AI -> फ़ाइलें लिखो -> Review */
    fun run(c: Context, project: File, command: String, done: ((Review?, String?) -> Unit)? = null) {
        if (busy) { done?.invoke(null, "Copilot अभी दूसरे काम में है"); return }
        val app = c.applicationContext
        busy = true; changed()
        say("▶ ${command.take(160)}")
        scope.launch {
            var err: String? = null; var rv: Review? = null
            try {
                say("फ़ाइलें पढ़ रहा हूँ: ${project.name}")
                val ctx = withContext(Dispatchers.IO) { context(project) }
                val prompt = "PROJECT: ${project.name}\n\n" + (if (ctx.isBlank()) "(project is empty - create the needed files)\n" else ctx) + "\nCOMMAND: $command"
                say("${DkaiKit.COPILOT} सोच रहा है…")
                val raw = withContext(Dispatchers.IO) {
                    try { CoderAi.fromPrefs(app)?.chat(DkaiKit.COPILOT_SYSTEM, listOf("user" to prompt)) } catch (e: Exception) { say("Coder AI: ${e.message?.take(100)}"); null }
                        ?: try { StoryAi.fromPrefs(app)?.write(DkaiKit.COPILOT_SYSTEM, listOf("user" to prompt))?.text } catch (e: Exception) { null }
                } ?: BrainManager(app).quickText(DkaiKit.COPILOT_SYSTEM, prompt)
                if (raw.isNullOrBlank()) throw IllegalStateException("AI से जवाब नहीं मिला (Settings → AI दिमाग / कोडिंग एजेंट की key देखें)")
                val edits = DkaiKit.parseEdits(raw)
                val summary = DkaiKit.summaryOf(raw)
                if (edits.isEmpty()) { say("कोई फ़ाइल नहीं बदली. AI: ${summary.ifBlank { raw.take(200) }}"); throw IllegalStateException("AI ने कोई फ़ाइल नहीं बदली") }
                val ch = withContext(Dispatchers.IO) { apply(project, edits) }
                rv = Review(project, ch, summary, command); review = rv
                for (x in ch) say("✎ ${x.path}  ${DkaiKit.lineDelta(x.old, x.new)}")
                say("✓ ${ch.size} फ़ाइल बदली — Review Changes: Undo Task / Keep all changes")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { err = e.message ?: "गलती"; say("✗ $err")
            } finally { busy = false; changed(); done?.invoke(rv, err) }
        }
    }

    private fun apply(project: File, edits: List<DkaiKit.Edit>): List<Change> {
        val out = ArrayList<Change>()
        for (e in edits) {
            val f = File(project, e.path)
            if (!DkaiCore.inside(project, f)) continue
            val old = if (f.isFile) try { f.readText() } catch (_: Exception) { null } else null
            if (old == e.content) continue
            f.parentFile?.mkdirs(); f.writeText(e.content)
            out.add(Change(e.path, old, e.content))
        }
        return out
    }

    /** सब पुराना वापस (नई बनी फ़ाइल हटे) */
    fun undo(): Int {
        val r = review ?: return 0
        var n = 0
        for (x in r.changes) try { val f = File(r.project, x.path); if (x.old == null) f.delete() else f.writeText(x.old); n++ } catch (_: Exception) {}
        review = null; say("↶ Undo: $n फ़ाइल पहले जैसी"); return n
    }

    fun keep() { if (review != null) { review = null; say("✓ बदलाव रखे गए") } }

    fun reviewText(r: Review): String = "${r.changes.size} files changed • " + r.changes.joinToString(", ") { it.path }.take(160)
}

/** zip खोलना (Import Zip Project / GitHub Clone) - zip-slip से सुरक्षित */
object DkaiZip {
    /** [zip] -> [dest]; सबकी एक ऊपर वाली परत हो तो हटाओ. वापस: कितनी फ़ाइलें */
    fun unzip(zip: File, dest: File): Int {
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            val tops = names.mapNotNull { it.replace('\\', '/').trimStart('/').substringBefore('/', "").takeIf { t -> t.isNotEmpty() } }.toSet()
            val strip = tops.size == 1 && names.all { it.replace('\\', '/').trimStart('/').contains('/') || it.endsWith("/") }
            var n = 0
            dest.mkdirs()
            for (e in z.entries()) {
                val rel = DkaiKit.zipTarget(e.name, strip) ?: continue
                val f = File(dest, rel)
                if (!DkaiCore.inside(dest, f)) continue
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                z.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                n++
            }
            return n
        }
    }

    private val http by lazy { NetFix.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).followRedirects(true).build() }

    /** GitHub रिपो -> DKAIProjects/[folder] (token हो तो private भी) */
    fun clone(c: Context, owner: String, repo: String, folder: String): Pair<File, Int> {
        val dest = File(DkaiWorkspace.projectsRoot(c), folder)
        if (dest.exists() && (dest.list()?.isNotEmpty() == true)) throw IllegalStateException("\"$folder\" पहले से मौजूद है - दूसरा नाम दें")
        val tok = Prefs.ghToken(c)
        val url = if (tok.isNotBlank()) "https://api.github.com/repos/$owner/$repo/zipball" else "https://codeload.github.com/$owner/$repo/zip/HEAD"
        val b = Request.Builder().url(url).header("User-Agent", "DKAI")
        if (tok.isNotBlank()) b.header("Authorization", "Bearer $tok").header("Accept", "application/vnd.github+json")
        val tmp = File(c.cacheDir, "clone_${System.currentTimeMillis()}.zip")
        try {
            http.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException(if (r.code == 404) "रिपो नहीं मिला (नाम देखें / private हो तो GitHub token)" else "GitHub ${r.code}")
                r.body?.byteStream()?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } ?: throw IllegalStateException("खाली जवाब")
            }
            val n = unzip(tmp, dest)
            try {
                File(dest, DkaiKit.CONFIG_DIR).mkdirs()
                File(dest, DkaiKit.CONFIG_DIR + "/config.json").writeText(org.json.JSONObject().put("name", folder).put("repo", "$owner/$repo")
                    .put("brand", DkaiKit.BRAND).put("port", DkaiKit.PORT).put("cloned", System.currentTimeMillis()).toString(2))
            } catch (_: Exception) {}
            DkaiWorkspace.setProject(c, dest)
            return dest to n
        } finally { tmp.delete() }
    }
}
