package com.dk.assistant.dkai

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 13 नवंबर 2026 (मालिक): DKAI UI अपग्रेड का pure logic - होम, Files, Memory, Broadcast, Copilot, Server, Editor */
class DkaiKitTest {

    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").let { if (it.exists()) it else File("app/src/main/java/com/dk/assistant/$p") }.readText()

    @Test fun brandAndPaths() {
        assertEquals("DKAI", DkaiKit.BRAND)
        assertEquals("DKAI Copilot", DkaiKit.COPILOT)
        assertEquals("DKAIProjects", DkaiKit.PROJECTS_DIR)
        assertEquals(".dkai", DkaiKit.CONFIG_DIR)
        assertEquals(8080, DkaiKit.PORT)
        assertEquals(0xFF10B981.toInt(), DkaiKit.EMERALD)
        assertEquals("http://localhost:8080/", DkaiKit.localUrl())
        assertEquals("http://localhost:9000/a.html", DkaiKit.localUrl(9000, "/a.html"))
    }

    @Test fun noVideoNoRoot() {
        // spec: वीडियो बनाना नहीं, root नहीं - नई फ़ाइलों में ऐसा कुछ न हो
        for (f in listOf("dkai/DkaiZip.kt", "dkai/DkaiHomeViews.kt", "dkai/DkaiScreen.kt", "dkai/DkaiSheets.kt")) {
            val s = src(f)
            assertFalse(f, s.contains("\"su\""))
            assertFalse(f, s.contains("MediaRecorder.VideoSource"))
            assertFalse(f, s.contains("Bypass", ignoreCase = false))
        }
    }

    @Test fun quickCards() {
        assertEquals(4, DkaiKit.QUICK.size)
        assertEquals(listOf("Deep Web Research", "Landing Page", "Expense Tracker", "Voice Assistant"), DkaiKit.QUICK.map { it.title })
        assertTrue(DkaiKit.needsMore(DkaiKit.QUICK[0]))
        assertFalse(DkaiKit.needsMore(DkaiKit.QUICK[2]))
        assertNull(DkaiKit.QUICK[3].mode)
        assertEquals(5, DkaiKit.HOME_MODES.size)
        assertEquals(DkaiMode.BROADCAST, DkaiKit.HOME_MODES.last())
    }

    @Test fun voiceTags() {
        assertEquals("Female • English", DkaiKit.voiceTag("Emma", true))
        assertEquals("Male • Hindi", DkaiKit.voiceTag("Aarav", false))
        assertTrue(DkaiKit.voiceTag("Xyz", false).startsWith("Male"))
    }

    @Test fun chatTextAndSearch() {
        assertEquals("", DkaiKit.chatText(emptyList(), "Chat #1"))
        val t = DkaiKit.chatText(listOf("u" to "hi", "d" to "hello"), "Chat #2")
        assertTrue(t.startsWith("DKAI • Chat #2")); assertTrue(t.contains("You: hi")); assertTrue(t.contains("DK: hello"))
        assertEquals(listOf(1), DkaiKit.searchHits(listOf("abc", "Hello World", "x"), "world"))
        assertTrue(DkaiKit.searchHits(listOf("a"), "  ").isEmpty())
    }

    @Test fun fileKindsAndSort() {
        assertEquals(DkaiKit.Kind.WEB, DkaiKit.kindOf("index.HTML", false))
        assertEquals(DkaiKit.Kind.STYLE, DkaiKit.kindOf("a.css", false))
        assertEquals(DkaiKit.Kind.SCRIPT, DkaiKit.kindOf("app.js", false))
        assertEquals(DkaiKit.Kind.IMAGE, DkaiKit.kindOf("favicon.ico", false))
        assertEquals(DkaiKit.Kind.DOC, DkaiKit.kindOf("README.md", false))
        assertEquals(DkaiKit.Kind.CONFIG, DkaiKit.kindOf(".dkai", true))
        assertEquals(DkaiKit.Kind.FOLDER, DkaiKit.kindOf("js", true))
        val e = listOf(DkaiKit.Entry("b.txt", false, 1, 50), DkaiKit.Entry("z", true, 0, 0), DkaiKit.Entry("a.js", false, 9, 10))
        assertEquals(listOf("z", "a.js", "b.txt"), DkaiKit.sorted(e, DkaiKit.Sort.NAME).map { it.name })
        assertEquals(listOf("z", "a.js", "b.txt"), DkaiKit.sorted(e, DkaiKit.Sort.DATE).map { it.name })
        assertEquals(listOf("z", "b.txt", "a.js"), DkaiKit.sorted(e, DkaiKit.Sort.SIZE).map { it.name })
        assertEquals(DkaiKit.Sort.DATE, DkaiKit.nextSort(DkaiKit.Sort.NAME))
        assertEquals(DkaiKit.Sort.NAME, DkaiKit.nextSort(DkaiKit.Sort.TYPE))
    }

