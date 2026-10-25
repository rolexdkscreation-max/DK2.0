package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.70: कोडिंग एजेंट - इतिहास, "वहीं से आगे", screenshot, 3 Gemini keys + मॉडल क्रम */
class CoderHistoryResumeTest {
    private val L = CoderHistory.Logic

    private fun u() = SevenPlus.Understanding(Seven.Kind.BUG, "बटन ठीक", "Settings का बटन दबाने पर crash", "spec",
        listOf("crash नहीं"), listOf("Voice पेज"), emptyList(), listOf("SettingsActivity.kt"), "", listOf("एक लाइन"), listOf("1. पढ़ो"), "")

    @Test fun title_shortFirstLine() {
        assertEquals("नई बातचीत", L.title("  "))
        assertEquals("Settings ठीक करो", L.title("🎤 Settings ठीक करो\nदूसरी लाइन"))
        val long = "क".repeat(80)
        assertTrue(L.title(long).length <= 58 && L.title(long).endsWith("…"))
    }

    @Test fun threadsAndLines_roundTrip_newestFirst_max100() {
        val t1 = CoderHistory.Thread("a", "पहला", 1, 10, "done", "me/DK2.0", 84, "https://x/84", "feature/x", "main", 5)
        val t2 = CoderHistory.Thread("b", "दूसरा", 2, 20, "run")
        val list = L.upsert(L.upsert(emptyList(), t1), t2)
        assertEquals(listOf("b", "a"), list.map { it.id })
        val back = L.index(L.indexJson(list))
        assertEquals(list, back)
        // वही धागा फिर = ऊपर, दोहराया नहीं
        assertEquals(listOf("a", "b"), L.upsert(list, t1.copy(updated = 30)).map { it.id })
        var many = emptyList<CoderHistory.Thread>()
        repeat(130) { many = L.upsert(many, CoderHistory.Thread("t$it", "x", it.toLong(), it.toLong(), "chat")) }
        assertEquals(CoderHistory.MAX_THREADS, many.size)
        assertEquals("t129", many.first().id)
        assertTrue(L.index("गड़बड़").isEmpty())

        val lines = listOf(CoderSession.Line("u", "काम \"quote\"\nदो लाइन"), CoderSession.Line("i", "📸 cap\n/data/x.png"))
        assertEquals(lines, L.lines(L.linesJson(lines)))
    }

    @Test fun checkpoint_roundTrip_keepsStagedAndUnderstanding() {
        val k = CoderHistory.Checkpoint("c1", "बटन ठीक करो", u(), "run", false, "me/DK2.0", "main", "feature/dk-x", "backup/x", 85,
            "https://x/85", linkedMapOf("a.kt" to "class A", "old.kt" to null), "⌨ कोडर: a.kt", 99, 1)
        val back = L.ck(L.ckJson(k))!!
        assertEquals(k.copy(at = back.at), back)
        assertNull(back.staged["old.kt"])        // हटाई जाने वाली फ़ाइल = null ही रहे
        assertNull(L.ck(null)); assertNull(L.ck("")); assertNull(L.ck("{टूटा"))
    }

    @Test fun reopen_rules() {
        val run = CoderHistory.Checkpoint("c", "t", u(), "run")
        assertEquals("auto", L.onReopen(run, busy = false))                       // ऐप बीच में बंद = अपने-आप आगे
        assertEquals("", L.onReopen(run, busy = true))                            // अभी चल रहा = कुछ नहीं
        assertEquals("ask", L.onReopen(run.copy(tries = CoderHistory.AUTO_RESUME_MAX), false))  // बार-बार crash = बटन
        assertEquals("ask", L.onReopen(run.copy(stage = "pause"), false))
        assertEquals("wait", L.onReopen(run.copy(stage = "pending"), false))       // मंज़ूरी का इंतज़ार वापस
        assertEquals("", L.onReopen(null, false))
    }

