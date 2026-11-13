package com.dk.assistant.dkai

import java.io.File
import java.util.Locale

/**
 * 13 नवंबर 2026 (मालिक): "DK AI" UI अपग्रेड का pure logic (Android View नहीं -> JVM test से जाँचा जाता है).
 * होम के 4 quick कार्ड, Files के प्रकार/क्रम, यादों के खाने, सोचने की गहराई, Broadcast Studio की स्क्रिप्ट,
 * Copilot के बदलाव, लोकल सर्वर (8080), Clone का URL, npm/pip पैकेज.
 */
object DkaiKit {

    const val BRAND = "DKAI"
    const val COPILOT = "DKAI Copilot"
    const val PROJECTS_DIR = "DKAIProjects"
    const val CONFIG_DIR = ".dkai"
    const val PORT = 8080
    /** READY वाला हरा (#10B981) */
    const val EMERALD = 0xFF10B981.toInt()

    /** आवाज़ कार्ड का छोटा टैग: "Female • Hindi" */
    fun voiceTag(name: String, female: Boolean): String {
        val lang = when (name) { "Emma", "Rohan", "Andrew", "Ava", "Brian" -> "English"; "Swara", "Ananya", "Aarav", "Madhur" -> "Hindi"; else -> "Hindi/English" }
        return (if (female) "Female" else "Male") + " • " + lang
    }

    // ------------------------------------------------------------------ होम: 2x2 quick कार्ड
    data class Quick(val title: String, val sub: String, val icon: String, val mode: DkaiMode?, val prompt: String)

    val QUICK = listOf(
        Quick("Deep Web Research", "Multi-source report with sources", "search", DkaiMode.CHAT,
            "Do a deep web research on: "),
        Quick("Landing Page", "Modern responsive website", "globe", DkaiMode.BUILDER,
            "Build a modern, fully responsive landing page for "),
        Quick("Expense Tracker", "Simple app with charts", "wallet", DkaiMode.BUILDER,
            "Build an expense tracker app with categories, monthly totals and a chart"),
        Quick("Voice Assistant", "Talk live with DK", "mic", null, ""),
    )

    /** prompt ':' / ' ' पर खत्म = उपयोगकर्ता आगे लिखे (भेजो मत); पूरा prompt = सीधा भेज सकते हैं */
    fun needsMore(q: Quick) = q.prompt.isNotEmpty() && (q.prompt.endsWith(": ") || q.prompt.endsWith(" "))

    /** होम का mode dropdown (Broadcast = Broadcast Studio खुलता है) */
    val HOME_MODES = listOf(DkaiMode.CHAT, DkaiMode.BUILDER, DkaiMode.AGENT, DkaiMode.PIPELINE, DkaiMode.BROADCAST)

    // ------------------------------------------------------------------ सत्र: खोज / कॉपी / शेयर
    /** चैट का साफ़ text (कॉपी / शेयर) - role "u" = You */
    fun chatText(msgs: List<Pair<String, String>>, title: String): String {
        if (msgs.isEmpty()) return ""
        return "$BRAND • $title\n\n" + msgs.joinToString("\n\n") { (r, t) -> (if (r == "u") "You: " else "DK: ") + t.trim() }
    }

    /** किन संदेशों में [q] (बड़े-छोटे अक्षर का फ़र्क नहीं) - उनके index */
    fun searchHits(texts: List<String>, q: String): List<Int> {
        val k = q.trim().lowercase(Locale.ROOT)
        if (k.isEmpty()) return emptyList()
        return texts.withIndex().filter { it.value.lowercase(Locale.ROOT).contains(k) }.map { it.index }
    }

    // ------------------------------------------------------------------ Files: प्रकार + क्रम
    enum class Kind { FOLDER, CONFIG, CODE, WEB, STYLE, SCRIPT, DATA, IMAGE, AUDIO, DOC, OTHER }

    fun kindOf(name: String, dir: Boolean): Kind {
        val l = name.lowercase(Locale.ROOT)
        if (dir) return if (l == CONFIG_DIR || l == ".git" || l == ".github") Kind.CONFIG else Kind.FOLDER
        val ext = l.substringAfterLast('.', "")
        return when (ext) {
            "html", "htm", "svg" -> Kind.WEB
            "css", "scss", "sass", "less" -> Kind.STYLE
            "js", "mjs", "cjs", "ts", "tsx", "jsx" -> Kind.SCRIPT
            "kt", "kts", "java", "py", "c", "h", "cpp", "hpp", "go", "rs", "rb", "php", "swift", "dart", "vue", "sh", "bat" -> Kind.CODE
            "json", "xml", "yml", "yaml", "toml", "csv", "tsv", "sql", "lock", "properties", "gradle", "ini", "cfg", "conf", "env" -> Kind.DATA
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico" -> Kind.IMAGE
            "mp3", "wav", "ogg", "m4a", "aac" -> Kind.AUDIO
            "md", "txt", "pdf", "log", "rst", "doc", "docx" -> Kind.DOC
            else -> if (l.startsWith(".")) Kind.CONFIG else Kind.OTHER
        }
    }

    /** Editor में खुलने लायक code/text? */
    fun opensInEditor(name: String) = kindOf(name, false).let { it != Kind.IMAGE && it != Kind.AUDIO } && DkaiCore.isTextName(name)

    enum class Sort(val label: String) { NAME("नाम"), DATE("तारीख"), SIZE("साइज़"), TYPE("प्रकार") }

    data class Entry(val name: String, val dir: Boolean, val modified: Long, val size: Long)

