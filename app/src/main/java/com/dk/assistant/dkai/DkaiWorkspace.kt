package com.dk.assistant.dkai

import android.content.Context
import com.dk.assistant.ComputerTools
import com.dk.assistant.GitHub
import org.json.JSONObject
import java.io.File

/**
 * DKAI की IDE का असली फ़ाइल-घर. तीन जड़ (root):
 *  - Workspace   = filesDir/dkai          (आपकी फ़ाइलें, GitHub से लाए रिपो, टर्मिनल यहीं चलता है)
 *  - App Studio  = filesDir/studio        (DK के बनाए ऐप)
 *  - DK Computer = externalFiles/computer (DK कंप्यूटर की फ़ाइलें / जोड़ी गई फोटो-PDF)
 * हर काम जड़ के अंदर ही (DkaiCore.inside) - बाहर का रास्ता कभी नहीं.
 */
object DkaiWorkspace {
    data class Root(val name: String, val dir: File)

    fun workspace(c: Context): File = File(c.filesDir, "dkai").apply { mkdirs() }

    fun roots(c: Context): List<Root> = listOf(
        Root("Workspace", workspace(c)),
        Root("App Studio", File(c.filesDir, "studio").apply { mkdirs() }),
        Root("DK Computer", try { ComputerTools.dir(c) } catch (_: Exception) { File(c.filesDir, "computer").apply { mkdirs() } }),
    )

    fun rootOf(c: Context, f: File): Root? = roots(c).firstOrNull { DkaiCore.inside(it.dir, f) }

    fun list(dir: File): List<File> = (dir.listFiles()?.toList() ?: emptyList())
        .filter { it.name != DkaiCore.REPO_META }
        .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

    const val MAX_EDIT = 1_500_000L

    /** फ़ाइल पढ़ो (text नहीं / बहुत बड़ी = null + वजह) */
    fun readText(f: File): Pair<String?, String?> {
        if (!f.isFile) return null to "फ़ाइल नहीं मिली"
        if (f.length() > MAX_EDIT) return null to "फ़ाइल बहुत बड़ी है (1.5MB से ज़्यादा)"
        val b = f.readBytes()
        if (!DkaiCore.looksText(b)) return null to "यह text फ़ाइल नहीं (फोटो/बाइनरी) - Editor में नहीं खुलेगी"
        return String(b, Charsets.UTF_8) to null
    }

    fun create(parent: File, name: String, folder: Boolean): File? {
        if (!DkaiCore.validName(name)) return null
        val f = File(parent, name.trim())
        if (f.exists()) return null
        return try { if (folder) { if (f.mkdirs()) f else null } else { f.parentFile?.mkdirs(); if (f.createNewFile()) f else null } } catch (_: Exception) { null }
    }

    fun rename(f: File, name: String): File? {
        if (!DkaiCore.validName(name)) return null
        val to = File(f.parentFile, name.trim())
        if (to.exists()) return null
        return if (f.renameTo(to)) to else null
    }

    fun delete(f: File): Boolean = try { f.deleteRecursively() } catch (_: Exception) { false }

    // ------------------------------------------------------------------ GitHub रिपो
    data class RepoMeta(val repo: String, val branch: String, val base: Map<String, String>)

    fun meta(dir: File): RepoMeta? = try {
        val j = JSONObject(File(dir, DkaiCore.REPO_META).readText())
        val b = j.getJSONObject("base"); val m = LinkedHashMap<String, String>()
        b.keys().forEach { k -> m[k] = b.getString(k) }
        RepoMeta(j.getString("repo"), j.getString("branch"), m)
    } catch (_: Exception) { null }

    /** यह फ़ोल्डर (या ऊपर का कोई) GitHub से लाया गया है? */
    fun repoDirOf(c: Context, f: File): File? {
        val ws = workspace(c)
        var d: File? = if (f.isDirectory) f else f.parentFile
        while (d != null && DkaiCore.inside(ws, d)) {
            if (File(d, DkaiCore.REPO_META).isFile) return d
            if (d.canonicalPath == ws.canonicalPath) return null
            d = d.parentFile
        }
        return null
    }

    /** रिपो की सारी text फ़ाइलें Workspace/<नाम> में (पुराना फ़ोल्डर नया हो जाता है). लौटाता है फ़ोल्डर + कितनी फ़ाइलें छूटीं */
    fun importRepo(c: Context, gh: GitHub, repo: String): Pair<File, Int> {
        val branch = gh.defaultBranch(repo)
        val skipped = HashSet<String>()
        val files = gh.snapshot(repo, branch, skipped)
        val dir = File(workspace(c), repo.substringAfter('/').ifBlank { "repo" })
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        val base = JSONObject()
        for ((p, content) in files) {
            val f = File(dir, p)
            if (!DkaiCore.inside(dir, f)) continue
            f.parentFile?.mkdirs(); f.writeText(content)
            base.put(p, DkaiCore.sha(content))
        }
        File(dir, DkaiCore.REPO_META).writeText(JSONObject().put("repo", repo).put("branch", branch).put("base", base).toString())
        return dir to skipped.size
    }

    /** अभी की text फ़ाइलें (रिपो फ़ोल्डर के अंदर, relative path) */
    fun current(dir: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val root = dir.canonicalPath
        dir.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile && it.name != DkaiCore.REPO_META && it.length() <= 1_000_000 }.forEach { f ->
            val b = f.readBytes()
            if (DkaiCore.looksText(b)) out[f.canonicalPath.removePrefix("$root/")] = String(b, Charsets.UTF_8)
        }
        return out
    }

    /** छोड़ी गई (binary / बड़ी) फ़ाइलें बदलाव में "हटाई" न गिनी जाएँ - वो base में थीं ही नहीं */
    fun pending(dir: File): Map<String, String?> {
        val m = meta(dir) ?: return emptyMap()
        return DkaiCore.changes(m.base, current(dir))
    }

    data class Pushed(val branch: String, val prUrl: String, val files: Int)

    /** नई branch + commit + PR (main पर सीधे कभी नहीं) */
    fun push(c: Context, gh: GitHub, dir: File, message: String): Pushed {
        val m = meta(dir) ?: error("यह फ़ोल्डर GitHub से नहीं लाया गया")
        val ch = pending(dir)
        if (ch.isEmpty()) error("कोई बदलाव नहीं")
        val br = DkaiCore.branchName(System.currentTimeMillis())
        gh.createBranch(m.repo, br, m.branch)
        gh.commit(m.repo, br, ch, message)
        val pr = gh.createPull(m.repo, br, m.branch, message, "DKAI (DK ऐप की IDE) से भेजे गए बदलाव: ${ch.size} फ़ाइल।")
        // अब जो भेजा वही नया base (अगली बार सिर्फ़ नए बदलाव)
        val base = JSONObject(); current(dir).forEach { (p, s) -> base.put(p, DkaiCore.sha(s)) }
        File(dir, DkaiCore.REPO_META).writeText(JSONObject().put("repo", m.repo).put("branch", m.branch).put("base", base).put("last_pr", pr.url).toString())
        return Pushed(br, pr.url, ch.size)
    }
}
