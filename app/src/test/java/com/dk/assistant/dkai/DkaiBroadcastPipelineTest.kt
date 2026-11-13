package com.dk.assistant.dkai

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 13 नवंबर 2026 (मालिक): Broadcast (Audio) अपग्रेड + Pipeline (Blueprint / क्रम / जाँच) + होम पर सिर्फ़ फ्री मॉडल */
class DkaiBroadcastPipelineTest {

    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").let { if (it.exists()) it else File("app/src/main/java/com/dk/assistant/$p") }.readText()
    private fun L(s: String, f: Boolean, t: String) = DkaiKit.Line(s, f, t)

    // ------------------------------------------------------------------ Broadcast
    @Test fun languages() {
        assertEquals(listOf("Auto", "Hindi (Devanagari)", "English", "Marathi", "Gujarati", "Bengali"), DkaiKit.LANGS.take(6))
        assertEquals("Auto", DkaiKit.normLang("Auto-Detect"))
        assertEquals(DkaiKit.HINDI, DkaiKit.normLang("Hindi"))
        assertEquals("Marathi", DkaiKit.normLang("Marathi"))
        assertEquals("Auto", DkaiKit.normLang("Klingon"))
        assertEquals("mr-IN", DkaiKit.micLocale("Marathi"))
        assertEquals("bn-IN", DkaiKit.micLocale("Bengali"))
        assertTrue(DkaiKit.ttsAccent(DkaiKit.HINDI).contains("pure", true))
        assertFalse(DkaiKit.studioStyle("Podcast", "English").contains("Hindi"))
    }

    @Test fun pureHindiPrompt() {
        val p = DkaiKit.scriptSystem("Podcast", DkaiKit.HINDI, multi = false)
        assertTrue(p.contains("Devanagari") && p.contains("Latin"))
        assertTrue(p.contains("AUDIO-ONLY") && p.contains("ONE speaker"))
        assertTrue(DkaiKit.scriptSystem("News", "Gujarati", false).contains("Gujarati script"))
        assertTrue(DkaiKit.scriptSystem("News", "Bengali", false).contains("Bengali script"))
    }

    @Test fun allVoicesIsTwoCharacterConversation() {
        val p = DkaiKit.scriptSystem("Podcast", DkaiKit.HINDI, multi = true, facts = "- 2 कप आटा")
        assertTrue(p.contains("CONVERSATION") && p.contains("exactly TWO") && p.contains("never a monologue"))
        assertTrue(p.contains("Swara (female)") && p.contains("Aarav (male)"))
        assertTrue(p.contains("2 कप आटा"))
        assertEquals("Emma" to "Rohan", DkaiKit.duo("English"))
        val mono = listOf(L("Swara", true, "a"), L("Swara", true, "b"), L("Swara", true, "c"), L("Swara", true, "d"))
        assertFalse(DkaiKit.isDialogue(mono))
        val talk = listOf(L("Swara", true, "a"), L("Aarav", false, "b"), L("Swara", true, "c"), L("Aarav", false, "d"))
        assertTrue(DkaiKit.isDialogue(talk))
    }

    @Test fun hindiPurityCheck() {
        assertTrue(DkaiKit.needsPureHindi(DkaiKit.HINDI, listOf(L("Swara", true, "Hello friends, aaj hum recipe banayenge"))))
        assertFalse(DkaiKit.needsPureHindi(DkaiKit.HINDI, listOf(L("Swara", true, "नमस्ते दोस्तों, आज हम पकवान बनाएँगे"))))
        assertFalse(DkaiKit.needsPureHindi("English", listOf(L("Emma", true, "Hello friends"))))
    }

    @Test fun namedVoicesAndCast() {
        for (n in listOf("Emma", "Swara", "Anaya", "Aarav", "Rohan")) assertTrue("$n नहीं", DkaiKit.STUDIO_VOICES.any { it.name == n })
        assertEquals(listOf("Emma", "Swara", "Anaya", "Aarav", "Rohan"), DkaiKit.VOICE_MENU.take(5))
        val talk = listOf(L("Swara", true, "a"), L("Aarav", false, "b"))
        val cast = DkaiKit.studioCast(talk, DkaiKit.ALL_VOICES)
        assertEquals("Swara", cast["Swara"]?.name); assertEquals("Aarav", cast["Aarav"]?.name)
        assertNotEquals(cast["Swara"]?.api, cast["Aarav"]?.api)
        val one = DkaiKit.studioCast(talk, "Rohan")
        assertTrue(one.values.all { it.name == "Rohan" })
    }

