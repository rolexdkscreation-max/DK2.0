package com.dk.assistant.agent

import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ImageApp
import com.dk.assistant.agent.core.ImageGenFlow
import com.dk.assistant.agent.core.ImageGenHost
import com.dk.assistant.agent.core.ImagePrompt
import com.dk.assistant.agent.core.UiElement
import com.dk.assistant.agent.core.VisionPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.153 (मालिक): "बटन नहीं मिला" नहीं चलेगा - नाम न हो तो जगह से, Enter से, और स्क्रीन देखकर दबाओ;
 * डाउनलोड भी ⋮ menu / long-press / स्क्रीन देखकर। इमेज धीरे बने तो भी रुको। prompt खुद पेशेवर बनाओ।
 */
class ImageGenSeeTest {
    private class Host(val d: FakeScreenDriver, val see: Boolean = false) : ImageGenHost {
        val looks = mutableListOf<String>()
        val points = mutableMapOf<String, Pair<Int, Int>>()
        var onEnter: (() -> Boolean)? = null
        var saved: String? = null
        override fun installed(pkg: String) = true
        override suspend fun openApp(pkg: String) = true
        override suspend fun longPress(x: Int, y: Int) = true
        override suspend fun newestImageSince(startMs: Long) = saved
        override fun now() = 0L
        override suspend fun imeEnter() = onEnter?.invoke() ?: false
        override fun canLook() = see
        override suspend fun look(goal: String): Pair<Int, Int>? { looks += goal; return points[goal] }
    }

    private val edit = UiElement(0, "", "", cls = "EditText", clickable = true, editable = true, bounds = Box(40, 2150, 860, 2260))
    private val voice = UiElement(1, "", "Voice mode", cls = "Button", clickable = true, bounds = Box(920, 2150, 1040, 2260))
    private val arrow = UiElement(1, "", "", cls = "View", clickable = true, bounds = Box(920, 2150, 1040, 2260))
    private val image = UiElement(3, "", "", cls = "ImageView", clickable = true, bounds = Box(40, 700, 1040, 1700))
    private val download = UiElement(1, "", "Download", cls = "Button", clickable = true, bounds = Box(900, 100, 1040, 200))

    /** भेजने के बाद: कुछ बार "बन रहा है" (या कुछ न दिखे), फिर तस्वीर */
    private fun phone(chat: List<UiElement>, extra: Map<String, List<UiElement>> = emptyMap(), waitScreen: List<UiElement> = listOf(edit, UiElement(5, "", "Stop generating", clickable = true, bounds = Box(920, 2150, 1040, 2260))), waitCaptures: Int = 2): FakeScreenDriver {
        val d = FakeScreenDriver(ImageApp.CHATGPT.pkg, mapOf("chat" to chat, "wait" to waitScreen, "done" to listOf(edit, voice, image), "viewer" to listOf(image, download)) + extra, "chat")
        var left = waitCaptures
        d.onCapture = { if (it.current == "wait" && left-- <= 0) it.current = "done" }
        d.onClick["done" to ""] = "viewer"
        return d
    }

    @Test fun composeUnlabeledArrow_afterTyping_isPressed() = runBlocking {
        val d = phone(listOf(edit, voice), mapOf("typed" to listOf(edit, arrow)))
        val prev = d.onCapture
        d.onCapture = { if (it.current == "chat" && it.typed.isNotEmpty()) it.current = "typed"; prev(it) }
        d.onClick["typed" to ""] = "wait"
        val r = ImageGenFlow(d, Host(d), pollMs = 10).run(ImageApp.CHATGPT, "a lion", download = false)
        assertTrue(r.error, r.ok)
        assertTrue(r.how, r.how.contains("send:new-button"))
        assertFalse("voice mode कभी नहीं", d.clicks.contains("voice mode"))
    }

    @Test fun labelOnIconChild_notClickable_stillWorks() = runBlocking {
        val icon = UiElement(2, "", "Send", cls = "ImageView", clickable = false, bounds = Box(940, 2170, 1020, 2240))
        val d = phone(listOf(edit, icon))
        d.onClick["chat" to "send"] = "wait"
        val r = ImageGenFlow(d, Host(d), pollMs = 10).run(ImageApp.CHATGPT, "a lion", download = false)
        assertTrue(r.error, r.ok); assertTrue(r.how.contains("send:label"))
    }

