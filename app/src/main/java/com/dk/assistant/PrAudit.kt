package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "कोडिंग एजेंट भी वैसे ही जाँचे जैसे Perplexity Computer ने PR #252 जाँचा").
 *
 * Push से पहले पूरे बदलाव (main के मुकाबले) की पक्की, बिना-AI जाँच - Android pure Kotlin, JVM unit test.
 * PR #252 में ये सब CI पास/फ़ेल से पहले ही पकड़ में आ सकते थे, पर एजेंट हर बदलाव अलग-अलग देखता था:
 *  1) ui_en.tsv में पहले से मौजूद शब्द दोबारा (बाद वाली लाइन पुराना English चुपचाप बदल देती है)
 *  2) पुराना बटन (जिस पर code में click लगा है) layout में visibility="gone" - फ़ीचर गायब, किसी ने माँगा नहीं
 *  3) नया बटन/इनपुट जिसका id नहीं या id code में कहीं इस्तेमाल नहीं = दबाने पर कुछ नहीं होता
 *  4) नई layout फ़ाइल जिसे कोई screen इस्तेमाल नहीं करती (R.layout.x / @layout/x / XBinding नहीं)
 *  5) नई res/raw या drawable फ़ाइल जो कहीं इस्तेमाल नहीं
 *  6) नई gradle dependency जिसका package किसी फ़ाइल में इस्तेमाल नहीं (APK बेवजह भारी)
 *
 * हर लाइन "AUDIT <path>: ..." - डिबगर/प्लानर को सीधा बताती है क्या गलत है और कैसे ठीक करें.
 */
object PrAudit {

