package com.dk.assistant

import com.dk.assistant.voice.engine.VoiceFeel
import com.dk.assistant.voice.engine.VoiceFeel.Delivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 23 नवंबर 2026 (मालिक: "DK असली इंसान जैसे बोले - धीरे से, प्यार से, गुस्से में चिल्लाकर, और सुरीली आवाज़ में गाना गाए.
 * Gemini TTS, सीधी Live आवाज़, ऑफ़लाइन TTS - सब पर"): [VoiceFeel] के नियम.
 */
class VoiceFeelNov23Test {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun detectsFeelingFromWords() {
        assertEquals(Delivery.WHISPER, VoiceFeel.detect("सुनो... धीरे से बताऊँ? मुझे आपकी बहुत याद आई।"))
        assertEquals(Delivery.WHISPER, VoiceFeel.detect("एक राज़ की बात है, किसी को मत बताना।"))
        assertEquals(Delivery.SHOUT, VoiceFeel.detect("कितनी बार कहा है!! खाना टाइम पर खाओ!"))
        assertEquals(Delivery.ANGRY, VoiceFeel.detect("मैं आपसे बहुत नाराज़ हूँ।"))
        assertEquals(Delivery.SAD, VoiceFeel.detect("अरे... उदास मत हो, मैं हूँ ना।"))
        assertEquals(Delivery.LOVING, VoiceFeel.detect("मुझे आपसे बहुत प्यार है।"))
        assertEquals(Delivery.EXCITED, VoiceFeel.detect("अरे वाह! बहुत बढ़िया खबर है।"))
        assertEquals(Delivery.NORMAL, VoiceFeel.detect("आज मौसम साफ़ है, धूप निकली है।"))
    }

    @Test fun calmingIsNotAnger_andNoFalseWordHits() {
        // सामने वाले को मनाना = गुस्सा नहीं
        assertTrue(VoiceFeel.detect("गुस्सा मत करो, प्लीज़।") != Delivery.ANGRY)
        assertTrue(VoiceFeel.detect("आप मुझसे नाराज़ हो?") != Delivery.ANGRY)
        // शब्द के बीच का हिस्सा नहीं गिनते
        assertEquals(Delivery.NORMAL, VoiceFeel.detect("इतनी लापरवाह मत बनो।").let { if (it == Delivery.EXCITED) it else Delivery.NORMAL })
        assertEquals(Delivery.NORMAL, VoiceFeel.detect("यह एक दिलचस्प किताब है।"))
    }

    @Test fun stageCuesAndNotesAreNeverSpoken() {
        assertEquals(Delivery.WHISPER, VoiceFeel.detect("(धीरे से) अरे सुनो"))
        assertEquals(Delivery.SHOUT, VoiceFeel.detect("[shouting] रुको!"))
        assertEquals(Delivery.SING, VoiceFeel.detect("♪ तुम मुस्कुराओ तो"))
        assertEquals("अरे सुनो", VoiceFeel.clean("(धीरे से) अरे सुनो"))
        assertEquals("तुम मुस्कुराओ तो", VoiceFeel.clean("♪ तुम मुस्कुराओ तो ♪"))
        // आम कोष्ठक वैसे ही
        assertEquals("(1) पहला काम", VoiceFeel.clean("(1) पहला काम"))
    }

    @Test fun songFlowAcrossOneAnswer() {
        val t = VoiceFeel.Tracker()
        // गाने का पूछना = शुरू नहीं
        assertEquals(Delivery.NORMAL, t.next("क्या मैं आपको एक गाना सुनाऊँ?"))
        assertEquals(Delivery.NORMAL, t.next("आज धूप अच्छी है।"))
        t.reset()
        assertEquals(Delivery.LOVING, t.next("तो सुनिए... आपके लिए एक गाना गाती हूँ।"))
        assertEquals(Delivery.SING, t.next("चाँद सितारे साथ तुम्हारे"))
        assertEquals(Delivery.SING, t.next("हर पल मुस्कान तुम्हारे"))
        assertTrue(t.next("कैसा लगा?") != Delivery.SING)
        assertEquals(Delivery.NORMAL, t.next("मौसम आज ठंडा है।"))
    }

    @Test fun songNeverRunsForever() {
        val t = VoiceFeel.Tracker()
        t.next("तो सुनिए...")
        var sung = 0
        repeat(VoiceFeel.MAX_SONG_SEGS + 5) { if (t.next("ला ला ला") == Delivery.SING) sung++ }
        assertEquals(VoiceFeel.MAX_SONG_SEGS, sung)
    }

    @Test fun geminiTtsStyleAndTags() {
        val base = SpeakingStyle.tts(CalmPersona.ttsStyle("x"))
        assertEquals(base, VoiceFeel.ttsStyle(Delivery.NORMAL, base))     // सामान्य = पहले जैसा
        for (d in Delivery.values().filter { it != Delivery.NORMAL }) {
            val st = VoiceFeel.ttsStyle(d, base)
            assertTrue(st.startsWith(d.tts)); assertTrue(st.length <= SpeakingStyle.TTS_MAX); assertTrue(st.contains(VoiceFeel.VOICE_CORE))
        }
        assertTrue(VoiceFeel.ttsStyle(Delivery.SING, base).contains("singing melodiously"))
        // 3.8 = सिर्फ़ शब्द; पुराने मॉडल = आगे टैग
        assertEquals("रुको!", VoiceFeel.ttsText("(चिल्लाकर) रुको!", Delivery.SHOUT, v38 = true))
        assertEquals("[shouting] रुको!", VoiceFeel.ttsText("(चिल्लाकर) रुको!", Delivery.SHOUT, v38 = false))
        assertEquals("[whispering] सुनो", VoiceFeel.ttsText("सुनो", Delivery.WHISPER, v38 = false))
        assertEquals("सुनो", VoiceFeel.ttsText("सुनो", Delivery.NORMAL, v38 = false))
    }

    @Test fun pipelineDeliveryReachesEngineThread() {
        assertEquals(Delivery.SING, VoiceFeel.during(Delivery.SING) { VoiceFeel.current("आज धूप है") })
        assertEquals(Delivery.NORMAL, VoiceFeel.current("आज धूप है"))
        assertEquals(Delivery.WHISPER, VoiceFeel.current("धीरे से बताऊँ?"))
    }

    @Test fun offlineVoiceGetsRealEffects() {
        assertNull(VoiceFeel.offlineMood(Delivery.NORMAL))
        assertTrue(VoiceFeel.offlineMood(Delivery.WHISPER)!!.airy)
        assertTrue(VoiceFeel.offlineMood(Delivery.SHOUT)!!.gain > 1f)
        assertTrue(VoiceFeel.offlineMood(Delivery.ANGRY)!!.gain > 1f)
        assertTrue(VoiceFeel.offlineMood(Delivery.LOVING)!!.gain < 1f)
        val sing = VoiceFeel.offlineMood(Delivery.SING)!!
        assertTrue(sing.shake > 0f && sing.speed < 1f)
        for (d in Delivery.values().filter { it != Delivery.NORMAL }) assertNotNull(d.name, VoiceFeel.offlineMood(d))
    }

    @Test fun promptInEveryVoiceMode() {
        val b = SpeakingStyle.block("धीरज")
        assertTrue(b.contains(VoiceFeel.LITE))
        assertTrue(src("BrainManager.kt").split("VoiceFeel.PROMPT").size - 1 >= 3)   // बड़े निर्देशों में पूरा नियम
        val p = VoiceFeel.PROMPT
        assertTrue(p.contains("whisper")); assertTrue(p.contains("shout")); assertTrue(p.contains("SINGING")); assertTrue(p.contains("very short sentences"))
        assertTrue(p.contains("never insult"))
        assertTrue(p.contains("कैसा लगा?") && p.contains("तो सुनिए"))   // Tracker के निशान वही जो DK बोलेगा
        assertTrue(VoiceFeel.songStarts("तो सुनिए...")); assertTrue(VoiceFeel.songEnds("कैसा लगा?"))
    }

    @Test fun wiredIntoAllEngines() {
        val pipe = src("voice/engine/SpeechPipeline.kt")
        assertTrue(pipe.contains("s.delivery = voiceTrack.next(text)"))
        assertTrue(pipe.contains("VoiceFeel.during(s.delivery)"))
        assertTrue(pipe.contains("voiceTrack.reset()"))
        val g = src("voice/engine/GeminiTtsEngine.kt")
        assertTrue(g.contains("VoiceFeel.ttsStyle(d, style0)"))
        assertTrue(g.contains("VoiceFeel.ttsText(text, d,"))
        val st3 = src("voice/engine/St3VoiceEngine.kt")
        assertTrue(st3.contains("VoiceFeel.offlineMood(VoiceFeel.current(text))"))
        assertTrue(st3.contains("St3Emotion.perform(raw, mood)"))
        val old = src("GeminiTts.kt")
        assertTrue(old.contains("VoiceFeel.current(text)"))
        assertTrue(old.contains("style.startsWith(FREE)"))    // कहानी / Broadcast का अपना style नहीं छेड़ते
        assertFalse(VoiceFeel.PROMPT.isBlank())
    }
}
