package com.dk.assistant

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * v5 मालिक: "DK कंप्यूटर भी वैसा ही काम करे जैसा Perplexity Computer" - Word (.docx), Excel (.xlsx) और चार्ट (.svg).
 * पूरी तरह Android-free (JUnit से जाँचा जाता है): Office Open XML सीधा zip में लिखते हैं, कोई library नहीं.
 */
object ComputerOffice {

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

    private fun zip(files: List<Pair<String, String>>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((n, t) in files) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
        return bo.toByteArray()
    }

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    // ================================================================== Word
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** **मोटा** / सादा टुकड़े */
    fun runs(line: String): List<Pair<String, Boolean>> {
        val t = line.replace(Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)"), "$1 ($2)").replace(Regex("`([^`]+)`"), "$1")
        val out = mutableListOf<Pair<String, Boolean>>()
        var bold = false
        for (p in t.split("**")) { if (p.isNotEmpty()) out += p to bold; bold = !bold }
        return out.ifEmpty { listOf("" to false) }
    }

    private fun run(text: String, bold: Boolean, size: Int, color: String? = null) =
        "<w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Nirmala UI\"/>" + (if (bold) "<w:b/><w:bCs/>" else "") +
            (color?.let { "<w:color w:val=\"$it\"/>" } ?: "") + "<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr>" +
            "<w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"

    private fun para(line: String, size: Int = 22, bold: Boolean = false, color: String? = null, indent: Int = 0, after: Int = 120) =
        "<w:p><w:pPr><w:spacing w:after=\"$after\"/>" + (if (indent > 0) "<w:ind w:left=\"$indent\" w:hanging=\"260\"/>" else "") + "</w:pPr>" +
            runs(line).joinToString("") { (t, b) -> run(t, b || bold, size, color) } + "</w:p>"

    fun isTableRow(l: String) = l.trim().let { it.startsWith("|") && it.endsWith("|") && it.length > 1 }
    fun isTableSep(l: String) = l.trim().matches(Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$"))
    fun cells(l: String) = l.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun table(rows: List<List<String>>): String {
        val n = rows.maxOf { it.size }
        val b = "<w:top w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/><w:left w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>" +
            "<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/><w:right w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>" +
            "<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>"
        return "<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>$b</w:tblBorders></w:tblPr>" +
            rows.mapIndexed { r, row ->
                "<w:tr>" + (0 until n).joinToString("") { i ->
                    "<w:tc><w:tcPr>" + (if (r == 0) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"DCE6F2\"/>" else "") + "</w:tcPr>" +
                        para(row.getOrElse(i) { "" }, 20, bold = r == 0, after = 40) + "</w:tc>"
                } + "</w:tr>"
            }.joinToString("") + "</w:tbl>" + para("", after = 60)
    }

    /** Markdown (शीर्षक, बुलेट, नंबर, **मोटा**, टेबल, लिंक) -> Word body */
    fun docxBody(title: String, md: String): String = buildString {
        if (title.isNotBlank()) append(para(title, 36, bold = true, color = "1F3864", after = 240))
        val lines = md.replace("\r", "").split('\n')
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trimEnd()
            if (isTableRow(l)) {
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && isTableRow(lines[i])) { if (!isTableSep(lines[i])) rows += cells(lines[i]); i++ }
                if (rows.isNotEmpty()) append(table(rows))
                continue
            }
            val h = Regex("^(#{1,6})\\s+(.*)$").find(l)
            when {
                l.isBlank() -> {}
                h != null -> { val lv = h.groupValues[1].length; append(para(h.groupValues[2], if (lv == 1) 32 else if (lv == 2) 28 else 24, bold = true, color = "2E5597", after = 120)) }
                Regex("^\\s*[-*•]\\s+").containsMatchIn(l) -> append(para("•  " + l.replace(Regex("^\\s*[-*•]\\s+"), ""), indent = 520))
                Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(l) -> append(para(l.trim(), indent = 520))
                l.trim() == "---" -> append(para("", after = 60))
                else -> append(para(l))
            }
            i++
        }
    }

    fun docx(title: String, md: String): ByteArray = zip(listOf(
        "[Content_Types].xml" to XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
        "_rels/.rels" to XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
        "word/document.xml" to XML + "<w:document xmlns:w=\"$W\"><w:body>" + docxBody(title, md) +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>",
    ))

    // ================================================================== Excel
    data class Sheet(val name: String, val rows: List<List<String>>)

    /** 0 -> A, 25 -> Z, 26 -> AA */
    fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }; return sb.toString() }

    private val NUM = Regex("^-?(\\d{1,15}|\\d{1,3}(,\\d{2,3})+)(\\.\\d+)?$")
    /** "1,25,000" / "12.5" = संख्या (Excel में जोड़-घटा हो सके); "007", "+91…" = text */
    fun number(s: String): String? {
        val t = s.trim()
        if (!NUM.matches(t) || (t.length > 1 && t.startsWith("0") && !t.startsWith("0."))) return null
        return t.replace(",", "")
    }

    fun sheetName(n: String, i: Int): String = n.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31).ifBlank { "Sheet${i + 1}" }

    fun sheetXml(rows: List<List<String>>): String = XML +
        "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        (if (rows.isNotEmpty()) "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>" else "") +
        "<cols>" + (0 until max(1, rows.maxOfOrNull { it.size } ?: 1)).joinToString("") { c ->
            val w = (rows.maxOfOrNull { it.getOrElse(c) { "" }.length } ?: 8).coerceIn(8, 60) + 2
            "<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$w\" customWidth=\"1\"/>"
        } + "</cols><sheetData>" +
        rows.mapIndexed { r, row ->
            "<row r=\"${r + 1}\">" + row.mapIndexed { c, v ->
                val ref = col(c) + (r + 1)
                val st = if (r == 0) " s=\"1\"" else ""
                number(v)?.takeIf { r > 0 }?.let { "<c r=\"$ref\"><v>$it</v></c>" }
                    ?: "<c r=\"$ref\" t=\"inlineStr\"$st><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>"
            }.joinToString("") + "</row>"
        }.joinToString("") + "</sheetData></worksheet>"

    fun xlsx(sheets: List<Sheet>): ByteArray {
        val ss = sheets.ifEmpty { listOf(Sheet("Sheet1", emptyList())) }
        val names = ss.mapIndexed { i, s -> sheetName(s.name, i) }.let { l -> l.mapIndexed { i, n -> if (l.take(i).contains(n)) "${n.take(28)} ${i + 1}" else n } }
        return zip(listOf(
            "[Content_Types].xml" to XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                ss.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
                "</Types>",
            "_rels/.rels" to XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml" to XML + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
                names.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                ss.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
                "<Relationship Id=\"rId${ss.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
            "xl/styles.xml" to XML + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
                "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCE6F2\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs></styleSheet>",
        ) + ss.mapIndexed { i, s -> "xl/worksheets/sheet${i + 1}.xml" to sheetXml(s.rows) })
    }

    /** CSV (उद्धरण "a,b" समेत) -> rows */
    fun csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (line in text.replace("\r", "").split('\n')) {
            if (line.isBlank()) continue
            val cells = mutableListOf<String>(); val sb = StringBuilder(); var q = false; var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    q && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    ch == '"' -> q = !q
                    ch == ',' && !q -> { cells += sb.toString(); sb.clear() }
                    else -> sb.append(ch)
                }
                i++
            }
            cells += sb.toString(); rows += cells
        }
        return rows
    }

    // ================================================================== चार्ट (SVG)
    data class Series(val name: String, val values: List<Double>)

    private fun f0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    private val COLORS = listOf("#2E75B6", "#ED7D31", "#70AD47", "#FFC000", "#7B4FBF", "#E04B6B", "#2BB5B0", "#8C8C8C")

    private fun fmt(v: Double): String = when {
        abs(v) >= 1e7 -> f1(v / 1e7) + " करोड़"
        abs(v) >= 1e5 -> f1(v / 1e5) + " लाख"
        v == Math.floor(v) -> f0(v)
        else -> f2(v)
    }

    /** "अच्छा" ऊपरी सीमा: 1, 2, 5 × 10^n */
    fun niceMax(v: Double): Double {
        if (v <= 0) return 1.0
        val e = Math.pow(10.0, Math.floor(Math.log10(v)))
        return listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * e >= v } * e
    }

    /** SVG -> पूरा पेज (WebView में PNG बनाने के लिए; स्क्रीन की density कुछ भी हो, पूरा भरे) */
    fun chartHtml(svg: String): String = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>html,body{margin:0;padding:0;background:#fff;overflow:hidden}svg{display:block;width:100vw;height:100vh}</style></head><body>" +
        svg + "</body></html>"

    /** type = bar / line / pie */
    fun chartSvg(type: String, title: String, labels: List<String>, series: List<Series>, w: Int = 900, h: Int = 540): String {
        val sb = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\" font-family=\"Noto Sans, Nirmala UI, sans-serif\">")
        sb.append("<rect width=\"$w\" height=\"$h\" fill=\"#ffffff\"/>")
        sb.append("<text x=\"${w / 2}\" y=\"34\" text-anchor=\"middle\" font-size=\"22\" font-weight=\"bold\" fill=\"#1F3864\">${esc(title)}</text>")
        val ss = series.filter { it.values.isNotEmpty() }
        if (ss.isEmpty() || labels.isEmpty()) return sb.append("<text x=\"${w / 2}\" y=\"${h / 2}\" text-anchor=\"middle\" fill=\"#888\">(डेटा नहीं)</text></svg>").toString()
        if (type == "pie") {
            val vals = labels.indices.map { (ss[0].values.getOrNull(it) ?: 0.0).coerceAtLeast(0.0) }
            val tot = vals.sum().takeIf { it > 0 } ?: 1.0
            val cx = w * 0.36; val cy = h * 0.55; val r = minOf(w, h) * 0.34
            var a0 = -PI / 2
            vals.forEachIndexed { i, v ->
                val a1 = a0 + 2 * PI * v / tot
                val col = COLORS[i % COLORS.size]
                if (v / tot >= 0.9999) sb.append("<circle cx=\"$cx\" cy=\"$cy\" r=\"$r\" fill=\"$col\"/>")
                else if (v > 0) {
                    val big = if (a1 - a0 > PI) 1 else 0
                    sb.append("<path d=\"M$cx,$cy L${f1(cx + r * cos(a0))},${f1(cy + r * sin(a0))} A$r,$r 0 $big 1 ${f1(cx + r * cos(a1))},${f1(cy + r * sin(a1))} Z\" fill=\"$col\" stroke=\"#fff\" stroke-width=\"2\"/>")
                }
                val ly = 90 + i * 30
                sb.append("<rect x=\"${(w * 0.70).toInt()}\" y=\"${ly - 14}\" width=\"16\" height=\"16\" fill=\"$col\"/>")
                sb.append("<text x=\"${(w * 0.70).toInt() + 24}\" y=\"$ly\" font-size=\"15\" fill=\"#333\">${esc(labels[i].take(24))} — ${f1(100 * v / tot)}%</text>")
                a0 = a1
            }
            return sb.append("</svg>").toString()
        }
        val L = 80; val R = 30; val T = 60; val B = 90 + if (ss.size > 1) 30 else 0
        val pw = w - L - R; val ph = h - T - B
        val top = niceMax(ss.flatMap { it.values }.maxOrNull() ?: 1.0)
        for (g in 0..5) {
            val y = T + ph - ph * g / 5.0
            sb.append("<line x1=\"$L\" y1=\"${f1(y)}\" x2=\"${w - R}\" y2=\"${f1(y)}\" stroke=\"#e5e5e5\"/>")
            sb.append("<text x=\"${L - 8}\" y=\"${f1(y + 5)}\" text-anchor=\"end\" font-size=\"13\" fill=\"#666\">${esc(fmt(top * g / 5))}</text>")
        }
        sb.append("<line x1=\"$L\" y1=\"${T + ph}\" x2=\"${w - R}\" y2=\"${T + ph}\" stroke=\"#999\"/>")
        val n = labels.size; val slot = pw.toDouble() / n
        labels.forEachIndexed { i, l ->
            val x = L + slot * (i + 0.5)
            sb.append("<text x=\"${f1(x)}\" y=\"${T + ph + 22}\" text-anchor=\"middle\" font-size=\"13\" fill=\"#333\">${esc(l.take(14))}</text>")
        }
        fun y(v: Double) = T + ph - ph * (v.coerceAtLeast(0.0) / top)
        if (type == "line") {
            ss.forEachIndexed { si, s ->
                val col = COLORS[si % COLORS.size]
                val pts = (0 until n).mapNotNull { i -> s.values.getOrNull(i)?.let { "${f1(L + slot * (i + 0.5))},${f1(y(it))}" } }
                sb.append("<polyline points=\"${pts.joinToString(" ")}\" fill=\"none\" stroke=\"$col\" stroke-width=\"3\"/>")
                pts.forEach { p -> val (px, py) = p.split(','); sb.append("<circle cx=\"$px\" cy=\"$py\" r=\"4.5\" fill=\"$col\"/>") }
            }
        } else {
            val bw = slot * 0.75 / ss.size
            ss.forEachIndexed { si, s ->
                val col = COLORS[si % COLORS.size]
                for (i in 0 until n) {
                    val v = s.values.getOrNull(i) ?: continue
                    val x = L + slot * i + slot * 0.125 + bw * si
                    sb.append("<rect x=\"${f1(x)}\" y=\"${f1(y(v))}\" width=\"${f1(bw - 2)}\" height=\"${f1(T + ph - y(v))}\" fill=\"$col\" rx=\"2\"/>")
                    if (ss.size == 1 && n <= 16) sb.append("<text x=\"${f1(x + bw / 2)}\" y=\"${f1(y(v) - 6)}\" text-anchor=\"middle\" font-size=\"12\" fill=\"#333\">${esc(fmt(v))}</text>")
                }
            }
        }
        if (ss.size > 1) ss.forEachIndexed { si, s ->
            val lx = L + si * 170
            sb.append("<rect x=\"$lx\" y=\"${h - 30}\" width=\"14\" height=\"14\" fill=\"${COLORS[si % COLORS.size]}\"/>")
            sb.append("<text x=\"${lx + 20}\" y=\"${h - 18}\" font-size=\"14\" fill=\"#333\">${esc(s.name.take(18))}</text>")
        }
        return sb.append("</svg>").toString()
    }
}