    @Test fun speed() {
        assertEquals(130, DkaiKit.DEFAULT_SPEED)
        assertEquals(50, DkaiKit.STUDIO_SPEEDS.first()); assertEquals(200, DkaiKit.STUDIO_SPEEDS.last())
        assertEquals(200, DkaiKit.clampSpeed(350)); assertEquals(50, DkaiKit.clampSpeed(10)); assertEquals(130, DkaiKit.clampSpeed(128))
        val pcm = ShortArray(24000 * 2) { (Math.sin(it / 10.0) * 8000).toInt().toShort() }
        val fast = DkaiKit.stretch(pcm, 24000, 2.0f)
        assertTrue("2x = आधी लंबाई (${fast.size})", fast.size in (pcm.size * 0.4).toInt()..(pcm.size * 0.6).toInt())
        val slow = DkaiKit.stretch(pcm, 24000, 0.5f)
        assertTrue("0.5x = दुगनी (${slow.size})", slow.size in (pcm.size * 1.8).toInt()..(pcm.size * 2.2).toInt())
    }

    @Test fun broadcastWiring() {
        val b = src("dkai/DkaiBroadcast.kt")
        assertTrue(b.contains("SpeechRecognizer"))                       // माइक से विषय
        assertTrue(b.contains("SeekBar"))                                // Seekbar
        assertTrue(b.contains("Intent.ACTION_SEND") || b.contains("ACTION_SEND"))
        assertTrue(b.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(b.contains("org.telegram.messenger") && b.contains("com.whatsapp"))
        assertTrue(b.contains("research("))
        assertFalse(b.contains("MediaRecorder.VideoSource"))             // वीडियो नहीं
        assertTrue(src("GeminiTts.kt").contains("const val FREE"))
    }

    // ------------------------------------------------------------------ होम पर सिर्फ़ फ्री मॉडल
    @Test fun freeModels() {
        assertTrue(DkaiKit.isFreeModel("gemini", "gemini-2.5-flash"))
        assertFalse(DkaiKit.isFreeModel("gemini", "gemini-2.5-pro"))
        assertFalse(DkaiKit.isFreeModel("gemini", "gemini-2.5-flash-preview-tts"))
        assertTrue(DkaiKit.isFreeModel("openrouter", "meta-llama/llama-3.3-70b-instruct:free"))
        assertFalse(DkaiKit.isFreeModel("openrouter", "openai/gpt-4o"))
        assertTrue(DkaiKit.isFreeModel("groq", "llama-3.3-70b-versatile"))
        assertFalse(DkaiKit.isFreeModel("groq", "whisper-large-v3"))
        assertFalse(DkaiKit.isFreeModel("zai", "glm-4.6"))
        assertTrue(DkaiKit.isFreeModel("zai", "glm-4.5-flash"))
    }

    @Test fun homeModelsOnlyForKeysAndFree() {
        val none = DkaiKit.homeModels({ false }, false, { emptyList() }, { emptyList() }, { it })
        assertTrue(none.isEmpty())
        val gem = DkaiKit.homeModels({ it == "gemini" }, false, { emptyList() }, { emptyList() }, { it })
        assertTrue(gem.isNotEmpty() && gem.all { it.provider == "gemini" && !it.paid && !it.model.contains("pro") })
        val paid = DkaiKit.homeModels({ it == "gemini" }, true, { emptyList() }, { emptyList() }, { it })
        assertTrue(paid.size >= gem.size)
        val or = DkaiKit.homeModels({ it == "openrouter" }, false, { listOf("openai/gpt-4o", "x/y:free") }, { emptyList() }, { it })
        assertEquals(listOf("x/y:free"), or.map { it.model })
        assertEquals(listOf("gemini-2.5-flash"), DkaiKit.pickable("gemini", listOf("gemini-2.5-flash", "gemini-2.5-pro"), false))
        assertTrue(src("dkai/DkaiSheets.kt").contains("homeModels("))
        assertTrue(src("dkai/DkaiScreen.kt").contains("DkaiKit.pickable("))
    }

    // ------------------------------------------------------------------ Pipeline
    @Test fun planParsing() {
        val j = DkaiPlan.parsePlan("""ok {"steps":[{"title":"Files","goal":"make index.html"},{"title":"UI","goal":"layout"}]}""")
        assertEquals(listOf("Files", "UI"), j.map { it.title })
        assertTrue(j.all { it.status == DkaiPlan.St.PENDING })
        val n = DkaiPlan.parsePlan("Step 1: File structure - folders\n2. UI layout: html css\n3) Logic")
        assertEquals(3, n.size); assertEquals("File structure", n[0].title); assertEquals("folders", n[0].goal)
        assertTrue(DkaiPlan.parsePlan("").isEmpty())
        val d = DkaiPlan.defaultPlan("todo app")
        assertEquals(listOf("File structure", "UI layout", "Functions & logic", "Error checking"), d.map { it.title })
        assertFalse(DkaiPlan.PLAN_SYSTEM.contains("=== FILE"))           // प्लान में कोड नहीं
        assertTrue(DkaiPlan.PLAN_SYSTEM.contains("Do NOT write any code"))
    }

    @Test fun sequentialContextAndStatus() {
        val steps = listOf(DkaiPlan.Step("A", "do a", DkaiPlan.St.DONE, summary = "made index", files = listOf("index.html")),
            DkaiPlan.Step("B", "do b"), DkaiPlan.Step("C", "do c"))
        val p = DkaiPlan.stepPrompt("goal", steps, 1, listOf("use blue"), "=== FILE: index.html ===\n<p>\n=== END ===\n")
        assertTrue(p.contains("<== CURRENT STEP") && p.contains("made index") && p.contains("index.html") && p.contains("use blue"))
        assertTrue(p.contains("NOW DO STEP 2: B"))
        assertEquals(1, DkaiPlan.nextIndex(steps))
        assertEquals("1/3", DkaiPlan.progress(steps))
        assertEquals(-1, DkaiPlan.nextIndex(steps.map { it.copy(status = DkaiPlan.St.DONE) }))
        val merged = DkaiPlan.merge(steps, listOf(DkaiPlan.Step("X", "x")))
        assertEquals(listOf("A", "X"), merged.map { it.title })
        val back = DkaiPlan.fromJson(DkaiPlan.toJson("goal", steps, listOf("n")))!!
        assertEquals(steps.map { it.title }, back.steps.map { it.title })
        assertEquals(DkaiPlan.St.DONE, back.steps[0].status); assertEquals(listOf("index.html"), back.steps[0].files)
        assertEquals(listOf("n"), back.notes)
        for (s in listOf("Pending", "In Progress", "Completed", "Failed")) assertTrue(DkaiPlan.St.values().any { it.label == s })
    }

    @Test fun syntaxChecks() {
        assertNull(DkaiPlan.brackets("function a(){ if (x) { return [1,2]; } } // }", true))
        assertNull(DkaiPlan.brackets("const s = '}'; const r = /[{(]/g; const t = `a${'$'}{b}`;", true))
        assertNotNull(DkaiPlan.brackets("function a(){ if (x) { return 1; }", true))
        assertNotNull(DkaiPlan.brackets("a(]", true))
        assertNull(DkaiPlan.brackets("body{color:red} /* } */ a{b:c}", false))
        assertNotNull(DkaiPlan.brackets("body{color:red", false))
        assertNull(DkaiPlan.syntax("a.json", """{"a":[1,2]}"""))
        assertNotNull(DkaiPlan.syntax("a.json", """{"a":[1,2}"""))
        assertNull(DkaiPlan.syntax("index.html", "<html><body><div><p>x</div><script>if(a){b()}</script></body></html>"))
        assertNotNull(DkaiPlan.syntax("index.html", "<html><body><div></body></html>"))
        assertNotNull(DkaiPlan.syntax("index.html", "<html><body><script>if(a){</script></body></html>"))
        assertNull(DkaiPlan.syntax("README.md", "{{{"))
    }

    @Test fun finalIntegrationCheck() {
        val html = """<link href="style/style.css"><script src="js/app.js"></script><a href="https://x.com/a.js">x</a><img src="data:image/png;base64,aa">"""
        assertEquals(listOf("style/style.css", "js/app.js"), DkaiPlan.refs("index.html", html))
        assertEquals("css/a.css", DkaiPlan.resolve("js/app.js", "../css/a.css"))
        assertNull(DkaiPlan.resolve("app.js", "../../x"))
        val ok = mapOf("index.html" to html, "style/style.css" to "body{}", "js/app.js" to "import {a} from './util'; a();", "js/util.js" to "export const a=()=>1;")
        assertTrue(DkaiPlan.integrate(ok).isEmpty())
        val bad = ok - "js/util.js"
        val probs = DkaiPlan.integrate(bad)
        assertEquals(1, probs.size); assertTrue(probs[0].msg.contains("./util"))
        val broken = ok + ("js/app.js" to "function(){")
        assertTrue(DkaiPlan.integrate(broken).any { it.path == "js/app.js" })
    }

    @Test fun pipelineWiring() {
        val sc = src("dkai/DkaiScreen.kt")
        assertTrue(sc.contains("DkaiPipeline(this).open(t)"))
        val p = src("dkai/DkaiPipeline.kt")
        for (k in listOf("Pause", "Stop", "Retry", "Skip", "Keep All", "Undo All", "Preview", "Final Build Check", "CoderSession.queue.addLast"))
            assertTrue("$k नहीं", p.contains(k))
        assertTrue(p.contains("DkaiPlan.syntax(") && p.contains("DkaiPlan.integrate("))
    }
}
