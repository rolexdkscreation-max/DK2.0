package com.dk.assistant

import com.dk.assistant.Seven.Agent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 📎 फ़ोटो/वीडियो, 🔎 जाँचकर्ता, पूरी समझ (क्या/कैसे), बड़ी पढ़ने की क्षमता */
class CoderMediaInspectorTest {

    private val photo = CoderMedia(CoderMedia.IMAGE, "photo 1", listOf("AAAA"))
    private val video = CoderMedia(CoderMedia.VIDEO, "video 2", listOf("F1", "F2", "F3"), video = "VVVV", durationMs = 12_400)

    // ------------------------------------------------------------------ फ़ोटो / वीडियो
    @Test fun parts_geminiGetsWholeVideo_othersGetFrames() {
        assertEquals(listOf("video/mp4" to "VVVV"), video.parts(gemini = true))
        assertEquals(listOf("image/jpeg" to "F1", "image/jpeg" to "F2", "image/jpeg" to "F3"), video.parts(gemini = false))
        assertEquals(listOf("image/jpeg" to "AAAA"), photo.parts(gemini = false))
        // बड़ा वीडियो (पूरा नहीं रखा) = Gemini को भी frames
        assertEquals(3, video.copy(video = null).parts(gemini = true).size)
    }

    @Test fun frameTimes_evenlySpread_insideTheVideo() {
        val t = CoderMedia.frameTimes(8_000, 4)
        assertEquals(listOf(1_000L, 3_000L, 5_000L, 7_000L), t)
        assertTrue(t.all { it in 1 until 8_000 })
        assertEquals(listOf(0L), CoderMedia.frameTimes(0))
        assertTrue(CoderMedia.frameTimes(5_000, 0).isEmpty())
    }

    @Test fun allParts_respectsTotalLimit_andItemLimit() {
        val big = "x".repeat(CoderMedia.TOTAL_B64_MAX / 2 + 10)
        val list = listOf(photo.copy(frames = listOf(big)), photo.copy(frames = listOf(big)), photo)
        val p = CoderMedia.allParts(list, gemini = true)
        assertEquals(1, p.size)                                   // दूसरी बड़ी फ़ोटो सीमा से बाहर
        val many = (1..10).map { photo.copy(label = "p$it") }
        assertEquals(CoderMedia.MAX_ITEMS, CoderMedia.allParts(many, gemini = false).size)
        // पूरा वीडियो न समाए तो उसके frames
        val hugeVideo = video.copy(video = "v".repeat(CoderMedia.TOTAL_B64_MAX + 1))
        assertEquals(3, CoderMedia.allParts(listOf(hugeVideo), gemini = true).size)
    }

    @Test fun describe_and_chip() {
        val d = CoderMedia.describe(listOf(photo, video))
        assertTrue(d, d.contains("PHOTO \"photo 1\"") && d.contains("VIDEO \"video 2\" (12s, 3 frames + full video)"))
        assertEquals("📎 1 फ़ोटो, 1 वीडियो", CoderMedia.chip(listOf(photo, video)))
        assertEquals("", CoderMedia.chip(emptyList()))
        assertEquals("", CoderMedia.describe(emptyList()))
    }

    // ------------------------------------------------------------------ पढ़ने की क्षमता
    @Test fun budget_bigForGemini_largerThanOldLimit() {
        assertTrue(CoderContext.budgetFor(true, "gemini-3.8-flash") >= 400_000)
        assertTrue(CoderContext.budgetFor(false, "llama-3.3-70b") > CoderContext.DEFAULT_BUDGET)
        assertTrue(CoderContext.budgetFor(false, "google/gemini-2.5-pro") >= 400_000)
    }

    @Test fun compact_bigBudget_keepsLongTaskAndMessages() {
        val task = "TASK FROM USER: " + "क".repeat(20_000)
        val msgs = listOf("user" to task, "model" to "ok") + (1..12).flatMap { listOf("user" to "R$it " + "x".repeat(30_000), "model" to "m$it") }
        val out = CoderContext.compact(msgs, 500_000)
        assertTrue(CoderContext.total(out) <= 500_000)
        // बड़े बजट पर पुराने संदेश 12k से ज़्यादा भी पूरे रहते हैं
        assertTrue(out.any { it.second.length >= 30_000 })
    }

