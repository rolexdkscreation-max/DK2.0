package com.dk.assistant.dkai

import com.dk.assistant.InvokeSession
import com.dk.assistant.LiveChime
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 14 नवंबर 2026 (मालिक): Live जल्दी कटना, Connecting लिखा, कटने की आवाज़, Chat 1 से, Broadcast होम पर
 * (किरदार की अलग आवाज़ + अंदाज़), चुने टैब में ही काम, बड़े काम से पहले "हाँ", GitHub Clone.
 */
class DkaiLiveModeTest {
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").let { if (it.exists()) it else File("app/src/main/java/com/dk/assistant/$p") }.readText()

    // ---------------------------------------------------------------- Live जल्दी नहीं कटे
    @Test fun micPressClearsShortIdleTimer() {
        assertTrue(InvokeSession.keepOnMic(true, false, 0L))
        assertTrue(InvokeSession.keepOnMic(false, true, 0L))
        assertTrue(InvokeSession.keepOnMic(false, false, 30_000L))
        assertFalse(InvokeSession.keepOnMic(false, false, 0L))
        assertTrue(src("dkai/DkaiScreen.kt").contains("svc.micPressed(\"DKAI माइक\")"))
    }

    @Test fun disconnectSoundOnlyOnRealDrop() {
        val t = 100_000L
        assertTrue(LiveChime.dropDue(seamless = false, enabled = true, inCall = false, lastAt = 0L, now = t))
        assertFalse(LiveChime.dropDue(seamless = true, enabled = true, inCall = false, lastAt = 0L, now = t))      // चुपचाप दोबारा जुड़ना
        assertFalse(LiveChime.dropDue(seamless = false, enabled = false, inCall = false, lastAt = 0L, now = t))
        assertFalse(LiveChime.dropDue(seamless = false, enabled = true, inCall = true, lastAt = 0L, now = t))      // फ़ोन कॉल पर नहीं
        assertFalse(LiveChime.dropDue(seamless = false, enabled = true, inCall = false, lastAt = t - 1_000, now = t))
    }

    @Test fun connectingNotProcessing() {
        assertEquals("CONNECTING", DkaiCore.callBadge(DkaiCore.Status.CONNECTING))
        assertEquals("Connecting…", DkaiCore.connectingOr(DkaiCore.Status.CONNECTING, DkaiCore.CallText("x", "y", false)).title)
        assertEquals("x", DkaiCore.connectingOr(DkaiCore.Status.LISTENING, DkaiCore.CallText("x", "y", false)).title)
    }

    // ---------------------------------------------------------------- चैट
    @Test fun chatsRenumberFromOne() {
        assertEquals(mapOf(3 to 1, 7 to 2, 12 to 3), DkaiChats.renumberPlan(listOf(12, 3, 7, 7, 0)))
        assertEquals("Chat 1", DkaiCore.chatLabel(1))
        assertFalse(DkaiCore.chatLabel(4).contains("#"))
    }

    // ---------------------------------------------------------------- Broadcast: किरदार + अंदाज़
    @Test fun scriptKeepsTone() {
        val l = DkaiKit.parseScript("Narrator (female, calm storytelling): एक गाँव था।\nRamu (male) [scared]: कौन है वहाँ?\nSita (female): (धीरे से) मैं हूँ।")
        assertEquals(3, l.size)
        assertEquals("calm storytelling", l[0].tone)
        assertTrue(l[0].female)
        assertEquals("scared", l[1].tone); assertFalse(l[1].female)
        assertEquals("धीरे से", l[2].tone); assertEquals("मैं हूँ।", l[2].text)
        // सेव -> फिर पढ़ो = वही
        val back = DkaiKit.parseScript(DkaiCast.scriptText(l))
        assertEquals(l.map { Triple(it.speaker, it.female, it.tone) }, back.map { Triple(it.speaker, it.female, it.tone) })
        assertEquals(l.map { it.text }, back.map { it.text })
    }

    @Test fun everyCharacterOwnVoice() {
        val names = listOf("Narrator" to true, "Ramu" to false, "Sita" to true, "Raja" to false, "Rani" to true, "Mantri" to false)
        val lines = names.map { (n, f) -> DkaiKit.Line(n, f, "नमस्ते") }
        val cast = DkaiKit.studioCast(lines, DkaiKit.ALL_VOICES)
        assertEquals(names.map { it.first }, cast.keys.toList())
        assertEquals("हर किरदार की अलग आवाज़", 6, cast.values.map { it.api }.distinct().size)
        for ((n, f) in names) assertEquals(n, f, cast[n]!!.female)
        // अगले भाग में वही आवाज़ (keep)
        val keep = mapOf("Ramu" to cast["Ramu"]!!)
        val next = DkaiKit.studioCast(listOf(DkaiKit.Line("Mantri", false, "हाँ"), DkaiKit.Line("Ramu", false, "जी")), DkaiKit.ALL_VOICES, keep)
        assertEquals(cast["Ramu"], next["Ramu"])
        assertNotEquals(next["Ramu"]!!.api, next["Mantri"]!!.api)
        // एक आवाज़ चुनी = सब उसी में
        val one = DkaiKit.studioCast(lines, "Rohan")
        assertEquals(1, one.values.map { it.name }.distinct().size)
    }

