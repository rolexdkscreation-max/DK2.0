package com.dk.assistant.agent

import com.dk.assistant.LiveToolSlim
import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ImageApp
import com.dk.assistant.agent.core.ImageGenFlow
import com.dk.assistant.agent.core.ImageGenHost
import com.dk.assistant.agent.core.UiElement
import com.dk.assistant.agent.core.VideoGenFlow
import com.dk.assistant.agent.core.VideoStudio
import com.dk.assistant.agent.core.WebAgentCore
import com.dk.assistant.agent.core.WebVideoRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 28 नवंबर 2026 (मालिक): "Gemini पर जाकर मेरी कहानी का 1 मिनट का 2D / 3D / इमोशनल वीडियो बनाओ, किरदार वही रहें, भाग-भाग में" +
 * "कोई भी वीडियो वेबसाइट (मैं लॉग-इन करूँगा, लिंक दूँगा) - वहाँ बनाओ".
 */
class VideoStudioTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    // ---------- plan ----------
    @Test fun seconds_fromWordsAndArgs() {
        assertEquals(60, VideoStudio.seconds(0, 0.0, "1 मिनट का वीडियो बनाओ"))
        assertEquals(90, VideoStudio.seconds(0, 0.0, "डेढ़ मिनट का"))
        assertEquals(30, VideoStudio.seconds(0, 0.0, "30 सेकंड का वीडियो"))
        assertEquals(120, VideoStudio.seconds(0, 0.0, "दो मिनट का 3D वीडियो"))
        assertEquals(60, VideoStudio.seconds(0, 0.0, "एक कहानी का वीडियो"))           // कुछ न कहा = 1 मिनट
        assertEquals(45, VideoStudio.seconds(45, 0.0, "1 मिनट"))                      // सीधा बताया = वही
        assertEquals(VideoStudio.MAX_SEC, VideoStudio.seconds(0, 10.0, ""))          // बहुत लंबा = 3 मिनट तक
    }

    @Test fun split_eightSecondParts() {
        assertEquals(List(8) { 8 }, VideoStudio.split(64))
        val s = VideoStudio.split(60)
        assertEquals(8, s.size); assertEquals(60, s.sum()); assertEquals(4, s.last())
        assertTrue(VideoStudio.split(62).last() >= 4)
    }

    @Test fun style_fromWords() {
        assertTrue(VideoStudio.style("1 मिनट का 2D वीडियो").contains("2D"))
        assertTrue(VideoStudio.style("3D वीडियो बनाओ").contains("3D"))
        assertTrue(VideoStudio.style("एक इमोशनल वीडियो").contains("emotional"))
        assertTrue(VideoStudio.style("कुछ भी").contains("2D"))      // कुछ न कहा = 2D कार्टून
    }

    @Test fun parsePlan_keepsCharactersAndLength() {
        val json = """{"title":"माँ का प्यार","characters":[{"name":"Raju","look":"8-year-old Indian boy, round face, short black hair, red t-shirt, blue shorts"},
            {"name":"Maa","look":"35-year-old Indian woman, long black braid, green saree with gold border, small red bindi"}],
            "parts":[{"n":1,"seconds":8,"scene":"Raju runs home in the rain holding a paper boat, village street, evening light","ends_with":"Raju at the door"},
            {"n":2,"seconds":8,"scene":"Maa opens the door and hugs wet Raju, warm lamp light inside the hut","ends_with":"hug"}]}"""
        val p = VideoStudio.parsePlan("```json\n$json\n```", "माँ और बेटे की कहानी", 24, VideoStudio.style("2D emotional"))
        assertEquals("माँ का प्यार", p.title)
        assertEquals(2, p.chars.size)
        assertEquals(3, p.parts.size)                 // 24 सेकंड = 3 भाग - कम आए तो भी पूरे
        assertEquals(24, p.totalSeconds)
        val p2 = VideoStudio.partPrompt(p, 1)
        assertTrue(p2.contains("Raju: 8-year-old Indian boy"))                // किरदार हर भाग में शब्दशः
        assertTrue(p2.contains("Maa: 35-year-old"))
        assertTrue(p2.contains("previous clip ended") && p2.contains("Raju at the door"))
        assertTrue(p2.startsWith("Now create the NEXT video"))
        assertTrue(VideoStudio.partPrompt(p, 0).startsWith("Create a video"))
        assertTrue(VideoStudio.webGoal(p, 0, "labs.google").contains("DOWNLOAD"))
    }

    @Test fun parsePlan_brokenAnswer_fallsBack() {
        val p = VideoStudio.parsePlan("sorry", "एक शेर और चूहे की दोस्ती", 60, "2D")
        assertEquals(8, p.parts.size); assertEquals(60, p.totalSeconds)
        assertTrue(p.chars.isNotEmpty())
        assertTrue(VideoStudio.partPrompt(p, 3).contains("शेर"))
        assertTrue(VideoStudio.planAsk("x", 60, "2D", 2).contains("exactly 2"))
    }

    @Test fun doneLine_isHonest() {
        val p = VideoStudio.fallback("कहानी", 24, "2D")
        assertTrue(VideoStudio.doneLine(p, 3, true, "Movies/DK", "").contains("तैयार"))
        val half = VideoStudio.doneLine(p, 1, false, "Download", "limit खत्म")
        assertTrue(half.contains("3 में से 1") && half.contains("limit खत्म"))
    }

    // ---------- Gemini ऐप में एक भाग ----------
    private class Host(val d: FakeScreenDriver) : ImageGenHost {
        var t = 0L
        var video: String? = null
        val opened = mutableListOf<String>()
        override fun installed(pkg: String) = true
        override suspend fun openApp(pkg: String): Boolean { opened += pkg; return true }
        override suspend fun longPress(x: Int, y: Int) = true
        override suspend fun newestImageSince(startMs: Long): String? = null
        override suspend fun newestVideoSince(startMs: Long): String? = video
        override fun now(): Long { t += 1_000; return t }
    }

    private val edit = UiElement(0, "", "Enter a prompt", cls = "EditText", clickable = true, editable = true, bounds = Box(40, 2150, 900, 2260))
    private val send = UiElement(1, "", "Send message", cls = "Button", clickable = true, bounds = Box(920, 2150, 1040, 2260))
    private val stop = UiElement(1, "", "Stop response", cls = "Button", clickable = true, bounds = Box(920, 2150, 1040, 2260))
    private val making = UiElement(3, "Generating your video. This may take a few minutes.", cls = "TextView", bounds = Box(40, 900, 900, 960))
    private val video = UiElement(4, "", "Video", cls = "FrameLayout", clickable = true, bounds = Box(40, 700, 1040, 1300))
    private val dl = UiElement(1, "", "Download video", cls = "Button", clickable = true, bounds = Box(900, 100, 1040, 200))

    private fun gemini(busy: Int = 4): Pair<FakeScreenDriver, Host> {
        val d = FakeScreenDriver(ImageApp.GEMINI.pkg, mapOf(
            "chat" to listOf(edit, send),
            "busy" to listOf(edit, stop, making),
            "done" to listOf(edit, send, video),
            "player" to listOf(video, dl),
        ), "chat")
        val h = Host(d)
        d.onClick["chat" to "send message"] = "busy"
        d.onClick["done" to "video"] = "player"
        d.onBack["player"] = "done"
        var left = busy
        d.onCapture = { if (it.current == "busy" && left-- <= 0) it.current = "done"; if (it.clicks.lastOrNull() == "download video") h.video = "content://media/external/video/media/7" }
        return d to h
    }

    @Test fun geminiPart_waitsDownloadsAndReturnsToChat() = runBlocking {
        val (d, h) = gemini()
        val r = VideoGenFlow(d, h, pollMs = 1).makeClip(ImageApp.GEMINI.target, "Create a video (8 seconds) of a boy", fresh = true)
        assertTrue(r.error, r.ok)
        assertEquals("content://media/external/video/media/7", r.file)
        assertEquals("Create a video (8 seconds) of a boy", d.typed.single())
        assertTrue(d.clicks.contains("download video"))
        assertEquals("done", d.current)            // अगला भाग उसी chat में
    }

    @Test fun geminiPart_limitStopsHonestly() = runBlocking {
        val (d, h) = gemini()
        d.screens["limit"] = listOf(edit, send, UiElement(5, "You've reached your video generation limit. Try again later.", cls = "TextView", bounds = Box(40, 900, 1000, 1000)))
        d.onClick["chat" to "send message"] = "limit"
        val r = VideoGenFlow(d, h, pollMs = 1).makeClip(ImageApp.GEMINI.target, "Create a video of a cat", fresh = false)
        assertFalse(r.ok); assertTrue(r.limit); assertNull(r.file)
    }

    @Test fun geminiPart_neverReady_timesOut() = runBlocking {
        val (d, h) = gemini(busy = 100_000)
        val r = VideoGenFlow(d, h, pollMs = 1, maxWaitMs = 60_000).makeClip(ImageApp.GEMINI.target, "Create a video of rain", fresh = false)
        assertFalse(r.ok); assertTrue(r.error.contains("वीडियो नहीं बना"))
    }

    @Test fun videoDetection_pure() {
        val o = com.dk.assistant.agent.core.ScreenObservation(1, ImageApp.GEMINI.pkg, listOf(edit, video))
        assertEquals(1, VideoGenFlow.videos(o).size)
        assertTrue(VideoGenFlow.isLimit("You have reached your limit, try again later"))
        assertFalse(VideoGenFlow.isLimit("Here is your video"))
        assertTrue(VideoGenFlow.isReady("Your video is ready!"))
        assertTrue(VideoGenFlow.busy(com.dk.assistant.agent.core.ScreenObservation(2, "x", listOf(making))))
    }

    // ---------- कोई भी वेबसाइट ----------
    private val box = UiElement(0, "", "Describe your video", cls = "EditText", clickable = true, editable = true, bounds = Box(40, 600, 1040, 800))
    private val gen = UiElement(1, "Generate", "", cls = "Button", clickable = true, bounds = Box(700, 850, 1040, 950))

    @Test fun webAction_parse() {
        assertEquals("tap", WebAgentCore.parse("""{"action":"click","id":3}""")?.kind)
        assertEquals(3, WebAgentCore.parse("""```json {"action":"tap","id":3} ```""")?.id)
        assertEquals("done", WebAgentCore.parse("""{"action":"finished"}""")?.kind)
        assertNull(WebAgentCore.parse("no json"))
        assertNull(WebAgentCore.parse("""{"action":"pay"}"""))
        val o = com.dk.assistant.agent.core.ScreenObservation(1, "com.android.chrome", listOf(box, gen))
        val ask = WebAgentCore.stepAsk("make video", o, emptyList(), 0, 10, withShot = true)
        assertTrue(ask.contains("[1] Button* 'generate'") && ask.contains("INPUT") && ask.contains("Never buy"))
    }

    @Test fun webRun_typesGeneratesWaitsAndFindsDownload() = runBlocking {
        val d = FakeScreenDriver("com.android.chrome", mapOf("site" to listOf(box, gen)), "site")
        val answers = ArrayDeque(listOf(
            """{"action":"type","id":0,"text":"PROMPT 1"}""",
            """{"action":"tap","id":1,"why":"generate"}""",
            """{"action":"wait","seconds":20}""",
            """{"action":"done"}""",
        ))
        var clock = 0L
        val r = WebVideoRun(d, think = { answers.removeFirstOrNull() }, newVideo = { _ -> null }, now = { clock += 1_000; clock }).run("goal")
        // done कहा पर फ़ोन में फ़ाइल नहीं = सच: "पक्का नहीं"
        assertTrue(r.ok); assertEquals(ImageGenFlow.UNVERIFIED, r.file)
        assertEquals(listOf("PROMPT 1"), d.typed)
        assertTrue(d.clicks.contains("generate"))
    }

    @Test fun webRun_fileAppears_isDone() = runBlocking {
        val d = FakeScreenDriver("com.android.chrome", mapOf("site" to listOf(box, gen)), "site")
        var n = 0
        val r = WebVideoRun(d, think = { n++; """{"action":"wait","seconds":10}""" }, newVideo = { _ -> if (n >= 3) "content://v/1" else null },
            now = { 0L }).run("goal")
        assertTrue(r.ok); assertEquals("content://v/1", r.file)
    }

    @Test fun webRun_loginStopsForUser() = runBlocking {
        val d = FakeScreenDriver("com.android.chrome", mapOf("site" to listOf(box, gen)), "site")
        val r = WebVideoRun(d, think = { """{"action":"login","why":"sign in page"}""" }, newVideo = { _ -> null }, now = { 0L }).run("goal")
        assertFalse(r.ok); assertTrue(r.needUser)
        val lim = WebVideoRun(d, think = { """{"action":"fail","why":"LIMIT: no credits left"}""" }, newVideo = { _ -> null }, now = { 0L }).run("goal")
        assertTrue(lim.limit); assertEquals("no credits left", lim.msg)
    }

    @Test fun webRun_brainSilent_givesUp() = runBlocking {
        val d = FakeScreenDriver("com.android.chrome", mapOf("site" to listOf(box, gen)), "site")
        val r = WebVideoRun(d, think = { null }, newVideo = { _ -> null }, now = { 0L }).run("goal")
        assertFalse(r.ok); assertNotNull(r.msg)
    }

    // ---------- DK लाइव से जुड़ा ----------
    @Test fun wiredIntoLive() {
        assertTrue(src("GeminiLiveTools.kt").contains("\"video_task\" -> com.dk.assistant.agent.android.VideoStudioAgent.start(svc, args)"))
        assertTrue(src("GeminiLive.kt").contains("VideoStudioAgent.liveNote(job)"))
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains("put(\"name\", \"video_task\")"))
        val first = "Make a long 1-3 min video (2D/3D/emotional) part by part in Gemini or any video website."
        assertTrue(setup.contains(first))
        assertTrue(LiveToolSlim.firstSentence(first) == first)          // router की लाइन कटे नहीं
        assertTrue("video_task" in com.dk.assistant.LeakedCall.TOOLS)
        assertTrue(com.dk.assistant.TaskStatus.ON_SCREEN_KEYS.contains("video"))
        val agent = src("agent/android/VideoStudioAgent.kt")
        assertTrue(agent.contains("svc.scope.launch"))                   // पीछे चलता है - DK लाइव बात करता रहे
        assertTrue(agent.contains("VideoJoin.join"))
    }
}
