package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "कोडिंग एजेंट पहली बार में ही सही करे, जैसे Perplexity").
 *
 * Push से पहले (बिना CI, सेकंडों में) - इस काम में जो नया जोड़ा गया, वह सच में मौजूद है?
 *  1) resource: @drawable/x, @raw/x, @layout/x, @string/x, @color/x, @id/x ... (XML) और R.drawable.x, R.id.x ... (Kotlin/Java)
 *  2) layout में custom View (<com.dk.assistant.ui.OrbView ...>) - उस package में वह class है?
 *  3) नई Kotlin/Java फ़ाइल जिसकी class/object कहीं इस्तेमाल नहीं (बेकार फ़ाइल)
 *
 * PR #252: layout में raw/energy_orb और drawable/ic_add इस्तेमाल किए पर बनाए नहीं -> AAPT fail, हर गलती ~10 मिनट का CI राउंड.
 * सिर्फ़ इसी काम के नए इस्तेमाल जाँचे जाते हैं (पुराने नहीं). बाहर की library के जाने-माने नाम (abc_, mtrl_, material_ ...) छोड़े जाते हैं.
 * हर लाइन "AUDIT <path>: ..." - Android पर निर्भर नहीं (JVM unit test).
 */
object RefCheck {
    /** फ़ाइल वाले resource (res/<type>/<name>.*) */
    private val FILE_TYPES = setOf("drawable", "mipmap", "raw", "layout", "anim", "animator", "font", "xml", "menu", "navigation", "color")
    /** values/ की xml वाले resource (<string name="x">) */
    private val VALUE_TYPES = setOf("string", "color", "dimen", "array", "bool", "integer", "plurals")
    private val CHECKED = FILE_TYPES + VALUE_TYPES + "id"
    /** AndroidX / Material / Google library के अपने resource - रिपो में नहीं होते, फिर भी मौजूद */
    private val LIB_PREFIX = listOf("abc_", "mtrl_", "material_", "design_", "m3_", "notification_", "exo_", "common_google", "googleg_",
        "lb_", "preference_", "tooltip_", "androidx_", "cardview_", "fastscroll_", "ic_mtrl", "ic_m3", "compat_", "browser_actions_", "test_")

