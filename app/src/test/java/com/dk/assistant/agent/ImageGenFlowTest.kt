package com.dk.assistant.agent

import com.dk.assistant.agent.core.Blocker
import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ImageApp
import com.dk.assistant.agent.core.ImageGenFlow
import com.dk.assistant.agent.core.ImageGenHost
import com.dk.assistant.agent.core.UiElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.152: DK लाइव - ChatGPT / Gemini ऐप में इमेज बनाना + डाउनलोड (नकली फ़ोन पर पूरा रास्ता) */
class ImageGenFlowTest {
    private class FakeHost(val apps: Set<String>) : ImageGenHost {
        var opened = mutableListOf<String>()
        var saved: String? = null
        var longPresses = 0
        override fun installed(pkg: String) = pkg in apps
        override suspend fun openApp(pkg: String): Boolean { opened += pkg; return true }
        override suspend fun longPress(x: Int, y: Int): Boolean { longPresses++; return true }
        override suspend fun newestImageSince(startMs: Long): String? = saved
        override fun now() = 1_000L
    }

    private val edit = UiElement(0, "", "Message", cls = "EditText", clickable = true, editable = true, bounds = Box(40, 2150, 900, 2260))
    private val send = UiElement(1, "", "Send message", cls = "Button", clickable = true, bounds = Box(920, 2150, 1040, 2260))
    private val newChat = UiElement(2, "", "New chat", cls = "Button", clickable = true, bounds = Box(950, 80, 1040, 170))
    private val stop = UiElement(1, "", "Stop generating", cls = "Button", clickable = true, bounds = Box(920, 2150, 1040, 2260))
    private val creating = UiElement(3, "Creating image", cls = "TextView", bounds = Box(40, 900, 700, 960))
    private val image = UiElement(3, "", "", cls = "ImageView", clickable = true, bounds = Box(40, 700, 1040, 1700))
    private val download = UiElement(1, "", "Download", cls = "Button", clickable = true, bounds = Box(900, 100, 1040, 200))

    private fun chatgpt(busyCaptures: Int = 3): Pair<FakeScreenDriver, FakeHost> {
        val d = FakeScreenDriver(ImageApp.CHATGPT.pkg, mapOf(
            "chat" to listOf(edit, send, newChat),
            "busy" to listOf(edit, stop, creating),
            "done" to listOf(edit, send, image),
            "viewer" to listOf(image, download),
        ), "chat")
        val h = FakeHost(setOf(ImageApp.CHATGPT.pkg, ImageApp.GEMINI.pkg))
        d.onClick["chat" to "send message"] = "busy"
        d.onClick["done" to ""] = "viewer"
        var busyLeft = busyCaptures
        d.onCapture = { if (it.current == "busy" && busyLeft-- <= 0) it.current = "done" }
        d.onClick["viewer" to "download"] = "viewer"
        return d to h
    }

    @Test fun chatgpt_generatesDownloads() = runBlocking {
        val (d, h) = chatgpt()
        val prev = d.onCapture
        d.onCapture = { prev(it); if (d.clicks.lastOrNull() == "download") h.saved = "IMG_123.png" }
        val r = ImageGenFlow(d, h, pollMs = 10).run(ImageApp.CHATGPT, "a red sports car on a mountain road")
        assertTrue(r.error, r.ok)
        assertEquals(ImageApp.CHATGPT, r.app)
        assertEquals(listOf(ImageApp.CHATGPT.pkg), h.opened)
        assertEquals("Create an image: a red sports car on a mountain road", d.typed.single())
        assertTrue("new chat पहले", d.clicks.first() == "new chat")
        assertTrue(d.clicks.contains("send message"))
        assertTrue(r.downloaded)
        assertEquals("IMG_123.png", r.file)
    }

    @Test fun downloadPressedButNotConfirmed() = runBlocking {
        val (d, h) = chatgpt()
        val r = ImageGenFlow(d, h, pollMs = 10).run(ImageApp.CHATGPT, "a cat")
        assertTrue(r.ok); assertFalse(r.downloaded); assertTrue(r.pressedSave); assertNull(r.file)
    }

    @Test fun noDownloadWhenNotAsked() = runBlocking {
        val (d, h) = chatgpt()
        val r = ImageGenFlow(d, h, pollMs = 10).run(ImageApp.CHATGPT, "a cat", download = false)
        assertTrue(r.ok); assertFalse(d.clicks.contains("download"))
    }

    @Test fun fallsBackToInstalledApp() = runBlocking {
        val (d, _) = chatgpt()
        val h = FakeHost(setOf(ImageApp.GEMINI.pkg))
        d.pkg = ImageApp.GEMINI.pkg
        ImageGenFlow(d, h, pollMs = 10).run(ImageApp.CHATGPT, "a cat", download = false)
        assertEquals(listOf(ImageApp.GEMINI.pkg), h.opened)
    }

    @Test fun noAppInstalled() = runBlocking {
        val (d, _) = chatgpt()
        val r = ImageGenFlow(d, FakeHost(emptySet())).run(null, "a cat")
        assertFalse(r.ok); assertTrue(r.error.contains("ChatGPT") && r.error.contains("Gemini"))
    }

    @Test fun appRefusesOrAsks_noImage_tellsReply() = runBlocking {
        val reply = UiElement(4, "I can't create images of real people. Want a cartoon instead?", cls = "TextView", bounds = Box(40, 900, 1000, 1100))
        val d = FakeScreenDriver(ImageApp.CHATGPT.pkg, mapOf("chat" to listOf(edit, send), "reply" to listOf(edit, send, reply)), "chat")
        d.onClick["chat" to "send message"] = "reply"
        val r = ImageGenFlow(d, FakeHost(setOf(ImageApp.CHATGPT.pkg)), pollMs = 2_000).run(ImageApp.CHATGPT, "photo of a famous actor")
        assertFalse(r.ok)
        assertTrue(r.appReply.contains("cartoon"))
    }

    @Test fun loginScreen_stopsForUser() = runBlocking {
        val pw = UiElement(0, "", "Password", cls = "EditText", clickable = true, editable = true, bounds = Box(40, 900, 1000, 1000))
        val login = UiElement(1, "Log in", cls = "Button", clickable = true, bounds = Box(40, 1100, 1000, 1200))
        val d = FakeScreenDriver(ImageApp.CHATGPT.pkg, mapOf("login" to listOf(pw, login)), "login")
        val r = ImageGenFlow(d, FakeHost(setOf(ImageApp.CHATGPT.pkg))).run(ImageApp.CHATGPT, "a cat")
        assertFalse(r.ok); assertEquals(Blocker.LOGIN, r.blocker)
        assertTrue(d.typed.isEmpty())
    }

    @Test fun parseAndPrompt() {
        assertEquals(ImageApp.CHATGPT, ImageApp.parse("ChatGPT"))
        assertEquals(ImageApp.CHATGPT, ImageApp.parse("चैट जीपीटी"))
        assertEquals(ImageApp.GEMINI, ImageApp.parse("gemini"))
        assertNull(ImageApp.parse("auto"))
        assertEquals("Generate an image of a tiger", ImageGenFlow.promptFor(ImageApp.GEMINI, "Generate an image of a tiger"))
        assertEquals("Create an image: शेर जंगल में", ImageGenFlow.promptFor(ImageApp.GEMINI, "शेर जंगल में।"))
    }
}
