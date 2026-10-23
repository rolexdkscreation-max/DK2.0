package com.dk.assistant

/**
 * 🔎 जाँचकर्ता (Android-free - JUnit से जाँचा जाता है).
 *
 * user रिपो / PR / checks / "काम हुआ या नहीं" पूछे तो मैनेजर इसे लगाता है. यह GitHub से असली हालत इकट्ठा करता है
 * (खुले PR + उनकी बदली फ़ाइलें और patch, checks + fail log, हाल में merge हुए PR, branches, release, टीम का अभी का काम)
 * और AI को पूरा भेजता है - AI सिर्फ़ इन्हीं तथ्यों से step-by-step हिंदी में बताता है.
 */
object RepoInspector {
    data class PFile(val path: String, val status: String, val add: Int, val del: Int, val patch: String = "")

    data class PDetail(
        val number: Int, val title: String, val url: String, val body: String,
        val state: String, val merged: Boolean, val mergedAt: String,
        val head: String, val headSha: String,
        val mergeable: Boolean? = null, val mergeState: String = "",
        val changedFiles: Int = 0, val additions: Int = 0, val deletions: Int = 0, val commits: Int = 0,
    )

    /** एक PR की पूरी जाँच */
    data class Deep(val pr: PDetail, val files: List<PFile>, val runs: List<RepoDoctor.RunI>, val failLog: String = "")

    /** कौन-से PR गहराई से देखने हैं: पूछा गया नंबर, वरना सारे खुले (सबसे नए पहले), ज़्यादा से ज़्यादा [max] */
    fun pick(focus: Int?, open: List<Int>, recentMerged: List<Int>, max: Int = 6): List<Int> =
        if (focus != null) listOf(focus)
        else (open + recentMerged.take(2)).distinct().take(max)

    /** एक PR के patch का हिस्सा (बहुत बड़ा हो तो हर फ़ाइल का पहला भाग) */
    fun patchOf(files: List<PFile>, budget: Int): String = buildString {
        val each = (budget / files.size.coerceAtLeast(1)).coerceAtLeast(1_500)
        for (f in files) {
            append("  • ").append(f.path).append(" [").append(f.status).append("] +").append(f.add).append(" -").append(f.del).append('\n')
            if (f.patch.isNotBlank() && length < budget) {
                val p = if (f.patch.length > each) f.patch.take(each) + "\n    … (patch का बाकी हिस्सा छोड़ा - ${f.patch.length - each} अक्षर)" else f.patch
                append(p.prependIndent("    ")).append('\n')
            }
        }
    }

    private fun runLine(r: RepoDoctor.RunI) = "${r.name}: " + when {
        r.running -> "⏳ चल रहा (${r.status})"
        r.passed -> "✓ पास"
        r.failed -> "✗ fail (${r.conclusion})"
        else -> r.conclusion.ifBlank { r.status }
    } + " • ${r.url}"

