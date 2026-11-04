package com.dk.assistant

import com.dk.assistant.voice.engine.GeminiTtsDirect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (नवंबर 2026): "DK लाइव Gemini Live से नहीं जुड़ता, Gemini TTS चालू करने पर भी नहीं".
 * जड़: बँटवारे (Gemini TTS) में Live से TEXT माँगा जाता था - native-audio मॉडल सिर्फ़ AUDIO लेते हैं (setup मना);
 * languageCode भी native-audio नहीं लेते; 6s में हल्का मोड. अब: हमेशा AUDIO, languageCode/affective नहीं,
 * 20s दौड़, साफ़ हिंदी कारण, बैकअप मोड (बोली-पहचान -> text दिमाग़ -> Edge TTS).
 */
class LiveSetupAndBackupTest {
    private fun src(n: String): String =
        listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun setupRules_audioOnly_noLanguageCode_noAffective() {
        for (m in listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025")) {
            assertFalse(m, LiveSetupRules.askText(m, split = true))
            assertFalse(m, LiveSetupRules.askText(m, split = false))
            assertFalse(m, LiveSetupRules.sendLanguageCode(m))
            assertFalse(m, LiveSetupRules.sendAffective(m))
        }
        assertEquals("AUDIO", LiveSetupRules.MODALITY)
    }

    @Test fun source_setupUsesRules() {
        val g = LiveSrc.all()
        assertTrue(g.contains("connText = LiveSetupRules.askText(m, split = true)"))
        assertTrue(g.contains("internal fun GeminiLive.textFor(m: String): Boolean = LiveSetupRules.askText(m, connSplit)"))
        assertTrue(g.contains("ex.lang && LiveSetupRules.sendLanguageCode(m)"))
        assertTrue(g.contains("LiveSetupRules.sendAffective(m)) put(\"enableAffectiveDialog\""))
        assertTrue(g.contains(".apply { if (!txt) put(\"outputAudioTranscription\", JSONObject()) }"))   // Gemini TTS इसी लिखावट को बोलता है
        assertFalse(g.contains("getBoolean(\"text_off|\$m\", false)"))
    }

    @Test fun failReasons_clearHindi() {
        assertEquals(LiveFailWhy.Kind.KEY, LiveFailWhy.classify(1008, "API key not valid. Please pass a valid API key."))
        assertEquals(LiveFailWhy.Kind.KEY, LiveFailWhy.classify(403, "PERMISSION_DENIED"))
        assertEquals(LiveFailWhy.Kind.QUOTA, LiveFailWhy.classify(1011, "You exceeded your current quota"))
        assertEquals(LiveFailWhy.Kind.MODEL, LiveFailWhy.classify(1008, "models/gemini-x-live is not found for API version v1beta"))
        assertEquals(LiveFailWhy.Kind.CONFIG, LiveFailWhy.classify(1007, "Request contains an invalid argument."))
        assertEquals(LiveFailWhy.Kind.NET, LiveFailWhy.classify(0, "Unable to resolve host generativelanguage.googleapis.com"))
        assertEquals(LiveFailWhy.Kind.NET, LiveFailWhy.classify(1007, "anything", online = false))
        assertEquals(LiveFailWhy.Kind.MIC, LiveFailWhy.classify(0, "माइक की परमिशन नहीं है"))
        assertEquals(LiveFailWhy.Kind.NO_KEY, LiveFailWhy.classify(0, "Gemini की key नहीं है"))
        assertEquals(LiveFailWhy.Kind.SERVER, LiveFailWhy.classify(1011, "Internal error encountered."))
        for (k in LiveFailWhy.Kind.values()) {
            assertFalse(k.msg.contains("कुछ गलत"))
            assertTrue(LiveFailWhy.link(k).startsWith("बैकअप मोड • "))
            assertTrue(LiveFailWhy.backupLine(k).contains(k.msg) && LiveFailWhy.backupLine(k).contains("बैकअप मोड"))
            assertTrue(k.retryMs in 60_000L..10 * 60_000L)
        }
        assertTrue(LiveFailWhy.Kind.KEY.msg.contains("चाबी गलत है"))
        assertTrue(LiveFailWhy.Kind.MODEL.msg.contains("मॉडल उपलब्ध नहीं है"))
        assertTrue(LiveFailWhy.Kind.NET.msg.contains("इंटरनेट की दिक्कत है"))
        assertTrue(LiveFailWhy.Kind.MIC.msg.contains("माइक की अनुमति नहीं मिली"))
        assertTrue(LiveFailWhy.Kind.CONFIG.msg.contains("सेटिंग में गलती है"))
        // key कभी संदेश में नहीं - सिर्फ़ code और server का कारण
        assertFalse(LiveFailWhy.detail(1008, "bad").contains("AIza"))
    }

