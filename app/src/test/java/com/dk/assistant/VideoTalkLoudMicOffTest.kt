package com.dk.assistant

import com.dk.assistant.voice.st3.St3Emotion
import com.dk.assistant.voice.st3.St3Loud
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 25 नवंबर 2026 (मालिक): वीडियो के बीच भी सुने, ऑफ़लाइन आवाज़ तेज़ (धीमी नहीं), माइक काटने पर अटपटा न बोले */
class VideoTalkLoudMicOffTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun nearVoiceDuringVideoIsUser() {
        val f = VideoTalk.Floor(); repeat(60) { f.add(500.0) }
        val base = f.level()
        assertEquals(500.0, base, 0.0)
        val loud = List(VideoTalk.NEED_CHUNKS) { 2_000.0 }
        assertTrue(VideoTalk.isUser(loud, base, near = 2_200.0, vadAvg = 0.8f))
        assertTrue(VideoTalk.isUser(loud, base, near = 0.0, vadAvg = -1f))          // पास का स्तर अभी नहीं सीखा
        // वीडियो की अपनी आवाज़ (उसी स्तर पर) = नहीं
        assertFalse(VideoTalk.isUser(List(VideoTalk.NEED_CHUNKS) { 600.0 }, base, 0.0, 0.9f))
        // तेज़ पर इंसानी नहीं (संगीत) = नहीं
        assertFalse(VideoTalk.isUser(loud, base, 0.0, 0.2f))
        // आपकी आम आवाज़ से बहुत धीमी (दूर की) = नहीं
        assertFalse(VideoTalk.isUser(loud, base, near = 9_000.0, vadAvg = 0.8f))
        // कम टुकड़े / स्तर पता नहीं = अभी फ़ैसला नहीं
        assertFalse(VideoTalk.isUser(loud.take(5), base, 0.0, 0.8f))
        assertFalse(VideoTalk.isUser(loud, VideoTalk.Floor().level(), 0.0, 0.8f))
    }

    @Test fun guardWired() {
        val m = src("GeminiLiveMic.kt")
        assertTrue(m.contains("VideoTalk.isUser(") && m.contains("mediaFloor.add(rms)") && m.contains("!dkOnlyNow"))
    }

    @Test fun offlineVoiceLoud() {
        val quiet = FloatArray(4_000) { if (it % 2 == 0) 0.05f else -0.05f }
        val out = St3Loud.speech(quiet)
        assertEquals(St3Loud.TARGET_RMS, St3Loud.rms(out), 0.01f)
        val peaky = FloatArray(1_000) { if (it == 10) 0.9f else 0.02f }
        assertTrue(St3Loud.speech(peaky).all { kotlin.math.abs(it) < 1f })
        val loud = FloatArray(100) { 0.3f }
        assertEquals(0.3f, St3Loud.speech(loud)[0], 0f)                     // पहले से तेज़ = कम नहीं
        assertEquals(St3Loud.TARGET_RMS / St3Emotion.TARGET_RMS, St3Loud.mood(FloatArray(1) { 0.1f })[0] / 0.1f, 0.01f)
        assertTrue(src("voice/st3/OfflineVoice.kt").contains("St3Loud.speech(wav)"))
        assertTrue(src("voice/engine/St3VoiceEngine.kt").contains("St3Loud.speech(raw)"))
    }

    @Test fun micOffQuietOnTap() {
        assertNull(MicOffSay.line("ओर्ब/माइक टैप"))
        assertNull(MicOffSay.line("DKAI ✕"))
        assertNull(MicOffSay.line("island से कॉल काटा"))
        assertEquals("ठीक है बॉस।", MicOffSay.line("बोला: माइक बंद करो"))
        assertEquals("ठीक है बॉस।", MicOffSay.line("user ने कहा: माइक बंद"))
        assertFalse(src("DKAccessibilityService.kt").contains("speaker.speak(\"ठीक है। ज़रूरत हो तो हे डीके बोलिए।\")"))
    }
}
