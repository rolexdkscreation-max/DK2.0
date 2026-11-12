package com.dk.assistant.voice.engine

import com.dk.assistant.agent.social.WaChatMode
import com.dk.assistant.agent.social.WaScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): (1) DK लाइव / Gemini TTS / Piper - जो चालू करूँ वही एक, बंद करने से अपने-आप दूसरा नहीं;
 * (2) Piper चुना = हमेशा Piper, ख़राबी = Gemini Live की सीधी आवाज़ (Gemini TTS नहीं); (3) हाई क्वालिटी महिला आवाज़ें
 * इंटरनेट से; (4) WhatsApp: खुली चैट में ही रुककर बात, बार-बार बैक / सर्च नहीं.
 */
class VoiceExclusiveHqTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    private val l = VoiceEngines.LIVE_NATIVE
    private val g = VoiceEngines.GEMINI_TTS
    private val p = PiperTtsEngine.ID
    private val all = setOf(l, g)

    @Test fun onlyWhatIsTurnedOnSpeaks() {
        assertEquals(g, VoiceModeController.choose(l, g, true, all))
        assertEquals(l, VoiceModeController.choose(g, l, true, all))
        assertEquals(l, VoiceModeController.choose(p, l, true, all))      // Piper चालू था, DK लाइव चालू किया = सिर्फ़ DK लाइव
        // बंद करना = कुछ नहीं बदलता (अपने-आप दूसरा नहीं)
        assertEquals(l, VoiceModeController.choose(l, l, false, all))
        assertEquals(g, VoiceModeController.choose(g, g, false, all))
        assertEquals(p, VoiceModeController.choose(p, g, false, all))
        assertEquals(g, VoiceModeController.choose(g, "edge_tts", true, all))   // जो इंजन है ही नहीं
    }

    @Test fun piperFailureGoesToLiveNeverGeminiTts() {
        assertEquals(PiperTtsEngine.Pick.NONE, PiperTtsEngine.pick(on = false, installed = true, failed = false))
        assertEquals(PiperTtsEngine.Pick.PIPER, PiperTtsEngine.pick(on = true, installed = true, failed = false))
        assertEquals(PiperTtsEngine.Pick.LIVE, PiperTtsEngine.pick(on = true, installed = true, failed = true))
        assertEquals(PiperTtsEngine.Pick.LIVE, PiperTtsEngine.pick(on = true, installed = false, failed = false))
        val e = src("voice/engine/PiperTtsEngine.kt")
        val fail = e.substringAfter("fun fail(c: Context, why: String)")
        assertFalse(fail.contains("setOn(app, false)"))                  // ख़राबी पर स्विच अपने-आप OFF नहीं
        assertTrue(fail.contains("VoiceEngines.markFailed(ID"))
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("engines.fallback()?.takeIf { chosen.id != com.dk.assistant.voice.engine.PiperTtsEngine.ID }"))
        val card = src("SettingsPiper.kt")
        assertTrue(card.contains("Piper बंद नहीं हुआ"))
    }

    @Test fun highQualityFemaleVoicesFromInternet() {
        assertEquals(3, PiperStore.HQ_VOICES.size)
        assertTrue(PiperStore.HQ_VOICES.all { it.hq && it.sid in 0..4 && it.title.contains("महिला") && it.title.contains("हाई क्वालिटी") })
        assertEquals(PiperStore.HQ_VOICES.size, PiperStore.HQ_VOICES.map { it.sid }.toSet().size)
        assertTrue(PiperStore.VOICES.none { it.hq })
        assertEquals(6, PiperStore.ALL_VOICES.map { it.id }.toSet().size)
        assertEquals(7, PiperStore.ST_FILES.size)
        assertTrue(PiperStore.ST_FILES.all { it.sha256.length == 64 && it.size > 0 })
        assertTrue(PiperStore.ST_SIZE in 140_000_000L..150_000_000L)
        assertEquals(40, PiperStore.ST_REV.length)
        val v = PiperStore.HQ_VOICES.first()
        assertEquals("https://huggingface.co/${PiperStore.ST_REPO}/resolve/${PiperStore.ST_REV}/voice.bin", PiperStore.fileUrl(v, "voice.bin"))
        // एक ही मॉडल: HQ आवाज़ बदलने पर दोबारा लोड नहीं
        assertEquals(PiperStore.ST_DIR, PiperTtsEngine.modelKey(PiperStore.HQ_VOICES[1].id))
        assertEquals("hi_IN-rohan-medium", PiperTtsEngine.modelKey("hi_IN-rohan-medium"))
        // नया = HQ, पहले से Piper medium फ़ोन में = वही (दोबारा डाउनलोड नहीं), सेव पसंद = वही
        assertEquals(PiperStore.BEST_VOICE, PiperStore.pickDefault(null, false))
        assertEquals(PiperStore.DEFAULT_VOICE, PiperStore.pickDefault(null, true))
        assertEquals("hi-hq-f3-siya", PiperStore.pickDefault("hi-hq-f3-siya", true))
        assertEquals(PiperStore.BEST_VOICE, PiperStore.pickDefault("gone", false))
        val c = PiperTtsEngine.hqConfig(2, 1.0f)
        assertEquals(2, c.sid); assertEquals(PiperStore.ST_STEPS, c.numSteps); assertEquals("hi", c.extra?.get("lang"))
        assertEquals(4, PiperTtsEngine.threadsFor(8, true)); assertEquals(2, PiperTtsEngine.threadsFor(8, false))
        assertEquals(1, PiperTtsEngine.threadsFor(2, true))
        val dl = src("voice/engine/PiperDownload.kt")
        assertTrue(dl.contains("if (v.hq) { fetchHq(c, v, done, tick); return }"))
    }

    @Test fun lowpassRemovesHarshHighs() {
        val rate = 44100
        fun rms(x: FloatArray) = Math.sqrt(x.drop(2000).sumOf { (it * it).toDouble() } / (x.size - 2000))
        fun tone(hz: Double) = FloatArray(8000) { (0.5 * Math.sin(2 * Math.PI * hz * it / rate)).toFloat() }
        val low = rms(SoftLowpass.forRates(rate, 24000).process(tone(1000.0)))
        val high = rms(SoftLowpass.forRates(rate, 24000).process(tone(18000.0)))
        assertTrue("आवाज़ वाली आवृत्ति बनी रहे: $low", low > 0.33)
        assertTrue("ऊँची खरखराहट कटे: $high", high < 0.03)
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
