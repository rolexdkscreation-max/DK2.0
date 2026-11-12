package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (नया Supertonic 3): एक ही "active" पसंद, अपना card, गड़बड़ = Live की सीधी आवाज़ (Gemini TTS नहीं),
 * अपनी स्पीड/पिच (आम VoiceTune इस पर दोबारा नहीं). DK लाइव / Gemini TTS के लिए सब पहले जैसा.
 */
class SupertonicWiringTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    @Test fun pipelineProfilesAndNoGeminiTtsBackup() {
        assertEquals(TextChunker.FAST_NET, SpeechPipeline.profileFor(VoiceEngines.GEMINI_TTS))
        assertEquals(TextChunker.STANDARD, SpeechPipeline.profileFor(VoiceEngines.LIVE_NATIVE))
        assertEquals(SupertonicTtsEngine.CHUNKS, SpeechPipeline.profileFor(SupertonicTtsEngine.ID))
        val p = src("voice/engine/SpeechPipeline.kt")
        assertTrue(p.contains("if (it.id != chosen.id && !chosen.liveOnTrouble) add(it)"))
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("engines.fallback()?.takeIf { !chosen.liveOnTrouble }"))
        val c = TextChunker(); c.reset(SupertonicTtsEngine.CHUNKS)
        assertEquals(listOf("मैं ठीक हूँ।"), c.feed("मैं ठीक हूँ। आप"))
        c.reset(SupertonicTtsEngine.CHUNKS)
        assertEquals(listOf("जी बिल्कुल,"), c.feed("जी बिल्कुल, मैं"))
    }

    @Test fun ownTuneNotDoubledByGlobalTune() {
        assertEquals(VoiceTune.NORMAL, VoiceTune.speedFor(true))
        assertEquals(VoiceTune.speedPct, VoiceTune.speedFor(false))
        assertTrue(VoiceTune.NEUTRAL_VER < 0)
        val p = src("voice/engine/SpeechPipeline.kt")
        assertTrue(p.contains("tuneVer = if (neutral) VoiceTune.applyFor(tr, tuneVer, true) else VoiceTune.apply(tr, tuneVer)"))
        assertTrue(p.contains("VoiceTune.playMs(pcm.size, VoiceEngine.RATE, VoiceTune.speedFor(neutral))"))
        assertTrue(src("voice/engine/SupertonicTtsEngine.kt").contains("PreviewPlayer.play(c, neutral = true)"))
    }

    @Test fun ownCardNotInEngineCard() {
        assertTrue(src("voice/engine/VoiceEngineCard.kt").contains("VoiceEngines.all.filter { !it.ownCard }"))
        val v = src("SettingsVoice.kt")
        assertTrue(v.contains("card(\"Supertonic 3 आवाज़\", "))
        assertTrue(v.contains("supertonicCard(box)"))
        val s = src("SettingsSupertonic.kt")
        assertTrue(s.contains("VoiceModeController.switch(app, SupertonicTtsEngine.ID, true)"))
        assertTrue(s.contains("VoiceEngines.setActive(app, VoiceEngines.LIVE_NATIVE)"))     // मॉडल हटाया = DK लाइव
        assertTrue(s.contains("SupertonicTiming.line()"))
    }

    @Test fun genConfigHindiAndSid() {
        val g = SupertonicTtsEngine.genConfig(4, 1.1f, 3)
        assertEquals(4, g.sid); assertEquals(3, g.numSteps); assertEquals(1.1f, g.speed, 0.0001f)
        assertEquals("hi", g.extra?.get("lang"))
        assertEquals(0, SupertonicTtsEngine.genConfig(-3, 1f).sid)
    }

    @Test fun speakableCleansText() {
        assertEquals("नमस्ते दोस्त", SupertonicTtsEngine.speakable("**नमस्ते** दोस्त \uD83D\uDE00"))
        assertEquals("", SupertonicTtsEngine.speakable("  ### "))
    }

    @Test fun proguardKeepsJniShape() {
        val pro = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro")).first { it.exists() }.readText()
        assertTrue(pro.contains("-keep class com.k2fsa.sherpa.onnx.** { *; }"))
        assertTrue(pro.contains("-keep class com.dk.assistant.voice.engine.SupertonicTtsEngine { *; }"))
        assertTrue(pro.contains("java.lang.Integer invoke(float[]);"))
        assertFalse(src("voice/engine/SupertonicTtsEngine.kt").contains("HumanVoice"))
    }
}
