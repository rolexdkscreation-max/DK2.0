package com.dk.assistant.dkai

import com.dk.assistant.Mode
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** DKAI: 3 हालत, आवाज़ें, गति, बैज, पिल, टर्मिनल रास्ते, GitHub बदलाव - सब असली नियम */
class DkaiCoreTest {
    private val I = VoiceSessionState.INACTIVE
    private val F = VoiceSessionState.ACTIVE_FULL
    private val M = VoiceSessionState.ACTIVE_MINIMIZED

    @Test fun stateIds() {
        assertEquals("inactive", I.id); assertEquals("active_full", F.id); assertEquals("active_minimized", M.id)
        assertFalse(I.active); assertTrue(F.active); assertTrue(M.active)
    }

    @Test fun transitions() {
        assertEquals(F, DkaiCore.next(I, VoiceEvent.MIC_TAP))
        assertEquals(M, DkaiCore.next(F, VoiceEvent.MINIMIZE))
        assertEquals(F, DkaiCore.next(M, VoiceEvent.EXPAND))
        assertEquals(I, DkaiCore.next(F, VoiceEvent.CLOSE))
        assertEquals(I, DkaiCore.next(M, VoiceEvent.CLOSE))
        assertEquals(I, DkaiCore.next(M, VoiceEvent.LIVE_ENDED))
        // Live बाहर से चालू (notification / वेक-वर्ड) = छोटी पट्टी, पूरी स्क्रीन नहीं छीनी
        assertEquals(M, DkaiCore.next(I, VoiceEvent.LIVE_STARTED))
        assertEquals(F, DkaiCore.next(F, VoiceEvent.LIVE_STARTED))
        // गलत हालत में बटन = कुछ नहीं बदलता
        assertEquals(I, DkaiCore.next(I, VoiceEvent.MINIMIZE))
        assertEquals(I, DkaiCore.next(I, VoiceEvent.EXPAND))
        assertEquals(M, DkaiCore.next(M, VoiceEvent.MINIMIZE))
        // back: पूरी स्क्रीन -> छोटी (बातचीत चलती रहे)
        assertEquals(M, DkaiCore.next(F, VoiceEvent.BACK))
        assertTrue(DkaiCore.handlesBack(F)); assertFalse(DkaiCore.handlesBack(M)); assertFalse(DkaiCore.handlesBack(I))
    }

    @Test fun modes() {
        assertEquals(listOf("Chat", "Builder", "Agent", "Pipeline"), DkaiMode.CARDS.map { it.label })
        assertEquals(listOf("Chat", "Builder", "Agent", "Pipeline", "Broadcast"), DkaiMode.VOICE_TABS.map { it.label })
        assertEquals(listOf("Home", "Files", "Editor", "Terminal", "Preview", "GitHub"), DkaiTab.values().map { it.label })
    }

    @Test fun voices() {
        assertEquals(listOf("Emma", "Swara", "Ananya", "Aarav", "Rohan"), DkaiCore.VOICES.map { it.name })
        assertEquals("Emma", DkaiCore.voiceFor("Sulafat", "female").name)
        assertEquals("Aarav", DkaiCore.voiceFor("Puck", "male").name)
        assertEquals("Rohan", DkaiCore.voiceFor("charon", null).name)
        assertEquals("Emma", DkaiCore.voiceFor("Zephyr", "female").name)
        assertEquals("Aarav", DkaiCore.voiceFor(null, "male").name)
        assertEquals(2, DkaiCore.VOICES.count { !it.female })
    }

    @Test fun speeds() {
        assertEquals(listOf("1.0x", "1.2x", "1.3x", "1.5x", "2.0x"), DkaiCore.SPEEDS.map { DkaiCore.speedLabel(it) })
        assertEquals(120, DkaiCore.nearestSpeed(115))
        assertEquals(200, DkaiCore.nearestSpeed(260))
        assertEquals(100, DkaiCore.nearestSpeed(70))
    }