    /** फ़ोल्डर हमेशा ऊपर; फिर चुने क्रम से (तारीख/साइज़ = नया/बड़ा पहले) */
    fun sorted(items: List<Entry>, s: Sort): List<Entry> {
        val byDir = compareBy<Entry> { !it.dir }
        val cmp = when (s) {
            Sort.NAME -> byDir.thenBy { it.name.lowercase(Locale.ROOT) }
            Sort.DATE -> byDir.thenByDescending { it.modified }.thenBy { it.name.lowercase(Locale.ROOT) }
            Sort.SIZE -> byDir.thenByDescending { it.size }.thenBy { it.name.lowercase(Locale.ROOT) }
            Sort.TYPE -> byDir.thenBy { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) }.thenBy { it.name.lowercase(Locale.ROOT) }
        }
        return items.sortedWith(cmp)
    }

    fun nextSort(s: Sort): Sort = Sort.values()[(s.ordinal + 1) % Sort.values().size]

    /** स्क्रीन पर पूरा रास्ता: /storage/emulated/0/DKAIProjects/2048/js */
    fun shownPath(f: File): String = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }

    // ------------------------------------------------------------------ AI Long-Term Memory के खाने
    enum class MemCat(val label: String, val prefix: String) {
        PREFERENCE("PREFERENCE", "Preference: "), PROFILE("PROFILE", "Profile: "), PROJECT("PROJECT", "Project: ");
    }

    /** "Preference: dark theme पसंद" -> PREFERENCE; बिना निशान = null (ALL में दिखती है) */
    fun memCat(fact: String): MemCat? = MemCat.values().firstOrNull { fact.startsWith(it.prefix, ignoreCase = true) }

    fun memBody(fact: String): String = memCat(fact)?.let { fact.substring(it.prefix.length).trim() } ?: fact.trim()

    fun memTag(cat: MemCat?, text: String): String {
        val t = text.trim()
        if (cat == null || t.isEmpty()) return t
        return cat.prefix + memBody(t)
    }

    /** null = ALL */
    fun memFilter(facts: List<String>, cat: MemCat?): List<String> = if (cat == null) facts else facts.filter { memCat(it) == cat }

    // ------------------------------------------------------------------ सोचने की गहराई (High / Medium / Low / Off)
    val DEPTHS = listOf("High", "Medium", "Low", "Off")

    /** ThinkLevel का स्तर -> बटन का नाम (auto = Off: अलग से सोचना नहीं, हर हिस्से का पुराना तय तरीका) */
    fun depthOf(level: String): String = when (level.trim().lowercase(Locale.ROOT)) {
        "high" -> "High"; "medium" -> "Medium"; "low" -> "Low"; else -> "Off"
    }

    fun levelOf(depth: String): String = when (depth.trim().lowercase(Locale.ROOT)) {
        "high" -> "high"; "medium" -> "medium"; "low" -> "low"; else -> "auto"
    }

    // ------------------------------------------------------------------ Broadcast Studio (सिर्फ़ आवाज़ - वीडियो नहीं)
    val FORMATS = listOf("News Bulletin", "Podcast", "Speech", "Story", "Lecture")
    /** 13 नवंबर 2026 (मालिक): Auto, शुद्ध हिंदी (देवनागरी), English, मराठी, ગુજરાતી, বাংলা (+ पुराना Hinglish रहा) */
    const val HINDI = "Hindi (Devanagari)"
    val LANGS = listOf("Auto", HINDI, "English", "Marathi", "Gujarati", "Bengali", "Hinglish")

    /** पुरानी सेव भाषा ("Auto-Detect", "Hindi") -> नई सूची का नाम */
    fun normLang(saved: String?): String = when (saved?.trim()) {
        null, "", "Auto-Detect", "Auto" -> "Auto"
        "Hindi" -> HINDI
        else -> saved.trim().takeIf { it in LANGS } ?: "Auto"
    }

    /** माइक से विषय बोलने की भाषा (Android SpeechRecognizer) */
    fun micLocale(lang: String): String = when (normLang(lang)) {
        "English" -> "en-IN"; "Marathi" -> "mr-IN"; "Gujarati" -> "gu-IN"; "Bengali" -> "bn-IN"
        else -> "hi-IN"
    }

    /** TTS का उच्चारण (Gemini को style में) - शुद्ध हिंदी चुनी = शुद्ध हिंदी लहजा */
    fun ttsAccent(lang: String): String = when (normLang(lang)) {
        HINDI -> "pure, standard Hindi with clear Devanagari pronunciation and a natural Indian Hindi accent (no English accent)"
        "Marathi" -> "natural Marathi with native Marathi pronunciation"
        "Gujarati" -> "natural Gujarati with native Gujarati pronunciation"
        "Bengali" -> "natural Bengali with native Bengali pronunciation"
        "English" -> "clear, natural Indian English"
        "Hinglish" -> "natural Indian Hinglish"
        else -> ""
    }

    /** Gemini TTS का पूरा style: format का अंदाज़ + भाषा का लहजा */
    fun studioStyle(format: String, lang: String): String =
        formatStyle(format) + ttsAccent(lang).takeIf { it.isNotBlank() }?.let { ", speaking $it" }.orEmpty()

    /** All Voices की बातचीत के दो किरदार (भाषा के हिसाब से नाम): होस्ट (महिला) + सवाल पूछने वाला देसी दोस्त (पुरुष) */
    fun duo(lang: String): Pair<String, String> = if (normLang(lang) == "English") "Emma" to "Rohan" else "Swara" to "Aarav"

    /** सच में बातचीत है? (कम-से-कम 2 किरदार, 4+ बारी, 3+ बार बोलने वाला बदला) - नहीं तो एक ही मोनोलॉग */
    fun isDialogue(lines: List<Line>): Boolean {
        if (lines.map { it.speaker }.distinct().size < 2 || lines.size < 4) return false
        return lines.zipWithNext().count { (a, b) -> a.speaker != b.speaker } >= 3
    }

    /** बोले जाने वाले text में अंग्रेज़ी (Latin) अक्षरों का हिस्सा (0..1) - शुद्ध हिंदी की जाँच */
    fun latinShare(lines: List<Line>): Float {
        var lat = 0; var all = 0
        for (l in lines) for (ch in l.text) if (ch.isLetter()) { all++; if (ch in 'a'..'z' || ch in 'A'..'Z') lat++ }
        return if (all == 0) 0f else lat / all.toFloat()
    }

    /** शुद्ध हिंदी चुनी और स्क्रिप्ट में अंग्रेज़ी ज़्यादा (12%+) = दोबारा लिखवाओ */
    fun needsPureHindi(lang: String, lines: List<Line>) = normLang(lang) == HINDI && latinShare(lines) > 0.12f
    const val ALL_VOICES = "All Voices"

    data class Line(val speaker: String, val female: Boolean, val text: String)

    /**
     * AI से लिखवाई स्क्रिप्ट की हर लाइन: "Name (female): बोलने वाली बात".
     * (male)/(female) न लिखा = नाम से अंदाज़ा; बोलने वाला ही न हो = Narrator (female).
     */
    fun parseScript(raw: String): List<Line> {
        val out = ArrayList<Line>()
        val re = Regex("""^\s*[*\-•]*\s*\**([^:()\n*]{1,40}?)\**\s*(?:\(([^)]{1,20})\))?\s*\**\s*[:：]\s*(.+)$""")
        for (l0 in raw.lines()) {
            val l = l0.trim().trim('*').trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("```")) continue
            val m = re.find(l)
            if (m != null) {
                val name = m.groupValues[1].trim().ifBlank { "Narrator" }
                val g = m.groupValues[2].lowercase(Locale.ROOT)
                val text = cleanSpeech(m.groupValues[3])
                if (text.isEmpty()) continue
                val female = when {
                    g.contains("female") || g.contains("woman") || g == "f" || g.contains("महिला") || g.contains("स्त्री") -> true
                    g.contains("male") || g.contains("man") || g == "m" || g.contains("पुरुष") -> false
                    else -> guessFemale(name)
                }
                out.add(Line(name, female, text))
            } else {
                val text = cleanSpeech(l)
                if (text.isEmpty()) continue
                // बिना नाम वाली लाइन = पिछले बोलने वाले की (या Narrator)
                val prev = out.lastOrNull()
                if (prev != null) out[out.size - 1] = prev.copy(text = prev.text + " " + text)
                else out.add(Line("Narrator", true, text))
            }
        }
        return out
    }

    private fun cleanSpeech(s: String) = s.replace(Regex("""\[[^\]]{0,40}\]"""), " ").replace(Regex("[*_#`]"), "").replace(Regex("\\s+"), " ").trim()

    private val FEMALE_HINT = listOf("narrator", "anchor", "host", "she", "girl", "woman", "mother", "maa", "mom", "queen", "rani", "didi", "priya",
        "ananya", "swara", "emma", "sita", "radha", "neha", "pooja", "riya", "aisha", "sara", "sarah", "meera", "kavya", "दीदी", "माँ", "रानी", "लड़की")
    private val MALE_HINT = listOf("guest", "he", "boy", "man", "father", "papa", "dad", "king", "raja", "bhai", "rohan", "aarav", "raj", "amit", "rahul",
        "arjun", "ram", "vikram", "dev", "karan", "राजा", "पिता", "भाई", "लड़का", "expert", "reporter")

    fun guessFemale(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        if (MALE_HINT.any { n == it || n.split(' ', '_', '-').contains(it) }) return false
        if (FEMALE_HINT.any { n == it || n.split(' ', '_', '-').contains(it) }) return true
        return !n.endsWith("an") && (n.endsWith("a") || n.endsWith("i") || n.endsWith("ी") || n.endsWith("ा"))
    }

    /**
     * हर बोलने वाले को अपनी आवाज़: लड़की = महिला आवाज़ें बारी-बारी, लड़का = पुरुष आवाज़ें.
     * [single] = एक ही आवाज़ (\"All Voices\" नहीं चुना) -> सब उसी में.
     */
    fun cast(lines: List<Line>, single: DkaiCore.Voice?): Map<String, DkaiCore.Voice> {
        val out = LinkedHashMap<String, DkaiCore.Voice>()
        val fem = DkaiCore.VOICES.filter { it.female }; val mal = DkaiCore.VOICES.filter { !it.female }
        var fi = 0; var mi = 0
        for (l in lines) {
            if (out.containsKey(l.speaker)) continue
            out[l.speaker] = single ?: if (l.female) fem[fi++ % fem.size] else mal[mi++ % mal.size]
        }
        return out
    }

    /**
     * Gemini TTS एक बार में ज़्यादा-से-ज़्यादा 2 बोलने वाले लेता है -> लगातार लाइनों के टुकड़े
     * (हर टुकड़े में <= 2 अलग नाम और <= [maxChars] अक्षर).
     */
    fun segments(lines: List<Line>, maxChars: Int = 2400): List<List<Line>> {
        val out = ArrayList<List<Line>>()
        var cur = ArrayList<Line>(); var names = LinkedHashSet<String>(); var len = 0
        for (l in lines) {
            val newName = l.speaker !in names
            if (cur.isNotEmpty() && ((newName && names.size >= 2) || len + l.text.length > maxChars)) {
                out.add(cur); cur = ArrayList(); names = LinkedHashSet(); len = 0
            }
            cur.add(l); names.add(l.speaker); len += l.text.length
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** उपयोगकर्ता ने खुद स्क्रिप्ट लिखी? (कम-से-कम 2 लाइन "Name: बात") - तब AI से नहीं लिखवाते */
    fun ownScript(text: String): List<Line> {
        val named = text.lines().count { Regex("""^\s*[A-Za-z\u0900-\u097F][\w\u0900-\u097F .]{0,30}(\([^)]{1,20}\))?\s*[:：]\s*\S""").containsMatchIn(it) }
        return if (named >= 2) parseScript(text) else emptyList()
    }

    /** हर format का बोलने का अंदाज़ (TTS style) */
    fun formatStyle(format: String): String = when (format) {
        "News Bulletin" -> "clear, confident, professional news-anchor"
        "Podcast" -> "warm, relaxed, conversational podcast"
        "Speech" -> "confident, inspiring public-speech"
        "Story" -> "expressive, emotional storytelling"
        "Lecture" -> "clear, calm, patient teaching"
        else -> "natural, warm"
    }

    /**
     * स्क्रिप्ट लिखने का निर्देश. [multi] (All Voices) = ठीक दो किरदारों की असली बातचीत (मोनोलॉग नहीं);
     * [facts] = पहले की गई रिसर्च (खाली = AI की अपनी पक्की जानकारी); [strict] = दोबारा लिखवाना (पिछली बार नियम टूटा).
     */
    fun scriptSystem(format: String, lang: String, multi: Boolean, facts: String = "", strict: String = ""): String = buildString {
        val l = normLang(lang)
        append("You are DK AI Broadcast Studio, a script writer for an AUDIO-ONLY ").append(format.lowercase(Locale.ROOT)).append(". ")
        append("Write ONLY the spoken script, no titles, no music cues, no stage directions, no markdown. ")
        append("Every line must be exactly: Name (female): text   or   Name (male): text. ")
        append("Speaker names before the colon must be in English letters. ")
        if (multi) {
            val (h, g) = duo(l)
            append("Write a natural, engaging CONVERSATION (never a monologue) between exactly TWO characters: ")
            append("$h (female) - the warm, knowledgeable host who explains clearly, and ")
            append("$g (male) - a friendly, curious desi character who asks simple questions, reacts naturally and sometimes adds light humour. ")
            append("They must take turns (host, guest, host, guest ...), each turn 1 to 3 short sentences, at least 12 turns in total. ")
            append("Let the guest's questions drive the topic step by step (for a recipe: ingredients, quantities, each step, tips; for information: what, why, how, examples). ")
        } else append("Use exactly ONE speaker for every line. Make it engaging, like a real presenter talking to listeners. ")
        append(when (l) {
            "English" -> "Write in natural English. "
            "Hinglish" -> "Write naturally mixing Hindi and English (Hinglish) as Indians speak, Hindi in Devanagari. "
            HINDI -> "Write the spoken text ONLY in pure, standard Hindi in Devanagari script. Do not use English words or Latin letters; use common Hindi words, " +
                "and if a name or term has no Hindi word, write it in Devanagari letters. Write numbers and quantities in Hindi words. "
            "Marathi" -> "Write the spoken text entirely in natural Marathi (Devanagari script). "
            "Gujarati" -> "Write the spoken text entirely in natural Gujarati (Gujarati script). "
            "Bengali" -> "Write the spoken text entirely in natural Bengali (Bengali script). "
            else -> "Write in the same language the user used for the topic (Hindi in Devanagari, English in English, Hinglish if mixed). "
        })
        if (facts.isNotBlank()) append("Base the content on these researched facts (stay accurate, do not invent numbers or claims):\n").append(facts.trim().take(6000)).append("\n")
        else append("Use only accurate, well-known facts; do not invent numbers or claims. ")
        append(if (multi) "Keep it about 300 to 550 words in total." else "Keep it about 250 to 450 words in total.")
        if (strict.isNotBlank()) append(" IMPORTANT: ").append(strict)
    }

    /** स्क्रिप्ट से पहले की रिसर्च का सवाल */
    fun researchQuery(topic: String, format: String): String =
        "Research this topic thoroughly for an audio $format and list the key accurate facts, steps, numbers and tips as short bullet points: ${topic.trim()}"

    // ------------------------------------------------------------------ Copilot (GitHub टैब): AI के बदलाव
    val COPILOT_CHIPS = listOf("Auto-Fix Code", "Make 100% Responsive", "Cyberpunk Dark Theme")

    fun chipPrompt(chip: String): String = when (chip) {
        "Auto-Fix Code" -> "Scan every file, find bugs, broken references, syntax errors and console errors, and fix them."
        "Make 100% Responsive" -> "Make the whole project 100% responsive for phone, tablet and desktop (viewport meta, flexible layout, media queries)."
        "Cyberpunk Dark Theme" -> "Restyle the project with a cyberpunk dark theme: near-black background, neon cyan and magenta accents, glowing borders, readable contrast."
        else -> chip
    }

    const val COPILOT_SYSTEM = "You are DK AI Copilot, an autonomous code agent working on a local project. " +
        "You receive the project's files and a command. Apply the command by rewriting files. " +
        "Reply with a one-line summary first, then for EVERY file you change or create output a block exactly like:\n" +
        "=== FILE: relative/path.ext ===\n<the complete new file content>\n=== END ===\n" +
        "Only output files that change. Never output partial files, diffs or placeholders like '...rest unchanged'. Keep paths relative to the project root."

    data class Edit(val path: String, val content: String)

    fun parseEdits(raw: String): List<Edit> {
        val out = ArrayList<Edit>()
        val re = Regex("""===\s*FILE:\s*(.+?)\s*===\s*\n(.*?)\n?===\s*END\s*===""", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(raw)) {
            val p = m.groupValues[1].trim().trim('`', '"', '\'').removePrefix("./")
            var body = m.groupValues[2]
            // ```html ... ``` में लपेटा हो तो उतारो
            val fence = Regex("""^\s*```[\w+-]*\s*\n(.*?)\n\s*```\s*$""", RegexOption.DOT_MATCHES_ALL).find(body)
            if (fence != null) body = fence.groupValues[1]
            if (p.isEmpty() || p.startsWith("/") || p.split('/').any { it == ".." }) continue
            out.add(Edit(p, body))
        }
        return out
    }

    fun summaryOf(raw: String): String = raw.substringBefore("=== FILE:").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200) ?: ""

    /** "+12 / -3 लाइन" */
    fun lineDelta(old: String?, new: String): String {
        val a = old?.lines()?.size ?: 0; val b = new.lines().size
        return if (old == null) "नई फ़ाइल • ${b} लाइन" else "${if (b >= a) "+" else ""}${b - a} लाइन (कुल $b)"
    }

    // ------------------------------------------------------------------ लोकल सर्वर
    fun mimeOf(name: String): String = when (name.lowercase(Locale.ROOT).substringAfterLast('.', "")) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs", "cjs" -> "application/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "ico" -> "image/x-icon"
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; "mp4" -> "video/mp4"
        "woff" -> "font/woff"; "woff2" -> "font/woff2"; "ttf" -> "font/ttf"
        "txt", "md" -> "text/plain; charset=utf-8"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    /** "/js/app.js?v=2" -> root/js/app.js; "/" या फ़ोल्डर -> index.html; जड़ से बाहर = null */
    fun resolve(root: File, reqPath: String): File? {
        var p = reqPath.substringBefore('?').substringBefore('#')
        p = try { java.net.URLDecoder.decode(p, "UTF-8") } catch (_: Exception) { p }
        if (p.split('/').any { it == ".." }) return null
        var f = File(root, p.trimStart('/'))
        if (f.isDirectory) f = File(f, "index.html")
        return if (DkaiCore.inside(root, f)) f else null
    }

    fun localUrl(port: Int = PORT, path: String = "/") = "http://localhost:$port$path"

    // ------------------------------------------------------------------ Clone
    /** "https://github.com/a/b(.git)" / "github.com/a/b/tree/x" / "a/b" -> (a, b) */
    fun parseRepo(url: String): Pair<String, String>? {
        val t = url.trim().removeSuffix("/").removeSuffix(".git")
        val m = Regex("""(?:https?://)?(?:www\.)?github\.com/([\w.-]+)/([\w.-]+)""").find(t)
            ?: Regex("""^([\w.-]+)/([\w.-]+)$""").find(t) ?: return null
        val o = m.groupValues[1]; val r = m.groupValues[2].removeSuffix(".git")
        if (o.isBlank() || r.isBlank()) return null
        return o to r
    }

    fun folderFor(url: String, typed: String): String {
        val t = typed.trim()
        if (t.isNotEmpty() && DkaiCore.validName(t)) return t
        return parseRepo(url)?.second ?: "project"
    }

    // ------------------------------------------------------------------ Package Manager Store
    fun cdnTag(pkg: String): String = "<script src=\"https://cdn.jsdelivr.net/npm/${pkg.trim()}\"></script>"

    /** </body> से पहले (या आख़िर में) script जोड़ो - पहले से हो तो वैसा ही */
    fun addScript(html: String, pkg: String): String {
        val tag = cdnTag(pkg)
        if (html.contains("cdn.jsdelivr.net/npm/${pkg.trim()}\"")) return html
        val i = html.lastIndexOf("</body>", ignoreCase = true)
        return if (i >= 0) html.substring(0, i) + "  " + tag + "\n" + html.substring(i) else html.trimEnd() + "\n" + tag + "\n"
    }

    /** requirements.txt में एक पैकेज (दोहराव नहीं) */
    fun addRequirement(txt: String, pkg: String, version: String?): String {
        val name = pkg.trim()
        val lines = txt.lines().filter { it.isNotBlank() }
        if (lines.any { it.trim().split("==", ">=", "<=", "~=", " ").first().equals(name, true) }) return txt
        return (lines + (if (version.isNullOrBlank()) name else "$name==$version")).joinToString("\n") + "\n"
    }

    fun validPkg(n: String) = Regex("""^(@[\w.-]+/)?[\w.-]{1,80}$""").matches(n.trim())

    // ------------------------------------------------------------------ 13 नवंबर 2026 (दूसरी spec): मेन्यू / शीट की सूचियाँ
    data class Row(val title: String, val sub: String)

    /** होम का Mode popup (5 मोड + छोटा विवरण) */
    fun modeRow(m: DkaiMode): Row = when (m) {
        DkaiMode.CHAT -> Row("Chat", "Consult & Plan")
        DkaiMode.BUILDER -> Row("Builder", "Full-Stack Apps")
        DkaiMode.AGENT -> Row("Agent", "Workspace & Code (non-root automation)")
        DkaiMode.PIPELINE -> Row("Pipeline", "Autonomous Build")
        DkaiMode.BROADCAST -> Row("Broadcast", "Voice & Audio Studio")
    }

    /** AI मॉडल शीट: नाम, विवरण, Gemini का असली मॉडल (null = सेटिंग्स में अपनी key/endpoint) */
    data class ModelCard(val title: String, val sub: String, val model: String?)

    val MODEL_CARDS = listOf(
        ModelCard("Gemini Flash", "Fast & versatile - Default", "gemini-3.8-flash"),
        ModelCard("Gemini Pro", "Deep reasoning & coding", "gemini-3.1-pro-preview"),
        ModelCard("Flash 2.5", "Balanced speed", "gemini-2.5-flash"),
        ModelCard("Flash Lite", "Ultra-fast responses", "gemini-3.5-flash-lite"),
        ModelCard("Pro (API)", "Custom key endpoint", null),
    )

    /** अभी के Gemini मॉडल से कौन-सा कार्ड चुना दिखे (कोई न मिले = -1) */
    fun modelCardOf(provider: String, model: String): Int {
        if (provider != "gemini") return MODEL_CARDS.size - 1
        val m = model.lowercase(Locale.ROOT).removePrefix("models/")
        MODEL_CARDS.forEachIndexed { i, c -> if (c.model != null && c.model == m) return i }
        return when {
            m.contains("flash-lite") -> 3
            m.contains("2.5-flash") -> 2
            m.contains("pro") -> 1
            m.contains("flash") -> 0
            else -> -1
        }
    }

    // ------------------------------------------------------------------ 13 नवंबर 2026 (मालिक): होम पर सिर्फ़ फ्री मॉडल
    /** होम के मॉडल शीट की एक पंक्ति ([paid] = सिर्फ़ तब जब AI दिमाग में paid key बताई हो) */
    data class HomeModel(val provider: String, val model: String, val title: String, val sub: String, val paid: Boolean = false)

    const val PREF_GEMINI_PAID = "gemini_paid_key"
    private val NOT_CHAT = Regex("tts|image|imagen|embed|whisper|guard|live|native-audio|veo|lyria|robotics|computer-use|aqa", RegexOption.IGNORE_CASE)

    /** यह मॉडल उस कंपनी के free tier में है? (Gemini Pro/Ultra/Argon नहीं; OpenRouter सिर्फ़ :free; Z.ai सिर्फ़ flash) */
    fun isFreeModel(provider: String, model: String): Boolean {
        val m = model.lowercase(Locale.ROOT).removePrefix("models/").trim()
        if (m.isEmpty() || NOT_CHAT.containsMatchIn(m)) return false
        return when (provider) {
            "gemini" -> m.contains("flash") && !Regex("pro|ultra|argon").containsMatchIn(m)
            "openrouter" -> m == "openrouter/free" || m.endsWith(":free")
            "zai" -> m.contains("flash")
            "groq", "nvidia" -> true
            else -> false
        }
    }

    /**
     * होम पर दिखने वाले मॉडल: सिर्फ़ उन कंपनियों के जिनकी key AI दिमाग में डली है, और सिर्फ़ फ्री मॉडल.
     * Paid (Gemini Pro) सिर्फ़ तब जब AI दिमाग में "मेरी Gemini key paid है" चालू हो.
     */
    fun homeModels(hasKey: (String) -> Boolean, geminiPaid: Boolean, cached: (String) -> List<String>,
                   fallback: (String) -> List<String>, name: (String) -> String, maxEach: Int = 5): List<HomeModel> {
        val out = ArrayList<HomeModel>()
        if (hasKey("gemini")) {
            for (c in MODEL_CARDS) {
                val m = c.model ?: continue
                val free = isFreeModel("gemini", m)
                if (free) out.add(HomeModel("gemini", m, c.title, c.sub + " • Free"))
                else if (geminiPaid) out.add(HomeModel("gemini", m, c.title, c.sub + " • Paid key", paid = true))
            }
        }
        for (id in listOf("groq", "openrouter", "zai", "nvidia")) {
            if (!hasKey(id)) continue
            (fallback(id) + cached(id)).map { it.trim() }.filter { isFreeModel(id, it) }.distinct().take(maxEach)
                .forEach { out.add(HomeModel(id, it, name(it), providerName(id) + " • Free")) }
        }
        return out
    }

    fun providerName(id: String) = when (id) { "gemini" -> "Google Gemini"; "groq" -> "Groq"; "openrouter" -> "OpenRouter"; "zai" -> "Z.ai (GLM)"; "nvidia" -> "NVIDIA NIM"; else -> id }

    /** long-press वाली सूची भी: फ्री वाले ही (Gemini paid key बताई हो तो Gemini के सब) */
    fun pickable(provider: String, models: List<String>, geminiPaid: Boolean): List<String> =
        models.filter { (provider == "gemini" && geminiPaid && !NOT_CHAT.containsMatchIn(it)) || isFreeModel(provider, it) }

    /** + (Add to Prompt) शीट */
    val ATTACH = listOf(
        Row("IDE Image Scan", "Visual UI & screenshot analysis"),
        Row("Attach Audio", "Voice notes & sound files"),
        Row("Import Zip Project", "Load full codebase archive"),
        Row("Attach Document", "PDF, DOCX, TXT, code files"),
        Row("Deliverables Studio", "Build formatted reports & docs"),
    )

    /** Broadcast Studio की आवाज़ें: नाम -> (Gemini आवाज़, लिंग) */
    data class StudioVoice(val name: String, val api: String, val female: Boolean)

    val STUDIO_VOICES = listOf(
        StudioVoice("Madhur", "Orus", false), StudioVoice("Swara", "Kore", true), StudioVoice("Andrew", "Puck", false),
        StudioVoice("Emma", "Sulafat", true), StudioVoice("Ava", "Aoede", true), StudioVoice("Brian", "Fenrir", false),
        // 13 नवंबर 2026 (मालिक): Anaya, Aarav, Rohan भी
        StudioVoice("Anaya", "Leda", true), StudioVoice("Aarav", "Charon", false), StudioVoice("Rohan", "Alnilam", false),
    )

    /** स्टूडियो के लिए cast: "All Voices" = लिंग से बारी-बारी; एक नाम = सब उसी आवाज़ में */
    fun studioCast(lines: List<Line>, pick: String): Map<String, StudioVoice> {
        val single = STUDIO_VOICES.firstOrNull { it.name == pick }
        val out = LinkedHashMap<String, StudioVoice>()
        val fem = STUDIO_VOICES.filter { it.female }; val mal = STUDIO_VOICES.filter { !it.female }
        var fi = 0; var mi = 0
        for (l in lines) {
            if (out.containsKey(l.speaker)) continue
            // किरदार का नाम ही किसी आवाज़ का नाम (Swara / Aarav ...) = वही आवाज़
            val named = STUDIO_VOICES.firstOrNull { it.name.equals(l.speaker.trim(), true) && it.female == l.female }
            out[l.speaker] = single ?: named ?: if (l.female) fem[fi++ % fem.size] else mal[mi++ % mal.size]
        }
        return out
    }

    /** आवाज़ चुनने की सूची का क्रम (मालिक के बताए पहले) */
    val VOICE_MENU = listOf("Emma", "Swara", "Anaya", "Aarav", "Rohan", "Madhur", "Andrew", "Ava", "Brian")

    /** रफ़्तार 0.5x - 2.0x (0.1 के कदम), default 1.3x */
    val STUDIO_SPEEDS = (50..200 step 10).toList()
    const val DEFAULT_SPEED = 130
    fun clampSpeed(pct: Int) = ((pct + 5) / 10 * 10).coerceIn(50, 200)

    /**
     * आवाज़ की रफ़्तार बदलना, सुर (pitch) वही - WSOLA (16-bit mono PCM). शेयर/सेव की फ़ाइल भी चुनी रफ़्तार पर बने.
     * [speed] 1.3 = 1.3 गुना तेज़ (छोटी फ़ाइल).
     */
    fun stretch(pcm: ShortArray, rate: Int, speed: Float): ShortArray {
        if (pcm.isEmpty() || kotlin.math.abs(speed - 1f) < 0.01f) return pcm.copyOf()
        val n = (rate * 0.03f).toInt().coerceAtLeast(64) and 1.inv()   // 30ms फ्रेम
        val hs = n / 2; val tol = (rate * 0.006f).toInt().coerceAtLeast(8)
        val ha = hs * speed
        val outLen = (pcm.size / speed).toInt() + n
        val out = FloatArray(outLen); val win = FloatArray(n) { (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / n)).toFloat() }
        var prev = 0; var k = 0
        while (true) {
            val target = (k * ha).toInt()
            if (target + n + tol >= pcm.size) break
            var best = target
            if (k > 0) {
                val nat = prev + hs; var bestC = Double.NEGATIVE_INFINITY
                var cand = (target - tol).coerceAtLeast(0)
                val end = (target + tol).coerceAtMost(pcm.size - n - 1)
                while (cand <= end) {
                    var cc = 0.0; var j = 0
                    while (j < hs && nat + j < pcm.size) { cc += pcm[cand + j] * pcm[nat + j].toDouble(); j += 2 }
                    if (cc > bestC) { bestC = cc; best = cand }
                    cand += 1
                }
            }
            val o = k * hs
            if (o + n > outLen) break
            for (j in 0 until n) out[o + j] += pcm[best + j] * win[j]
            prev = best; k++
        }
        val len = (k * hs + hs).coerceAtMost(outLen)
        return ShortArray(len) { out[it].toInt().coerceIn(-32768, 32767).toShort() }
    }

    fun toShorts(b: ByteArray): ShortArray = ShortArray(b.size / 2) { ((b[it * 2].toInt() and 0xff) or (b[it * 2 + 1].toInt() shl 8)).toShort() }
    fun toBytes(s: ShortArray): ByteArray = ByteArray(s.size * 2).also { for (i in s.indices) { it[i * 2] = (s[i].toInt() and 0xff).toByte(); it[i * 2 + 1] = (s[i].toInt() shr 8).toByte() } }

    /** Gemini TTS के लिए बोलने वाले का साफ़ नाम (अंग्रेज़ी अक्षर) - वरना Speaker1/2 */
    fun ttsName(name: String, idx: Int): String {
        val t = name.trim()
        return if (Regex("^[A-Za-z][A-Za-z0-9 ]{0,24}$").matches(t)) t else "Speaker${idx + 1}"
    }

    /** "00:00 / 02:04" */
    fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return String.format(Locale.US, "%02d:%02d", s / 60, s % 60) }

    // ------------------------------------------------------------------ WAV (टुकड़े जोड़ना + waveform)
    /** WAV -> (PCM bytes, sample rate). WAV नहीं = (bytes, 24000) */
    fun wavPcm(b: ByteArray): Pair<ByteArray, Int> {
        if (b.size < 44 || b[0] != 'R'.code.toByte() || b[1] != 'I'.code.toByte()) return b to 24000
        fun i32(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        var rate = 24000
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII); val len = i32(p + 4)
            if (id == "fmt " && p + 16 <= b.size) rate = i32(p + 12)
            if (id == "data") { val end = (p + 8 + len).coerceAtMost(b.size); return b.copyOfRange(p + 8, end) to rate }
            p += 8 + len + (len and 1)
            if (len < 0) break
        }
        return b.copyOfRange(44, b.size) to rate
    }

    /** 16-bit PCM -> [n] ऊँचाइयाँ (0..1) waveform के लिए */
    fun peaks(pcm: ByteArray, n: Int): FloatArray {
        val out = FloatArray(n)
        val samples = pcm.size / 2
        if (samples == 0 || n <= 0) return out
        val per = (samples / n).coerceAtLeast(1)
        var mx = 1f
        for (i in 0 until n) {
            var peak = 0
            val s0 = i * per; val s1 = minOf(samples, s0 + per)
            var j = s0
            while (j < s1) { val v = ((pcm[2 * j + 1].toInt() shl 8) or (pcm[2 * j].toInt() and 0xff)).let { kotlin.math.abs(it) }; if (v > peak) peak = v; j += 4 }
            out[i] = peak.toFloat(); if (peak > mx) mx = peak.toFloat()
        }
        for (i in 0 until n) out[i] = (out[i] / mx).coerceIn(0.06f, 1f)
        return out
    }

    // ------------------------------------------------------------------ Copilot Enhancement Modal (2x3)
    val ENHANCE = listOf(
        "Auto-Fix runtime errors", "Enhance visual layout & CSS styling", "Add sleek Cyberpunk dark theme",
        "Make responsive across mobile & desktop", "Auto-link missing CDN libraries & icons", "Add smooth interactive animations & effects",
    )

    fun enhancePrompt(picks: List<Int>, custom: String, cdn: Boolean): String {
        val parts = picks.sorted().mapNotNull { ENHANCE.getOrNull(it) }.toMutableList()
        if (custom.isNotBlank()) parts.add(custom.trim())
        if (parts.isEmpty()) return ""
        return parts.joinToString(". ") + "." + if (cdn) " You may link external CDN libraries, fonts and icon packs (jsdelivr/cdnjs/Google Fonts) with full https URLs where useful." else " Do not add external CDN links."
    }

    // ------------------------------------------------------------------ Clone presets
    data class Preset(val label: String, val repo: String, val folder: String)

    val CLONE_PRESETS = listOf(
        Preset("Dashboard", "puikinsh/Adminator-admin-dashboard", "dashboard"),
        Preset("REST API", "typicode/json-server", "rest-api"),
        Preset("Game", "gabrielecirulli/2048", "2048"),
    )

    /** zip के अंदर का रास्ता -> प्रोजेक्ट में (GitHub की ऊपर वाली "repo-main/" परत हटाकर); गलत रास्ता = null */
    fun zipTarget(entry: String, stripTop: Boolean): String? {
        var p = entry.replace('\\', '/').trimStart('/')
        if (stripTop) p = p.substringAfter('/', "")
        if (p.isEmpty() || p.split('/').any { it == ".." }) return null
        return p
    }

    // ------------------------------------------------------------------ Editor: लाइन नंबर + रंग
    fun gutter(text: String): String { val n = text.count { it == '\n' } + 1; return (1..n).joinToString("\n") }

    enum class Tok { KEYWORD, STRING, COMMENT, TAG, NUMBER }
    data class Span(val start: Int, val end: Int, val tok: Tok)

    private val KW = setOf("function", "return", "const", "let", "var", "if", "else", "for", "while", "class", "import", "export", "from",
        "new", "this", "true", "false", "null", "undefined", "async", "await", "fun", "val", "def", "public", "private", "static", "void",
        "int", "package", "object", "when", "try", "catch", "throw", "switch", "case", "break", "continue", "default", "extends", "in", "of")

    /** हल्का syntax रंग (html/css/js/kt/py) - सिर्फ़ पहले [limit] अक्षर */
    fun highlight(text: String, name: String, limit: Int = 60_000): List<Span> {
        val t = if (text.length > limit) text.substring(0, limit) else text
        val ext = name.lowercase(Locale.ROOT).substringAfterLast('.', "")
        val out = ArrayList<Span>()
        val html = ext == "html" || ext == "htm" || ext == "xml" || ext == "svg"
        val rules = ArrayList<Pair<Regex, Tok>>()
        if (html) { rules.add(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL) to Tok.COMMENT); rules.add(Regex("</?[A-Za-z][\\w-]*|/?>") to Tok.TAG) }
        rules.add(Regex("\"(?:[^\"\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\n]|\\\\.)*'|`[^`]*`") to Tok.STRING)
        if (ext == "py" || ext == "sh" || ext == "yml" || ext == "yaml" || ext == "toml") rules.add(Regex("#[^\n]*") to Tok.COMMENT)
        else rules.add(Regex(if (html) "/\\*.*?\\*/" else "//[^\n]*|/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL) to Tok.COMMENT)
        rules.add(Regex("\\b\\d+(?:\\.\\d+)?\\b") to Tok.NUMBER)
        rules.add(Regex("\\b[A-Za-z_]+\\b") to Tok.KEYWORD)
        // एक ही बाएँ-से-दाएँ पास: जो पहले शुरू हो वही जीते (string के अंदर का // comment नहीं, उसके बाद का असली comment हाँ)
        val all = Regex(rules.joinToString("|") { "(" + it.first.pattern + ")" }, RegexOption.DOT_MATCHES_ALL)
        for (m in all.findAll(t)) {
            val gi = (1..rules.size).firstOrNull { m.groups[it] != null } ?: continue
            val tok = rules[gi - 1].second
            if (tok == Tok.KEYWORD && m.value !in KW) continue
            out.add(Span(m.range.first, m.range.last + 1, tok))
        }
        return out.sortedBy { it.start }
    }

    /** Editor के ऊपर की चिन्ह-पट्टी: TAB = 2 space */
    val SYMBOLS = listOf("TAB", "(", ")", "{", "}", "[", "]", "<", ">", "/", "=", ";")
    fun symbolText(k: String) = if (k == "TAB") "  " else k

    /** खुली फ़ाइलों की टैब: नई आगे, ज़्यादा-से-ज़्यादा [max] */
    fun openTabs(tabs: List<String>, add: String, max: Int = 6): List<String> {
        val l = tabs.filter { it != add }.toMutableList()
        l.add(add)
        while (l.size > max) l.removeAt(0)
        return l
    }

    /** सर्वर कितनी देर से: "4m 12s" */
    fun uptime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s" }
}
