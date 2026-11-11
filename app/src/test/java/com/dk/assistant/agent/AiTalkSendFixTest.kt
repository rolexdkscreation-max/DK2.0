package com.dk.assistant.agent

import com.dk.assistant.agent.android.WakeForWork
import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ImageApp
import com.dk.assistant.agent.core.ImageGenFlow
import com.dk.assistant.agent.core.ImageGenHost
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): "जाओ DK, GPT / Claude से बातचीत करो - तो ऐप खोले, उसमें टाइप करे और सही भेजने वाले बटन से भेजे,
 * माइक पर नहीं. ऐप में क्या लिखा है, DK को पूरा दिखे. मैं सवाल बोलूँ तो वहीं पूछे और जवाब बताए, वहीं रहे.
 * और काम के बीच स्क्रीन बंद न हो."
 */
class AiTalkSendFixTest {
    private class Host(val apps: Set<String>) : ImageGenHost {
        val opened = mutableListOf<String>()
        override fun installed(pkg: String) = pkg in apps
        override suspend fun openApp(pkg: String): Boolean { opened += pkg; return true }
        override suspend fun longPress(x: Int, y: Int) = true
        override suspend fun newestImageSince(startMs: Long): String? = null
        override fun now() = 1_000L
    }

    private val edit = UiElement(0, "", "Message", cls = "EditText", clickable = true, editable = true, bounds = Box(40, 2150, 900, 2260))
    private val send = UiElement(1, "", "Send prompt", cls = "Button", clickable = true, bounds = Box(920, 2150, 1040, 2260))
    private val voice = UiElement(2, "", "Start voice mode", cls = "Button", clickable = true, bounds = Box(920, 2150, 1040, 2260))

    // ------------------------------------------------------------------ भेजने के बाद उसी जगह माइक आया = दोबारा टैप नहीं
    @Test fun afterSend_buttonTurnsIntoMic_neverTapsIt() = runBlocking {
        val q = "नमस्ते ChatGPT, आज का मौसम कैसा है"
        // ऐप ने लिखा हुआ अभी खाने से हटाया नहीं दिखाया (पढ़ने में देर) - पर भेजो बटन की जगह माइक आ गया
        val stillTyped = edit.copy(id = 9, text = q)
        val d = FakeScreenDriver(ImageApp.CHATGPT.pkg, mapOf("chat" to listOf(edit, send), "sent" to listOf(stillTyped, voice)), "chat")
        d.onClick["chat" to "send prompt"] = "sent"
        val r = ImageGenFlow(d, Host(setOf(ImageApp.CHATGPT.pkg)), pollMs = 10, maxWaitMs = 50).ask(ImageApp.CHATGPT, q)
        assertTrue(r.error, r.ok)
        assertFalse("माइक / voice mode कभी नहीं", d.clicks.contains("start voice mode"))
        assertEquals(0, d.gestureTaps)
        assertEquals(listOf("send prompt"), d.clicks)
    }

    // ------------------------------------------------------------------ लिखने से पहले भी वहीं था (बिना नाम का माइक) = भेजो बटन नहीं
    @Test fun buttonThatExistedBeforeTyping_isNotTheSendButton() {
        val mic = UiElement(3, "", "", cls = "View", clickable = true, bounds = Box(920, 2150, 1040, 2260))
        val before = ScreenObservation(1, ImageApp.CLAUDE.pkg, listOf(edit, mic))
        val after = ScreenObservation(2, ImageApp.CLAUDE.pkg, listOf(edit.copy(text = "hello"), mic))
        assertEquals(null, ImageGenFlow.rightmostInRow(after, before))
        assertTrue(ImageGenFlow.blindSendPoints(after, before).none { (x, y) -> x in 920..1040 && y in 2150..2260 })
        // लिखने के बाद आया / बदला हुआ तीर = ठीक
        val arrow = mic.copy(id = 4, bounds = Box(925, 2150, 1040, 2260))
        val after2 = ScreenObservation(3, ImageApp.CLAUDE.pkg, listOf(edit.copy(text = "hello"), arrow))
        assertEquals(arrow, ImageGenFlow.rightmostInRow(after2, before))
        // पुराना तरीका (बिना "पहले") वैसा ही
        assertEquals(mic, ImageGenFlow.rightmostInRow(after))
    }