    // ------------------------------------------------------------------ समझ: क्या बदलेगा, कैसे, फ़ोटो में क्या दिखा
    @Test fun understanding_explainsWhatHowAndMedia() {
        val o = Seven.json("""{"understood":"टाइपिंग बार में 📎 बटन","changes":["📎 बटन जुड़ेगा","फ़ोटो AI तक जाएगी"],"steps":["CodingActivity में बटन","CoderAi में फ़ोटो भेजना"],"spec":"s","acceptance":["बटन दिखे"],"not_touch":["🎤 बटन"],"area":["CodingActivity.kt - bar"],"questions":[]}""")
        val u = SevenPlus.Understanding.parse(o, Seven.Kind.FEATURE, "b", emptyList()).copy(mediaNotes = "Screen shows a chat with a red error")
        assertEquals(2, u.changes.size); assertEquals(2, u.steps.size)
        val h = u.hindi()
        assertTrue(h, h.contains("मैंने आपकी बात ऐसे समझी") && h.contains("🔧 क्या-क्या बदलेगा") && h.contains("• 📎 बटन जुड़ेगा"))
        assertTrue(h, h.contains("🪜 कैसे करेंगे") && h.contains("1. CodingActivity में बटन") && h.contains("2. CoderAi में फ़ोटो भेजना"))
        assertTrue(h, h.contains("🖼 आपकी फ़ोटो/वीडियो में मैंने देखा") && h.contains("red error"))
        assertTrue(h, h.contains("इन्हें नहीं छेड़ा जाएगा") && h.contains("\"हाँ\""))
    }

    @Test fun managerStatus_kind() {
        assertEquals(Seven.Kind.STATUS, Seven.kindOf("status", "PR #50 merge हुआ?"))
        assertTrue(Seven.Prompts.MANAGER.contains("\"status\""))
        assertTrue(Seven.Prompts.INSPECTOR.contains("ONLY job"))
    }

    // ------------------------------------------------------------------ टीम: फ़ोटो सही एजेंटों तक, status = जाँचकर्ता
    private class MediaAi(vararg script: Pair<Agent, String>) : TeamAi {
        private val q = script.groupBy({ it.first }, { it.second }).mapValues { ArrayDeque(it.value) }
        val sawMedia = mutableListOf<Agent>()
        val systems = mutableMapOf<Agent, String>()
        override suspend fun ask(agent: Agent, system: String, msgs: List<Pair<String, String>>): String {
            systems[agent] = system
            return q[agent]?.removeFirstOrNull() ?: error("no reply for $agent")
        }
        override suspend fun askMedia(agent: Agent, system: String, msgs: List<Pair<String, String>>, media: List<CoderMedia>): String {
            sawMedia.add(agent); return ask(agent, system, msgs)
        }
        override val budget = 500_000
    }

    @Test fun understand_withMedia_promptEngineerLooksFirst_andNotesGoToEveryone() = runBlocking {
        val ai = MediaAi(
            Agent.PROMPT to "The screen shows the coding chat; the send bar has no attach button.\nUSER WANTS: an attach button",
            Agent.MANAGER to """{"kind":"feature","brief":"add attach button","acceptance":["बटन दिखे"]}""",
            Agent.PROMPT to """{"understood":"📎 बटन","changes":["बटन"],"steps":["UI"],"spec":"add","acceptance":["बटन दिखे"],"not_touch":[],"area":[],"questions":[]}""")
        val team = SevenTeam(ai, MemoryFiles(mapOf("src/A.kt" to "class A")), null, full = true) { _, _ -> }
        team.media = listOf(photo)
        val u = team.understand("यहाँ बटन जोड़ो")
        assertTrue(u.mediaNotes, u.mediaNotes.contains("no attach button"))
        assertEquals(listOf(Agent.PROMPT, Agent.PROMPT), ai.sawMedia)          // पहले देखा, फिर spec के समय भी
        assertTrue(ai.systems[Agent.MANAGER]!!.contains("no attach button"))    // मैनेजर को लिखा विवरण
        assertTrue(ai.systems[Agent.MANAGER]!!.contains("TEAM WORK RULES"))     // सबके लिए नियम
        assertTrue(u.hindi().contains("🖼"))
    }

