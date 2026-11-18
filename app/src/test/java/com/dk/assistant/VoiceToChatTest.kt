package com.dk.assistant

import com.dk.assistant.backend.BackendException
import com.dk.assistant.backend.BackendWire
import com.dk.assistant.backend.VoiceGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 18 नवंबर 2026 (मालिक): मुफ़्त वाले की 10 मिनट वाली आवाज़ पूरी = DK बोले "अब चैट में बात कर सकते हैं" और चैट अपने-आप खुले;
 * चैट Live से जुड़ी नहीं और मुफ़्त वाले की चैट कभी सर्वर पर जाकर "अपनी Gemini key डालें" पर नहीं रुकती.
 * डीप रिसर्च / गहरी सोच में Gemini 4 Hydrogen पहले.
 */
class VoiceToChatTest {
    @After fun reset() { AiRouter.readyOverride = null; AiRouter.freeOverride = null; AiRouter.onChat = null }

    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()

    @Test fun handoffMessageSaysChatUnlimited() {
        val m = BackendWire.VOICE_DONE_CHAT
        assertTrue(m.contains("10 मिनट"))
        assertTrue(m.contains("चैट में बात कर सकते हैं"))
        assertTrue(m.contains("जितनी चाहें"))
        assertTrue(BackendWire.WARN_HI.contains("चैट"))
        // पुराना तय शब्द वैसा ही (सर्वर/पुराने tests)
        assertEquals("आज की मुफ़्त बातचीत पूरी हो गई। इससे ज़्यादा बात करने के लिए पहले लाइसेंस की लें।", BackendWire.VOICE_DONE)
    }

    @Test fun voiceDoneGoesToChat() {
        val e = VoiceGate.toChat()
        assertTrue(VoiceGate.isChatHandoff(e))
        assertTrue(e.stopsTalk)                          // आवाज़ रुकती है
        assertEquals(BackendWire.VOICE_DONE_CHAT, AiRouter.say(e))
        assertTrue(AiRouter.voiceDoneToChat(e))
        // मुफ़्त वाले को सर्वर से "लाइसेंस चाहिए" = चैट; NEED_OWN_KEY = पहले जैसा (key डालो)
        AiRouter.freeOverride = true
        assertTrue(AiRouter.voiceDoneToChat(BackendException(402, BackendWire.LICENSE_REQUIRED, "x", BackendWire.VOICE_DONE)))
        assertFalse(AiRouter.voiceDoneToChat(BackendException(402, BackendWire.NEED_OWN_KEY, "x")))
        AiRouter.freeOverride = false
        assertFalse(AiRouter.voiceDoneToChat(BackendException(402, BackendWire.LICENSE_REQUIRED, "x", BackendWire.VOICE_DONE)))
    }

    @Test fun toChatSpeaksOnceOpensChatEveryTime() {
        val r = src("AiRouter.kt")
        assertTrue(r.contains("val again = now - chatAt < 30_000"))
        assertTrue(r.contains("if (!again) DKState.reply(say)"))          // लिखा भी (चैट में दिखे)
        assertTrue(r.contains("if (cb != null) cb(say, speak && !again)"))   // चैट हर बार खुले, बोले एक बार
        assertTrue(r.contains("if (voiceDoneToChat(e)) { toChat(BackendWire.VOICE_DONE_CHAT, speak); return }"))
    }

    @Test fun freeChatNeverFallsToServerAi() {
        AiRouter.readyOverride = true
        AiRouter.freeOverride = true
        assertFalse(AiRouter.fallback(AiException(429, "limit")))
        assertFalse(AiRouter.fallback(null))
        AiRouter.freeOverride = false
        assertTrue(AiRouter.fallback(AiException(429, "limit")))   // लाइसेंस वाले = पहले जैसा
    }

    @Test fun wired() {
        val svc = src("DKAccessibilityService.kt")
        assertTrue(svc.contains("AiRouter.onChat = "))
        assertTrue(svc.contains("ChatActivity::class.java"))
        assertTrue(svc.contains("stopLive(\"मुफ़्त आवाज़ पूरी - अब चैट\", quiet = true)"))
        assertTrue(svc.contains("if (!quiet) agent.stop()"))
        val vg = src("backend/VoiceGate.kt")
        assertTrue(vg.contains("if (doneToday(c, now)) return toChat()"))
        assertTrue(vg.contains("AiRouter.toChat(BackendWire.VOICE_DONE_CHAT, speak = true)"))
        assertTrue(src("AiRouter.kt").contains("backendReady() && serverAiOk()"))
    }

    @Test fun hydrogenForDeepWork() {
        assertEquals("gemini-4-hydrogen", GeminiClient.DEEP_MODELS.first())
        assertEquals("gemini-4-hydrogen", GeminiClient.THINK_MODELS.first())
        assertTrue("gemini-4-argon" in GeminiClient.THINK_MODELS)
        assertTrue("gemini-3.8-flash" in GeminiClient.DEEP_MODELS)          // Hydrogen न चले तो पुराना
        // आम बातचीत का मॉडल नहीं बदला
        assertEquals("gemini-3.5-flash-lite", OneKeyLanes.TALK_MODEL)
        assertFalse("gemini-4-hydrogen" in GeminiClient.BACKUP_MODELS)
        // सोचने का स्तर: गहरी सोच = high, डीप रिसर्च = medium, बाकी पहले जैसा
        assertEquals("high", ThinkLevel.searchLevel("gemini-4-hydrogen", think = true, deep = false))
        assertEquals("medium", ThinkLevel.searchLevel("models/gemini-4-hydrogen", think = false, deep = true))
        assertNull(ThinkLevel.searchLevel("gemini-4-hydrogen", think = false, deep = false))
        assertNull(ThinkLevel.searchLevel("gemini-3.8-flash", think = true, deep = true))
        assertNull(ThinkLevel.searchLevel("gemini-4-hydrogen", think = true, deep = false, chosen = ThinkLevel.LOW))
        assertTrue(OneKeyLanes.hindi().contains("Hydrogen"))
    }
}
