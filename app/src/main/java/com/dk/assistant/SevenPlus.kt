package com.dk.assistant

import org.json.JSONObject

/**
 * 11-एजेंट सिस्टम के नए नियम (Android पर निर्भर नहीं - पूरी तरह unit test होते हैं).
 *
 *  - RepoMap      : प्लानर/प्रॉम्प्ट इंजीनियर के लिए पूरे रिपो का नक्शा (हर फ़ाइल में कौन-सी class / function)
 *  - RepoSearch   : पूरे रिपो में खोज ({"search":["नाम", …]}) - अंदाज़े से फ़ाइल चुनने की जगह
 *  - Where        : प्लान का हर कदम सही जगह पर है? (anchor फ़ाइल में सच में है, नई फ़ाइल पहले से तो नहीं)
 *  - Perf         : परफॉरमेंस एजेंट की पक्की (बिना AI) जाँच - सिर्फ़ नई लाइनों में
 *  - Qa           : QA एजेंट की पक्की जाँच - अधूरा / नकली code (TODO, NotImplementedError, नकली progress)
 *  - Understand   : प्रॉम्प्ट इंजीनियर की समझ + user की "हाँ" / "नहीं" / सुधार
 *  - Deploy       : डिप्लॉयमेंट एजेंट - कौन-सा workflow release बनाता है, release सच में बनी?
 */
object SevenPlus {

    private fun isCode(p: String) = p.endsWith(".kt") || p.endsWith(".kts") || p.endsWith(".java")

    // ------------------------------------------------------------------ रिपो का नक्शा
    object RepoMap {
        private val DECL = Regex("""^\s*(?:(?:private|internal|public|protected|override|open|abstract|data|sealed|inline|suspend|enum|annotation|inner|companion|lateinit|const|operator|infix|tailrec|external|actual|expect|@\w+(?:\([^)]*\))?)\s+)*(class|object|interface|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)""")
        private val JAVA = Regex("""^\s*(?:public|private|protected|static|final|abstract|synchronized|\s)*\s*(class|interface|enum)\s+([A-Za-z_]\w*)""")
        private val XML_ID = Regex("""@\+id/([A-Za-z_]\w*)""")

        /** एक फ़ाइल के नाम: class/object/interface हमेशा, fun सब, सिर्फ़ ऊपरी स्तर (indent ≤ 4) के val/var */
        fun names(path: String, src: String): List<String> {
            if (path.endsWith(".xml")) return XML_ID.findAll(src).map { "id/" + it.groupValues[1] }.distinct().take(40).toList()
            if (!isCode(path)) return emptyList()
            val out = LinkedHashSet<String>()
            for (line in src.lines()) {
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue
                val indent = line.length - t.length
                val m = DECL.find(line) ?: JAVA.find(line) ?: continue
                val kind = m.groupValues[1]; val name = m.groupValues[2]
                when (kind) {
                    "class", "object", "interface", "enum" -> out.add("$kind $name")
                    "fun" -> out.add("fun $name")
                    else -> if (indent <= 4) out.add(name)
                }
            }
            return out.toList()
        }

        /** पूरा नक्शा, ज़्यादा से ज़्यादा maxChars. test फ़ाइलें आख़िर में (कोड पहले) */
        fun build(ws: Map<String, String>, maxChars: Int = 24_000): String {
            val sb = StringBuilder()
            val order = ws.keys.sortedWith(compareBy<String>({ it.contains("/test/") }, { it }))
            for (p in order) {
                val n = names(p, ws[p] ?: continue)
                if (n.isEmpty()) continue
                val line = "$p: " + n.take(60).joinToString(", ") + "\n"
                if (sb.length + line.length > maxChars) { sb.append("… (नक्शा छोटा किया - बाकी के लिए {\"search\":[…]} करें)\n"); break }
                sb.append(line)
            }
            return sb.toString()
        }
    }

    // ------------------------------------------------------------------ रिपो में खोज
    object RepoSearch {
        const val MAX_TERMS = 6
        const val MAX_HITS = 40