    @Test fun keyboardEnter_whenNoButton() = runBlocking {
        val d = phone(listOf(edit))
        val h = Host(d); h.onEnter = { d.current = "wait"; true }
        val r = ImageGenFlow(d, h, pollMs = 10).run(ImageApp.CHATGPT, "a lion", download = false)
        assertTrue(r.error, r.ok); assertTrue(r.how.contains("send:enter"))
    }

    @Test fun looksAtScreen_toFindSend() = runBlocking {
        val hidden = UiElement(2, "", "", cls = "View", bounds = Box(920, 2150, 1040, 2260))
        val d = phone(listOf(edit, hidden))
        d.onClick["chat" to ""] = "wait"
        val h = Host(d, see = true); h.points[ImageGenFlow.LOOK_SEND] = 980 to 2205
        val r = ImageGenFlow(d, h, pollMs = 10).run(ImageApp.CHATGPT, "a lion", download = false)
        assertTrue(r.error, r.ok); assertTrue(r.how.contains("send:look"))
        assertEquals(ImageGenFlow.LOOK_SEND, h.looks.first())
    }

    @Test fun noWayToSend_tellsToTurnOnScreenSeeing_andDumpsScreen() = runBlocking {
        val d = phone(listOf(edit))
        val r = ImageGenFlow(d, Host(d), pollMs = 10).run(ImageApp.CHATGPT, "a lion")
        assertFalse(r.ok)
        assertTrue(r.error, r.error.contains("स्क्रीन देखना चालू करो"))
        assertTrue(r.screenDump.contains("EditText"))
    }

    @Test fun slowImage_withoutBusyText_waitsInsteadOfGivingUp() = runBlocking {
        val bubble = UiElement(6, "Create an image: a lion", cls = "TextView", bounds = Box(200, 400, 1040, 500))
        val d = phone(listOf(edit, voice, UiElement(7, "", "Send message", clickable = true, bounds = Box(800, 2150, 900, 2260))),
            waitScreen = listOf(edit, voice, bubble), waitCaptures = 25)          // ~50 सेकंड, कोई "बना रहा है" नहीं
        d.onClick["chat" to "send message"] = "wait"
        val r = ImageGenFlow(d, Host(d), pollMs = 2_000).run(ImageApp.CHATGPT, "a lion", download = false)
        assertTrue(r.error, r.ok)
    }

    @Test fun imageWithoutImageClass_foundByLooking() = runBlocking {
        val plain = UiElement(3, "", "", cls = "View", bounds = Box(40, 700, 1040, 1700))
        val send = UiElement(7, "", "Send message", clickable = true, bounds = Box(920, 2150, 1040, 2260))
        val d = FakeScreenDriver(ImageApp.CHATGPT.pkg, mapOf("chat" to listOf(edit, send), "done" to listOf(edit, send, plain)), "chat")
        d.onClick["chat" to "send message"] = "done"
        val h = Host(d, see = true); h.points[ImageGenFlow.LOOK_IMAGE] = 540 to 1200
        val r = ImageGenFlow(d, h, pollMs = 2_000).run(ImageApp.CHATGPT, "a lion", download = false)
        assertTrue(r.error, r.ok); assertTrue(r.how, r.how.contains("image:look"))
    }

    @Test fun download_viaMoreMenu() = runBlocking {
        val more = UiElement(1, "", "More options", cls = "Button", clickable = true, bounds = Box(960, 100, 1040, 200))
        val save = UiElement(2, "Save image", cls = "TextView", clickable = true, bounds = Box(600, 220, 1040, 320))
        val d = phone(listOf(edit, UiElement(7, "", "Send message", clickable = true, bounds = Box(920, 2150, 1040, 2260))),
            extra = mapOf("viewer2" to listOf(image, more), "menu" to listOf(image, save)))
        d.onClick["chat" to "send message"] = "wait"
        d.onClick["done" to ""] = "viewer2"
        d.onClick["viewer2" to "more options"] = "menu"
        val h = Host(d)
        val prev = d.onCapture
        d.onCapture = { prev(it); if (d.clicks.lastOrNull() == "save image") h.saved = "IMG_9.png" }
        val r = ImageGenFlow(d, h, pollMs = 10).run(ImageApp.CHATGPT, "a lion")
        assertTrue(r.error, r.ok); assertTrue(r.downloaded); assertEquals("IMG_9.png", r.file)
    }

