package com.dk.assistant

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 14 नवंबर 2026 (मालिक): कोडिंग एजेंट → "Clone" बटन. रिपो चुनो (अपने GitHub से या link/owner/repo लिखकर) →
 * फ़ोन में उतरे ([com.dk.assistant.dkai.DkaiZip.clone], token हो तो private भी) → हिसाब दिखे → आगे:
 * 📂 फ़ाइलें देखो • 🔎 कोड की जाँच (एजेंट, पहले समझ + हाँ) • 🩺 रिपो की हालत (PR / builds / release) • 🤖 इसी रिपो पर काम.
 */
class RepoClone(private val a: Activity, private val onWork: (String) -> Unit, private val onUse: () -> Unit) {
    private fun toast(t: String) = UiLang.toast(a, t, Toast.LENGTH_SHORT).show()
    private fun live() = !a.isFinishing && !a.isDestroyed

    fun open() {
        val gh = GitHub.fromPrefs(a)
        if (gh == null) { askName(); return }
        toast("आपके रिपो ला रहा हूँ…")
        CoderSession.scope.launch {
            val repos = try { withContext(Dispatchers.IO) { gh.reposAll() } } catch (e: Exception) { emptyList() }
            if (!live()) return@launch
            val names = repos.map { it.fullName + if (it.private) "  (private)" else "" } + "✏️ link या owner/repo लिखें"
            UiDialog(a, Glass.DIALOG).setTitle("📥 कौन सा रिपो clone करें?")
                .setItems(names.toTypedArray()) { _, i -> if (i < repos.size) start(repos[i].fullName) else askName() }
                .setNegativeButton("बंद करें", null).show()
        }
    }

    private fun askName() {
        val e = EditText(a).apply { hint = "github.com/owner/repo या owner/repo"; setText(Prefs.ghRepo(a)); setSingleLine(); UiLang.keep(this) }
        val box = LinearLayout(a).apply { val p = (20 * a.resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, 0); addView(e) }
        UiDialog(a, Glass.DIALOG).setTitle("📥 रिपो clone करें").setView(box)
            .setPositiveButton("Clone") { _, _ -> start(e.text.toString()) }
            .setNegativeButton("रद्द", null).show()
    }

    private fun start(input: String) {
        val r = RepoCloneCore.parse(input) ?: run { toast("रिपो का नाम सही नहीं — owner/repo लिखें"); return }
        val name = RepoCloneCore.folder(r.first, r.second)
        val dest = File(com.dk.assistant.dkai.DkaiWorkspace.projectsRoot(a), name)
        if (dest.exists() && dest.list()?.isNotEmpty() == true) {
            UiDialog(a, Glass.DIALOG).setTitle("यह रिपो पहले से फ़ोन में है")
                .setMessage("${r.first}/${r.second} पहले clone हो चुका है। पुराना वाला खोलें या GitHub से ताज़ा उतारें?")
                .setPositiveButton("ताज़ा उतारो") { _, _ -> run(r, name, dest, fresh = true) }
                .setNeutralButton("पुराना खोलो") { _, _ -> done("${r.first}/${r.second}", dest) }
                .setNegativeButton("रद्द", null).show()
        } else run(r, name, dest, fresh = false)
    }

    private fun run(r: Pair<String, String>, name: String, dest: File, fresh: Boolean) {
        val full = "${r.first}/${r.second}"
        CoderSession.add("s", "📥 $full clone हो रहा है…")
        CoderSession.listener?.invoke()
        CoderSession.scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    if (fresh) dest.deleteRecursively()
                    Result.success(com.dk.assistant.dkai.DkaiZip.clone(a.applicationContext, r.first, r.second, name).first)
                } catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess { d -> if (live()) done(full, d) else CoderSession.add("d", "📥 $full clone हो गया") }
                .onFailure { e -> CoderSession.add("e", "Clone नहीं हुआ: " + (e.message ?: e.javaClass.simpleName).take(200)) }
            CoderSession.listener?.invoke()
        }
    }

    private fun done(full: String, dir: File) {
        CoderSession.scope.launch {
            val s = withContext(Dispatchers.IO) { RepoCloneCore.summary(dir) }
            val rep = RepoCloneCore.report(full, s, dir.absolutePath)
            CoderSession.add("d", rep); CoderSession.listener?.invoke()
            if (!live()) return@launch
            val acts = arrayOf("📂 फ़ाइलें देखो", "🔎 कोड की जाँच करो (एजेंट)", "🩺 रिपो की हालत (PR / builds / release)", "🤖 इसी रिपो पर काम करो")
            UiDialog(a, Glass.DIALOG).setTitle("📥 $full तैयार")
                .setItems(acts) { _, i ->
                    when (i) {
                        0 -> browse(dir, dir)
                        1 -> { use(full); onWork(RepoCloneCore.checkTask(full, s)) }
                        2 -> { use(full); RepoDoctorRunner.scan(a, question = "रिपो $full की पूरी हालत बताओ")?.let { CoderSession.add("e", it) }; CoderSession.listener?.invoke() }
                        else -> { use(full); onUse() }
                    }
                }.setNegativeButton("बंद करें", null).show()
        }
    }

    /** यही रिपो कोडिंग एजेंट का चुना रिपो बने (token हो तो GitHub मोड) */
    private fun use(full: String) {
        if (Prefs.ghRepo(a) != full) Prefs.edit(a) { putString("gh_repo", full); putString("gh_branch", "") }
    }

    /** छोटा फ़ाइल ब्राउज़र: फ़ोल्डर छूओ = अंदर, फ़ाइल छूओ = पढ़ो */
    private fun browse(root: File, dir: File) {
        val kids = (dir.listFiles() ?: emptyArray()).filter { it.name != ".dkai" }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val up = dir.canonicalPath != root.canonicalPath
        val names = (if (up) listOf("⬆ ..") else emptyList()) + kids.map { (if (it.isDirectory) "📁 " else "📄 ") + it.name }
        val title = "📂 " + (dir.relativeTo(root).path.ifBlank { root.name })
        UiDialog(a, Glass.DIALOG).setTitle(title)
            .setItems(names.toTypedArray()) { _, i ->
                if (up && i == 0) { browse(root, dir.parentFile ?: root); return@setItems }
                val f = kids[i - if (up) 1 else 0]
                if (f.isDirectory) browse(root, f) else read(root, f)
            }.setNegativeButton("बंद करें", null).show()
    }

    private fun read(root: File, f: File) {
        val text = try {
            if (f.length() > 200_000) "(फ़ाइल बड़ी है - ${RepoCloneCore.size(f.length())}; शुरू का हिस्सा)\n\n" + f.inputStream().use { String(it.readNBytesCompat(20_000)) }
            else f.readText().take(20_000)
        } catch (e: Exception) { "पढ़ नहीं पाया: ${e.message}" }
        val tv = TextView(a).apply {
            this.text = text; textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true); UiLang.keep(this)
            val p = (14 * a.resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, p / 2)
        }
        UiDialog(a, Glass.DIALOG).setTitle("📄 " + f.relativeTo(root).path)
            .setView(ScrollView(a).apply { addView(tv) })
            .setPositiveButton("वापस") { _, _ -> browse(root, f.parentFile ?: root) }
            .setNegativeButton("बंद करें", null).show()
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = read(buf, off, n - off); if (r < 0) break; off += r }
        return buf.copyOf(off)
    }
}
