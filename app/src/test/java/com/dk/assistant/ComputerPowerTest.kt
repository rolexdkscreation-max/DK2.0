package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/** v5 मालिक: "DK कंप्यूटर को Perplexity Computer जैसा पावरफुल बनाओ" - नए टूल */
class ComputerPowerTest {

    private fun unzip(b: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return out
    }

    // ------------------------------------------------------------ Word
    @Test fun docxHasPartsAndContent() {
        val md = "# परिचय\nयह **ज़रूरी** बात है & <खास>\n- पहला\n1. एक\n\n| नाम | कीमत |\n|---|---|\n| A | 100 |\n[Google](https://google.com)"
        val z = unzip(ComputerOffice.docx("मेरी रिपोर्ट", md))
        assertTrue(z.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")))
        val d = z["word/document.xml"]!!
        assertTrue(d.contains("मेरी रिपोर्ट")); assertTrue(d.contains("परिचय"))
        assertTrue(d.contains("&amp;")); assertTrue(d.contains("&lt;खास&gt;")); assertFalse(d.contains("<खास>"))
        assertTrue(d.contains("<w:b/>"))                       // **ज़रूरी**
        assertTrue(d.contains("•  पहला"))
        assertTrue(d.contains("<w:tbl>")); assertTrue(d.contains("कीमत")); assertFalse(d.contains("---"))
        assertTrue(d.contains("Google (https://google.com)"))
        // XML ठीक बना (parser से जाँच)
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(d.toByteArray()))
    }

    @Test fun mdHelpers() {
        assertEquals(listOf("a " to false, "b" to true, " c" to false), ComputerOffice.runs("a **b** c"))
        assertTrue(ComputerOffice.isTableSep("|---|:---:|"))
        assertFalse(ComputerOffice.isTableSep("| a | b |"))
        assertEquals(listOf("a", "b"), ComputerOffice.cells("| a | b |"))
    }

