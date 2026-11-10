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

    /**
     * audit K2: यूज़र ने काम में यह सुरक्षित फ़ाइल साफ़ माँगी है? (फ़ाइल का नाम, या workflow/signing जैसा शब्द).
     * न माँगी हो तो 7-एजेंट टीम उसे बदल नहीं सकती - पहले यह रोक सिर्फ़ prompt में लिखी थी.
     */
    fun askedFor(task: String, path: String): Boolean {
        val t = task.lowercase(Locale.ROOT)
        val p = path.trim().trimStart('/').lowercase(Locale.ROOT)
        val name = p.substringAfterLast('/')
        if (name.isNotBlank() && t.contains(name)) return true
        if (p.startsWith(".github/workflows/")) return WORKFLOW_WORDS.any { t.contains(it) }
        if (name.substringAfterLast('.', "") in protectedExt) return SIGNING_WORDS.any { t.contains(it) }
        if (name.startsWith("gradlew")) return t.contains("gradle wrapper") || t.contains("gradlew")
        return false
    }

    /** बदली गई सुरक्षित फ़ाइलें जो यूज़र ने नहीं माँगीं (खाली = ठीक) */
    fun blockedIn(paths: Collection<String>, task: String): List<String> = paths.filter { isProtected(it) && !askedFor(task, it) }

    private val WORKFLOW_WORDS = listOf("workflow", ".github", "github actions", "github action", "वर्कफ़्लो", "वर्कफ्लो")
    private val SIGNING_WORDS = listOf("keystore", "signing", "jks", "p12", "साइनिंग", "कीस्टोर")

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

    /** PR ka description (Hindi). backup = पुराने फ़ीचर को बदलने/हटाने से पहले बनी backup branch; rootCause = bug/build की असली जड़ */
    fun prBody(summary: String, changes: String, protected: List<String>, backup: String = "", rootCause: String = "", note: String = ""): String = buildString {
        append("DK कोडिंग एजेंट ने यह PR बनाया (सुरक्षित PR मोड)।\n\n")
        append("## क्या बदला\n").append(summary.trim().ifBlank { "-" }).append("\n\n")
        if (rootCause.isNotBlank()) append("## समस्या की जड़ (root cause)\n").append(rootCause.trim()).append("\n\n")
        append("## फ़ाइलें\n```\n").append(changes.trim()).append("\n```\n\n")
        if (protected.isNotEmpty()) append("## ⚠ संवेदनशील फ़ाइलें बदलीं\n").append(protected.joinToString("\n") { "- `$it`" }).append("\n\n")
        if (backup.isNotBlank()) append("## 🗂 बैकअप (पुराना फ़ीचर जानबूझकर बदला/हटाया)\n")
            .append("बदलाव से पहले का पूरा code branch `$backup` में सुरक्षित है। वापस लाना हो तो: GitHub पर `$backup` से `main` के लिए PR बनाएँ, ")
            .append("या DK से बोलें \"$backup से पुराना फ़ीचर वापस लाओ\"।\n\n")
        append("## पुराने features की सुरक्षा\n- सारे पुराने unit tests इसी PR पर दोबारा चलते हैं; कोई test हटाया या कमज़ोर नहीं किया जाता (जब तक आपने न कहा हो)\n")
            .append("- `main` को अभी हाथ नहीं लगाया गया — merge आपकी मंज़ूरी से ही होगा\n\n")
        append("## Merge से पहले\n- [ ] PR build check (unit tests + debug APK) हरा है\n- [ ] बदलाव पढ़ लिए, पुराने features नहीं छेड़े गए\n- [ ] ज़रूरत हो तो debug APK फ़ोन पर चलाकर देखा\n")
        // 📒 एजेंट की काम-कॉपी (अगला राउंड/एजेंट यही पढ़कर आगे बढ़ता है)
        if (note.isNotBlank()) append("\n").append(note.trim()).append("\n")
    }

    // ---------------------------------------------------------------- पुराना फ़ीचर बदलना/हटाना → पहले बैकअप

    private val removeWords = listOf("हटा", "निकाल दो", "निकालो", "मिटा", "खत्म कर", "ख़त्म कर",
        "remove", "delete", "get rid", "hatao", "hata do", "hatado", "mitao")
    private val changeWords = listOf("बदल", "की जगह", "replace", "rename", "rewrite", "redesign", "change the existing", "badal")

    /** User ने किसी मौजूदा फ़ीचर/हिस्से को हटाने को कहा (तब उसके tests हटाना भी जायज़) */
    fun removesOldFeature(task: String): Boolean {
        val low = task.lowercase(Locale.ROOT)
        return removeWords.any { low.contains(it) }
    }

    /**
     * User ने खुद किसी मौजूदा (पुराने) फ़ीचर को बदलने/हटाने को कहा? तब यह जानबूझकर बदलाव है (नया-फ़ीचर जोखिम नहीं) -
     * पहले बैकअप, फिर बदलाव. "नया … बनाओ/जोड़ो" अकेला ऐसा नहीं माना जाता.
     * (गलती से हाँ भी हो तो नुकसान नहीं - बस एक सस्ती backup branch बनती है)
     */
    fun changesOldFeature(task: String): Boolean {
        val low = task.lowercase(Locale.ROOT)
        return removesOldFeature(task) || changeWords.any { low.contains(it) }
    }

    /** Backup branch ka naam: backup/dk-<shabd>-<MMdd-HHmm> */
    fun backupName(task: String, now: Date = Date()): String = "backup/dk-" + branchName(task, now).removePrefix(PREFIX)

    // ---------------------------------------------------------------- bug / गलती → पहले जड़

    private val bugWords = listOf("bug", "fix", "error", "crash", "exception", "not working", "doesn't work", "broken", "fail",
        "बग", "गलती", "ग़लती", "त्रुटि", "एरर", "दिक्कत", "समस्या", "ठीक कर", "ठीक करो", "काम नहीं", "नहीं चल", "नहीं हो रहा", "बंद हो जा", "क्रैश", "अटक")

    /** काम bug/गलती ठीक करने का है? (user का बताया bug, reviewer की गड़बड़ी, या fail build/test) - तब पहले root cause */
    fun isFixTask(task: String): Boolean {
        if (task.startsWith("BUILD FAILED") || task.startsWith("REVIEWER")) return true
        val low = task.lowercase(Locale.ROOT)
        return bugWords.any { low.contains(it) }
    }

    // ---------------------------------------------------------------- पुराने tests की सुरक्षा

    private fun isTestFile(p: String) = (p.contains("/src/test/") || p.contains("/src/androidTest/")) && (p.endsWith(".kt") || p.endsWith(".java"))
    private fun testCount(src: String) = Regex("@Test\\b").findAll(src).count()

    /**
     * नया काम पुराने features को चुपके से न तोड़े: मौजूदा test फ़ाइल हटाना या उसके @Test कम करना = गड़बड़ी
     * (test पास कराने के लिए test ही हटा देना). allowed = user ने खुद पुराना फ़ीचर हटाने/बदलने को कहा.
     * staged: path -> नया content (null = हटाई), original: path -> पुराना (null = नई फ़ाइल)
     * @return Hindi/English गलतियाँ (खाली = ठीक)
     */
    fun testGuard(staged: Map<String, String?>, original: Map<String, String?>, allowed: Boolean): List<String> {
        if (allowed) return emptyList()
        val out = mutableListOf<String>()
        staged.forEach { (p, now) ->
            if (!isTestFile(p)) return@forEach
            val before = original[p] ?: return@forEach          // नई test फ़ाइल - ठीक
            if (now == null) { out.add("$p: existing test file deleted - old features lose their tests (restore it; fix the code instead)"); return@forEach }
            val b = testCount(before); val a = testCount(now)
            if (a < b) out.add("$p: @Test count dropped $b -> $a - do not delete/weaken existing tests to make the build pass; fix the code instead")
        }
        return out
    }

    // ---------------------------------------------------------------- test की ईमानदारी (नवंबर 2026)

    private val ASSERT_LINE = Regex("""\b(assert\w*|expect\w*|verify|shouldBe|shouldEqual|check\s*\(|fail\s*\()""")

    /**
     * पुराने (main वाले) test का कोई assertion बदला या हटाया = पुराना फ़ीचर अब बिना पहरे के.
     * test पास कराने के लिए पुराने test का अपेक्षित मान बदलना मना (AGENTS.md: पुराने tests कमज़ोर नहीं). नई test जोड़ना ठीक.
     * allowed = user ने खुद पुराना फ़ीचर बदलने/हटाने को कहा.
     */
    fun oldAssertsChanged(staged: Map<String, String?>, original: Map<String, String?>, allowed: Boolean): List<String> {
        if (allowed) return emptyList()
        val out = mutableListOf<String>()
        staged.forEach { (p, now) ->
            if (!isTestFile(p) || now == null) return@forEach
            val before = original[p] ?: return@forEach
            val nowLines = now.lines().map { it.trim() }.toHashSet()
            val gone = before.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("//") && ASSERT_LINE.containsMatchIn(it) && it !in nowLines }
            if (gone.isNotEmpty()) out.add("$p: existing test assertion changed/removed - an old test must not be edited to make the build pass; " +
                "fix the main code so the old test passes as it is (restore these lines): " + gone.take(4).joinToString(" | ") { it.take(140) })
        }
        return out
    }

    private val LIMIT_NAME = Regex("""(?i)(min|max|threshold|limit|toleran|variance|timeout|confidence|score|margin|epsilon|eps\b|ratio|cap\b|attempt|retr|distance|similar)""")
    private val SECURE_FILE = Regex("""(?i)(guard|lock|liveness|face|auth|login|pin\b|password|secur|payment|otp|verif|biometric|permission|safe|alarm)""")
    private val NUM_DECL = Regex("""\b(?:const\s+)?(?:val|var)\s+(\w+)\s*(?::\s*[\w<>?]+)?\s*=\s*(-?\d[\d_]*(?:\.\d+)?(?:[eE]-?\d+)?[fFdDlL]?)\b""")
    private val NUM_PARAM = Regex("""\b(\w+)\s*:\s*(?:Float|Double|Int|Long)\s*=\s*(-?\d[\d_]*(?:\.\d+)?(?:[eE]-?\d+)?[fFdDlL]?)\b""")

    /** फ़ाइल की नामवाली संख्या-सीमाएँ: name -> मान (जैसे minVariance -> 0.0001f) */
    fun limits(src: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (re in listOf(NUM_DECL, NUM_PARAM)) re.findAll(src).forEach { val n = it.groupValues[1]; if (LIMIT_NAME.containsMatchIn(n)) m.putIfAbsent(n, it.groupValues[2]) }
        return m
    }

    /**
     * नवंबर 2026 (PR #249: test fail हुआ तो liveness की सीमा minVariance 10 गुना ढीली + test की हलचल बढ़ा दी = "गोलपोस्ट खिसकाना").
     * failed = पिछली बार जब CI fail हुआ तब की staged हालत, now = अब. उस fail के बाद:
     *  - सुरक्षा वाली फ़ाइल (guard/lock/face/auth/payment…) में कोई सीमा बदली, या
     *  - कोई सीमा बदली और उसी राउंड में किसी test फ़ाइल के मान/assertion भी बदले
     * = रोक. user ने काम में वही नाम/सीमा बदलने को कहा हो तो छूट.
     */
    fun goalposts(failed: Map<String, String?>, now: Map<String, String?>, task: String): List<String> {
        val low = task.lowercase(Locale.ROOT)
        val changedLimits = mutableListOf<Triple<String, String, String>>()   // path, name, "a -> b"
        var testTouched = false
        for ((p, c) in now) {
            val was = failed[p]
            if (c == null || was == null || c == was) continue
            if (isTestFile(p)) { testTouched = true; continue }
            val a = limits(was); val b = limits(c)
            for ((n, v) in a) { val nv = b[n] ?: continue; if (nv != v && !low.contains(n.lowercase(Locale.ROOT))) changedLimits.add(Triple(p, n, "$v -> $nv")) }
        }
        if (changedLimits.isEmpty()) return emptyList()
        return changedLimits.filter { (p, _, _) -> SECURE_FILE.containsMatchIn(p) || testTouched }.map { (p, n, ch) ->
            "$p: limit \"$n\" changed ($ch) right after the test failed" + (if (testTouched) " AND the test was changed in the same fix" else " in a security file") +
                " - that only makes the test pass, the behaviour is not fixed. Restore the limit and fix the real logic (or explain with evidence why the old value was wrong)"
        }
    }
}