        /** हर शब्द के लिए path:line: लाइन. शब्द छोटे-बड़े अक्षर से फ़र्क नहीं (पर पहले सटीक मिलान) */
        fun search(ws: Map<String, String>, terms: List<String>, maxHits: Int = MAX_HITS): String {
            val sb = StringBuilder()
            for (term in terms.map { it.trim() }.filter { it.length >= 2 }.distinct().take(MAX_TERMS)) {
                var hits = 0
                sb.append("=== SEARCH \"").append(term).append("\" ===\n")
                for ((p, src) in ws.entries.sortedWith(compareBy<Map.Entry<String, String>>({ it.key.contains("/test/") }, { it.key }))) {
                    if (hits >= maxHits) break
                    src.lines().forEachIndexed { i, l ->
                        if (hits < maxHits && l.contains(term, ignoreCase = true)) {
                            sb.append(p).append(':').append(i + 1).append(": ").append(l.trim().take(180)).append('\n'); hits++
                        }
                    }
                }
                if (hits == 0) sb.append("(कहीं नहीं मिला / NOT FOUND anywhere)\n")
                else if (hits >= maxHits) sb.append("(और भी हैं - शब्द और सटीक करें)\n")
            }
            return sb.toString()
        }

        /** प्लानर/डिबगर/प्रॉम्प्ट इंजीनियर का जवाब {"search":[…]} */
        fun terms(text: String): List<String>? =
            Seven.json(text)?.optJSONArray("search")?.let { Seven.strings(it).take(MAX_TERMS) }?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ कदम सही जगह पर?
    object Where {
        private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

        /**
         * @param read फ़ाइल अभी की हालत (null = नहीं है)
         * @return गलत कदमों की list (खाली = सारे कदम सही जगह)
         */
        fun problems(plan: Seven.Plan, read: (String) -> String?): List<String> {
            val out = mutableListOf<String>()
            val created = HashSet<String>()
            for ((i, s) in plan.steps.withIndex()) {
                val n = i + 1
                val cur = read(s.file)
                when (s.action) {
                    "edit" -> {
                        if (cur == null && s.file !in created) { out.add("कदम $n: ${s.file} रिपो में नहीं है (edit नहीं हो सकता) — search करके सही path लिखें, या action=create"); continue }
                        if (cur == null) continue
                        if (s.anchor.isBlank()) { out.add("कदम $n: ${s.file} के लिए anchor नहीं दिया — बदलाव किस लाइन के पास होगा, फ़ाइल से सटीक text दें"); continue }
                        if (!norm(cur).contains(norm(s.anchor))) out.add("कदम $n: anchor \"${s.anchor.take(80)}\" ${s.file} में नहीं मिला — यह गलत जगह है, फ़ाइल पढ़कर/search करके असली लाइन दें")
                    }
                    "create" -> { if (cur != null) out.add("कदम $n: ${s.file} पहले से मौजूद है — create नहीं, edit करें (पुराना code न मिटे)"); created.add(s.file) }
                }
            }
            return out
        }
    }

    // ------------------------------------------------------------------ परफॉरमेंस (पक्की जाँच)
    object Perf {
        /** सिर्फ़ नई लाइनें (पहले से चल रहा code नहीं छेड़ते) */
        private fun added(before: String?, after: String): List<String> {
            val old = before?.lines()?.map { it.trim() }?.toHashSet() ?: hashSetOf()
            return after.lines().map { it.trim() }.filter { it.isNotBlank() && it !in old && !it.startsWith("//") && !it.startsWith("*") }
        }

        /** @return समस्याएँ (Hindi) - "must" = ठीक करना ज़रूरी */
        fun scan(path: String, before: String?, after: String): List<String> {
            if (!isCode(path) || path.contains("/test/")) return emptyList()
            val add = added(before, after)
            val out = mutableListOf<String>()
            if (add.any { it.contains("Thread.sleep(") }) out.add("$path: Thread.sleep() — thread रुक जाता है (UI जाम / ANR); coroutine में delay() इस्तेमाल करें")
            if (add.any { it.contains("GlobalScope.") }) out.add("$path: GlobalScope — काम कभी नहीं रुकता (memory/battery leak); मौजूदा scope (lifecycleScope / svc.scope / CoderSession.scope) इस्तेमाल करें")
            if (add.any { it.contains("runBlocking") }) out.add("$path: runBlocking — app के code में thread जाम करता है; suspend / launch इस्तेमाल करें")
            if (add.any { Regex("""while\s*\(\s*true\s*\)""").containsMatchIn(it) } && add.none { it.contains("delay(") || it.contains("break") || it.contains("return") || it.contains("isActive") })
                out.add("$path: while(true) बिना delay/break — CPU 100% और battery खत्म")
            if (add.any { it.contains("postDelayed(") } && !after.contains("removeCallbacks"))
                out.add("$path: postDelayed() है पर removeCallbacks() कहीं नहीं — screen बंद होने पर भी चलता रहेगा (leak)")
            return out
        }
    }

