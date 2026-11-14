package com.dk.assistant

import java.io.File

/**
 * 14 नवंबर 2026 (मालिक): कोडिंग एजेंट के अंदर ही "रिपो Clone करो / जाँच करो" - रिपो फ़ोन में उतरे, फ़ाइलें दिखें,
 * और एजेंट उसी की जाँच करे. यहाँ सिर्फ़ शुद्ध नियम (JVM test - [RepoCloneTest]); चलाना [RepoClone] में.
 */
object RepoCloneCore {
    /** "owner/repo", github.com का पूरा link, ".git" वाला link - सब से (owner, repo). गलत हो तो null */
    fun parse(input: String): Pair<String, String>? {
        var t = input.trim().removeSuffix("/").removeSuffix(".git")
        t = t.replace(Regex("^(https?://)?(www\\.)?github\\.com/", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^git@github\\.com:", RegexOption.IGNORE_CASE), "")
        val parts = t.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val ok = Regex("^[A-Za-z0-9_.-]{1,100}$")
        val o = parts[0]; val r = parts[1]
        if (!ok.matches(o) || !ok.matches(r) || o == "." || o == ".." || r == "." || r == "..") return null
        return o to r
    }

    /** फ़ोन में फ़ोल्डर का नाम (सिर्फ़ सुरक्षित अक्षर) */
    fun folder(owner: String, repo: String): String = (repo + "-" + owner).replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)

    data class Summary(val files: Int, val bytes: Long, val tops: List<String>, val langs: List<Pair<String, Int>>)

    private val LANG = mapOf(
        "kt" to "Kotlin", "kts" to "Kotlin", "java" to "Java", "js" to "JavaScript", "ts" to "TypeScript", "tsx" to "TypeScript",
        "jsx" to "JavaScript", "py" to "Python", "html" to "HTML", "css" to "CSS", "xml" to "XML", "json" to "JSON", "md" to "Markdown",
        "go" to "Go", "rs" to "Rust", "c" to "C", "cpp" to "C++", "h" to "C/C++", "swift" to "Swift", "dart" to "Dart", "php" to "PHP",
        "rb" to "Ruby", "sh" to "Shell", "yml" to "YAML", "yaml" to "YAML", "gradle" to "Gradle", "sql" to "SQL")

    fun lang(name: String): String? = LANG[name.substringAfterLast('.', "").lowercase()]

    /** फ़ोल्डर का छोटा हिसाब: कितनी फ़ाइलें, कितना बड़ा, ऊपर के फ़ोल्डर, कौन सी भाषा कितनी */
    fun summary(dir: File): Summary {
        var n = 0; var b = 0L
        val count = HashMap<String, Int>()
        dir.walkTopDown().onEnter { it.name != ".git" && it.name != ".dkai" }.filter { it.isFile }.forEach { f ->
            n++; b += f.length(); lang(f.name)?.let { count[it] = (count[it] ?: 0) + 1 }
        }
        val tops = (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".dkai") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).map { if (it.isDirectory) it.name + "/" else it.name }
        return Summary(n, b, tops, count.entries.sortedByDescending { it.value }.map { it.key to it.value })
    }

    fun size(b: Long): String = when {
        b >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0)
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }

    fun report(full: String, s: Summary, path: String): String = buildString {
        append("📥 $full फ़ोन में clone हो गया\n")
        append("📂 ").append(path).append('\n')
        append("📄 ${s.files} फ़ाइलें • ${size(s.bytes)}\n")
        if (s.langs.isNotEmpty()) append("🧩 ").append(s.langs.take(5).joinToString(" • ") { "${it.first} ${it.second}" }).append('\n')
        if (s.tops.isNotEmpty()) append("🗂 ").append(s.tops.take(12).joinToString("  ")).append(if (s.tops.size > 12) "  …" else "")
    }

    /** कोडिंग एजेंट को \"कोड की जाँच\" वाला काम (कुछ नहीं बदलना - सिर्फ़ रिपोर्ट). एजेंट पहले समझ दिखाकर हाँ लेता है */
    fun checkTask(full: String, s: Summary): String =
        "रिपो $full की पूरी जाँच करो (कोई बदलाव नहीं, सिर्फ़ रिपोर्ट): ढाँचा, build और tests की हालत, बड़े bug या खतरे, " +
            "और सबसे ज़रूरी सुधार क्रम से। रिपो में ${s.files} फ़ाइलें हैं" +
            (if (s.langs.isNotEmpty()) " (" + s.langs.take(3).joinToString { it.first } + ")" else "") + "।"
}
