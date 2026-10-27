package com.dk.assistant

import java.io.File

/**
 * v3.89: 📒 काम-कॉपी फ़ोन में ही (app की अपनी जगह, filesDir/worknotes/). खोलने में न इंटरनेट, न GitHub API.
 * PR में भी कॉपी रहती है (दूसरे फ़ोन / GitHub पर देखने के लिए) - पर अब वहाँ हर छोटे कदम पर नहीं,
 * सिर्फ़ हालत बदलने पर या कुछ मिनट बाद लिखी जाती है (API की बचत).
 */
object LocalNotes {
    const val DIR = "worknotes"
    /** PR में दोबारा लिखने से पहले कम से कम इतना समय (जब तक हालत न बदले) */
    const val PR_EVERY_MS = 3 * 60_000L

    /** "owner/repo" + branch -> सुरक्षित फ़ाइल नाम */
    fun fileName(repo: String, branch: String): String {
        fun safe(x: String) = x.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(80).ifBlank { "_" }
        return safe(repo) + "__" + safe(branch) + ".md"
    }

    /** markdown की "हालत" लाइन (बदली तो PR में तुरंत लिखो) */
    fun status(md: String): String = md.lines().firstOrNull { it.startsWith("**हालत:**") }.orEmpty()

    /** PR में अभी लिखना है? */
    fun prDue(md: String, lastMd: String?, lastAt: Long, now: Long): Boolean =
        lastMd == null || (md != lastMd && (status(md) != status(lastMd) || now - lastAt >= PR_EVERY_MS))

    fun save(dir: File, repo: String, branch: String, md: String): Boolean = try {
        val d = File(dir, DIR); d.mkdirs()
        val f = File(d, fileName(repo, branch))
        val tmp = File(d, f.name + ".tmp")
        tmp.writeText(md); if (!tmp.renameTo(f)) { f.writeText(md); tmp.delete() }
        true
    } catch (_: Exception) { false }

    fun load(dir: File, repo: String, branch: String): String? = try {
        File(File(dir, DIR), fileName(repo, branch)).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** सारी काम-कॉपियाँ, नई पहले: (नाम, फ़ाइल) */
    fun all(dir: File): List<File> = (File(dir, DIR).listFiles { f -> f.isFile && f.name.endsWith(".md") }?.toList() ?: emptyList())
        .sortedByDescending { it.lastModified() }
}