    @Test fun download_byLooking_whenNoButtonAnywhere() = runBlocking {
        val icon = UiElement(4, "", "", cls = "View", bounds = Box(960, 100, 1040, 200))
        val d = phone(listOf(edit, UiElement(7, "", "Send message", clickable = true, bounds = Box(920, 2150, 1040, 2260))),
            extra = mapOf("viewer3" to listOf(image, icon)))
        d.onClick["chat" to "send message"] = "wait"
        d.onClick["done" to ""] = "viewer3"
        val h = Host(d, see = true); h.points[ImageGenFlow.LOOK_DOWNLOAD] = 1000 to 150
        val r = ImageGenFlow(d, h, pollMs = 10).run(ImageApp.CHATGPT, "a lion")
        assertTrue(r.error, r.ok); assertTrue(r.pressedSave); assertTrue(r.how.contains("download:look"))
    }

    // ---- पेशेवर prompt ----
    @Test fun prompt_kindsAndFallbackKeepUserWords() {
        assertEquals(ImagePrompt.Kind.LOGO, ImagePrompt.kind("DK Creation का लोगो बनाओ"))
        assertEquals(ImagePrompt.Kind.THUMBNAIL, ImagePrompt.kind("youtube thumbnail for my vlog"))
        assertEquals(ImagePrompt.Kind.WALLPAPER, ImagePrompt.kind("फोन के लिए वॉलपेपर"))
        assertEquals(ImagePrompt.Kind.GENERAL, ImagePrompt.kind("शेर जंगल में"))
        val f = ImagePrompt.fallback("DK Creation का लोगो, नीला और सुनहरा")
        assertTrue(f.startsWith("DK Creation का लोगो, नीला और सुनहरा"))
        assertTrue(f.contains("vector logo") && f.contains("Aspect ratio 1:1"))
    }

    @Test fun prompt_askHasRules_cleanStripsPreamble() {
        val a = ImagePrompt.ask("मेरे दुकान \"Rolex Mobile\" का पोस्टर")
        assertTrue(a.contains("Keep EVERY detail") && a.contains("Rolex Mobile"))
        assertTrue(a.contains("Aspect ratio 4:5"))
        val good = "A majestic lion standing on a rocky cliff at golden hour, cinematic wildlife photography, warm backlight, detailed mane, shallow depth of field. Aspect ratio 1:1."
        assertEquals(good, ImagePrompt.clean("Prompt: \"$good\"", "lion"))
        assertEquals(ImagePrompt.fallback("lion"), ImagePrompt.clean("ok", "lion"))
        assertEquals(ImagePrompt.fallback("lion"), ImagePrompt.clean(null, "lion"))
        assertTrue(ImagePrompt.isDetailed("A majestic lion standing proudly on a rocky cliff at golden hour, cinematic wildlife photography style, warm golden backlight, " +
            "dramatic sky in the background, detailed mane, shallow depth of field, rich warm colour palette, epic mood, ultra detailed"))
        assertFalse(ImagePrompt.isDetailed("a lion"))
    }

    @Test fun visionPoint_parse() {
        assertEquals(540 to 1200, VisionPoint.parse("{\"found\":true,\"point\":[500,500]}", 1080, 2400))
        assertEquals(540 to 1200, VisionPoint.parse("```json\n{\"box_2d\":[400,400,600,600]}\n```", 1080, 2400))
        assertNull(VisionPoint.parse("{\"found\":false}", 1080, 2400))
        assertNull(VisionPoint.parse("sorry", 1080, 2400))
        assertNull(VisionPoint.parse("{\"point\":[2000,5]}", 1080, 2400))
    }

    private fun src(name: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "src/main/java/com/dk/assistant/$name"), File(dir, "app/src/main/java/com/dk/assistant/$name"))
                .firstOrNull { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("$name नहीं मिला")
    }

    @Test fun wiring() {
        val a = src("agent/android/ImageGenAgent.kt")
        assertTrue(a.contains("craft(svc, user, given)"))
        assertTrue(a.contains("Prefs.screenshot(svc)"))           // स्क्रीन देखना सिर्फ़ मालिक की इजाज़त से
        assertTrue(a.contains("ACTION_IME_ENTER"))
        assertNotNull(Regex("\"user_words\"").find(src("GeminiLiveSetup.kt")))
        assertTrue(src("GeminiLiveSetup.kt").contains("never just copy his words"))
    }
}