    @Test fun memoryCategories() {
        assertEquals(DkaiKit.MemCat.PREFERENCE, DkaiKit.memCat("Preference: dark theme"))
        assertNull(DkaiKit.memCat("plain fact"))
        assertEquals("dark theme", DkaiKit.memBody("Preference: dark theme"))
        assertEquals("Project: Kotlin app", DkaiKit.memTag(DkaiKit.MemCat.PROJECT, "Kotlin app"))
        assertEquals("Project: x", DkaiKit.memTag(DkaiKit.MemCat.PROJECT, "Profile: x"))
        val all = listOf("Preference: a", "Profile: b", "c")
        assertEquals(all, DkaiKit.memFilter(all, null))
        assertEquals(listOf("Profile: b"), DkaiKit.memFilter(all, DkaiKit.MemCat.PROFILE))
    }

    @Test fun reasoningDepth() {
        assertEquals(listOf("High", "Medium", "Low", "Off"), DkaiKit.DEPTHS)
        assertEquals("Off", DkaiKit.depthOf("auto"))
        assertEquals("High", DkaiKit.depthOf("HIGH"))
        assertEquals("auto", DkaiKit.levelOf("Off"))
        for (d in DkaiKit.DEPTHS) assertEquals(d, DkaiKit.depthOf(DkaiKit.levelOf(d)))
    }

    @Test fun broadcastScript() {
        val l = DkaiKit.parseScript("**Anchor (female):** नमस्ते, आज की खबरें\nReporter (male): धन्यवाद\nऔर आगे")
        assertEquals(2, l.size)
        assertTrue(l[0].female); assertFalse(l[1].female)
        assertEquals("धन्यवाद और आगे", l[1].text)
        // अपनी स्क्रिप्ट: 2+ "Name:" लाइन
        assertEquals(2, DkaiKit.ownScript("Riya: hi\nRohan: hello").size)
        assertTrue(DkaiKit.ownScript("एक कहानी सुनाओ राजा की").isEmpty())
        // कहानी = अलग-अलग महिला/पुरुष आवाज़ें
        val cast = DkaiKit.cast(listOf(DkaiKit.Line("Narrator", true, "a"), DkaiKit.Line("Raja", false, "b"), DkaiKit.Line("Rani", true, "c")), null)
        assertTrue(cast["Narrator"]!!.female); assertFalse(cast["Raja"]!!.female); assertTrue(cast["Rani"]!!.female)
        assertNotEquals(cast["Narrator"], cast["Rani"])
        // TTS: हर टुकड़े में <= 2 बोलने वाले
        val segs = DkaiKit.segments(listOf(DkaiKit.Line("A", true, "1"), DkaiKit.Line("B", false, "2"), DkaiKit.Line("C", true, "3"), DkaiKit.Line("A", true, "4")))
        assertTrue(segs.all { s -> s.map { it.speaker }.toSet().size <= 2 })
        assertEquals(4, segs.sumOf { it.size })
        assertTrue(DkaiKit.formatStyle("Story").contains("story"))
        assertEquals("natural, warm", DkaiKit.formatStyle("?"))
        assertTrue(DkaiKit.scriptSystem("Story", "Hindi", true).contains("AUDIO-ONLY"))
        assertTrue(DkaiKit.scriptSystem("Speech", "English", false).contains("ONE speaker"))
    }

    @Test fun studioVoicesAndAudio() {
        val c = DkaiKit.studioCast(listOf(DkaiKit.Line("A", true, "x"), DkaiKit.Line("B", false, "y")), DkaiKit.ALL_VOICES)
        assertTrue(c["A"]!!.female); assertFalse(c["B"]!!.female)
        val one = DkaiKit.studioCast(listOf(DkaiKit.Line("A", true, "x"), DkaiKit.Line("B", false, "y")), "Brian")
        assertEquals(setOf("Brian"), one.values.map { it.name }.toSet())
        assertEquals("Anchor", DkaiKit.ttsName("Anchor", 0)); assertEquals("Speaker2", DkaiKit.ttsName("रिपोर्टर", 1))
        assertEquals("02:04", DkaiKit.clock(124_000)); assertEquals("00:00", DkaiKit.clock(-5))
        val raw = ByteArray(10) { it.toByte() }
        assertEquals(24000, DkaiKit.wavPcm(raw).second)
        val p = DkaiKit.peaks(ByteArray(400) { if (it % 2 == 1) 0x40 else 0 }, 10)
        assertEquals(10, p.size); assertTrue(p.all { it in 0.06f..1f })
    }