    @Test fun understand_statusQuestion_goesToInspector_noSpec() = runBlocking {
        val ai = MediaAi(Agent.MANAGER to """{"kind":"status","brief":"user asks whether PR 50 is merged"}""")
        val events = mutableListOf<String>()
        val u = SevenTeam(ai, MemoryFiles(emptyMap()), null, full = true) { _, t -> events.add(t) }.understand("PR #50 का काम हुआ या नहीं?")
        assertEquals(Seven.Kind.STATUS, u.kind)
        assertTrue(u.answer.isBlank())
        assertTrue(events.joinToString(), events.any { it.contains("जाँचकर्ता") })
        assertFalse(ai.systems.containsKey(Agent.PROMPT))                     // spec नहीं बना, सीधे जाँचकर्ता
    }

    // ------------------------------------------------------------------ जाँचकर्ता के तथ्य
    private fun snap() = RepoDoctor.Snap("u/r", "main", "abc1234567", 0L,
        listOf(RepoDoctor.Pr(52, "Coder media", "https://github.com/u/r/pull/52", "feature/x", "main", false, "sha52", "me")),
        listOf(RepoDoctor.RunI(1, "compile", "completed", "failure", "https://run/1", "pull_request", "feature/x", "sha52", 10L),
            RepoDoctor.RunI(2, "Build DK APK", "completed", "success", "https://run/2", "push", "main", "abc1234567", 5L)),
        SevenPlus.Deploy.Rel("v3.41", "DK 3.41", "main", listOf("DK.apk"), "https://rel"))

    @Test fun inspectorFacts_containEverything() {
        val pr = RepoInspector.PDetail(52, "Coder media", "https://github.com/u/r/pull/52", "adds 📎", "open", false, "", "feature/x", "sha52",
            mergeable = true, changedFiles = 2, additions = 30, deletions = 2, commits = 1)
        val files = listOf(RepoInspector.PFile("app/CodingActivity.kt", "modified", 20, 1, "@@ -1 +1 @@\n-old\n+new attach button"),
            RepoInspector.PFile("app/CoderMedia.kt", "added", 10, 0, "+class CoderMedia"))
        val deep = listOf(RepoInspector.Deep(pr, files, snap().runs.filter { it.sha == "sha52" }, "e: CodingActivity.kt:12 Unresolved reference: foo"))
        val merged = listOf(RepoInspector.PDetail(51, "UI smooth", "https://github.com/u/r/pull/51", "", "closed", true, "2026-10-20T10:00:00Z", "feature/ui", "s51"))
        val team = RepoInspector.team(true, "📎 बटन", listOf("s" to "⌨ कोडर: कदम 1/3", "u" to "hi", "e" to "fail"))
        val f = RepoInspector.facts("PR #52 का हाल?", snap(), "🩺 रिपो डॉक्टर — u/r", deep, merged, "", team)
        listOf("USER QUESTION: PR #52 का हाल?", "LATEST RELEASE: DK 3.41", "PR #52: Coder media", "mergeable: yes", "compile: ✗ fail (failure)",
            "Unresolved reference: foo", "app/CodingActivity.kt [modified] +20 -1", "+new attach button", "app/CoderMedia.kt [added]",
            "#51 UI smooth — merged", "Build DK APK: ✓ पास", "BUSY: yes", "कोडर: कदम 1/3").forEach { assertTrue("$it\n$f", f.contains(it)) }
        assertFalse(f.contains("  - hi"))                                       // user के संदेश टीम-हाल में नहीं
    }

    @Test fun inspectorFacts_capped_andPick() {
        val pr = RepoInspector.PDetail(9, "big", "u", "", "open", false, "", "h", "s")
        val files = (1..50).map { RepoInspector.PFile("f$it.kt", "modified", 1, 1, "x".repeat(20_000)) }
        val f = RepoInspector.facts("q", snap(), "rep", listOf(RepoInspector.Deep(pr, files, emptyList())), emptyList(), "", "", budget = 60_000)
        assertTrue(f.length <= 60_000 + 100)
        assertEquals(listOf(50), RepoInspector.pick(50, listOf(52, 53), listOf(51)))
        assertEquals(listOf(52, 53, 51, 49), RepoInspector.pick(null, listOf(52, 53), listOf(51, 49, 48)))
    }
}
