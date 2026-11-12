package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "मेरा coding agent इतना slow क्यों? फटाक से और अच्छे से करे").
 *
 * जड़ (feature/dk-1112-1236, wallpaper वाला काम): CI ने साफ़ बताया "Wallpapers.kt:25 Unresolved reference: dk_wp_logo",
 * पर डिबगर ने तीन बार सिर्फ़ comment और JPEG quality (88 -> 100) बदली - गलती वाली लाइनें छुई ही नहीं. हर बार
 * push + Codespace/CI का पूरा राउंड (6-10 मिनट) और नतीजा वही fail; 3 बार के बाद काम रुक गया.
 *
 * इलाज: push से पहले (बिना AI, सेकंडों में) देखो कि सुधार सच में गलती पर निशाना लगाता है या नहीं.
 * निशाना चूका = तब, जब सारी बातें सही हों:
 *  - पिछली fail compile गलती थी (फ़ाइल:लाइन पता है; test fail पर यह जाँच नहीं - वहाँ जड़ कहीं भी हो सकती है)
 *  - सुधार ने सिर्फ़ उन्हीं फ़ाइलों को बदला जिनमें गलती थी (कोई नई फ़ाइल / दूसरी फ़ाइल नहीं - वहाँ नई चीज़ बनी हो सकती है)
 *  - हर गलती वाली लाइन (±[NEAR] लाइन) comment हटाकर भी हूबहू वैसी की वैसी है
 *  - जोड़ी गई किसी code लाइन में गलती का कोई नाम (जैसे dk_wp_logo) नहीं - यानी वह नाम कहीं बनाया भी नहीं गया
 * चूका = push नहीं; डिबगर को उसी असली गलती के साथ साफ़ बताओ कि पिछले सुधार ने गलत जगह बदला. CI का राउंड बचा.
 * Pure Kotlin -> JUnit: FixAimTest.
 */
object FixAim {
    /** गलती वाली लाइन के ऊपर-नीचे इतनी लाइनें भी "गलती की जगह" */
    const val NEAR = 2

    /** हर लाइन से comment हटाओ (लाइनें उतनी ही रहें, ताकि लाइन नंबर वही रहें) */
    fun noComments(src: String): List<String> {
        val out = StringBuilder(src.length)
        var i = 0; val n = src.length
        var inStr = false
        while (i < n) {
            val ch = src[i]; val nx = if (i + 1 < n) src[i + 1] else '\u0000'
            when {
                inStr -> { out.append(ch); if (ch == '\\' && i + 1 < n) { out.append(nx); i++ } else if (ch == '"' || ch == '\n') inStr = false }
                ch == '"' -> { inStr = true; out.append(ch) }
                ch == '/' && nx == '/' -> { while (i < n && src[i] != '\n') i++; continue }
                ch == '/' && nx == '*' -> {
                    val e = src.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    for (k in i until e) if (src[k] == '\n') out.append('\n')
                    i = e; continue
                }
                else -> out.append(ch)
            }
            i++
        }
        return out.toString().lines().map { it.trim() }
    }

    private val IDENT = Regex("""[A-Za-z_][A-Za-z0-9_]{3,}""")
    private val COMMON = setOf("this", "that", "with", "from", "return", "true", "false", "null", "else", "when", "override",
        "private", "public", "internal", "fun", "val", "var", "class", "object", "import", "package", "String", "Unit", "type",
        "expected", "found", "reference", "Unresolved", "inferred", "mismatch", "None", "value")

    /**
     * @param log पिछली fail का log (CI या Codespace sandbox)
     * @param failed fail हुए commit के समय की फ़ाइलें (path -> content; null = हटाई हुई)
     * @param now अभी की (सुधार के बाद) फ़ाइलें
     * @return null = सुधार निशाने पर (या पक्का नहीं कह सकते - तब push होने दो); वरना डिबगर के लिए साफ़ वजह
     */
    fun missed(log: String, failed: Map<String, String?>, now: Map<String, String?>): String? {
        val sites = BuildLog.sites(log)
        if (sites.isEmpty() || sites.any { it.kind != "compile" }) return null
        val changed = (failed.keys + now.keys).filter { failed[it] != now[it] }
        if (changed.isEmpty()) return null                                    // कुछ नहीं बदला - वह जाँच पहले से अलग है (NO_CHANGE)
        val errFiles = changed.associateWith { p -> sites.filter { s -> p == s.path || p.endsWith("/" + s.path) || s.path.endsWith("/$p") } }
        if (errFiles.values.any { it.isEmpty() }) return null                // किसी दूसरी फ़ाइल में भी बदला = शायद वहाँ नई चीज़ बनी
        val names = sites.flatMap { s -> IDENT.findAll(s.msg).map { it.value }.toList() }.filter { it !in COMMON }.toSet()
        val notes = mutableListOf<String>()
        for (p in changed) {
            val before = failed[p] ?: return null; val after = now[p] ?: return null
            val old = noComments(before); val new = noComments(after)
            // गलती वाली लाइन का अपना नाम भी (जैसे dk_wp_logo वाली लाइन के नाम) - सुधार उन्हीं से जुड़ा हो तो निशाने पर
            val near = HashSet<String>()
            for (s in errFiles.getValue(p)) {
                val from = (s.line - 1 - NEAR).coerceAtLeast(0); val to = (s.line - 1 + NEAR).coerceAtMost(old.size - 1)
                if (from > to) return null
                val win = old.subList(from, to + 1)
                if (!containsBlock(new, win)) return null                     // गलती की जगह बदली = निशाने पर
                old.getOrNull(s.line - 1)?.let { l -> IDENT.findAll(l).forEach { if (it.value !in COMMON) near.add(it.value) } }
            }
            val added = new.toMutableList().apply { old.forEach { remove(it) } }.filter { it.isNotEmpty() }
            if (added.any { a -> (names + near).any { n -> a.contains(n) } }) return null   // गलती का नाम कहीं बनाया/बदला
            notes += if (added.isEmpty()) "$p: सिर्फ़ comment / खाली जगह बदली" else "$p: बदली लाइनें गलती से जुड़ी नहीं (" +
                added.take(3).joinToString(" | ") { it.take(80) } + ")"
        }
        val where = sites.take(6).joinToString("\n") { "- ${it.path}:${it.line} ${it.msg.take(160)}" }
        return "FIX MISSED THE ERROR (पक्की जाँच, push नहीं किया - CI का राउंड बचा): पिछले सुधार ने गलती वाली लाइनें छुई ही नहीं.\n" +
            notes.joinToString("\n") + "\nअसली गलती (वही, अब भी):\n$where\n" +
            "इन्हीं लाइनों को ठीक करो (या गायब नाम सच में बनाओ). comment / दूसरी लाइनें बदलना सुधार नहीं है."
    }

    /** win की लाइनें (क्रम से, लगातार) list में कहीं हैं? */
    private fun containsBlock(list: List<String>, win: List<String>): Boolean {
        if (win.isEmpty()) return true
        for (i in 0..(list.size - win.size)) {
            var ok = true
            for (k in win.indices) if (list[i + k] != win[k]) { ok = false; break }
            if (ok) return true
        }
        return false
    }
}
