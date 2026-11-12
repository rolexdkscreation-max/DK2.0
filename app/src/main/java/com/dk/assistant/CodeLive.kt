package com.dk.assistant

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * नवंबर 2026 (मालिक): "AI कोड लिख रहा है तो साइड में पूरा कोड दिखे" - DK कंप्यूटर, कोडिंग एजेंट, ऐप स्टूडियो.
 * हर बार जब कोई एजेंट फ़ाइल लिखता / बदलता / हटाता है, वो यहाँ आती है (पूरी फ़ाइल + पहले वाली).
 * साइड पैनल (CodeLivePanel) इसे सुनता है और फ़ाइल को लिखते हुए (टाइप होते) दिखाता है, रंगों के साथ.
 * यह हिस्सा Android-मुक्त है (unit test) - सिर्फ़ थीम की पसंद Context से.
 */
object CodeLive {
    const val WHO_COMPUTER = "computer"
    const val WHO_CODER = "coder"
    /** ऐप स्टूडियो: "studio:<slug>" (हर project अलग) */
    fun studio(slug: String) = "studio:$slug"
    fun isStudio(who: String) = who.startsWith("studio:")

    const val MAX_ITEMS = 60
    const val MAX_CHARS = 300_000

    data class Write(
        val id: Long, val who: String, val path: String, val text: String, val old: String?,
        val deleted: Boolean = false, val at: Long = System.currentTimeMillis(),
    ) {
        val lines: Int get() = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        /** बदली हुई लाइनें (0 से). नई फ़ाइल = सारी */
        val changed: IntRange get() = changedLines(old, text)
        val isNew: Boolean get() = old == null
        val name: String get() = path.substringAfterLast('/')
    }

    private val items = ArrayList<Write>()
    private var nextId = 1L
    private val listeners = CopyOnWriteArrayList<(Write) -> Unit>()

    fun listen(l: (Write) -> Unit) { listeners.add(l) }
    fun unlisten(l: (Write) -> Unit) { listeners.remove(l) }

    /** एजेंट ने फ़ाइल लिखी / बदली. [old] = पहले का पूरा पाठ (नई फ़ाइल हो तो null) */
    fun put(who: String, path: String, text: String, old: String? = null): Write {
        val w = synchronized(items) {
            val x = Write(nextId++, who, path.trim().trimStart('/'), cut(text), old?.let { cut(it) })
            items.add(x)
            while (items.size > MAX_ITEMS) items.removeAt(0)
            x
        }
        listeners.forEach { try { it(w) } catch (_: Exception) {} }
        return w
    }

    /** एजेंट ने फ़ाइल हटाई */
    fun gone(who: String, path: String, old: String? = null): Write {
        val w = synchronized(items) {
            val x = Write(nextId++, who, path.trim().trimStart('/'), "", old?.let { cut(it) }, deleted = true)
            items.add(x)
            while (items.size > MAX_ITEMS) items.removeAt(0)
            x
        }
        listeners.forEach { try { it(w) } catch (_: Exception) {} }
        return w
    }

    private fun cut(t: String) = if (t.length > MAX_CHARS) t.take(MAX_CHARS) + "\n… (बहुत बड़ी फ़ाइल - आगे का हिस्सा यहाँ नहीं दिखता)" else t

    /** हर फ़ाइल का आख़िरी रूप, सबसे नई पहले */
    fun files(match: (String) -> Boolean): List<Write> = synchronized(items) {
        items.filter { match(it.who) }.asReversed().distinctBy { it.who + "|" + it.path }
    }

    fun latest(match: (String) -> Boolean): Write? = synchronized(items) { items.lastOrNull { match(it.who) } }

    fun count(match: (String) -> Boolean): Int = files(match).size

    fun clear() = synchronized(items) { items.clear() }

    /** पुराने और नए पाठ में बदला हिस्सा (आगे और पीछे की एक जैसी लाइनें छोड़कर). कुछ न बदला = खाली */
    fun changedLines(old: String?, new: String): IntRange {
        val b = new.split('\n')
        if (old == null) return 0 until b.size
        val a = old.split('\n')
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val end = b.size - s
        return if (end <= p) (if (a.size == b.size) IntRange.EMPTY else p until (p + 1).coerceAtMost(b.size)) else p until end
    }

