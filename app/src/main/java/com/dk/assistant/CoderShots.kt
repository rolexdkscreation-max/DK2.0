package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Emulator screenshot के नियम (Android के बिना - unit test) */
object Shots {
    fun isImage(name: String) = name.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") }
    fun isText(name: String) = name.lowercase().let { it.endsWith(".txt") || it.endsWith(".log") }

    /** कौन-से artifact में screenshot हैं (uaag-screenshots, ui-preview-shots …). APK वाले नहीं */
    fun isShotArtifact(name: String): Boolean = name.lowercase().let { n ->
        !n.contains("apk") && (n.contains("screenshot") || n.contains("shot") || n.contains("screen") || n.startsWith("ui-"))
    }

    /** कोडिंग स्क्रीन की "i" लाइन: पहली लाइन = शीर्षक, बाकी = फ़ोन में तस्वीर का पता */
    fun line(caption: String, paths: List<String>): String = (listOf(caption.replace('\n', ' ')) + paths).joinToString("\n")
    fun parse(text: String): Pair<String, List<String>> {
        val l = text.lines()
        return l.firstOrNull().orEmpty() to l.drop(1).filter { it.isNotBlank() }
    }

    /** status.txt / crash_log.txt से छोटा हाल */
    fun note(texts: Map<String, String>): String {
        val st = texts["status.txt"]?.trim().orEmpty()
        val crash = texts["crash_log.txt"]?.trim().orEmpty()
        return buildString {
            if (st.isNotBlank()) append(if (st.contains("chal raha", true)) "ऐप emulator पर चल रहा था" else "⚠ ऐप emulator पर बंद हो गया (crash?)")
            if (crash.isNotBlank()) { if (isNotEmpty()) append(" • "); append("crash log: ").append(crash.lines().firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim()?.take(140) ?: "है") }
        }
    }

    /** 25 नवंबर 2026: नई स्क्रीन के screenshot के लिए कोडर यह फ़ाइल लिखता है; .github/ui/shots.sh इसे emulator पर चलाता है */
    const val EXTRA = ".github/ui/extra_shots.sh"
    /** emulator पर हर स्क्रीन के screenshot वाला workflow (workflow_dispatch से किसी भी branch पर) */
    const val UI_WORKFLOW = "ui-preview.yml"

    /** बदली फ़ाइलों में स्क्रीन/दिखावट वाला कुछ है? (तब रिपोर्ट में screenshot की बात) */
    fun uiChanged(paths: Collection<String>): Boolean = paths.any { p ->
        val n = p.substringAfterLast('/')
        p == EXTRA || p.contains("/res/layout") || p.contains("/res/drawable") || p.contains("/res/values/") || p.contains("/ui/") ||
            n.endsWith("Activity.kt") || n.startsWith("Settings") || n.contains("Screen") || n.contains("Dialog") || n.contains("Sheet") || n.contains("Card")
    }

    /**
     * user screenshot माँग रहा है? ("स्क्रीनशॉट दो", "screenshot दिखाओ", "कैसा दिखता है ss भेजो").
     * screenshot वाला नया फ़ीचर बनवाना (जोड़ो/बनाओ/feature) = नया काम, यह नहीं.
     */
    fun wantsShots(input: String): Boolean {
        val t = input.lowercase()
        if (!mentions(t)) return false
        val ask = listOf("दो", "दें", "दीजिए", "दिखाओ", "दिखाइए", "भेजो", "भेजिए", "चाहिए", "लाओ", "कैसा दिख", "give", "show", "send").any { t.contains(it) }
        val build = listOf("फ़ीचर", "फीचर", "feature", "जोड़", "बनाओ", "add ", "बटन", "option").any { t.contains(it) }
        return ask && !build && t.length <= 200
    }

    /** बात में screenshot का ज़िक्र (काम के साथ माँगा हो तो काम पूरा होते ही अपने-आप लाओ) */
    fun mentions(text: String): Boolean = text.lowercase().let { t ->
        listOf("स्क्रीनशॉट", "स्क्रीन शॉट", "स्क्रीनशॉर्ट", "screenshot", "screen shot").any { t.contains(it) }
    }

    /** आख़िरी रिपोर्ट की screenshot लाइन (GitHub मोड) */
    fun reportNote(paths: Collection<String>, github: Boolean): String = when {
        !github -> ""
        uiChanged(paths) -> "📸 स्क्रीनशॉट: build पूरा होते ही emulator के screenshot इसी चैट में तस्वीरों की पट्टी में आएँगे (टैप = बड़ी)। " +
            "नई स्क्रीन के screenshot चाहिए तो \"स्क्रीनशॉट दो\" लिखें — emulator पर नई और पुरानी (main) दोनों स्क्रीनें लाकर दिखाऊँगा।"
        else -> "📸 स्क्रीनशॉट चाहिए तो \"स्क्रीनशॉट दो\" लिखें — emulator पर ऐप खोलकर screenshot लाऊँगा।"
    }

    /** 01_khulte_hi.png -> "01 khulte hi" */
    fun label(file: String): String = file.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
}

/**
 * Emulator के screenshot ऐप में: PR/branch के build (GitHub Actions) पूरे होने पर उनके screenshot artifact
 * फ़ोन में लाकर कोडिंग स्क्रीन (और इतिहास) में तस्वीरों की पट्टी. टीम का काम नहीं रुकता - यह अलग से चलता है.
 */
object CoderShots {
    /** अभी जिन commit के build देखे जा रहे हैं */
    private val active = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** जिन build (run) की तस्वीरें दिखा चुके - दोबारा नहीं */
    private val shown = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    /** build ख़त्म होने का कितना इंतज़ार (emulator वाला build ~10-15 मिनट) */
    private const val MAX_WAIT_MS = 30 * 60_000L
    private const val POLL_MS = 45_000L