    /** log 11:09: 18 जोड़ियाँ, एक भी WebSocket नहीं खुला - पहले "लिमिट खत्म" दिखा (कारण में "limit" शब्द था) */
    @Test fun unreachable_notQuota() {
        assertEquals(LiveFailWhy.Kind.UNREACHABLE, LiveFailWhy.classify(0, "connection खुला ही नहीं - 5s में Google के server तक नहीं पहुँचा"))
        assertTrue(LiveFailWhy.Kind.UNREACHABLE.msg.contains("इंटरनेट की दिक्कत है"))
        assertTrue(LiveFailWhy.Kind.UNREACHABLE.msg.contains("VPN"))
        assertFalse(LiveFailWhy.classify(0, "सारी जोड़ियाँ अभी रुकी हुई हैं (अभी-अभी नाकाम)") == LiveFailWhy.Kind.QUOTA)
        assertEquals(LiveFailWhy.Kind.SLOW, LiveFailWhy.classify(0, "setup का जवाब 5 सेकंड में नहीं आया"))
        val g = LiveSrc.all()
        assertFalse(g.contains("गलत key / limit / अभी-अभी नाकाम"))
        assertTrue(g.contains("r.opened.incrementAndGet()"))
        assertTrue(g.contains("kind = LiveFailWhy.Kind.UNREACHABLE"))
        assertTrue(g.contains("lastRaceFail = it"))
    }

    /** नवंबर 2026: Worker हटा (मालिक) - वही साफ़ कारण अब सीधे Gemini TTS के लिए (पहले WorkerWhy) */
    @Test fun ttsReasons() {
        assertTrue(GeminiTtsDirect.why(401, "").contains("key गलत"))
        assertTrue(GeminiTtsDirect.why(400, "API key not valid. Please pass a valid API key.").contains("key गलत"))
        assertTrue(GeminiTtsDirect.why(429, "{}").contains("लिमिट"))
        assertTrue(GeminiTtsDirect.why(404, "").contains("मॉडल"))
        assertTrue(GeminiTtsDirect.why(503, "").contains("server 503"))
        assertTrue(GeminiTtsDirect.network("timeout").contains("समय सीमा"))
        assertTrue(GeminiTtsDirect.network("Unable to resolve host x").contains("इंटरनेट"))
        assertEquals(GeminiTtsDirect.Next.KEY, GeminiTtsDirect.next(403))
        assertEquals(GeminiTtsDirect.Next.KEY, GeminiTtsDirect.next(400, "API key not valid"))
        for (c in listOf(400, 404, 429, 500, 502)) assertEquals("$c", GeminiTtsDirect.Next.MODEL, GeminiTtsDirect.next(c))
        assertEquals(GeminiTtsDirect.Next.STOP, GeminiTtsDirect.next(418))
    }

    @Test fun source_lightModeGone_backupWired() {
        val s = com.dk.assistant.AccSrc.all(); val g = LiveSrc.all()
        for (bad in listOf("lightMode", "enterLight(", "lightBack(", "hearLight(", "LINK_LIGHT")) assertFalse(bad, s.contains(bad))
        assertFalse(src("DKState.kt").contains("LINK_LIGHT"))
        assertFalse(src("LiveRace.kt").contains("LIGHT_AFTER_MS") || src("LiveRace.kt").contains("BACKGROUND_EVERY_MS"))
        assertTrue(s.contains("speaker.localOnly = true") && s.contains("speaker.localOnly = false"))
        assertTrue(s.contains("DKState.setLink(LiveFailWhy.link(kind))"))
        assertTrue(s.contains("liveTroubleSay(LiveFailWhy.backupLine(kind))"))
        assertTrue(g.split("svc.liveConnected()").size - 1 >= 2)                 // नया connection और गर्म connection दोनों
        val sp = src("Speaker.kt")
        // नवंबर 2026: Kokoro हटाया - बैकअप मोड = Gemini TTS, वह न चले = तुरंत फ़ोन की (Google / Android) आवाज़
        assertTrue(sp.contains("if (localOnly) {") && sp.contains("BackupVoice.phoneFirst(") && !sp.contains("kokoroWav"))
        // नवंबर 2026: Edge की जगह Piper, अब Kokoro. Kokoro न चले = बैकअप मोड में फ़ोन की अपनी आवाज़ (चुप नहीं); आम मोड में फ़ोन की आवाज़ अब भी नहीं
        assertTrue(sp.contains("else if (localOnly && !aborted && !paused) {") && sp.contains("PhoneVoice.say(appCtx, chunk)"))
        assertTrue(sp.contains("PhoneVoice.stop()"))
        assertFalse(VoicePolicy.PHONE_TTS_ALLOWED)
        assertFalse(sp.contains("KokoroTtsEngine")); assertFalse(sp.contains("EdgeTtsEngine"))
        assertFalse(src("voice/engine/GeminiTtsEngine.kt").contains(".readTimeout(8, TimeUnit.SECONDS)"))
    }

    /** नवंबर 2026: Worker वाली request अब ऐप से सीधे - वही API revision, वही speech_config, वही audio delta */
    @Test fun direct_ttsRequestMatchesDocs() {
        val e = src("voice/engine/GeminiTtsEngine.kt")
        assertTrue(e.contains(".header(\"Api-Revision\", GeminiTtsDirect.API_REVISION)"))
        assertEquals("2026-05-20", GeminiTtsDirect.API_REVISION)
        val b = org.json.JSONObject(GeminiTtsDirect.body("gemini-3.8-flash-tts", "नमस्ते", "Kore", "कोमल"))
        assertEquals("Kore", b.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0).getString("voice"))
        assertTrue(b.getBoolean("stream"))
        val line = "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"AQIDBA==\"}}"
        assertEquals(listOf<Byte>(1, 2, 3, 4), GeminiTtsDirect.pcmFromLine(line)!!.toList())
    }
}
