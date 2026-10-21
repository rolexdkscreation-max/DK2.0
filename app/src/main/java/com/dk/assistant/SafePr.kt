package com.dk.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "सुरक्षित PR मोड" के नियम (Android पर निर्भर नहीं - unit test हो सकते हैं).
 *
 * तरीका: main पर कभी सीधे push नहीं → हर काम की नई feature branch → वहीं commit →
 * Pull Request → GitHub Actions (unit tests + debug build) → आप review करें → तभी Merge.
 */
object SafePr {
    const val PREFIX = "feature/dk-"

    /** Ye files purane chalte features / release / signing chalाती हैं - user ne saaf na maanga ho to mat chhuo */
    private val protectedPrefixes = listOf(".github/workflows/", "AGENTS.md")
    private val protectedNames = listOf("gradle.properties", "settings.gradle.kts", "settings.gradle", "gradlew", "gradlew.bat")
    private val protectedExt = listOf("jks", "keystore", "p12")

    fun isProtected(path: String): Boolean {
        val p = path.trim().trimStart('/')
        val name = p.substringAfterLast('/')
        return protectedPrefixes.any { p.startsWith(it) } || name in protectedNames ||
            name.substringAfterLast('.', "").lowercase() in protectedExt
    }

    fun protectedIn(paths: Collection<String>): List<String> = paths.filter { isProtected(it) }

    /** Kaam ke shabdon se branch ka naam: feature/dk-<shabd>-<MMdd-HHmm> (sirf a-z 0-9 -) */
    fun branchName(task: String, now: Date = Date()): String {
        val slug = task.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .split('-').filter { it.length > 1 }.take(4).joinToString("-")
            .take(30).trim('-')
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.US).format(now)
        return PREFIX + (if (slug.isBlank()) "" else "$slug-") + stamp
    }

    /** Is branch par seedha push mana? (main / default branch) */
    fun isBlocked(branch: String, base: String): Boolean =
        branch.isBlank() || branch == base || branch == "main" || branch == "master"

    /** Agent ko pehli baar milne wale repo ke niyam + workflow files (chhote kiye hue) */
    fun projectContext(files: Map<String, String>): String {
        val sb = StringBuilder()
        files["AGENTS.md"]?.let { sb.append("===== AGENTS.md (project rules - FOLLOW THEM) =====\n").append(it.take(6000)).append("\n\n") }
        val flows = files.keys.filter { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) }.sorted()
        if (flows.isNotEmpty()) {
            sb.append("===== GitHub Actions workflows (how this repo is tested/built/released) =====\n")
            flows.forEach { f -> sb.append("--- $f\n").append(files[f].orEmpty().take(2500)).append("\n") }
            sb.append('\n')
        }
        val agents = files.keys.filter { it.endsWith(".kt") && Regex("(Agent|Team|Runner|Coder)[A-Za-z]*\\.kt$").containsMatchIn(it.substringAfterLast('/')) }.sorted()
        if (agents.isNotEmpty()) sb.append("Existing agent code (read before touching related features): ").append(agents.joinToString()).append('\n')
        return sb.toString().trim()
    }

    private val prWords = listOf("पीआर", "पी आर", "pull request", "पुल रिक्वेस्ट")
    private val noWords = setOf("मत", "नहीं", "नही", "mat", "nahi", "nahin", "dont", "cancel", "रद्द", "no")
    private val doWords = setOf("कर", "करो", "करें", "करना", "कीजिए", "करिए", "दो", "दीजिए", "दें", "dijiye", "kar", "karo", "karen", "kardo", "do", "it", "now")

    /** "merge" / "मर्ज" (पर "मर्ज़ी"/"मर्ज़" = बीमारी नहीं) */
    private fun isMergeWord(t: String) = t.startsWith("merge") || t == "मर्ज" || t == "मर्ग"

    /**
     * Awaaz/likhai me "PR merge कर दो" jaisa hukm?
     * null = merge ki baat nahi (normal kaam, jaise "merge sort banao" ya "आपकी मर्ज़ी"); true = merge karo; false = mat karo.
     */
    fun mergeIntent(text: String): Boolean? {
        val low = text.lowercase(Locale.ROOT).trim()
        val tokens = low.split(Regex("[\\s,.!?।:;\"'()-]+")).filter { it.isNotBlank() }
        if (tokens.none { isMergeWord(it) }) return null
        val pr = tokens.any { it == "pr" || it.startsWith("pr#") } || prWords.any { low.contains(it) }
        val no = tokens.any { it in noWords } || low.contains("अभी नहीं") || low.contains("abhi nahi")
        // PR का नाम न लिया हो तो सिर्फ़ छोटा, साफ़ हुक्म ("merge कर दो", "मर्ज करो") माना जाए
        if (!pr && (tokens.size > 4 || tokens.any { !isMergeWord(it) && it !in doWords && it !in noWords })) return null
        return !no
    }

    /** PR ka description (Hindi) */
    fun prBody(summary: String, changes: String, protected: List<String>): String = buildString {
        append("DK कोडिंग एजेंट ने यह PR बनाया (सुरक्षित PR मोड)।\n\n")
        append("## क्या बदला\n").append(summary.trim().ifBlank { "-" }).append("\n\n")
        append("## फ़ाइलें\n```\n").append(changes.trim()).append("\n```\n\n")
        if (protected.isNotEmpty()) append("## ⚠ संवेदनशील फ़ाइलें बदलीं\n").append(protected.joinToString("\n") { "- `$it`" }).append("\n\n")
        append("## Merge से पहले\n- [ ] PR build check (unit tests + debug APK) हरा है\n- [ ] बदलाव पढ़ लिए, पुराने features नहीं छेड़े गए\n- [ ] ज़रूरत हो तो debug APK फ़ोन पर चलाकर देखा\n")
    }
}
