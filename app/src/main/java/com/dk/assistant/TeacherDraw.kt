package com.dk.assistant

/**
 * 23 नवंबर 2026 (मालिक: "जो टॉपिक ऐसे समझ में नहीं आता, DK बोले - मैं ड्रॉ करके दिखाती हूँ - और बोर्ड पर ड्रॉ करके समझाए"):
 * DK के चित्र की छोटी भाषा. Android के बिना (JVM test) - बोर्ड पर बनाना [WhiteboardView] करता है.
 *
 * DK लिखती है (जवाब में [DRAW] ... [/DRAW], या DK Live में teacher_draw tool का figure):
 * ```
 * size 100 60
 * circle 50 30 12 orange fill
 * arrow 20 50 40 38 blue
 * text 50 55 "सूरज" big
 * ```
 * x बाएँ से दाएँ 0–100, y ऊपर से नीचे 0–(ऊँचाई, बिना size = 60). हर लाइन (या ";") एक चीज़.
 */
object TeacherDraw {
    const val OPEN = "[DRAW]"
    const val CLOSE = "[/DRAW]"
    const val W = 100f
    const val DEFAULT_H = 60f
    const val MAX_SHAPES = 160

    enum class Kind { LINE, ARROW, DASH, CIRCLE, ELLIPSE, RECT, POLY, PATH, CURVE, DOT, TEXT }

    /** [pts] = चित्र के अपने नाप में (x, y जोड़े; CIRCLE = cx cy r; ELLIPSE = cx cy rx ry; RECT = x y w h) */
    data class Shape(val kind: Kind, val pts: List<Float>, val color: Int, val fill: Boolean = false,
                     val text: String = "", val size: Float = 1f)

    data class Figure(val title: String, val h: Float, val shapes: List<Shape>)

    const val INK = 0xFF212121.toInt()

    /** रंग के नाम (हिंदी भी) -> ARGB */
    val COLORS: Map<String, Int> = mapOf(
        "black" to INK, "काला" to INK,
        "red" to 0xFFD32F2F.toInt(), "लाल" to 0xFFD32F2F.toInt(),
        "blue" to 0xFF1565C0.toInt(), "नीला" to 0xFF1565C0.toInt(),
        "green" to 0xFF2E7D32.toInt(), "हरा" to 0xFF2E7D32.toInt(),
        "orange" to 0xFFEF6C00.toInt(), "नारंगी" to 0xFFEF6C00.toInt(),
        "yellow" to 0xFFF9A825.toInt(), "पीला" to 0xFFF9A825.toInt(),
        "purple" to 0xFF6A1B9A.toInt(), "बैंगनी" to 0xFF6A1B9A.toInt(),
        "brown" to 0xFF6D4C41.toInt(), "भूरा" to 0xFF6D4C41.toInt(),
        "gray" to 0xFF757575.toInt(), "grey" to 0xFF757575.toInt(), "स्लेटी" to 0xFF757575.toInt(),
        "pink" to 0xFFD81B60.toInt(), "गुलाबी" to 0xFFD81B60.toInt(),
        "sky" to 0xFF0288D1.toInt(), "cyan" to 0xFF00838F.toInt(),
    )

    private val KINDS: Map<String, Kind> = mapOf(
        "line" to Kind.LINE, "arrow" to Kind.ARROW, "dash" to Kind.DASH, "dashed" to Kind.DASH,
        "circle" to Kind.CIRCLE, "ellipse" to Kind.ELLIPSE, "oval" to Kind.ELLIPSE,
        "rect" to Kind.RECT, "box" to Kind.RECT, "poly" to Kind.POLY, "polygon" to Kind.POLY,
        "path" to Kind.PATH, "curve" to Kind.CURVE, "dot" to Kind.DOT, "point" to Kind.DOT,
        "text" to Kind.TEXT, "label" to Kind.TEXT,
    )

    /** हर तरह के कम से कम अंक */
    private fun need(k: Kind): Int = when (k) {
        Kind.LINE, Kind.ARROW, Kind.DASH, Kind.RECT, Kind.ELLIPSE -> 4
        Kind.CIRCLE -> 3
        Kind.POLY -> 6
        Kind.PATH -> 4
        Kind.CURVE -> 6
        Kind.DOT, Kind.TEXT -> 2
    }

    private val TOKEN = Regex("\"([^\"]*)\"|“([^”]*)”|(\\S+)")

    fun color(tok: String): Int? {
        val t = tok.trim().lowercase()
        COLORS[t]?.let { return it }
        val hex = t.removePrefix("#")
        if (t.startsWith("#") && (hex.length == 6 || hex.length == 8) && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            val v = hex.toLong(16)
            return (if (hex.length == 6) v or 0xFF000000L else v).toInt()
        }
        return null
    }