    private val XML_REF = Regex("""@(\w+)/([\w.]+)""")                 // @+id / @android: / ?attr नहीं पकड़ता
    private val CODE_REF = Regex("""(?<![\w.])R\.(\w+)\.(\w+)""")      // android.R.x / material.R.x नहीं
    /**
     * नवंबर 2026 (feature/dk-1112-1236: `com.dk.assistant.R.drawable.dk_wp_logo` - तस्वीर थी ही नहीं, यह जाँच उसे छोड़ देती थी
     * क्योंकि ऊपर वाला regex \".R\" से पहले बिंदु देखकर रुक जाता है. 3 CI राउंड बर्बाद). पूरा नाम वाला R भी पकड़ो -
     * सिर्फ़ android./androidx./com.google./com.android. वाले (library के) नहीं.
     */
    private val QUAL_REF = Regex("""(?<![\w.])((?:[a-z_]\w*\.)+)R\.(\w+)\.(\w+)""")
    private val LIB_PKG = listOf("android.", "androidx.", "com.google.", "com.android.", "kotlin.", "java.")
    private val RES_PATH = Regex("""/res/([a-z]+)(?:-[\w-]+)?/([^/]+)$""")
    private val VALUE_DECL = Regex("""<(string-array|integer-array|array|string|color|dimen|bool|integer|plurals|item)\b([^>]*)>""")
    private val NAME_ATTR = Regex("""\bname\s*=\s*"([^"]+)"""")
    private val TYPE_ATTR = Regex("""\btype\s*=\s*"([^"]+)"""")
    private val ID_DECL = Regex("""@\+id/(\w+)""")
    private val TAG = Regex("""<([a-z][\w]*(?:\.[\w]+)+)[\s/>]""")

    private fun norm(n: String) = n.replace('.', '_')

    /** रिपो में मौजूद resource: "drawable:ic_add", "string:app_name", "id:btnSend" */
    fun existing(all: Map<String, String>, extra: Set<String> = emptySet()): Set<String> {
        val have = HashSet<String>()
        // binary (png/webp/ttf…) फ़ाइलें map में नहीं आतीं - उनके path से
        for (p in extra) RES_PATH.find(p)?.let { m -> if (m.groupValues[1] != "values") have.add("${m.groupValues[1]}:${norm(m.groupValues[2].substringBefore('.'))}") }
        for ((p, c) in all) {
            val m = RES_PATH.find(p) ?: continue
            val dir = m.groupValues[1]; val file = m.groupValues[2]
            if (dir == "values") {
                VALUE_DECL.findAll(c).forEach { d ->
                    val tag = d.groupValues[1]; val attrs = d.groupValues[2]
                    val name = NAME_ATTR.find(attrs)?.groupValues?.get(1) ?: return@forEach
                    val type = when (tag) { "string-array", "integer-array", "array" -> "array"; "item" -> TYPE_ATTR.find(attrs)?.groupValues?.get(1) ?: return@forEach; else -> tag }
                    have.add("$type:${norm(name)}")
                }
            } else have.add("$dir:${norm(file.substringBefore('.'))}")
            if (p.endsWith(".xml")) ID_DECL.findAll(c).forEach { have.add("id:${it.groupValues[1]}") }
        }
        // Kotlin/Java में बने id (View.generateViewId नहीं, पर ids.xml/@+id सब ऊपर); layout के बाहर भी @+id हो सकता है
        all.forEach { (p, c) -> if (p.endsWith(".xml") && RES_PATH.find(p) == null) ID_DECL.findAll(c).forEach { have.add("id:${it.groupValues[1]}") } }
        return have
    }

    /** एक फ़ाइल के resource इस्तेमाल ("type:name") */
    fun refs(path: String, src: String): Set<String> {
        val out = LinkedHashSet<String>()
        when {
            path.endsWith(".xml") -> XML_REF.findAll(src.replace(Regex("(?s)<!--.*?-->"), "")).forEach { m ->
                val type = m.groupValues[1]; val name = m.groupValues[2]
                if (type in CHECKED) out.add("$type:${norm(name)}")
            }
            path.endsWith(".kt") || path.endsWith(".java") -> {
                val code = SevenSymbols.strip(src)
                CODE_REF.findAll(code).forEach { m ->
                    val type = m.groupValues[1]; val name = m.groupValues[2]
                    if (type in CHECKED) out.add("$type:$name")
                }
                QUAL_REF.findAll(code).forEach { m ->
                    val pkg = m.groupValues[1]; val type = m.groupValues[2]; val name = m.groupValues[3]
                    if (type in CHECKED && LIB_PKG.none { pkg.startsWith(it) }) out.add("$type:$name")
                }
            }
        }
        return out.filterNot { r -> LIB_PREFIX.any { r.substringAfter(':').startsWith(it) } }.toSet()
    }

    private fun isAppFile(p: String) = p.contains("/src/") && (p.endsWith(".xml") || p.endsWith(".kt") || p.endsWith(".java"))

    /** 1) इस काम के नए resource इस्तेमाल जो रिपो में कहीं नहीं (बदलाव समेत) */
    /** hint = true: साथ में उसी तरह के मौजूद नाम + "AI तस्वीर नहीं बना सकता" ([nearby]) - टीम की पक्की जाँच में */
    fun missingResources(changed: Map<String, String?>, before: (String) -> String?, all: Map<String, String>, extra: Set<String> = emptySet(),
                         hint: Boolean = false): List<String> {
        if (all.keys.none { it.contains("/res/values") }) return emptyList()     // Android रिपो नहीं
        if (GitHub.TRUNCATED in extra) return emptyList()                         // रिपो पूरा नहीं आया - पक्का नहीं कह सकते
        val have = existing(all, extra)
        val out = mutableListOf<String>()
        for ((p, now) in changed) {
            if (now == null || !isAppFile(p)) continue
            val fresh = refs(p, now) - before(p)?.let { refs(p, it) }.orEmpty()
            val miss = fresh.filter { r ->
                val t = r.substringBefore(':'); val n = r.substringAfter(':')
                r !in have && !(t == "color" && "color:$n" in have)
            }
            if (miss.isNotEmpty()) out += "AUDIT $p: ये resource इस्तेमाल किए पर रिपो में कहीं नहीं (build fail होगा - AAPT/Unresolved reference): " +
                miss.take(12).joinToString(", ") { r -> "@" + r.replace(':', '/') } +
                " - या तो इन्हें इसी काम में बनाएँ (res/<type>/<name> या values/*.xml में), या मौजूद नाम इस्तेमाल करें" +
                (if (hint) nearby(miss, have) else "")
        }
        return out
    }

    /** फ़ाइल वाले resource जो AI text से बना ही नहीं सकता (png/webp/jpg/mp3...) - सिर्फ़ XML (vector/shape) बन सकता है */
    private val BINARY_ONLY = setOf("drawable", "mipmap", "raw", "font")

    /**
     * नवंबर 2026: गायब नाम के साथ उसी तरह के मौजूद नाम (डिबगर/कोडर को सीधा रास्ता) + binary की सच्चाई.
     * जैसे: "मौजूद drawable: dk_wp_1, dk_wp_2 ..." और "AI नई png/webp/jpg तस्वीर नहीं बना सकता".
     */
    fun nearby(miss: Collection<String>, have: Set<String>, max: Int = 25): String {
        val types = miss.map { it.substringBefore(':') }.distinct()
        val sb = StringBuilder()
        for (t in types) {
            val want = miss.filter { it.startsWith("$t:") }.map { it.substringAfter(':') }
            val names = have.filter { it.startsWith("$t:") }.map { it.substringAfter(':') }
            if (names.isEmpty()) continue
            // पहले वे जिनका शुरू का हिस्सा गायब नाम जैसा (dk_wp_logo -> dk_wp_1..5), फिर बाकी
            val pre = want.map { w -> w.substringBeforeLast('_', w) }.toSet()
            val close = names.filter { n -> pre.any { p -> p.length >= 3 && n.startsWith(p) } }.sorted()
            val list = (close + names.sorted()).distinct().take(max)
            sb.append("\nमौजूद $t (इन्हीं में से चुनें): ").append(list.joinToString(", ")).append(if (names.size > list.size) " …" else "")
        }
        if (types.any { it in BINARY_ONLY })
            sb.append("\nध्यान: AI नई png/webp/jpg तस्वीर, आवाज़ या font फ़ाइल नहीं बना सकता. drawable के लिए या तो ऊपर का मौजूद नाम लें, " +
                "या res/drawable/<name>.xml में vector/shape XML बनाएँ; असली तस्वीर चाहिए तो रिपोर्ट में लिखें कि user तस्वीर दे")
        return sb.toString()
    }

    /**
     * CI/sandbox log की "Unresolved reference: x" जिनका इस्तेमाल code में R.<type>.x है पर रिपो में वह resource नहीं -
     * डिबगर को पक्की बात (AI अंदाज़ा नहीं): यह resource है ही नहीं + मौजूद नाम. खाली = ऐसी कोई गलती नहीं.
     */
    fun ciMissing(log: String, all: Map<String, String>, extra: Set<String> = emptySet()): String {
        if (all.keys.none { it.contains("/res/values") } || GitHub.TRUNCATED in extra) return ""
        val names = Regex("""Unresolved reference:?\s*'?(\w+)'?""").findAll(log).map { it.groupValues[1] }.toSet()
        if (names.isEmpty()) return ""
        val have = existing(all, extra)
        val used = HashSet<String>()
        for ((p, c) in all) if (p.endsWith(".kt") || p.endsWith(".java")) refs(p, c).forEach { r -> if (r.substringAfter(':') in names) used.add(r) }
        val miss = used.filter { it !in have }.sorted()
        if (miss.isEmpty()) return ""
        return "RESOURCE MISSING (पक्का - रिपो में यह फ़ाइल/नाम है ही नहीं, इसलिए Unresolved reference): " +
            miss.joinToString(", ") { "R." + it.replace(':', '.') } + nearby(miss, have)
    }

    /** रिपो की Kotlin/Java classes: "package.Name" */
    fun classes(all: Map<String, String>): Set<String> {
        val out = HashSet<String>()
        for ((p, c) in all) {
            if (!p.endsWith(".kt") && !p.endsWith(".java")) continue
            val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(c)?.groupValues?.get(1) ?: ""
            Regex("""\b(?:class|object|interface)\s+([A-Z]\w*)""").findAll(SevenSymbols.strip(c)).forEach { out.add(if (pkg.isBlank()) it.groupValues[1] else "$pkg.${it.groupValues[1]}") }
        }
        return out
    }

    /** 2) layout में नया custom View (<com.app.X>) जिसकी class रिपो में नहीं (package रिपो का ही हो तब) */
    fun missingViews(changed: Map<String, String?>, before: (String) -> String?, all: Map<String, String>): List<String> {
        val cls = classes(all)
        val roots = all.entries.filter { it.key.endsWith(".kt") || it.key.endsWith(".java") }
            .mapNotNull { Regex("""(?m)^\s*package\s+([\w.]+)""").find(it.value)?.groupValues?.get(1)?.split('.')?.take(2)?.joinToString(".") }.toSet()
        if (roots.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        for ((p, now) in changed) {
            if (now == null || !p.contains("/res/") || !p.endsWith(".xml")) continue
            val old = before(p)?.let { o -> TAG.findAll(o).map { it.groupValues[1] }.toSet() }.orEmpty()
            val miss = TAG.findAll(now).map { it.groupValues[1] }.distinct()
                .filter { it !in old && it.split('.').take(2).joinToString(".") in roots && it !in cls }.toList()
            if (miss.isNotEmpty()) out += "AUDIT $p: layout में ये View इस्तेमाल किए पर इनकी class रिपो में नहीं (app खुलते ही crash / build fail): " +
                miss.joinToString(", ") + " - class इसी काम में बनाएँ या मौजूद View इस्तेमाल करें"
        }
        return out
    }

    /** 3) नई Kotlin/Java फ़ाइल (src/main) जिसकी कोई class/object कहीं और इस्तेमाल नहीं */
    fun deadNewCode(changed: Map<String, String?>, before: (String) -> String?, all: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        for ((p, now) in changed) {
            if (now == null || before(p) != null || !p.contains("/src/main/") || !(p.endsWith(".kt") || p.endsWith(".java"))) continue
            val names = Regex("""(?m)^(?:[a-z]+\s+)*(?:class|object|interface)\s+([A-Z]\w*)""").findAll(SevenSymbols.strip(now)).map { it.groupValues[1] }.toList()
            if (names.isEmpty()) continue                        // सिर्फ़ top-level functions/extensions - नाम से नहीं जाँचते
            val used = names.any { n -> val re = Regex("""\b${Regex.escape(n)}\b"""); all.any { (q, c) -> q != p && re.containsMatchIn(c) } }
            if (!used) out += "AUDIT $p: नई फ़ाइल की ${names.take(3).joinToString()} कहीं इस्तेमाल नहीं (कोई screen/code इसे नहीं बुलाता) - " +
                "इसे जहाँ काम आना है वहाँ जोड़ें (Activity / Manifest / बुलाने वाला code), या यह फ़ाइल न बनाएँ"
        }
        return out
    }

    /** नई dependency (implementation/api/kapt/ksp "group:artifact:version") जो पहले नहीं थी */
    private val DEP = Regex("""(?:implementation|api|kapt|ksp|testImplementation)\s*\(?\s*["']([\w.\-]+):([\w.\-]+):[^"']+["']""")
    private val DEP_ALIAS = Regex("""(?:implementation|api|kapt|ksp)\s*\(\s*libs\.([\w.]+)\s*\)""")

    fun newDependencies(changed: Map<String, String?>, before: (String) -> String?): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for ((p, now) in changed) {
            if (now == null || !(p.endsWith(".gradle.kts") || p.endsWith(".gradle") || p.endsWith("libs.versions.toml"))) continue
            val old = before(p).orEmpty()
            fun deps(t: String) = DEP.findAll(t).map { "${it.groupValues[1]}:${it.groupValues[2]}" }.toSet() + DEP_ALIAS.findAll(t).map { "libs." + it.groupValues[1] }.toSet()
            (deps(now) - deps(old)).forEach { out.add(p to it) }
        }
        return out
    }

    /**
     * नई library सिर्फ़ तब, जब काम में साफ़ माँगी गई हो (PR #252: Lottie जोड़ी -> Robolectric tests टूटे, बाद में हटानी पड़ी).
     * task में "library / dependency / लाइब्रेरी / implementation" या उस library का नाम हो = छूट.
     */
    fun unaskedDependencies(changed: Map<String, String?>, before: (String) -> String?, task: String): List<String> {
        val low = task.lowercase()
        val asked = listOf("library", "dependency", "dependencies", "लाइब्रेरी", "डिपेंडेंसी", "implementation", "sdk", "gradle").any { low.contains(it) }
        if (asked) return emptyList()
        return newDependencies(changed, before).filter { (_, d) -> d.substringAfterLast(':').substringAfterLast('.').lowercase().let { a -> a.length < 3 || !low.contains(a) } }
            .map { (p, d) -> "AUDIT $p: नई dependency \"$d\" जोड़ी पर काम में कोई नई library नहीं माँगी - नई library build या unit tests तोड़ सकती है. " +
                "इसे हटाएँ और app में पहले से मौजूद चीज़ों से काम करें" }
    }
}