    @Test fun copilotEdits() {
        val raw = "Fixed the button.\n=== FILE: index.html ===\n```html\n<h1>Hi</h1>\n```\n=== END ===\n=== FILE: ../evil.js ===\nx\n=== END ===\n=== FILE: js/app.js ===\nconsole.log(1)\n=== END ==="
        val e = DkaiKit.parseEdits(raw)
        assertEquals(listOf("index.html", "js/app.js"), e.map { it.path })
        assertEquals("<h1>Hi</h1>", e[0].content)
        assertEquals("Fixed the button.", DkaiKit.summaryOf(raw))
        assertTrue(DkaiKit.lineDelta(null, "a\nb").contains("नई फ़ाइल"))
        assertTrue(DkaiKit.lineDelta("a", "a\nb\nc").startsWith("+2"))
        assertEquals(3, DkaiKit.COPILOT_CHIPS.size)
        for (ch in DkaiKit.COPILOT_CHIPS) assertNotEquals(ch, DkaiKit.chipPrompt(ch))
        assertTrue(DkaiKit.COPILOT_SYSTEM.contains("=== FILE:"))
    }

    @Test fun enhanceAndPresets() {
        assertEquals(6, DkaiKit.ENHANCE.size)
        assertEquals("", DkaiKit.enhancePrompt(emptyList(), " ", true))
        val p = DkaiKit.enhancePrompt(listOf(3, 0), "blue hero", false)
        assertTrue(p.startsWith(DkaiKit.ENHANCE[0])); assertTrue(p.contains("blue hero")); assertTrue(p.contains("Do not add external CDN"))
        assertEquals(listOf("Dashboard", "REST API", "Game"), DkaiKit.CLONE_PRESETS.map { it.label })
        for (pr in DkaiKit.CLONE_PRESETS) assertNotNull(DkaiKit.parseRepo(pr.repo))
    }

    @Test fun repoAndZip() {
        assertEquals("a" to "b", DkaiKit.parseRepo("https://github.com/a/b.git"))
        assertEquals("a" to "b", DkaiKit.parseRepo("github.com/a/b/tree/main"))
        assertEquals("o" to "r", DkaiKit.parseRepo("o/r"))
        assertNull(DkaiKit.parseRepo("not a repo"))
        assertEquals("b", DkaiKit.folderFor("https://github.com/a/b", ""))
        assertEquals("mine", DkaiKit.folderFor("https://github.com/a/b", "mine"))
        assertEquals("src/a.js", DkaiKit.zipTarget("repo-main/src/a.js", true))
        assertNull(DkaiKit.zipTarget("repo-main/", true))
        assertNull(DkaiKit.zipTarget("x/../../etc/passwd", false))
        assertEquals("a/b", DkaiKit.zipTarget("\\a\\b", false))
    }

    @Test fun serverResolve() {
        val root = Files.createTempDirectory("dkai").toFile()
        try {
            File(root, "js").mkdirs(); File(root, "index.html").writeText("x"); File(root, "js/app.js").writeText("y")
            assertEquals(File(root, "index.html").canonicalPath, DkaiKit.resolve(root, "/")?.canonicalPath)
            assertEquals(File(root, "js/app.js").canonicalPath, DkaiKit.resolve(root, "/js/app.js?v=2")?.canonicalPath)
            assertNull(DkaiKit.resolve(root, "/../secret"))
            assertNull(DkaiKit.resolve(root, "/%2e%2e/secret"))
            assertTrue(DkaiKit.mimeOf("a.HTML").startsWith("text/html"))
            assertEquals("application/octet-stream", DkaiKit.mimeOf("a.bin"))
        } finally { root.deleteRecursively() }
    }

    @Test fun packages() {
        val h = DkaiKit.addScript("<html><body>\n</body></html>", "chart.js")
        assertTrue(h.contains(DkaiKit.cdnTag("chart.js"))); assertTrue(h.indexOf("chart.js") < h.indexOf("</body>"))
        assertEquals(h, DkaiKit.addScript(h, "chart.js"))
        assertEquals("requests==2.0\n", DkaiKit.addRequirement("", "requests", "2.0"))
        assertEquals("requests==1\n", DkaiKit.addRequirement("requests==1\n", "Requests", "2"))
        assertTrue(DkaiKit.validPkg("@scope/name")); assertTrue(DkaiKit.validPkg("chart.js")); assertFalse(DkaiKit.validPkg("a b"))
    }