    /** बटन जैसे views - इन्हें id + code में काम चाहिए */
    private val ACTION_TAGS = setOf("Button", "ImageButton", "FloatingActionButton", "ExtendedFloatingActionButton", "MaterialButton",
        "EditText", "TextInputEditText", "Switch", "SwitchCompat", "SwitchMaterial", "CheckBox", "RadioButton", "SeekBar", "Slider", "Chip")
    private val ELEMENT = Regex("""<([A-Za-z][\w.]*)(\s[^<>]*?)?/?>""")
    private val ID_ATTR = Regex("""android:id\s*=\s*"@\+id/(\w+)"""")
    private val DEP = Regex("""(?:implementation|api|kapt|ksp)\s*\(?\s*["']([\w.\-]+):([\w.\-]+):[^"']+["']""")

    data class El(val tag: String, val attrs: String) {
        val short get() = tag.substringAfterLast('.')
        val id get() = ID_ATTR.find(attrs)?.groupValues?.get(1)
        fun attr(name: String) = Regex("""$name\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1)
        val label get() = attr("android:text") ?: attr("android:contentDescription") ?: attr("android:hint") ?: ""
    }

    fun elements(xml: String): List<El> = ELEMENT.findAll(xml).map { El(it.groupValues[1], it.groupValues[2]) }.filter { !it.tag.startsWith("?") }.toList()

    /**
     * changed = इस काम में बदली फ़ाइलें (null = हटाई), before = main/काम से पहले की हालत, all = पूरा workspace (बदलाव समेत; null = नहीं मिला).
     * allowHide = user ने खुद कुछ छिपाने/हटाने को कहा (तब 2 नहीं).
     */
    fun audit(changed: Map<String, String?>, before: (String) -> String?, all: Map<String, String>?, allowHide: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val code = all?.filterKeys { it.endsWith(".kt") || it.endsWith(".java") }.orEmpty()
        val res = all?.filterKeys { it.endsWith(".xml") || it.endsWith(".kt") || it.endsWith(".java") || it.endsWith(".kts") || it.endsWith(".gradle") }.orEmpty()
        for ((path, now) in changed) {
            if (now == null) continue
            val old = before(path)
            when {
                path.endsWith(".tsv") -> out += dupKeys(path, now, old)
                path.contains("/res/layout") && path.endsWith(".xml") -> {
                    out += layout(path, now, old, code, allowHide)
                    if (old == null && all != null) {
                        val name = path.substringAfterLast('/').removeSuffix(".xml")
                        val binding = name.split('_').joinToString("") { s -> s.replaceFirstChar { it.uppercase() } } + "Binding"
                        val used = res.any { (p, c) -> p != path && (c.contains("R.layout.$name") || c.contains("@layout/$name") || c.contains(binding)) }
                        if (!used) out += "AUDIT $path: नई layout \"$name\" को कोई screen इस्तेमाल नहीं करती (R.layout.$name / @layout/$name / $binding कहीं नहीं) - " +
                            "या तो उसकी Activity/Fragment में setContentView/inflate से जोड़ें, या यह फ़ाइल न बनाएँ"
                    }
                }
                old == null && all != null && Regex("""/res/(raw|drawable)[^/]*/[^/]+$""").containsMatchIn(path) -> {
                    val kind = if (path.contains("/res/raw")) "raw" else "drawable"
                    val name = path.substringAfterLast('/').substringBefore('.')
                    val used = res.any { (p, c) -> p != path && (c.contains("R.$kind.$name") || c.contains("@$kind/$name")) }
                    if (!used) out += "AUDIT $path: नई $kind फ़ाइल \"$name\" कहीं इस्तेमाल नहीं (R.$kind.$name / @$kind/$name नहीं) - हटाएँ या इस्तेमाल करें"
                }
                (path.endsWith(".gradle.kts") || path.endsWith(".gradle")) && all != null -> out += deps(path, now, old, all)
            }
        }
        return out.distinct()
    }

    /** 1) एक ही हिंदी key दो बार - सिर्फ़ नए दोहराव (पुराने पहले से हों तो नहीं) */
    fun dupKeys(path: String, now: String, old: String?): List<String> {
        fun dups(t: String?): Map<String, Int> = t.orEmpty().lineSequence().filter { it.isNotBlank() && !it.startsWith("#") && it.indexOf('\t') > 0 }
            .map { it.substringBefore('\t') }.groupingBy { it }.eachCount().filterValues { it > 1 }
        val before = dups(old)
        val fresh = dups(now).filter { (k, n) -> n > (before[k] ?: 1) }.keys
        if (fresh.isEmpty()) return emptyList()
        return listOf("AUDIT $path: ये शब्द पहले से मौजूद हैं, दोबारा जोड़ने से पुराना English बदल जाता है - नई लाइनें हटाएँ (पुरानी रहने दें): " +
            fresh.take(20).joinToString(", ") { "\"$it\"" })
    }

    /** 2) + 3) layout */
    fun layout(path: String, now: String, old: String?, code: Map<String, String>, allowHide: Boolean): List<String> {
        val out = mutableListOf<String>()
        val file = path.substringAfterLast('/')
        val oldEls = old?.let { elements(it) }.orEmpty()
        val oldById = oldEls.mapNotNull { e -> e.id?.let { it to e } }.toMap()
        val newEls = elements(now)
        fun linesWith(id: String) = code.values.flatMap { c -> c.lineSequence().filter { it.contains("R.id.$id") }.toList() }
        // 2) पहले दिखता था, अब gone - और code उस पर click लगाता है पर खुद दिखाता/छिपाता नहीं
        if (!allowHide) for (e in newEls) {
            val id = e.id ?: continue
            val was = oldById[id] ?: continue
            if (e.attr("android:visibility") != "gone" || was.attr("android:visibility") == "gone") continue
            val ls = linesWith(id)
            val clicked = ls.any { it.contains("setOnClickListener") || it.contains("setOnTouchListener") }
            val toggled = ls.any { it.contains("visib", true) || it.contains("isGone") || it.contains("isVisible") }
            if (clicked && !toggled) out += "AUDIT $path: \"$id\" पहले दिखता था, अब visibility=\"gone\" - code में इसका click काम है पर इसे कोई दिखाता नहीं " +
                "= फ़ीचर गायब। user ने छिपाने को नहीं कहा तो visibility=\"gone\" हटाएँ"
        }
        // 3) नए बटन/इनपुट: id नहीं, या id का code में कोई इस्तेमाल नहीं
        if (code.isNotEmpty()) {
            val oldDead = oldEls.filter { it.short in ACTION_TAGS && it.id == null }.map { it.short + "|" + it.label }.toMutableList()
            for (e in newEls) {
                if (e.short !in ACTION_TAGS) continue
                if (e.attr("android:onClick") != null || e.attr("android:enabled") == "false" || e.attr("android:clickable") == "false") continue
                val id = e.id
                val what = "${e.short}" + if (e.label.isNotBlank()) " \"${e.label.take(40)}\"" else ""
                if (id == null) {
                    if (oldDead.remove(e.short + "|" + e.label)) continue   // पहले से ऐसा था - इस काम का नहीं
                    out += "AUDIT $path: नया $what बिना android:id - code इसे पकड़ ही नहीं सकता, दबाने पर कुछ नहीं होगा। id दें और उसकी Activity में काम जोड़ें (या हटाएँ)"
                } else if (id !in oldById && linesWith(id).isEmpty()) {
                    out += "AUDIT $path: नया $what (@+id/$id) किसी code में इस्तेमाल नहीं (R.id.$id कहीं नहीं) - $file वाली Activity में setOnClickListener/काम जोड़ें"
                }
            }
        }
        return out
    }

    /** 6) नई dependency - उसका package (group के पहले 2 हिस्से) किसी code/xml में नहीं */
    fun deps(path: String, now: String, old: String?, all: Map<String, String>): List<String> {
        val had = old.orEmpty().let { o -> DEP.findAll(o).map { it.groupValues[1] + ":" + it.groupValues[2] }.toSet() }
        val src = all.filterKeys { (it.endsWith(".kt") || it.endsWith(".java") || it.endsWith(".xml")) }.values
        val out = mutableListOf<String>()
        for (m in DEP.findAll(now)) {
            val g = m.groupValues[1]; val a = m.groupValues[2]
            if ("$g:$a" in had) continue
            val pkg = g.split('.').take(2).joinToString(".")
            if (pkg.count { it == '.' } < 1) continue
            if (src.none { it.contains(pkg) }) out += "AUDIT $path: नई dependency $g:$a जोड़ी पर उसका package ($pkg…) किसी फ़ाइल में इस्तेमाल नहीं - APK बेवजह भारी, हटाएँ"
        }
        return out
    }
}
