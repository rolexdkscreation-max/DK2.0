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
    fun fetchLater(c: Context, gh: GitHub, repo: String, sha: String) {
        if (sha.isBlank() || !active.add("$repo@$sha")) return
        val app = c.applicationContext
        CoderSession.scope.launch {
            val until = System.currentTimeMillis() + MAX_WAIT_MS
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
                if (runs.isNotEmpty() && runs.all { it.status == "completed" && it.id in shown }) break
                delay(POLL_MS)
            }
            } finally { active.remove("$repo@$sha") }
            cleanup(app)
        }
    }

    /** पुराने screenshot हटाओ (सबसे नए 40 फ़ोल्डर रखो) */
    private fun cleanup(c: Context) {
        val all = dir(c).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        all.drop(40).forEach { it.deleteRecursively() }
    }
}