    @Test fun resumeNote_tellsTeamWhatIsDone() {
        val n = L.resumeNote(CoderHistory.Checkpoint("c", "t", u(), "run", branch = "feature/dk-x", prNum = 7,
            staged = mapOf("a.kt" to "x"), last = "रिव्यूअर"))
        assertTrue(n.contains("RESUME") && n.contains("do NOT redo"))
        assertTrue(n.contains("feature/dk-x") && n.contains("PR #7") && n.contains("a.kt"))
    }

    @Test fun continueWords() {
        assertTrue(L.isContinue("आगे बढ़ो"))
        assertTrue(L.isContinue("वहीं से शुरू करो"))
        assertTrue(L.isContinue("🎤 continue"))
        assertFalse(L.isContinue("Settings में नया बटन जोड़ो"))
        assertFalse(L.isContinue("आगे बढ़ो " + "और फिर पूरा नया पेज बनाओ जिसमें बहुत कुछ हो"))  // लंबा = नया काम
    }

    @Test fun statusAfterWork() {
        assertEquals("wait", L.statusAfter("d", cancelled = false, waiting = true))
        assertEquals("stop", L.statusAfter("e", cancelled = true, waiting = false))
        assertEquals("done", L.statusAfter("d", false, false))
        assertEquals("fail", L.statusAfter("e", false, false))
        assertTrue(L.statusHi("pause").contains("आगे"))
    }

    @Test fun shots_pickArtifactsAndParse() {
        assertTrue(Shots.isShotArtifact("uaag-screenshots"))
        assertTrue(Shots.isShotArtifact("ui-preview-shots"))
        assertFalse(Shots.isShotArtifact("DK-apk"))
        assertTrue(Shots.isImage("01_khulte_hi.PNG")); assertFalse(Shots.isImage("status.txt"))
        val line = Shots.line("📸 cap\nदूसरी", listOf("/a/1.png", "/a/2.png"))
        assertEquals("📸 cap दूसरी" to listOf("/a/1.png", "/a/2.png"), Shots.parse(line))
        assertEquals("01 khulte hi", Shots.label("01_khulte_hi.png"))
        assertTrue(Shots.note(mapOf("status.txt" to "DK chal raha hai")).contains("चल रहा"))
        assertTrue(Shots.note(mapOf("status.txt" to "DK band ho gaya (crash?)", "crash_log.txt" to "x\njava.lang.IllegalStateException: boom")).contains("IllegalStateException"))
    }

    @Test fun geminiKeys_andCoderModelChain() {
        assertEquals(listOf("k1", "k3"), GeminiModels.distinctKeys(listOf(" k1 ", "", "k1", "k3")))
        assertEquals(GeminiModels.CODER, GeminiModels.CODER_CHAIN.first())
        assertEquals("gemini-3.8-flash", GeminiModels.CODER)
        assertTrue(GeminiModels.CODER_CHAIN.none { GeminiModels.isLegacy(it) || it.contains("pro") || it.contains("argon") })
        assertEquals(GeminiModels.CODER_CHAIN.size, GeminiModels.CODER_CHAIN.distinct().size)
        assertTrue(GeminiModels.oldCoderDefault("gemini-3.5-flash-lite"))
        assertTrue(GeminiModels.oldCoderDefault(""))
        assertFalse(GeminiModels.oldCoderDefault("gemini-3.6-flash"))
    }

    @Test fun allLimits_waitThenRetry_notStop() {
        assertEquals(0, CoderAi.nextLimitWait(401, 30, 0))                 // key गलत = रुकना बेकार
        assertEquals(30, CoderAi.nextLimitWait(429, 30, 0))
        assertEquals(20, CoderAi.nextLimitWait(429, 3, 0))                 // कम से कम 20
        assertEquals(120, CoderAi.nextLimitWait(429, 3600, 0))             // ज़्यादा से ज़्यादा 120
        assertEquals(60, CoderAi.nextLimitWait(429, 0, 0))
        assertEquals(10, CoderAi.nextLimitWait(429, 60, CoderAi.LIMIT_WAIT_MAX_SEC - 10))  // कुल सीमा से आगे नहीं
        assertEquals(0, CoderAi.nextLimitWait(429, 60, CoderAi.LIMIT_WAIT_MAX_SEC))
    }
}