    @Test fun toneGoesToTts() {
        val seg = listOf(DkaiKit.Line("A", true, "x", "excited"), DkaiKit.Line("B", false, "y"))
        val st = DkaiKit.toneStyle("warm storytelling", seg)
        assertTrue(st.startsWith("warm storytelling"))
        assertTrue(st.contains("line 1 (A) excited"))
        assertEquals("warm storytelling", DkaiKit.toneStyle("warm storytelling", listOf(DkaiKit.Line("B", false, "y"))))
        assertEquals("calm, sad", DkaiKit.lineStyle("calm", DkaiKit.Line("A", true, "x", "sad")))
    }

    @Test fun formatFromTopic() {
        assertEquals("Story", DkaiKit.formatFor("एक भूत की कहानी सुनाओ"))
        assertEquals("Story", DkaiKit.formatFor("write a story about a lion"))
        assertEquals("News Bulletin", DkaiKit.formatFor("आज की खबर"))
        assertEquals("Speech", DkaiKit.formatFor("स्कूल के लिए भाषण"))
        assertEquals("Lecture", DkaiKit.formatFor("photosynthesis पर लेक्चर"))
        assertEquals("Podcast", DkaiKit.formatFor("AI का भविष्य"))
    }

    @Test fun partialScriptShowsTail() {
        val (done, tail) = DkaiCast.partial("Narrator (female): पहली लाइन\nRamu (male): आधी ला")
        assertEquals(1, done.size); assertEquals("Ramu (male): आधी ला", tail)
        assertTrue(DkaiCast.partial("बस शुरू").first.isEmpty())
    }