    // ------------------------------------------------------------ Excel
    @Test fun xlsxNumbersAndText() {
        val z = unzip(ComputerOffice.xlsx(listOf(ComputerOffice.Sheet("दाम/सूची", listOf(listOf("नाम", "कीमत", "फ़ोन"), listOf("A", "1,25,000", "09876"), listOf("B", "12.5", "+91 98"))))))
        assertTrue(z.keys.containsAll(listOf("xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/_rels/workbook.xml.rels")))
        val sh = z["xl/worksheets/sheet1.xml"]!!
        assertTrue(sh.contains("<c r=\"B2\"><v>125000</v></c>"))
        assertTrue(sh.contains("<c r=\"B3\"><v>12.5</v></c>"))
        assertTrue(sh.contains("09876"))   // 0 से शुरू = text
        assertTrue(sh.contains("t=\"inlineStr\" s=\"1\""))  // header मोटा
        assertTrue(z["xl/workbook.xml"]!!.contains("name=\"दाम सूची\""))
        for (x in z.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values)
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(x.toByteArray()))
    }

    @Test fun excelHelpers() {
        assertEquals("A", ComputerOffice.col(0)); assertEquals("Z", ComputerOffice.col(25)); assertEquals("AA", ComputerOffice.col(26)); assertEquals("AZ", ComputerOffice.col(51))
        assertEquals("1500", ComputerOffice.number("1,500")); assertNull(ComputerOffice.number("12abc")); assertNull(ComputerOffice.number("007"))
        assertEquals("0.5", ComputerOffice.number("0.5"))
        assertEquals(listOf(listOf("a", "b,c", "d\"e")), ComputerOffice.csv("a,\"b,c\",\"d\"\"e\""))
        assertEquals("Sheet2", ComputerOffice.sheetName("  ", 1))
        // एक जैसे नाम = अलग कर दो
        val wb = unzip(ComputerOffice.xlsx(listOf(ComputerOffice.Sheet("A", emptyList()), ComputerOffice.Sheet("A", emptyList()))))["xl/workbook.xml"]!!
        assertTrue(wb.contains("name=\"A\"")); assertTrue(wb.contains("name=\"A 2\""))
    }

    // ------------------------------------------------------------ चार्ट
    @Test fun charts() {
        val l = listOf("जन", "फ़र", "मार्च")
        val bar = ComputerOffice.chartSvg("bar", "बिक्री", l, listOf(ComputerOffice.Series("2026", listOf(10.0, 25.0, 15.5))))
        assertEquals(3 + 1, Regex("<rect ").findAll(bar).count())   // 3 बार + सफ़ेद पृष्ठभूमि
        assertTrue(bar.contains("बिक्री")); assertTrue(bar.contains("15.50")); assertFalse(bar.contains("15,5"))
        val line = ComputerOffice.chartSvg("line", "t", l, listOf(ComputerOffice.Series("a", listOf(1.0, 2.0, 3.0)), ComputerOffice.Series("b", listOf(3.0, 2.0, 1.0))))
        assertEquals(2, Regex("<polyline").findAll(line).count())
        val pie = ComputerOffice.chartSvg("pie", "t", l, listOf(ComputerOffice.Series("", listOf(1.0, 1.0, 2.0))))
        assertEquals(3, Regex("<path ").findAll(pie).count()); assertTrue(pie.contains("50.0%"))
        assertTrue(ComputerOffice.chartSvg("bar", "t", emptyList(), emptyList()).contains("डेटा नहीं"))
        assertEquals(50.0, ComputerOffice.niceMax(43.0), 0.0); assertEquals(1000.0, ComputerOffice.niceMax(1000.0), 0.0)
        for (x in listOf(bar, line, pie)) javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(x.toByteArray()))
        assertTrue(ComputerOffice.chartHtml(bar).contains("100vw"))
    }

    // ------------------------------------------------------------ ब्राउज़र
    @Test fun browserActions() {
        val c = ComputerBrowser.actionJs(JSONObject().put("click", 7))!!
        assertTrue(c.contains("[data-dk-i=\"7\"]")); assertTrue(c.contains(".click()"))
        assertTrue(ComputerBrowser.actionJs(JSONObject().put("click", "Login"))!!.contains("\"Login\""))
        val t = ComputerBrowser.actionJs(JSONObject().put("type", JSONArray().put(3).put("नासिक \"स्कूल\"")))!!
        assertTrue(t.contains("\"नासिक \\\"स्कूल\\\"\""))         // ठीक से quote
        assertTrue(t.contains("password")); assertTrue(t.contains("otp"))   // पासवर्ड/OTP मना
        assertTrue(ComputerBrowser.actionJs(JSONObject().put("enter", 3))!!.contains("Enter"))
        assertTrue(ComputerBrowser.actionJs(JSONObject().put("scroll", "up"))!!.contains("-innerHeight"))
        assertNull(ComputerBrowser.actionJs(JSONObject().put("fly", 1)))
        assertTrue(ComputerBrowser.SNAPSHOT_JS.contains("data-dk-i"))
    }

    @Test fun browserFormat() {
        val raw = JSONObject().put("title", "Shop").put("url", "https://x.in").put("text", "a".repeat(100))
            .put("els", JSONArray().put(JSONArray().put(1).put("button").put("खोजें").put("")).put(JSONArray().put(2).put("a").put("होम").put("https://x.in/")))
            .put("scroll", JSONArray().put(0).put(3000)).toString()
        val f = ComputerBrowser.format(raw, 0, 40)
        assertTrue(f.contains("[1] button: खोजें")); assertTrue(f.contains("[2] a: होम → https://x.in/"))
        assertTrue(f.contains("बाकी 60 अक्षर")); assertTrue(f.contains("\"from\":40"))
        assertTrue(ComputerBrowser.format("not json").startsWith("ERROR"))
        assertEquals("ab\"c", ComputerBrowser.unquote("\"ab\\\"c\"")); assertEquals("null", ComputerBrowser.unquote("null"))
    }

    // ------------------------------------------------------------ देखना
    @Test fun seeHelpers() {
        assertEquals(1400 to 700, ComputerSee.fit(2800, 1400)); assertEquals(500 to 300, ComputerSee.fit(500, 300))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), ComputerSee.pages(null, 20))
        assertEquals(listOf(1, 2), ComputerSee.pages("2-3", 20)); assertEquals(listOf(4), ComputerSee.pages("5", 20))
        assertEquals(emptyList<Int>(), ComputerSee.pages("9", 3)); assertEquals(listOf(0, 1), ComputerSee.pages("", 2))
    }

    // ------------------------------------------------------------ रोज़ / हर हफ़्ते
    @Test fun repeatingJobs() {
        assertEquals(ComputerJobs.DAY, ComputerJobs.everyMs("daily")); assertEquals(ComputerJobs.DAY, ComputerJobs.everyMs("रोज़"))
        assertEquals(ComputerJobs.WEEK, ComputerJobs.everyMs("weekly")); assertEquals(0L, ComputerJobs.everyMs("कभी"))
        val d = ComputerJobs.DAY
        assertEquals(5000L, ComputerJobs.next(5000, d, 1000))
        assertEquals(1000 + 3 * d, ComputerJobs.next(1000, d, 1000 + 2 * d + 5))   // बीच के छूटे दिन छोड़कर अगला
        val j = ComputerJobs.Job(20001, 1000, "ख़बरें बताओ", d)
        assertEquals(listOf(j), ComputerJobs.decode(ComputerJobs.encode(listOf(j))))
        assertEquals(0L, ComputerJobs.decode("[{\"id\":1,\"at\":5,\"task\":\"x\"}]")[0].every)   // पुराने काम = एक बार
        // restart: दोहराने वाला कभी "देर से" नहीं, बस अगले समय पर; बहुत पुराना भी नहीं हटता
        val p = ComputerJobs.plan(listOf(j), 1000 + 30 * d + 7)
        assertEquals(1, p.size); assertFalse(p[0].late); assertEquals(1000 + 31 * d, p[0].fireAt)
    }

    // ------------------------------------------------------------ पिछले काम
    @Test fun pastTasks() {
        val l = listOf(
            ComputerPast.Item(1, "नासिक के स्कूलों की फ़ीस", "DPS 80000", listOf("schools.pdf")),
            ComputerPast.Item(2, "मोबाइल तुलना 20000 के अंदर", "Redmi, Samsung", listOf("phones.xlsx")),
            ComputerPast.Item(3, "आज की ख़बरें", "…", emptyList()),
        )
        assertEquals("मोबाइल तुलना 20000 के अंदर", ComputerPast.search(l, "पिछली बार वाली मोबाइल लिस्ट")[0].task)
        assertEquals("schools.pdf", ComputerPast.search(l, "schools")[0].files[0])
        assertEquals(3L, ComputerPast.search(l, "")[0].at)        // खाली = सबसे नया
        assertTrue(ComputerPast.search(l, "क्रिकेट").isEmpty())
        assertEquals(l, ComputerPast.decode(ComputerPast.encode(l)))
        assertTrue(ComputerPast.show(emptyList()).contains("कुछ नहीं"))
    }

    // ------------------------------------------------------------ जोड़
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun wiring() {
        for (t in listOf("browse", "look", "make_docx", "make_xlsx", "make_chart", "sub_agents", "past_tasks")) {
            assertTrue(t, ComputerAgent.TOOL_IDS.contains(t))
            assertTrue(t, src("ComputerAgent.kt").contains("\"$t\" ->"))
            assertTrue(t, src("SettingsCoder.kt").contains("\"$t\" to"))
        }
        assertTrue(ComputerAgent.SUB_TOOLS.none { it in setOf("phone_task", "coding_task", "browse", "write_file", "schedule", "sub_agents") })
        assertTrue(ComputerAgent.PARALLEL_SAFE.contains("look"))
        val a = src("ComputerAgent.kt")
        assertTrue(a.contains("ComputerPast.add(")); assertTrue(a.contains("ComputerBrowser.close()"))
        assertTrue(a.contains("Never type passwords, OTP"))
        assertTrue(src("ComputerTools.kt").contains("ComputerJobs.fired("))
        assertTrue(src("ComputerActivity.kt").contains("OpenMultipleDocuments"))
        assertTrue(src("ComputerBrowser.kt").contains("s != \"http\" && s != \"https\""))   // upi:/intent: नहीं खुलते
    }
}
