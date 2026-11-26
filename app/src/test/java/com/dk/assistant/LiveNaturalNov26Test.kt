package com.dk.assistant

import com.dk.assistant.voice.engine.VoiceFeel
import com.dk.assistant.voice.engine.VoiceFeel.Delivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 26 नवंबर 2026 (मालिक): DK लाइव - बोलना खत्म होते ही ~1 s में जवाब, बीच में बोलें तो DK तुरंत रुके,
 * और इंसान जैसी आवाज़ (हँसी, साँस, ठहराव, छोटे वाक्य, भावुक, राज़ की बात फुसफुसाकर, कभी थका अंदाज़).
 */
class LiveNaturalNov26Test {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun fasterEndWhenCaptionLags() {
        // caption आवाज़ से पीछे: पहले 700 ms, अब 550 ms - पर "और / to" पर रुके तो सोचने का पूरा समय (800) वैसा ही
        assertEquals(550, TurnEnd.CAPTION_WAIT_MS)
        val v = 10_000L
        assertEquals(550, TurnEnd.adaptiveEndMs("मैं कल बाज़ार गया", v - 200, v, 1_500, 0, true, 500, v + 300))
        assertEquals(TurnEnd.CONT_END_MS, TurnEnd.adaptiveEndMs("kal meeting hai to", v + 300, v, 1_500, 0, true, 500, v + 400))
        assertEquals(800, TurnEnd.CONT_END_MS)
        // पूरा सवाल = 0.36 s (पहले जैसा तेज़)
        assertEquals(TurnEnd.EARLY_END_MS, TurnEnd.adaptiveEndMs("आज मौसम कैसा है", v + 300, v, 1_500, 0, true, 500, v + 400))
        assertTrue(TurnEnd.CAPTION_WAIT_MS < GeminiLive.SERVER_SILENCE_MS)
    }

    @Test fun bargeStopsSoonerAndDucksDeeper() {
        assertEquals(600L, SoftBarge.STOP_MS)
        assertTrue(SoftBarge.DUCK_VOLUME <= 0.2f)
        // लगातार आवाज़ + मालिक = 0.6 s में रुका (पहले 1 s)
        val b = SoftBarge(); b.trigger("speaker", 0, 0)
        var t = 0L; var act = SoftBarge.Act.NONE
        while (t < 2_000 && act == SoftBarge.Act.NONE) { t += 40; act = b.feed(true, true, 40, t) }
        assertEquals(SoftBarge.Act.STOP, act); assertTrue("रुका $t ms पर", t in 560..680)
        // 0.2 s का झोंका (खटखट / गूँज) फिर भी नहीं रोकता
        b.reset(); b.trigger("speaker", 0, 0); t = 0; act = SoftBarge.Act.NONE
        repeat(5) { t += 40; act = b.feed(true, true, 40, t) }
        while (t < 2_000 && act == SoftBarge.Act.NONE) { t += 40; act = b.feed(false, true, 40, t) }
        assertEquals(SoftBarge.Act.UNDUCK, act)
        assertTrue(src("GeminiLiveMic.kt").contains("\${SoftBarge.STOP_MS}ms+"))
    }

    @Test fun secretIsWhispered() {
        assertEquals(Delivery.WHISPER, VoiceFeel.detect("सुनो... यह बात किसी को नहीं बताना।"))
        assertEquals(Delivery.WHISPER, VoiceFeel.detect("सिर्फ़ आपको बता रही हूँ, हम दोनों के बीच।"))
        assertEquals(Delivery.WHISPER, VoiceFeel.detect("किसी को मत बताओ ना"))
    }

    @Test fun laughAndTired() {
        assertEquals(Delivery.LAUGH, VoiceFeel.detect("हाहा, आप भी ना!"))
        assertEquals(Delivery.LAUGH, VoiceFeel.detect("(हँसते हुए) अरे बाबा"))
        assertEquals("अरे बाबा", VoiceFeel.clean("(हँसते हुए) अरे बाबा"))
        assertEquals(Delivery.TIRED, VoiceFeel.detect("उफ़्फ़... आज थोड़ी थक गई हूँ।"))
        assertEquals(Delivery.TIRED, VoiceFeel.detect("मुझे नींद आ रही है"))
        // सामने वाले की फ़िक्र = थकान नहीं
        assertEquals(Delivery.NORMAL, VoiceFeel.detect("आप थक गए होंगे, आराम कर लीजिए।"))
        for (d in listOf(Delivery.LAUGH, Delivery.TIRED)) {
            assertNotNull(VoiceFeel.offlineMood(d))
            assertTrue(VoiceFeel.ttsStyle(d, "x").startsWith(d.tts))
            assertTrue(VoiceFeel.ttsText("ठीक है", d, v38 = false).startsWith(d.tag))
        }
    }

    @Test fun livePromptHasHumanRules() {
        val lite = VoiceFeel.LITE
        for (w in listOf("1-3 very short sentences", "never a long story", "laugh", "breath", "sigh", "emotional", "किसी को नहीं बताना", "whisper", "tired", "never say these directions"))
            assertTrue("LITE: $w", lite.contains(w))
        assertTrue(SpeakingStyle.block("धीरज").contains(lite))        // छोटे Live निर्देश में भी
        val p = VoiceFeel.PROMPT
        for (w in listOf("real little laugh", "small breath", "whisper it in his ear", "tired and sleepy", "emotional", "1-3 short sentences"))
            assertTrue("PROMPT: $w", p.contains(w))
        assertTrue(lite.length < 900)
    }
}