    @Test fun status() {
        assertEquals(DkaiCore.Status.LISTENING, DkaiCore.voiceStatus(Mode.LISTENING, live = true, muted = false, work = false, connected = true))
        assertEquals(DkaiCore.Status.SPEAKING, DkaiCore.voiceStatus(Mode.SPEAKING, true, false, false, true))
        assertEquals(DkaiCore.Status.MUTED, DkaiCore.voiceStatus(Mode.LISTENING, true, true, false, true))
        assertEquals(DkaiCore.Status.CONNECTING, DkaiCore.voiceStatus(Mode.IDLE, true, false, false, false))
        assertEquals(DkaiCore.Status.OFFLINE, DkaiCore.voiceStatus(Mode.IDLE, false, false, false, false))
        assertEquals(DkaiCore.Status.THINKING, DkaiCore.voiceStatus(Mode.WORKING, true, false, true, true))
        assertEquals(DkaiCore.Status.READY, DkaiCore.homeStatus(true, Mode.IDLE, false, false))
        assertEquals(DkaiCore.Status.SETUP, DkaiCore.homeStatus(false, Mode.IDLE, false, false))
        assertEquals("READY", DkaiCore.Status.READY.label)
    }

    @Test fun pillAndText() {
        assertEquals("Gemini Flash", DkaiCore.modelPill("Google Gemini", "gemini-2.5-flash"))
        assertEquals("Gemini Flash-Lite", DkaiCore.modelPill("Gemini", "gemini-3.5-flash-lite"))
        assertEquals("Gemini Pro", DkaiCore.modelPill("Google", "models/gemini-3.1-pro-preview"))
        assertEquals("Hello, how can I...", DkaiCore.preview(DkaiCore.HELLO))
        assertEquals("ठीक है", DkaiCore.preview("  ठीक   है "))
        assertEquals("Chat 1", DkaiCore.chatLabel(0)); assertEquals("Chat 7", DkaiCore.chatLabel(7))
        assertTrue(DkaiCore.voiceModeNote(DkaiMode.BUILDER).contains("app_studio"))
    }

    @Test fun names() {
        assertTrue(DkaiCore.validName("index.html"))
        assertFalse(DkaiCore.validName("")); assertFalse(DkaiCore.validName(".."))
        assertFalse(DkaiCore.validName("a/b")); assertFalse(DkaiCore.validName("a\nb"))
        assertTrue(DkaiCore.isTextName("main.kt")); assertTrue(DkaiCore.isTextName("Makefile"))
        assertFalse(DkaiCore.isTextName("photo.png"))
        assertTrue(DkaiCore.isHtml("A.HTM")); assertFalse(DkaiCore.isHtml("a.js"))
        assertFalse(DkaiCore.looksText(byteArrayOf(1, 0, 2))); assertTrue(DkaiCore.looksText("abc".toByteArray()))
    }

    @Test fun terminalPaths() {
        val root = Files.createTempDirectory("dkai").toFile().canonicalFile
        val sub = File(root, "web").apply { mkdirs() }
        assertNull(DkaiCore.cd("ls -la", root, root))
        assertEquals(sub, DkaiCore.cd("cd web", root, root))
        assertEquals(root, DkaiCore.cd("cd ..", sub, root))
        assertEquals(root, DkaiCore.cd("cd", sub, root))
        assertEquals(sub, DkaiCore.cd("cd ~/web", root, root))
        assertFalse(DkaiCore.inside(root, DkaiCore.cd("cd ..", root, root)!!))
        assertTrue(DkaiCore.inside(root, File(sub, "x.txt")))
        assertEquals("~", DkaiCore.prompt(root, root)); assertEquals("~/web", DkaiCore.prompt(sub, root))
        assertEquals(0, DkaiCore.historyAt(listOf("a", "b"), 0, -1)); assertEquals(2, DkaiCore.historyAt(listOf("a", "b"), 1, 1))
        root.deleteRecursively()
    }

    @Test fun gitChanges() {
        val base = mapOf("a.txt" to DkaiCore.sha("one"), "b.txt" to DkaiCore.sha("two"), "c.txt" to DkaiCore.sha("x"))
        val now = mapOf("a.txt" to "one", "b.txt" to "TWO", "d.txt" to "new")
        val ch = DkaiCore.changes(base, now)
        assertEquals(setOf("b.txt", "d.txt", "c.txt"), ch.keys)
        assertEquals("TWO", ch["b.txt"]); assertEquals("new", ch["d.txt"]); assertNull(ch["c.txt"])
        assertTrue(DkaiCore.changes(base, mapOf("a.txt" to "one", "b.txt" to "two", "c.txt" to "x")).isEmpty())
        assertEquals("dkai/20260101-000000", DkaiCore.branchName(1767225600000L))
    }
}