    /** फ़ाइल के नाम से भाषा (रंग के लिए) */
    fun langOf(path: String): String {
        val n = path.substringAfterLast('/').lowercase()
        if (n == "dockerfile" || n == "makefile" || n.startsWith(".git")) return "sh"
        return when (val e = n.substringAfterLast('.', "")) {
            "kt", "kts" -> "kotlin"; "java" -> "java"; "js", "mjs", "cjs", "jsx" -> "js"; "ts", "tsx" -> "ts"
            "html", "htm", "xml", "svg", "vue" -> "markup"; "css", "scss", "less" -> "css"
            "py" -> "python"; "sh", "bash", "yml", "yaml", "toml", "properties", "rb", "r", "pro" -> "sh"
            "json" -> "json"; "md", "txt", "csv" -> "text"; "dart" -> "dart"; "swift" -> "swift"; "gradle" -> "kotlin"
            "c", "h", "cpp", "cc", "hpp", "cs", "go", "rs", "php" -> "c"
            else -> if (e.isEmpty()) "text" else "c"
        }
    }

    // ---------------- रंग (टोकन) ----------------

    enum class Kind { KEYWORD, STRING, COMMENT, NUMBER, TYPE, TAG }
    data class Span(val start: Int, val end: Int, val kind: Kind)
    /** एक लाइन के रंग + लाइन के आख़िर में टिप्पणी (/* या <!--) खुली रही? */
    data class LineSpans(val spans: List<Span>, val inBlock: Boolean)