    // ------------------------------------------------------------------ QA (पक्की जाँच)
    object Qa {
        /** अधूरा या नकली code - सिर्फ़ नई लाइनों में */
        fun scan(path: String, before: String?, after: String): List<String> {
            if (!isCode(path) || path.contains("/test/")) return emptyList()
            val old = before?.lines()?.map { it.trim() }?.toHashSet() ?: hashSetOf()
            val add = after.lines().map { it.trim() }.filter { it.isNotBlank() && it !in old }
            val out = mutableListOf<String>()
            if (add.any { Regex("""\bTODO\s*\(""").containsMatchIn(it) || it.contains("NotImplementedError") })
                out.add("$path: TODO()/NotImplementedError — काम अधूरा छोड़ा है, चलते ही crash")
            if (add.any { Regex("""//\s*(TODO|FIXME)\b""").containsMatchIn(it) }) out.add("$path: // TODO / FIXME — काम अधूरा छोड़ा है")
            // नकली progress: बिना असली काम के बढ़ता प्रतिशत (पुरानी branch में मिला था: हर 2 सेकंड +25%)
            val fakeProgress = add.any { Regex("""progress\s*\+=\s*\d+""").containsMatchIn(it) } && add.any { it.contains("delay(") }
            if (fakeProgress) out.add("$path: progress सिर्फ़ समय के साथ बढ़ रहा है (delay + progress +=) — यह नकली progress है, असली काम की हालत दिखाएँ")
            return out
        }
    }

