package com.dk.assistant.voice.engine

import com.dk.assistant.LiveTextOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 20 नवंबर 2026 (मालिक): डिफ़ॉल्ट DK लाइव की सीधी आवाज़ ही. Live / Gemini TTS की आवाज़ न आए (limit / नेट / सिर्फ़ लिखावट)
 * = फ़ोन में लगी DK ऑफ़लाइन आवाज़ (St3). चार्जर / Wi-Fi जैसी घोषणाएँ और टेक्स्ट चैट का "बोलकर भी" भी यही बोले.
 */
class OfflineVoiceFallbackTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n"))
        .first { it.exists() }.readText()

    @Test fun defaultStaysLive_offlineStillManualOnly() {
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.migrated(null, false))
        assertFalse(St3VoiceEngine.defaultOn)
        assertEquals(St3VoiceEngine.ID, VoiceEngines.ST3_OFFLINE)
    }

    @Test fun fallbackOrder_geminiTtsThenOffline() {
        assertEquals(listOf(VoiceEngines.GEMINI_TTS, VoiceEngines.ST3_OFFLINE), VoiceEngines.FALLBACK_ORDER)
        val pipe = src("voice/engine/SpeechPipeline.kt")
        assertTrue(pipe.contains("addAll(VoiceEngines.fallbacks(chosen.id))"))
        assertTrue(pipe.contains("VoiceEngines.fallbacks().forEach"))
        assertTrue(src("GeminiLive.kt").contains("listOf(chosen) + engines.fallbacks(chosen.id)"))
    }

    @Test fun liveTextWithoutAudio_rule() {
        assertTrue(LiveTextOnly.need(split = false, dropped = false, muted = false, waiting = false, recvBytes = 0, text = "जी, बताइए"))
        assertFalse(LiveTextOnly.need(false, false, false, false, recvBytes = 4800, text = "जी, बताइए"))   // Live बोल चुका
        assertFalse(LiveTextOnly.need(split = true, dropped = false, muted = false, waiting = false, recvBytes = 0, text = "जी"))
        assertFalse(LiveTextOnly.need(false, dropped = true, muted = false, waiting = false, recvBytes = 0, text = "जी, बताइए"))
        assertFalse(LiveTextOnly.need(false, false, muted = true, waiting = false, recvBytes = 0, text = "जी, बताइए"))
        assertFalse(LiveTextOnly.need(false, false, false, waiting = true, recvBytes = 0, text = "जी, बताइए"))
        assertFalse(LiveTextOnly.need(false, false, false, false, 0, " । "))
        val srv = src("GeminiLiveServer.kt")
        assertTrue(srv.indexOf("val wasMuted = leakMute") in 0 until srv.indexOf("leakFilter = newLeakFilter(); leakMute = false\n        tagFilter?.finish()"))
        assertTrue(srv.indexOf("offlineTextOnly(wasDropping, wasMuted)") in 0 until srv.indexOf("        finishTurn()\n        FriendTalk.touch"))
    }

    @Test fun speaker_offlineInsteadOfSilence_andTextChat() {
        val sp = src("Speaker.kt")
        assertTrue(sp.contains("@Volatile var offlineFirst = false"))
        assertTrue(sp.contains("OfflineVoice.say(appCtx, text, voiceAttrs().usage)"))
        assertTrue(sp.contains("if (offlineOk) { offline = true;"))
        assertTrue(sp.contains("if (offline) return"))                       // ऑफ़लाइन बोलते समय Gemini की limit खर्च नहीं
        assertFalse(sp.contains("TextToSpeech("))
        val ag = src("AgentRunner.kt")
        assertTrue(ag.contains("speaker.offlineFirst = typed && speak"))
        assertTrue(src("ChatActivity.kt").contains("svc.runCommand(t, speak = swSpeak.isChecked, typed = true)"))
        assertTrue(src("DKAccessibilityService.kt").contains("agent.run(text, speak, typed = typed)"))
    }

    @Test fun announcementsAndPhoneVoice_offlineFirst() {
        val pv = src("PhoneVoice.kt")
        val say = pv.substringAfter("suspend fun say(").substringBefore("suspend fun android(")
        assertTrue(say.indexOf("OfflineVoice.say(c, text)") in 0 until say.indexOf("android(c, text)"))
        assertTrue(pv.contains("OfflineVoice.stop()"))
        val plus = src("plus/PlusVoice.kt")
        assertTrue(plus.contains("OfflineVoice.ready(app)") && plus.contains("OfflineVoice.say(app, text)"))
        assertTrue(plus.contains("if (!ok) android.os.Handler(android.os.Looper.getMainLooper()).post { phone(app, text) }"))
        assertTrue(src("plus/EventTriggers.kt").contains("PlusVoice.say(c, line(ev, pct))"))
    }

    @Test fun offlineVoice_doesNotStopConversationUnlessSpeaking() {
        val ov = src("voice/st3/OfflineVoice.kt")
        val stop = ov.substringAfter("fun stop() {")
        assertTrue(stop.trimStart().startsWith("if (!active) return"))
        assertFalse(ov.substringAfter("suspend fun say(").substringBefore("fun stop()").contains("St3Engine.stop()"))
        assertTrue(src("voice/st3/St3Player.kt").contains("private val usage: Int = AudioAttributes.USAGE_ASSISTANT"))
    }
}
