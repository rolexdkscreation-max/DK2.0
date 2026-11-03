package com.dk.assistant.agent

import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ImageApp
import com.dk.assistant.agent.core.ImageGenFlow
import com.dk.assistant.agent.core.ImageGenHost
import com.dk.assistant.agent.core.NodePick
import com.dk.assistant.agent.core.ShopAsk
import com.dk.assistant.agent.core.UiElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.156 मालिक: "ChatGPT में prompt लिख देता है पर भेज नहीं पाता / टैप नहीं कर पाता" +
 * "मुझे शर्ट / जींस / जूते / घड़ी देखनी है - किस ऐप पर दिखाऊँ?"
 */
class ChatSendShopTest {
    private class Host(val apps: Set<String>) : ImageGenHost {
        override fun installed(pkg: String) = pkg in apps
        override suspend fun openApp(pkg: String) = true
        override suspend fun longPress(x: Int, y: Int) = true
        override suspend fun newestImageSince(startMs: Long): String? = null
        override fun now() = 1_000L
    }

    private val edit = UiElement(0, "", "Message", cls = "EditText", clickable = true, editable = true, bounds = Box(40, 2150, 900, 2260))
    private val send = UiElement(1, "", "Send prompt", cls = "Button", clickable = true, bounds = Box(920, 2150, 1040, 2260))

    // ------------------------------------------------------------------ ChatGPT में सवाल भेजो + जवाब पढ़ो
    @Test fun ask_typesExactPrompt_sends_readsAnswer() = runBlocking {
        val q = "भारत की राजधानी क्या है"
        val mine = UiElement(5, q, cls = "TextView", bounds = Box(300, 400, 1040, 480))
        val ans = UiElement(6, "भारत की राजधानी नई दिल्ली है।", cls = "TextView", bounds = Box(40, 520, 1040, 640))
        val copy = UiElement(7, "", "Copy", cls = "Button", clickable = true, bounds = Box(40, 660, 120, 720))
        val d = FakeScreenDriver(ImageApp.CHATGPT.pkg, mapOf(
            "chat" to listOf(edit, send),
            "answer" to listOf(mine, ans, copy, edit, send),
        ), "chat")
        d.onClick["chat" to "send prompt"] = "answer"
        val r = ImageGenFlow(d, Host(setOf(ImageApp.CHATGPT.pkg)), pollMs = 10).ask(ImageApp.CHATGPT, q)
        assertTrue(r.error, r.ok)
        assertEquals(q, d.typed.single())                       // "Create an image:" नहीं - जो कहा वही
        assertTrue(d.clicks.contains("send prompt"))
        assertTrue(r.appReply, r.appReply.contains("नई दिल्ली"))
        assertFalse(r.appReply.contains(q))                      // अपना सवाल जवाब में नहीं
    }

    // ------------------------------------------------------------------ भेजें बटन न नाम से, न "click" वाला - फिर भी उँगली से टैप
    @Test fun unlabeledArrowNotClickable_edgeTapSends() = runBlocking {
        val arrow = UiElement(1, "", "", cls = "View", clickable = false, bounds = Box(905, 2150, 1040, 2260))
        val d = FakeScreenDriver(ImageApp.CHATGPT.pkg, mapOf("chat" to listOf(edit, arrow), "sent" to listOf(edit)), "chat")
        d.onClick["chat" to ""] = "sent"
        val r = ImageGenFlow(d, Host(setOf(ImageApp.CHATGPT.pkg)), pollMs = 10, maxWaitMs = 50).ask(ImageApp.CHATGPT, "hello there my friend")
        assertTrue(r.error, r.ok)
        assertTrue(d.gestureTaps > 0)
    }

    @Test fun edgeTapNeverOnVoiceButton() {
        val voice = UiElement(1, "", "Start voice mode", cls = "Button", clickable = true, bounds = Box(905, 2150, 1040, 2260))
        val o = com.dk.assistant.agent.core.ScreenObservation(1, ImageApp.CHATGPT.pkg, listOf(edit, voice))
        val pts = ImageGenFlow.blindSendPoints(o)
        assertTrue(pts.none { (x, y) -> x in 905..1040 && y in 2150..2260 })
    }