    // ------------------------------------------------------------------ समझ + मंज़ूरी
    data class Understanding(
        val kind: Seven.Kind,
        val brief: String,
        val understood: String,
        val spec: String,
        val acceptance: List<String>,
        val notTouch: List<String>,
        val questions: List<String>,
        val area: List<String>,
        val answer: String = "",
        /** user को क्या-क्या बदला हुआ मिलेगा */
        val changes: List<String> = emptyList(),
        /** टीम कैसे करेगी - कदम दर कदम */
        val steps: List<String> = emptyList(),
        /** फ़ोटो/वीडियो में प्रॉम्प्ट इंजीनियर ने क्या देखा (हर एजेंट को लिखा हुआ जाता है) */
        val mediaNotes: String = "",
        /** user ने जो-जो माँगा - हर बात अलग (कुछ छूटे नहीं, कुछ जुड़े नहीं) */
        val wants: List<String> = emptyList(),
        /** अभी कोड में क्या हो रहा है / समस्या की वजह - सीधी भाषा में */
        val now: String = "",
        /** v3.6: काम के साथ पूछे गए सवालों का मैनेजर का जवाब (काम नहीं रुकता - पहले समझाना, फिर "हाँ") */
        val reply: String = ""
    ) {
        val needsAnswer get() = questions.isNotEmpty()

        /**
         * user को दिखाने वाला संदेश - जैसे कोई समझदार साथी समझाता है:
         * आपने क्या माँगा (हर बात अलग), अभी क्या हो रहा है, क्या बदलेगा, क्या नहीं छेड़ा जाएगा, और क्या साफ़ नहीं.
         * तकनीकी अंदाज़े (फ़ाइलों के path, काम का प्रकार, शर्तों की लंबी list) यहाँ नहीं - वे टीम के लिए spec में हैं.
         */
        fun hindi(): String = buildString {
            if (reply.isNotBlank()) append("👔 मैनेजर — आपके सवालों का जवाब:\n").append(reply.trim()).append("\n\n")
            append("✍ मैंने आपकी बात ऐसे समझी:\n").append(understood.ifBlank { brief }.trim()).append("\n")
            if (wants.isNotEmpty()) append("\nआप ये चाहते हैं:\n").append(wants.mapIndexed { i, x -> "${i + 1}. $x" }.joinToString("\n")).append("\n")
            if (now.isNotBlank()) append("\n🔍 अभी क्या हो रहा है:\n").append(now.trim()).append("\n")
            if (mediaNotes.isNotBlank()) append("\n🖼 आपकी फ़ोटो/वीडियो में मैंने देखा:\n").append(mediaNotes.trim().take(1500)).append("\n")
            if (changes.isNotEmpty()) append("\n🔧 क्या-क्या बदलेगा:\n").append(changes.joinToString("\n") { "• $it" }).append("\n")
            if (steps.isNotEmpty()) append("\n🪜 कैसे करेंगे:\n").append(steps.mapIndexed { i, x -> "${i + 1}. $x" }.joinToString("\n")).append("\n")
            if (notTouch.isNotEmpty()) append("\n🛡 इन्हें नहीं छेड़ा जाएगा:\n").append(notTouch.joinToString("\n") { "• $it" }).append("\n")
            if (questions.isNotEmpty()) {
                append("\n❓ यह मुझे साफ़ नहीं हुआ — पहले बता दीजिए:\n").append(questions.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n"))
                append("\n\nजवाब लिखें (या 🎤 से बोलें) — फिर मैं दोबारा समझकर बताऊँगा।")
            } else append("\nक्या मैंने सही समझा? \"हाँ\" लिखें/बोलें तो काम शुरू होगा। कुछ अलग चाहिए तो वही लिखें, मैं दोबारा समझूँगा। \"रद्द\" = काम नहीं होगा।")
        }

        companion object {
            fun parse(o: JSONObject?, kind: Seven.Kind, brief: String, acceptance: List<String>): Understanding {
                val acc = Seven.strings(o?.optJSONArray("acceptance")).ifEmpty { acceptance }
                return Understanding(kind, brief,
                    o?.optString("understood").orEmpty().trim(),
                    o?.optString("spec").orEmpty().trim().ifBlank { brief },
                    acc, Seven.strings(o?.optJSONArray("not_touch")), Seven.strings(o?.optJSONArray("questions")).take(4),
                    Seven.strings(o?.optJSONArray("area")).take(8),
                    changes = Seven.strings(o?.optJSONArray("changes")).take(12),
                    steps = Seven.strings(o?.optJSONArray("steps")).take(10),
                    wants = Seven.strings(o?.optJSONArray("wants")).take(10),
                    now = o?.optString("now").orEmpty().trim())
            }
        }
    }

    enum class Reply { YES, NO, OTHER }

    private val YES = setOf("हाँ", "हां", "हा", "हाँजी", "जी", "जीहाँ", "जीहां", "ha", "haa", "haan", "han", "haanji", "yes", "y", "ok", "okay",
        "ओके", "ठीक", "ठीकहै", "सही", "सहीहै", "करो", "शुरू", "शुरूकरो", "हाँकरो", "हांकरो", "हाँशुरूकरो", "हांशुरूकरो", "start", "go",
        "theek", "theekhai", "thikhai", "sahi", "sahihai", "karo", "shuru", "shurukaro", "बिल्कुल", "bilkul", "done", "confirm", "approved")
    private val NO = setOf("नहीं", "नही", "ना", "no", "nahi", "nahin", "na", "रद्द", "रद्दकरो", "cancel", "stop", "रुको", "मतकरो", "छोड़ो", "chhodo", "rehnedo", "रहनेदो", "मत", "mat")

    private val FILLER = setOf("सही", "समझा", "समझे", "समझी", "है", "हैं", "आगे", "बढ़ो", "कर", "दो", "दीजिए", "करो", "शुरू", "बिल्कुल", "जी",
        "please", "plz", "go", "ahead", "ji", "hai", "samjha", "kar", "do", "karo", "aage", "badho", "बॉस", "boss", "भाई", "bhai", "अभी", "काम",
        "kaam", "abhi", "बस", "bas", "it", "that", "is", "right", "correct", "fine", "इसे", "ise")

    /** user का जवाब: साफ़ "हाँ" / "नहीं" (छोटा), वरना OTHER = सुधार/नई जानकारी */
    fun reply(text: String): Reply {
        val t = text.lowercase().replace(Regex("[\\s,.!?।:;\"'()\\-🎤]+"), "")
        if (t.isBlank()) return Reply.OTHER
        if (t in NO) return Reply.NO
        if (t in YES) return Reply.YES
        // "हाँ, सही समझा" / "yes go ahead" जैसे छोटे जवाब
        val words = text.lowercase().split(Regex("[\\s,.!?।:;\"'()\\-]+")).filter { it.isNotBlank() && it != "🎤" }
        if (words.size in 1..6) {
            if (words.all { it in NO || it in FILLER } && words.any { it in NO }) return Reply.NO
            // सिर्फ़ हाँ वाले शब्द ("हाँ सही समझा", "yes go ahead"); "हाँ लेकिन बटन लाल" = सुधार (OTHER)
            if (words.all { it in YES || it in FILLER } && words.any { it in YES }) return Reply.YES
        }
        return Reply.OTHER
    }

    /**
     * user का नया संदेश + इंतज़ार वाली समझ -> (असली काम, मंज़ूर समझ या null, नॉर्मल?), null = रद्द.
     * "हाँ" = वही काम, मंज़ूर समझ के साथ (सवाल बाकी हों तो "हाँ" भी जवाब माना - दोबारा समझो).
     * सवालों का जवाब / सुधार = काम + जवाब, फिर से समझना.
     */
    fun resolve(input: String, pendingTask: String?, u: Understanding?, pendingNormal: Boolean, forceNormal: Boolean): Triple<String, Understanding?, Boolean>? {
        if (pendingTask == null || u == null) return Triple(input, null, forceNormal)
        val normal = forceNormal || pendingNormal
        return when (reply(input)) {
            Reply.YES -> if (!u.needsAnswer) Triple(pendingTask, u, normal)
                else Triple(pendingTask + "\n\nUSER'S ANSWER TO YOUR QUESTIONS: " + input, null, normal)
            Reply.NO -> null
            Reply.OTHER -> Triple(pendingTask + (if (u.needsAnswer) "\n\nUSER'S ANSWER TO YOUR QUESTIONS: " else "\n\nUSER'S CORRECTION (follow this): ") + input, null, normal)
        }
    }

    // ------------------------------------------------------------------ डिप्लॉयमेंट
    object Deploy {
        /** release बनाने वाला workflow: main पर push से चलता है + हाथ से भी (workflow_dispatch) + "release" बनाता है */
        fun releaseWorkflow(workflows: List<Pair<String, String>>): String? = workflows.firstOrNull { (_, y) ->
            val t = y.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
            Regex("\\bworkflow_dispatch\\b").containsMatchIn(t) && Regex("\\bpush\\b").containsMatchIn(t) &&
                (t.contains("gh release") || t.contains("action-gh-release") || t.contains("create-release"))
        }?.first

        /** @param target release का commit/branch, @param publishedMs कब बनी (epoch ms, 0 = पता नहीं) */
        data class Rel(val tag: String, val name: String, val target: String, val assets: List<String>, val url: String, val publishedMs: Long = 0)

        private fun hex(s: String) = s.length >= 7 && s.all { it in '0'..'9' || it in 'a'..'f' }

        /** release सच में इस merge के बाद बनी और उसमें APK है? @return "" = ठीक, वरना कमी */
        fun verify(r: Rel?, mergeSha: String, mergedMs: Long, apk: String = ".apk"): String = when {
            r == null -> "कोई release नहीं मिली"
            hex(r.target) && mergeSha.isNotBlank() && !r.target.startsWith(mergeSha.take(7)) && !mergeSha.startsWith(r.target.take(7)) ->
                "नई release (${r.tag}) इस merge (${mergeSha.take(7)}) की नहीं है — उसका commit ${r.target.take(7)} है"
            mergedMs > 0 && r.publishedMs > 0 && r.publishedMs < mergedMs - 60_000 ->
                "सबसे नई release (${r.tag}) merge से पहले की है — इस merge की APK अभी नहीं बनी"
            r.assets.none { it.endsWith(apk, ignoreCase = true) } -> "release ${r.tag} में APK फ़ाइल नहीं है"
            else -> ""
        }
    }
}