    /** चित्र की लिखाई -> [Figure] (गलत लाइन छोड़ दो; कुछ भी न बने = null) */
    fun parse(spec: String): Figure? {
        var h = DEFAULT_H
        var title = ""
        val shapes = ArrayList<Shape>()
        for (raw in spec.replace("\r", "").split('\n', ';')) {
            val line = raw.trim().trimStart('-', '•', '*').trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue
            val toks = TOKEN.findAll(line).map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] } to (m.groupValues[3].isEmpty()) }.toList()
            if (toks.isEmpty()) continue
            val head = toks[0].first.lowercase().trimEnd(':')
            if (head == "size") {
                toks.getOrNull(2)?.first?.toFloatOrNull()?.let { h = it.coerceIn(20f, 160f) }
                continue
            }
            if (head == "title") { title = toks.drop(1).joinToString(" ") { it.first }.trim(); continue }
            val kind = KINDS[head] ?: continue
            val nums = ArrayList<Float>()
            var col = INK
            var fill = false
            var text = ""
            var size = 1f
            for ((t, quoted) in toks.drop(1)) {
                if (quoted) { text = t; continue }
                val n = t.trimEnd(',').toFloatOrNull()
                if (n != null && text.isEmpty()) { nums += n; continue }
                val low = t.lowercase()
                when {
                    low == "fill" || low == "filled" -> fill = true
                    low == "big" || low == "bold" -> size = 1.4f
                    low == "small" -> size = 0.8f
                    color(low) != null -> col = color(low)!!
                    kind == Kind.TEXT -> text = if (text.isEmpty()) t else "$text $t"
                }
            }
            if (nums.size < need(kind)) continue
            if (kind == Kind.TEXT && text.isBlank()) continue
            val pts = when (kind) {
                Kind.POLY, Kind.PATH -> nums.take(nums.size / 2 * 2).take(80)
                else -> nums.take(need(kind))
            }
            if (pts.any { it.isNaN() || it < -50f || it > 300f }) continue
            shapes += Shape(kind, pts, col, fill, text.take(60), size)
            if (shapes.size >= MAX_SHAPES) break
        }
        if (shapes.isEmpty()) return null
        return Figure(title, h, shapes)
    }

    /** जवाब के हिस्से क्रम से: (text या चित्र). अधूरा [DRAW] (बंद नहीं हुआ) = आगे का सब छोड़ो, बोला न जाए */
    data class Part(val text: String, val figure: Boolean)

    fun split(answer: String): List<Part> {
        val out = ArrayList<Part>()
        var i = 0
        val up = answer.uppercase()
        while (i < answer.length) {
            val o = up.indexOf(OPEN, i)
            if (o < 0) { out += Part(answer.substring(i), false); break }
            if (o > i) out += Part(answer.substring(i, o), false)
            val c = up.indexOf(CLOSE, o + OPEN.length)
            if (c < 0) break
            out += Part(answer.substring(o + OPEN.length, c).trim(), true)
            i = c + CLOSE.length
        }
        return out.filter { it.text.isNotBlank() }
    }

    /** बोलने / पढ़ने वाला text - चित्र की लिखाई हटाकर */
    fun strip(answer: String): String = split(answer).filter { !it.figure }.joinToString(" ") { it.text.trim() }
        .replace(Regex("[ \\t]{2,}"), " ").trim()

    /** जवाब के सारे चित्र */
    fun figures(answer: String): List<String> = split(answer).filter { it.figure }.map { it.text }

    /** मॉडल के लिए नियम (दोनों जगह एक ही भाषा) */
    const val GRAMMAR = "Grid: x 0-100 left to right, y 0-60 top to bottom (\"size 100 80\" = taller). One item per line: " +
        "line x1 y1 x2 y2 | arrow x1 y1 x2 y2 | dash x1 y1 x2 y2 | circle cx cy r | ellipse cx cy rx ry | rect x y w h | " +
        "poly x1 y1 x2 y2 x3 y3 ... (closed) | path x1 y1 x2 y2 ... (open) | curve x1 y1 cx cy x2 y2 | dot x y | text x y \"label\". " +
        "After the numbers you may add a colour (black red blue green orange yellow purple brown gray pink sky), \"fill\", and for text \"big\"/\"small\". " +
        "Keep it simple and clear like a teacher's board sketch (5-40 items), label every important part in Hindi, use arrows to show flow."

    /** Teacher स्क्रीन के अपने जवाब (लिखा हुआ) */
    const val WRITTEN_RULE = "DRAWING: when a picture explains it better (diagram, shape, graph, process, cycle, body part, machine, map, " +
        "geometry, number line, flow), DRAW it on the board: write the figure between [DRAW] and [/DRAW] at the right place in your answer, " +
        "then explain it part by part (\"ऊपर वाला गोला सूरज है…\"). $GRAMMAR"

    /** DK Live (बोलकर) - चित्र tool से, बोलकर कभी नहीं */
    const val LIVE_RULE = "DRAWING on the Teacher board: when a topic is hard to get by words (diagram, shape, graph, process, cycle, body part, " +
        "machine, map, geometry), say a short line like \"रुकिए, मैं ड्रॉ करके दिखाती हूँ\", call teacher_draw with the figure, then explain it part by part. " +
        "Never speak the drawing code aloud. "
}