    // ------------------------------------------------------------------ स्क्रीन पढ़ना: लंबी chat में नीचे का बटन न छूटे
    @Test fun nodePick_keepsComposerAndSendInLongChat() {
        val h = 2400
        val msgs = (0 until 400).map { NodePick.Cand(editable = false, clickable = false, labeled = true, top = 200 + it, bottom = 240 + it) }
        val box = NodePick.Cand(editable = true, clickable = true, labeled = true, top = 2150, bottom = 2260)
        val sendC = NodePick.Cand(editable = false, clickable = true, labeled = false, top = 2150, bottom = 2260)
        val all = msgs + box + sendC
        val keep = NodePick.keep(all, 180, h)
        assertEquals(180, keep.size)
        assertTrue(400 in keep && 401 in keep)
        assertEquals(keep.sorted(), keep)                       // स्क्रीन वाला क्रम
        assertEquals((0 until 5).toList(), NodePick.keep(all.take(5), 180, h))
    }

    @Test fun screenReadersUseNodePick() {
        val drv = src("agent/android/AccessibilityScreenDriver.kt")
        assertTrue(drv.contains("NodePick.keep(") && drv.contains("svc.windows") && drv.contains("ACTION_PASTE"))
        assertFalse(drv.contains("list.size >= 220"))
        val acc = src("DKAccessibilityService.kt") + src("DKAccessibilityScreen.kt")
        assertTrue(acc.contains("NodePick.keep(cands, SCREEN_CAP"))
        assertFalse(acc.contains("out.size >= 140"))
    }

    @Test fun liveWiring() {
        val setup = src("GeminiLiveSetup.kt"); val tools = src("GeminiLiveTools.kt")
        assertTrue(setup.contains("\"ai_app_prompt\"") && setup.contains("\"shop_search\""))
        assertTrue(setup.contains("never tell the user that you cannot tap"))
        assertTrue(tools.contains("ImageGenAgent.ask(svc, args)") && tools.contains("ShopOpen.tool(svc, args)"))
        assertTrue(com.dk.assistant.LeakedCall.TOOLS.containsAll(listOf("ai_app_prompt", "shop_search")))
        assertTrue(src("agent/android/ImageGenAgent.kt").contains("Prefs.taskLook(svc)"))
    }

    // ------------------------------------------------------------------ shopping
    @Test fun shop_wishAndItem() {
        assertTrue(ShopAsk.isWish("मुझे एक शर्ट देखना है"))
        assertEquals("shirt", ShopAsk.item("मुझे एक शर्ट देखना है"))
        assertEquals("t-shirt", ShopAsk.item("टीशर्ट दिखाओ"))
        assertEquals("jeans", ShopAsk.item("मुझे जींस देखनी है"))
        assertEquals("shoes", ShopAsk.item("शूज दिखाओ"))
        assertEquals("watch", ShopAsk.item("मुझे वॉच देखना है"))
        assertEquals("lower track pants", ShopAsk.item("मुझे लॉयर देखना है"))
        assertEquals("black shirt for men", ShopAsk.item("लड़कों की काली शर्ट दिखाओ"))
        assertFalse(ShopAsk.isWish("आज मौसम कैसा है"))
        assertNull(ShopAsk.item("गाना चलाओ"))
    }

    @Test fun shop_installedAskAndPick() {
        val pk = setOf("com.flipkart.android", "com.meesho.supply", "in.amazon.mShop.android.shopping")
        val list = ShopAsk.installed { it in pk }
        assertEquals(listOf("flipkart", "meesho", "amazon"), list.map { it.key })
        assertEquals("ठीक है, शर्ट दिखाता हूँ। किस ऐप पर दिखाऊँ - Flipkart, Meesho या Amazon?", ShopAsk.question("शर्ट", list))
        assertEquals("meesho", ShopAsk.pick("मेसो पे दिखाओ", list)?.key)
        assertEquals("flipkart", ShopAsk.pick("फ्लिपकार्ट", list)?.key)
        assertEquals("amazon", ShopAsk.pick("अमेज़न पर", list)?.key)
        assertEquals("meesho", ShopAsk.pick("दूसरा वाला", list)?.key)
        assertNull(ShopAsk.pick("पता नहीं", list))
        assertTrue(ShopAsk.installed { false }.isEmpty())
    }

    @Test fun shopToolAsksWhenManyApps() {
        val s = src("ShopOpen.kt")
        assertTrue(s.contains("ASK_USER") && s.contains("ShopAsk.question(hi, have)"))
        assertTrue(s.contains("AppLauncher.shopSearch(svc, shop.key, item)"))
        assertTrue(s.contains("Never buy or pay"))
    }

    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()
}