    @Test fun voiceScreenIsDetected_butNotTheNormalVoiceButton() {
        val listening = UiElement(5, "Listening…", cls = "TextView", bounds = Box(300, 1000, 800, 1080))
        assertTrue(ImageGenFlow.voiceScreen(ScreenObservation(1, ImageApp.CHATGPT.pkg, listOf(listening))))
        assertFalse(ImageGenFlow.voiceScreen(ScreenObservation(1, ImageApp.CHATGPT.pkg, listOf(edit, voice))))
    }

    // ------------------------------------------------------------------ वही ऐप सामने = वही chat (New chat नहीं), जब तक मालिक न कहे
    @Test fun appAlreadyOpen_followUpStaysInSameChat() = runBlocking {
        val newChat = UiElement(7, "", "New chat", cls = "Button", clickable = true, bounds = Box(900, 100, 1040, 200))
        val q = "और इसका कारण क्या है भाई"
        val mine = UiElement(5, q, cls = "TextView", bounds = Box(300, 400, 1040, 480))
        val ans = UiElement(6, "इसका कारण गर्म हवा है।", cls = "TextView", bounds = Box(40, 520, 1040, 640))
        val d = FakeScreenDriver(ImageApp.CHATGPT.pkg, mapOf("chat" to listOf(newChat, edit, send), "answer" to listOf(newChat, mine, ans, edit, send)), "chat")
        d.onClick["chat" to "send prompt"] = "answer"
        val r = ImageGenFlow(d, Host(setOf(ImageApp.CHATGPT.pkg)), pollMs = 10).ask(ImageApp.CHATGPT, q)
        assertTrue(r.error, r.ok)
        assertFalse(d.clicks.contains("new chat"))
        assertTrue(r.appReply, r.appReply.contains("गर्म हवा"))
        // मालिक ने नई chat कही
        val d2 = FakeScreenDriver(ImageApp.CHATGPT.pkg, mapOf("chat" to listOf(newChat, edit, send)), "chat")
        ImageGenFlow(d2, Host(setOf(ImageApp.CHATGPT.pkg)), pollMs = 10, maxWaitMs = 30).ask(ImageApp.CHATGPT, q, newChat = true)
        assertTrue(d2.clicks.contains("new chat"))
    }

    // ------------------------------------------------------------------ DK को ऐप की पूरी स्क्रीन दिखे (कुछ दबाए बिना)
    @Test fun readShowsWholeScreenTopToBottom() = runBlocking {
        val a = UiElement(5, "पहला संदेश", cls = "TextView", bounds = Box(40, 300, 1040, 380))
        val b = UiElement(6, "Claude का जवाब यहाँ", cls = "TextView", bounds = Box(40, 500, 1040, 580))
        val d = FakeScreenDriver(ImageApp.CLAUDE.pkg, mapOf("chat" to listOf(b, edit.copy(text = "आधा लिखा"), a)), "chat")
        val h = Host(setOf(ImageApp.CLAUDE.pkg))
        val r = ImageGenFlow(d, h).read(ImageApp.CLAUDE.target)
        assertTrue(r.error, r.ok)
        assertEquals("पहला संदेश\nClaude का जवाब यहाँ\n[लिखने की जगह] आधा लिखा", r.appReply)
        assertTrue(d.clicks.isEmpty()); assertEquals(0, d.gestureTaps)
        assertTrue(h.opened.isEmpty())                                   // पहले से सामने = दोबारा नहीं खोला
    }

    @Test fun hindiGptNames() {
        assertEquals(ImageApp.CHATGPT, ImageApp.parse("जीपीटी"))
        assertEquals(ImageApp.CHATGPT, ImageApp.parse("चैटजीपीटी"))
        assertEquals(ImageApp.CLAUDE, ImageApp.parse("क्लाउड"))
    }

    // ------------------------------------------------------------------ स्क्रीन बंद / लॉक = पहले जगाओ
    @Test fun wakeBeforeWork_wired() {
        assertTrue(WakeForWork.needWake(interactive = false, locked = false))
        assertTrue(WakeForWork.needWake(interactive = true, locked = true))
        assertFalse(WakeForWork.needWake(interactive = true, locked = false))
        assertTrue(src("agent/android/AiTalkAgent.kt").contains("WakeForWork.ensure(svc)"))
        assertTrue(src("agent/android/ImageGenAgent.kt").contains("WakeForWork.ensure(svc)"))
        val live = src("GeminiLiveSetup.kt")
        assertTrue(live.contains(".put(\"ask\").put(\"read\")"))
        assertTrue(live.contains("\"new_chat\""))
        assertTrue(live.contains("जाओ GPT / जीपीटी से बात करो"))
    }

    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()
}