    /**
     * AI के लिए पूरे तथ्य. [budget] = कितने अक्षर तक (मॉडल की क्षमता के हिसाब से बड़ा)
     */
    fun facts(
        question: String, s: RepoDoctor.Snap, doctorReport: String, deep: List<Deep>,
        merged: List<PDetail>, mainFailLog: String, team: String, budget: Int = 200_000,
    ): String = buildString {
        append("USER QUESTION: ").append(question.trim()).append("\n\n")
        append("REPOSITORY: ").append(s.repo).append(" (base branch ").append(s.base).append(")\n")
        if (s.mainSha.isNotBlank()) append("BASE HEAD COMMIT: ").append(s.mainSha.take(10)).append('\n')
        s.release?.let { r -> append("LATEST RELEASE: ").append(r.name.ifBlank { r.tag }).append(" • tag ").append(r.tag)
            .append(" • files: ").append(r.assets.joinToString().ifBlank { "(कोई APK नहीं)" }).append(" • ").append(r.url).append('\n') }
            ?: append("LATEST RELEASE: none\n")
        append("\nREPO DOCTOR SUMMARY (automatic):\n").append(doctorReport.trim()).append("\n")
        val baseRuns = RepoDoctor.latest(s.runs.filter { it.sha == s.mainSha && it.sha.isNotBlank() })
        if (baseRuns.isNotEmpty()) append("\nCHECKS ON ${s.base}:\n").append(baseRuns.joinToString("\n") { "  " + runLine(it) }).append('\n')
        if (mainFailLog.isNotBlank()) append("\nFAILURE LOG ON ${s.base}:\n").append(mainFailLog.trim()).append('\n')

        val room = (budget - length - 4_000).coerceAtLeast(20_000)
        val per = room / deep.size.coerceAtLeast(1)
        for (d in deep) {
            val p = d.pr
            val start = length
            append("\n===== PR #").append(p.number).append(": ").append(p.title).append(" =====\n")
            append("URL: ").append(p.url).append('\n')
            append("STATE: ").append(if (p.merged) "MERGED (${p.mergedAt})" else p.state.uppercase())
            if (!p.merged && p.state == "open") append(" • mergeable: ").append(when (p.mergeable) { true -> "yes"; false -> "NO (conflict)"; null -> "unknown" })
                .append(if (p.mergeState.isNotBlank()) " (${p.mergeState})" else "")
            append('\n')
            append("BRANCH: ").append(p.head).append(" • commits ").append(p.commits).append(" • files ").append(p.changedFiles.coerceAtLeast(d.files.size))
                .append(" • +").append(p.additions).append(" -").append(p.deletions).append('\n')
            if (p.body.isNotBlank()) append("DESCRIPTION:\n").append(p.body.trim().take(per / 4)).append('\n')
            val runs = RepoDoctor.latest(d.runs)
            append("CHECKS ON LAST COMMIT:\n").append(if (runs.isEmpty()) "  (कोई check नहीं चला / नहीं दिखा)" else runs.joinToString("\n") { "  " + runLine(it) }).append('\n')
            if (d.failLog.isNotBlank()) append("FAILURE LOG:\n").append(d.failLog.trim().take(per / 3)).append('\n')
            if (d.files.isNotEmpty()) append("CHANGED FILES + PATCH:\n").append(patchOf(d.files, (per - (length - start)).coerceAtLeast(4_000)))
        }
        val others = merged.filter { m -> deep.none { it.pr.number == m.number } }
        if (others.isNotEmpty()) {
            append("\nRECENTLY CLOSED PRs:\n")
            others.forEach { m -> append("  #").append(m.number).append(" ").append(m.title).append(" — ").append(if (m.merged) "merged ${m.mergedAt}" else "closed without merge").append(" • ").append(m.url).append('\n') }
        }
        if (s.branches.isNotEmpty()) append("\nOTHER BRANCHES WITH WORK (no PR): ").append(s.branches.joinToString { "${it.name} (+${it.ahead})" }).append('\n')
        if (team.isNotBlank()) append("\nCODING TEAM NOW (this app):\n").append(team.trim()).append('\n')
    }.let { if (it.length > budget) it.take(budget) + "\n… (बाकी तथ्य सीमा से बाहर)" else it }

    /** टीम का अभी का हाल (कोडिंग स्क्रीन के आख़िरी कदम) */
    fun team(busy: Boolean, task: String?, lines: List<Pair<String, String>>, max: Int = 40): String = buildString {
        append(if (busy) "BUSY: yes" else "BUSY: no (idle)")
        if (!task.isNullOrBlank()) append(" • task: ").append(task.take(300))
        append('\n')
        lines.filter { it.first in setOf("s", "d", "e") }.takeLast(max).forEach { (r, t) ->
            append(when (r) { "e" -> "  ✗ "; "d" -> "  » "; else -> "  - " }).append(t.lineSequence().take(3).joinToString(" / ").take(300)).append('\n')
        }
    }
}