    val KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "import", "package", "private", "public", "protected", "internal", "override", "open", "abstract", "final", "true", "false",
        "null", "nil", "None", "True", "False", "def", "const", "let", "function", "static", "void", "new", "try", "catch", "finally",
        "throw", "throws", "suspend", "data", "enum", "async", "await", "from", "in", "is", "as", "this", "self", "super", "lateinit",
        "extends", "implements", "export", "default", "switch", "case", "typeof", "instanceof", "yield", "lambda", "with", "pass",
        "elif", "and", "or", "not", "struct", "func", "guard", "inline", "companion", "sealed", "typealias", "by", "init", "constructor",
        "int", "long", "float", "double", "boolean", "char", "byte", "short", "string", "bool", "type", "readonly", "undefined",
    )

    private val MARKUP = setOf("markup")
    private val HASH = setOf("python", "sh")
    private val NO_BLOCK = setOf("python", "sh", "json", "text")

    /** एक लाइन के रंग. [inBlock] = पिछली लाइन से टिप्पणी खुली आई */
    fun lineSpans(line: String, lang: String, inBlock: Boolean = false): LineSpans {
        if (lang == "text") return LineSpans(emptyList(), false)
        return if (lang in MARKUP) markup(line, inBlock) else codeLine(line, lang, inBlock)
    }

    private fun codeLine(line: String, lang: String, startBlock: Boolean): LineSpans {
        val out = mutableListOf<Span>()
        val blocks = lang !in NO_BLOCK
        var i = 0
        if (startBlock) {
            val e = line.indexOf("*/")
            if (e < 0) return LineSpans(listOf(Span(0, line.length, Kind.COMMENT)), true)
            out += Span(0, e + 2, Kind.COMMENT); i = e + 2
        }
        while (i < line.length) {
            val ch = line[i]
            val nx = if (i + 1 < line.length) line[i + 1] else ' '
            when {
                blocks && ch == '/' && nx == '*' -> {
                    val e = line.indexOf("*/", i + 2)
                    if (e < 0) { out += Span(i, line.length, Kind.COMMENT); return LineSpans(out, true) }
                    out += Span(i, e + 2, Kind.COMMENT); i = e + 2
                }
                lang !in HASH && lang != "json" && lang != "css" && ch == '/' && nx == '/' -> { out += Span(i, line.length, Kind.COMMENT); return LineSpans(out, false) }
                lang in HASH && ch == '#' -> { out += Span(i, line.length, Kind.COMMENT); return LineSpans(out, false) }
                ch == '"' || ch == '\'' || ch == '`' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != ch) { if (line[j] == '\\') j++; j++ }
                    val end = (j + 1).coerceAtMost(line.length)
                    out += Span(i, end, Kind.STRING); i = end
                }
                ch == '@' && (nx.isLetter() || nx == '_') -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '.')) j++
                    out += Span(i, j, Kind.TAG); i = j
                }
                ch.isLetter() || ch == '_' || ch == '$' -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '$')) j++
                    val w = line.substring(i, j)
                    if (lang == "css") { if (j < line.length && line.substring(j).trimStart().startsWith(":") && !w.first().isUpperCase()) out += Span(i, j, Kind.KEYWORD) }
                    else if (w in KEYWORDS) out += Span(i, j, Kind.KEYWORD)
                    else if (w.first().isUpperCase() && w.length > 1 && w.any { it.isLowerCase() }) out += Span(i, j, Kind.TYPE)
                    i = j
                }
                ch.isDigit() -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '.')) j++
                    out += Span(i, j, Kind.NUMBER); i = j
                }
                lang == "css" && ch == '#' && (nx.isLetterOrDigit()) -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '-' || line[j] == '_')) j++
                    out += Span(i, j, Kind.NUMBER); i = j
                }
                else -> i++
            }
        }
        return LineSpans(out, false)
    }

    /** HTML / XML / SVG: <टैग, नाम="पाठ", <!-- टिप्पणी --> (टैग के बाहर का पाठ सादा) */
    private fun markup(line: String, startBlock: Boolean): LineSpans {
        val out = mutableListOf<Span>()
        var i = 0
        if (startBlock) {
            val e = line.indexOf("-->")
            if (e < 0) return LineSpans(listOf(Span(0, line.length, Kind.COMMENT)), true)
            out += Span(0, e + 3, Kind.COMMENT); i = e + 3
        }
        var inTag = false
        while (i < line.length) {
            val ch = line[i]
            when {
                line.startsWith("<!--", i) -> {
                    val e = line.indexOf("-->", i + 4)
                    if (e < 0) { out += Span(i, line.length, Kind.COMMENT); return LineSpans(out, true) }
                    out += Span(i, e + 3, Kind.COMMENT); i = e + 3
                }
                ch == '<' -> {
                    var j = i + 1
                    if (j < line.length && (line[j] == '/' || line[j] == '!' || line[j] == '?')) j++
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '-' || line[j] == ':' || line[j] == '_')) j++
                    out += Span(i, j, Kind.TAG); inTag = true; i = j
                }
                inTag && (ch == '"' || ch == '\'') -> {
                    var j = i + 1
                    while (j < line.length && line[j] != ch) j++
                    val end = (j + 1).coerceAtMost(line.length)
                    out += Span(i, end, Kind.STRING); i = end
                }
                inTag && (ch.isLetter() || ch == '_') -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '-' || line[j] == ':' || line[j] == '_')) j++
                    out += Span(i, j, Kind.KEYWORD); i = j
                }
                inTag && ch == '>' -> { out += Span(i, i + 1, Kind.TAG); inTag = false; i++ }
                inTag && ch == '/' && i + 1 < line.length && line[i + 1] == '>' -> { out += Span(i, i + 2, Kind.TAG); inTag = false; i += 2 }
                else -> i++
            }
        }
        return LineSpans(out, false)
    }

    /** टाइप होने की चाल: हर frame कितने अक्षर (बड़ी फ़ाइल भी ~2.5 सेकंड में पूरी) */
    fun revealStep(chars: Int): Int = maxOf(18, chars / 150)

    /** इतने से बड़ा हिस्सा = सीधा पूरा दिखाओ (फ़ोन धीमा न हो) */
    const val ANIMATE_MAX = 40_000

    // ---------------- थीम (कोड का रंग) ----------------

    data class Theme(
        val id: String, val name: String, val bg: Int, val fg: Int, val gutter: Int,
        val keyword: Int, val string: Int, val comment: Int, val number: Int, val type: Int, val tag: Int, val changed: Int,
    ) {
        val light: Boolean get() = ((bg shr 16 and 0xFF) + (bg shr 8 and 0xFF) + (bg and 0xFF)) > 450
        fun color(k: Kind): Int = when (k) {
            Kind.KEYWORD -> keyword; Kind.STRING -> string; Kind.COMMENT -> comment
            Kind.NUMBER -> number; Kind.TYPE -> type; Kind.TAG -> tag
        }
    }

    val THEMES = listOf(
        Theme("neon", "DK नियॉन", 0xFF0B1020.toInt(), 0xFFE2E8F0.toInt(), 0xFF475569.toInt(), 0xFF67E8F9.toInt(), 0xFF86EFAC.toInt(),
            0xFF64748B.toInt(), 0xFFF0ABFC.toInt(), 0xFFFDE68A.toInt(), 0xFFFFB86C.toInt(), 0x3338BDF8),
        Theme("monokai", "Monokai", 0xFF272822.toInt(), 0xFFF8F8F2.toInt(), 0xFF75715E.toInt(), 0xFFF92672.toInt(), 0xFFE6DB74.toInt(),
            0xFF75715E.toInt(), 0xFFAE81FF.toInt(), 0xFF66D9EF.toInt(), 0xFFA6E22E.toInt(), 0x33A6E22E),
        Theme("dracula", "Dracula", 0xFF282A36.toInt(), 0xFFF8F8F2.toInt(), 0xFF6272A4.toInt(), 0xFFFF79C6.toInt(), 0xFFF1FA8C.toInt(),
            0xFF6272A4.toInt(), 0xFFBD93F9.toInt(), 0xFF8BE9FD.toInt(), 0xFF50FA7B.toInt(), 0x3350FA7B),
        Theme("light", "सफ़ेद (GitHub)", 0xFFFFFFFF.toInt(), 0xFF24292F.toInt(), 0xFF8C959F.toInt(), 0xFFCF222E.toInt(), 0xFF0A3069.toInt(),
            0xFF6E7781.toInt(), 0xFF0550AE.toInt(), 0xFF953800.toInt(), 0xFF116329.toInt(), 0x3328A745),
        Theme("solar", "Solarized", 0xFF002B36.toInt(), 0xFF93A1A1.toInt(), 0xFF586E75.toInt(), 0xFF859900.toInt(), 0xFF2AA198.toInt(),
            0xFF586E75.toInt(), 0xFFD33682.toInt(), 0xFFB58900.toInt(), 0xFF268BD2.toInt(), 0x33268BD2),
        Theme("matrix", "हरा टर्मिनल", 0xFF000000.toInt(), 0xFF00FF66.toInt(), 0xFF006622.toInt(), 0xFF7CFFB2.toInt(), 0xFFA8FF60.toInt(),
            0xFF2E8B57.toInt(), 0xFFCCFF00.toInt(), 0xFF9EFFCF.toInt(), 0xFF00E5FF.toInt(), 0x3300FF66),
    )
    const val DEFAULT_THEME = "neon"
    private const val PREFS = "dk_code_live"
    private const val KEY_THEME = "theme"

    fun theme(id: String?): Theme = THEMES.firstOrNull { it.id == id } ?: THEMES.first { it.id == DEFAULT_THEME }
    fun theme(c: Context): Theme = theme(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, DEFAULT_THEME))
    fun setTheme(c: Context, id: String) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, theme(id).id).apply() }

    /** पैनल की ऊपर वाली लाइन: कौन, क्या कर रहा है */
    fun status(w: Write?): String = when {
        w == null -> "अभी कोई कोड नहीं लिखा गया — AI जैसे ही फ़ाइल लिखेगा, यहीं दिखेगी"
        w.deleted -> "🗑 हटाई: ${w.path}"
        w.isNew -> "✍ नई फ़ाइल: ${w.path} • ${w.lines} लाइन"
        else -> "✏ बदली: ${w.path} • ${w.changed.let { if (it.isEmpty()) "कोई लाइन नहीं" else "लाइन ${it.first + 1}-${it.last + 1}" }}"
    }

    fun whoLabel(who: String): String = when {
        who == WHO_COMPUTER -> "DK कंप्यूटर"
        who == WHO_CODER -> "कोडिंग एजेंट"
        isStudio(who) -> "ऐप स्टूडियो"
        else -> who
    }
}