    @Test fun broadcastFilesDeleteTogether() {
        val dir = createTempDir("bcast")
        try {
            File(dir, "broadcast_3.wav").writeText("w"); File(dir, "broadcast_3.json").writeText("j")
            File(dir, "share").mkdirs(); File(dir, "share/broadcast_3_x100.m4a").writeText("m"); File(dir, "share/broadcast_30_x100.m4a").writeText("other")
            File(dir, "broadcast_4.wav").writeText("w")
            assertEquals(3, DkaiCast.delete(dir, 3))
            assertFalse(File(dir, "broadcast_3.wav").exists())
            assertTrue("दूसरा Broadcast बचा रहे", File(dir, "broadcast_4.wav").exists())
            assertTrue(File(dir, "share/broadcast_30_x100.m4a").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun broadcastModeBuildsOnHome() {
        val sc = src("dkai/DkaiScreen.kt")
        assertTrue(sc.contains("DkaiMode.BROADCAST -> cast.start(t)"))
        assertTrue(src("dkai/DkaiCastCard.kt").contains("quickStream"))       // लाइव लिखना
        assertTrue(src("dkai/DkaiCastCard.kt").contains("v.play()"))          // आवाज़ अपने-आप
    }

    // ---------------------------------------------------------------- चुने टैब में ही काम
    @Test fun liveFollowsSelectedTab() {
        assertEquals(DkaiCore.Route.Pass, DkaiCore.routeFor(null, "story_task"))
        assertEquals(DkaiCore.Route.Run("cast"), DkaiCore.routeFor(DkaiMode.BROADCAST, "story_task"))
        assertTrue(DkaiCore.routeFor(DkaiMode.BROADCAST, "coding_task") is DkaiCore.Route.Ask)
        assertEquals(DkaiCore.Route.Run("pipeline"), DkaiCore.routeFor(DkaiMode.PIPELINE, "build_website"))
        assertEquals(DkaiCore.Route.Ask(DkaiMode.BROADCAST), DkaiCore.routeFor(DkaiMode.PIPELINE, "story_task"))
        assertEquals(DkaiCore.Route.Run("builder"), DkaiCore.routeFor(DkaiMode.BUILDER, "make_game"))
        assertEquals(DkaiCore.Route.Run("agent"), DkaiCore.routeFor(DkaiMode.AGENT, "story_task"))
        assertEquals(DkaiCore.Route.Run("chat"), DkaiCore.routeFor(DkaiMode.CHAT, "story_task"))
        assertEquals(DkaiCore.Route.Pass, DkaiCore.routeFor(DkaiMode.BROADCAST, "web_search"))     // खोज / ऐप खोलो पर रोक नहीं
        assertTrue(DkaiCore.voiceModeNote(DkaiMode.BROADCAST).contains("story_task"))
        assertTrue(src("dkai/DkaiVoice.kt").contains("voiceTab = s.mode"))
    }

    // ---------------------------------------------------------------- बड़े काम से पहले हाँ
    @Test fun bigWorkNeedsOwnYes() {
        assertTrue(LiveWorkGate.needs("story_task", "", "भूत की कहानी"))
        assertTrue(LiveWorkGate.needs("coding_task", "", "login page बनाओ"))
        assertFalse(LiveWorkGate.needs("coding_task", "", "हाँ"))             // चल रहे काम का जवाब
        assertFalse(LiveWorkGate.needs("computer_task", "", "हाँ कर दो"))
        assertTrue(LiveWorkGate.needs("coding_task", "", "website बनाओ"))
        assertFalse(LiveWorkGate.needs("app_studio", "status", ""))
        assertTrue(LiveWorkGate.needs("app_studio", "build", "todo ऐप"))
        assertFalse(LiveWorkGate.needs("phone_task", "", "YouTube खोलो"))      // छोटा काम तुरंत
        val t0 = 1_000_000L
        assertEquals(LiveWorkGate.Gate.Ask, LiveWorkGate.check("story_task", null, t0, "हाँ", t0))
        val p = LiveWorkGate.Pending("story_task", "x", t0)
        assertEquals(LiveWorkGate.Gate.NotHeard, LiveWorkGate.check("story_task", p, t0 - 5, "हाँ", t0 + 1_000))    // हाँ पूछने से पहले की
        assertEquals(LiveWorkGate.Gate.NotHeard, LiveWorkGate.check("story_task", p, t0 + 500, "नहीं", t0 + 1_000))
        assertEquals(LiveWorkGate.Gate.Run, LiveWorkGate.check("story_task", p, t0 + 500, "हाँ बनाओ", t0 + 1_000))
        assertEquals(LiveWorkGate.Gate.Ask, LiveWorkGate.check("coding_task", p, t0 + 500, "हाँ", t0 + 1_000))     // दूसरा काम
        assertEquals(LiveWorkGate.Gate.Ask, LiveWorkGate.check("story_task", p, t0 + 500, "हाँ", t0 + LiveWorkGate.WINDOW_MS + 1))
        assertTrue(src("GeminiLiveTools.kt").contains("workGate(name, args)"))
        assertTrue(src("AgentRunner.kt").contains("confirmVoice("))
    }

    @Test fun terminalAsksForAnythingThatChanges() {
        assertTrue(DkaiTerm.readOnly("ls -la"))
        assertTrue(DkaiTerm.readOnly("cat a.txt | grep x"))
        assertTrue(DkaiTerm.readOnly("git status"))
        assertFalse(DkaiTerm.readOnly("git clone https://github.com/a/b"))
        assertFalse(DkaiTerm.readOnly("mkdir x"))
        assertFalse(DkaiTerm.readOnly("ls; touch y"))
        assertFalse(DkaiTerm.readOnly("echo hi > a.txt"))
        assertFalse(DkaiTerm.readOnly("find . -delete"))
        assertEquals(DkaiTerm.Gate.Run, DkaiTerm.liveGate("ls -la", false, null, 0L, "", 1L))
        assertEquals(DkaiTerm.Gate.Ask, DkaiTerm.liveGate("npm install", false, null, 0L, "", 1L))
    }

    // ---------------------------------------------------------------- GitHub Clone (codeload zip की ऊपर वाली परत हटे)
    @Test fun cloneZipStripsTopFolder() {
        val tmp = createTempDir("clone")
        try {
            val zip = File(tmp, "r.zip")
            ZipOutputStream(zip.outputStream()).use { z ->
                for ((n, t) in listOf("DK2.0-main/" to null, "DK2.0-main/README.md" to "hi", "DK2.0-main/app/a.kt" to "x")) {
                    z.putNextEntry(ZipEntry(n)); if (t != null) z.write(t.toByteArray()); z.closeEntry()
                }
            }
            val dest = File(tmp, "out")
            assertEquals(2, DkaiZip.unzip(zip, dest))
            assertEquals("hi", File(dest, "README.md").readText())
            assertTrue(File(dest, "app/a.kt").isFile)
            assertTrue(src("dkai/DkaiPanels.kt").contains("Clone (URL से)"))
        } finally { tmp.deleteRecursively() }
    }

    @Test fun creatorAsksBeforeLeavingApp() {
        val d = src("dkai/DkaiDevTools.kt")
        assertTrue(d.contains("GitHub खोलें?"))
        assertTrue(d.contains("DkaiModals.clone(s, COMMUNITY)"))
        assertTrue(src("dkai/DkaiSettings.kt").contains("s.devTools()"))   // Dev Tools अब सेटिंग्स के अंदर
    }
}
