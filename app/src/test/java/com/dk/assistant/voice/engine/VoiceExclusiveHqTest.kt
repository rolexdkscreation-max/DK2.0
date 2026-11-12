package com.dk.assistant.voice.engine

import com.dk.assistant.agent.social.WaChatMode
import com.dk.assistant.agent.social.WaScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): (1) DK लाइव / Gemini TTS - जो चालू करूँ वही एक, बंद करने से अपने-आप दूसरा नहीं;
 * (2) WhatsApp: खुली चैट में ही रुककर बात, बार-बार बैक / सर्च नहीं.
 * (नवंबर 2026: पहला Supertonic 3 पूरी तरह हटाया - उसके tests भी हटे)
 */
class VoiceExclusiveHqTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    private val l = VoiceEngines.LIVE_NATIVE
    private val g = VoiceEngines.GEMINI_TTS
    private val all = setOf(l, g)

    @Test fun onlyWhatIsTurnedOnSpeaks() {
        assertEquals(g, VoiceModeController.choose(l, g, true, all))
        assertEquals(l, VoiceModeController.choose(g, l, true, all))
        // बंद करना = कुछ नहीं बदलता (अपने-आप दूसरा नहीं)
        assertEquals(l, VoiceModeController.choose(l, l, false, all))
        assertEquals(g, VoiceModeController.choose(g, g, false, all))
        assertEquals(g, VoiceModeController.choose(g, "edge_tts", true, all))   // जो इंजन है ही नहीं
    }

    @Test fun whatsappStaysInOpenChat() {
        assertTrue(WaChatMode.sameChat("rahul", "Rahul Sharma"))
        assertTrue(WaChatMode.sameChat("Rahul Sharma", "rahul sharma"))
        assertFalse(WaChatMode.sameChat("Amit", "Rahul Sharma"))
        assertFalse(WaChatMode.sameChat("", "Rahul"))
        val w = src("agent/android/WaLook.kt")
        val open = w.substringAfter("private suspend fun open(").substringBefore("val list = chatList(svc, d)")
        assertTrue(open.contains("WaChatMode.sameChat(said, t)"))       // पहले से खुली = चैट सूची पर वापस (back) नहीं
    }

    @Test fun selfModeKeepsReplyingHere() {
        val now = 1_000L
        val s = WaChatMode.State(now + 60_000)
        val them = listOf(WaScreen.Msg("hi", true), WaScreen.Msg("kaise ho?", false))
        // "खुद कर दो" नहीं कहा = कुछ नहीं (बॉस से पूछने वाला रास्ता वैसा ही)
        assertTrue(WaChatMode.here(s, "Rahul", them, null, false, now) is WaChatMode.Here.Wait)
        WaChatMode.selfOn(s, "Rahul")
        val r = WaChatMode.here(s, "Rahul", them, null, false, now)
        assertTrue(r is WaChatMode.Here.Reply)
        assertEquals("kaise ho?", (r as WaChatMode.Here.Reply).msg.text)
        // वही बात दोबारा नहीं, अपना आख़िरी मैसेज = रुको
        assertTrue(WaChatMode.here(s, "Rahul", them, "kaise ho?", false, now) is WaChatMode.Here.Wait)
        assertTrue(WaChatMode.here(s, "Rahul", them + WaScreen.Msg("badhiya", true), null, false, now) is WaChatMode.Here.Wait)
        // अगली बात = फिर यहीं जवाब
        assertTrue(WaChatMode.here(s, "Rahul", them + WaScreen.Msg("badhiya", true) + WaScreen.Msg("kal milte hain?", false), "kaise ho?", false, now) is WaChatMode.Here.Reply)
        // दूसरी चैट (खुद-जवाब नहीं) = कुछ नहीं
        assertTrue(WaChatMode.here(s, "Amit", them, null, false, now) is WaChatMode.Here.Wait)
        // पैसे / OTP = खुद नहीं
        assertTrue(WaChatMode.here(s, "Rahul", listOf(WaScreen.Msg("OTP bhejo jaldi", false)), null, false, now) is WaChatMode.Here.Ask)
        // समय पूरा = बंद
        assertTrue(WaChatMode.here(s, "Rahul", them, null, false, now + 120_000) is WaChatMode.Here.Stop)
        val a = src("agent/android/WaAutoChat.kt")
        val watch = a.substringAfter("private fun watch()").substringBefore("private fun autoReply")
        assertTrue(watch.contains("WaLook.sendHere("))
        assertFalse(watch.contains("exactReply") || watch.contains("smartReply") || watch.contains("openChat"))
    }
}