    fun dir(c: Context) = File(c.filesDir, "coder_shots").apply { mkdirs() }

    /** इस commit के build पूरे होने तक (background में) देखो; screenshot मिलें तो "i" लाइन */
    fun fetchLater(c: Context, gh: GitHub, repo: String, sha: String, waitMs: Long = MAX_WAIT_MS, untilName: String = "") {
        if (sha.isBlank() || !active.add("$repo@$sha$untilName")) return
        val app = c.applicationContext
        CoderSession.scope.launch {
            val until = System.currentTimeMillis() + waitMs
            var noted = false
            try {
            while (System.currentTimeMillis() < until) {
                val runs = try { withContext(Dispatchers.IO) { gh.runsFor(repo, sha) } } catch (_: Exception) { emptyList() }
                for (r in runs.filter { it.status == "completed" && it.id !in shown }) {
                    val arts = try { withContext(Dispatchers.IO) { gh.artifacts(repo, r.id) } } catch (_: Exception) { emptyList() }
                    val shotArts = arts.filter { !it.expired && Shots.isShotArtifact(it.name) }
                    if (shotArts.isEmpty()) { shown.add(r.id); continue }
                    for (art in shotArts) {
                        try {
                            val files = withContext(Dispatchers.IO) { gh.artifactFiles(repo, art.id) }
                            val out = File(dir(app), "${sha.take(7)}_${r.id}_${art.id}").apply { mkdirs() }
                            val paths = mutableListOf<String>()
                            val texts = mutableMapOf<String, String>()
                            files.forEach { (n, b) ->
                                if (Shots.isImage(n)) { val f = File(out, n); f.writeBytes(b); if (b.size > 100) paths.add(f.absolutePath) }
                                else texts[n] = String(b, Charsets.UTF_8).take(4000)
                            }
                            val note = Shots.note(texts)
                            if (paths.isNotEmpty()) CoderSession.add("i", Shots.line("📸 Emulator screenshot — ${r.name} (${if (r.conclusion == "success") "✓ पास" else r.conclusion})" +
                                if (note.isNotBlank()) " • $note" else "", paths.sorted()))
                            else if (note.isNotBlank()) CoderSession.add("s", "📸 ${r.name}: तस्वीर नहीं बनी • $note")
                        } catch (e: Exception) {
                            if (!noted) { noted = true; CoderSession.add("s", "📸 screenshot नहीं ला पाया: " + (e.message ?: e.javaClass.simpleName).take(160)) }
                        }
                    }
                    shown.add(r.id)
                }
                // सारे build पूरे और देख लिए = बस
                // untilName = अभी चलाया गया workflow (जैसे UI Preview) - वह दिखे और पूरा हो, तभी बस (पुराने पूरे build पर रुकना नहीं)
                val waitingFor = untilName.isNotBlank() && runs.none { it.name.contains(untilName, ignoreCase = true) }
                if (!waitingFor && runs.isNotEmpty() && runs.all { it.status == "completed" && it.id in shown }) break
                delay(POLL_MS)
            }
            } finally { active.remove("$repo@$sha$untilName") }
            cleanup(app)
        }
    }

    /** ui-preview (हर स्क्रीन + नई स्क्रीन) ~50 मिनट तक चल सकता है */
    private const val UI_WAIT_MS = 70 * 60_000L
    /** ui-preview.yml का name: */
    private const val UI_RUN = "UI Preview"

    /**
     * 25 नवंबर 2026 (मालिक: "स्क्रीनशॉट माँगा जाए तो स्क्रीनशॉट भी देगा"): इस branch पर emulator वाला ui-preview चलाओ और
     * उसके screenshot (नया + main वाला पुराना) चैट में. नेटवर्क background में; नतीजा चैट की लाइनों में.
     */
    fun request(c: Context, gh: GitHub, repo: String, branches: List<String>) {
        val app = c.applicationContext
        CoderSession.scope.launch {
            var branch = branches.firstOrNull().orEmpty()
            try {
                // पहली branch (काम वाली) merge के बाद हट चुकी हो तो अगली (main)
                var sha = ""
                for (b in branches.filter { it.isNotBlank() }.distinct()) {
                    sha = withContext(Dispatchers.IO) { try { gh.remoteBranchSha(repo, b) } catch (e: Exception) { Quiet.note(e); "" } }
                    if (sha.isNotBlank()) { branch = b; break }
                }
                if (sha.isBlank()) {
                    CoderSession.add("e", "📸 GitHub पर branch नहीं मिली (" + branches.joinToString() + ") — रिपो/branch जाँचें")
                } else {
                    withContext(Dispatchers.IO) { gh.dispatchWorkflow(repo, UI_WORKFLOW, branch) }
                    CoderSession.add("s", "📸 branch $branch पर emulator चला दिया ($UI_WORKFLOW) — ऐप की स्क्रीनें (नई + पुरानी main वाली) खोलकर screenshot ले रहा है। " +
                        "20-40 मिनट में तस्वीरें इसी चैट में आएँगी (टैप = बड़ी)।")
                    fetchLater(app, gh, repo, sha, UI_WAIT_MS, untilName = UI_RUN)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CoderSession.add("e", "📸 screenshot वाला emulator नहीं चला पाया: " + (e.message ?: e.javaClass.simpleName).take(160) +
                    " — token में Actions: Read and write दें, या GitHub → Actions → \"UI Preview\" → Run workflow (branch $branch)")
            }
        }
    }

    /** पुराने screenshot हटाओ (सबसे नए 40 फ़ोल्डर रखो) */
    private fun cleanup(c: Context) {
        val all = dir(c).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        all.drop(40).forEach { it.deleteRecursively() }
    }
}