    @Test fun sheetsAndModels() {
        assertEquals(5, DkaiKit.ATTACH.size)
        assertEquals("IDE Image Scan", DkaiKit.ATTACH[0].title)
        assertEquals("Deliverables Studio", DkaiKit.ATTACH.last().title)
        assertEquals(DkaiKit.MODEL_CARDS.size - 1, DkaiKit.modelCardOf("openai", "gpt"))
        assertEquals(3, DkaiKit.modelCardOf("gemini", "gemini-2.5-flash-lite-preview"))
        for (m in DkaiKit.HOME_MODES) assertTrue(DkaiKit.modeRow(m).title.isNotBlank())
    }

    @Test fun editorHelpers() {
        assertEquals("1\n2\n3", DkaiKit.gutter("a\nb\nc"))
        assertEquals("1", DkaiKit.gutter(""))
        val js = "const a = \"// not comment\"; // real\nlet n = 42"
        val sp = DkaiKit.highlight(js, "app.js")
        fun tokAt(i: Int) = sp.firstOrNull { i >= it.start && i < it.end }?.tok
        assertEquals(DkaiKit.Tok.KEYWORD, tokAt(0))
        assertEquals(DkaiKit.Tok.STRING, tokAt(js.indexOf("// not")))
        assertEquals(DkaiKit.Tok.COMMENT, tokAt(js.indexOf("// real")))
        assertEquals(DkaiKit.Tok.NUMBER, tokAt(js.indexOf("42")))
        val html = "<a href=\"http://x.com\">hi</a><!-- c -->"
        val hs = DkaiKit.highlight(html, "index.html")
        assertTrue(hs.any { it.tok == DkaiKit.Tok.TAG }); assertTrue(hs.any { it.tok == DkaiKit.Tok.COMMENT })
        assertFalse(hs.any { it.tok == DkaiKit.Tok.COMMENT && html.substring(it.start, it.end).startsWith("//") })
        // spans एक-दूसरे पर न चढ़ें
        val all = (sp + hs)
        assertTrue(sp.zipWithNext().all { (a, b) -> a.end <= b.start }); assertTrue(all.all { it.start < it.end })
        assertEquals(listOf("TAB", "(", ")", "{", "}", "[", "]", "<", ">", "/", "=", ";"), DkaiKit.SYMBOLS)
        assertEquals("  ", DkaiKit.symbolText("TAB")); assertEquals("{", DkaiKit.symbolText("{"))
        assertEquals(listOf("b", "c", "a"), DkaiKit.openTabs(listOf("a", "b", "c"), "a"))
        assertEquals(listOf("c", "d"), DkaiKit.openTabs(listOf("b", "c"), "d", 2))
        assertEquals("5s", DkaiKit.uptime(5_000)); assertEquals("2m 5s", DkaiKit.uptime(125_000)); assertEquals("1h 1m", DkaiKit.uptime(3_660_000))
    }

    @Test fun screenWiring() {
        val sc = src("dkai/DkaiScreen.kt")
        assertTrue(sc.contains("DkaiSheets.models(")); assertTrue(sc.contains("DkaiSheets.attach("))
        // 14 नवंबर 2026 (मालिक): मोड / IDE टैब / Dev Tools हटे - ⚙ = एक सूची, नीचे Perplexity जैसा डिब्बा
        assertFalse(sc.contains("DkaiSheets.modes(")); assertFalse(sc.contains("DkaiDevTools")); assertFalse(sc.contains("gitTab"))
        assertTrue(sc.contains("settingsList()")); assertTrue(sc.contains("host.settingsItems()"))
        assertTrue(sc.contains("Logo3D(c, R.drawable.ic_dk)")); assertTrue(sc.contains("ComputerEyes(c)")); assertTrue(sc.contains("InlineDictation(c,"))
        assertTrue(sc.contains("DkaiUi.closeTop(root)")); assertTrue(sc.contains("DkaiTheme.load(c)"))
        assertTrue(src("dkai/DkaiViews.kt").contains("R.drawable.ic_dk"))
        assertTrue(src("dkai/DkaiVoice.kt").contains("Voices & Speed"))
    }
}
