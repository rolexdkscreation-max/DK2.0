package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.7 (मालिक): फ़ोन का काम = पहले काम (चुप), फिर "हो गया सर" + "और कुछ?"; आम बात पर जवाब रुके नहीं (देरी);
 * आवाज़ इंजन चुप रहे तो Live की अपनी आवाज़ और छूटा जवाब दोबारा.
 */
class TaskFirstVoiceTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun nothingBeforeTheTask() {
        assertEquals(DoneGate.Verdict.DROP, TaskFirst.beforeToolCommand("ठीक है, कर रहा हूँ।"))
        assertEquals(DoneGate.Verdict.DROP, TaskFirst.beforeToolCommand(""))
        assertEquals(DoneGate.Verdict.DROP, TaskFirst.beforeToolCommand("हो गया सर, भेज दिया"))
        assertEquals(DoneGate.Verdict.DROP, TaskFirst.beforeToolCommand("अच्छा, सुनो। मैं अभी यूट्यूब खोलता हूँ"))
        // काम के लिए ज़रूरी सवाल बचता है
        assertEquals(DoneGate.Verdict.PLAY, TaskFirst.beforeToolCommand("किसे भेजूँ सर?"))
        assertTrue(src("GeminiLive.kt").contains("TaskFirst.beforeToolCommand(heldActText())"))
    }

    @Test fun normalTalkIsNotHeldLong() {
        // आम बात (हुक्म नहीं): 0.9 s के बाद बजे - पहले 2.5-8 s रुकती थी
        assertFalse(TaskFirst.release(false, 300, "मुझे लगा आप"))
        assertTrue(TaskFirst.release(false, TaskFirst.SOFT_HOLD_MS, "मुझे लगा आप थक गए हो"))
        assertTrue(TaskFirst.SOFT_HOLD_MS <= 1_000)
        // झूठा "हो गया" कभी जल्दी नहीं छूटता
        assertFalse(TaskFirst.release(false, 5_000, "हो गया सर, भेज दिया"))
        assertFalse(TaskFirst.release(true, 9_000, "हो गया सर, खोल दिया"))
        // हुक्म: ज़रूरी सवाल तुरंत, बाकी tool का इंतज़ार
        assertTrue(TaskFirst.release(true, 200, "किसको मैसेज करूँ?"))
        assertFalse(TaskFirst.release(true, 1_000, "ठीक है"))
        assertTrue(TaskFirst.release(true, TaskFirst.CMD_TALK_MS, "माफ़ करना, यह ऐप फ़ोन में नहीं है"))
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("TaskFirst.release(actCommand && !actAfterTool, System.currentTimeMillis() - actHoldAt"))
        assertTrue(g.contains("actHold = true; actHoldAt = now"))
    }

    @Test fun promptSaysDoneSirThenAsk() {
        val p = TaskFirst.prompt(false)
        assertTrue(p.contains("say NOTHING before the work"))
        assertTrue(p.contains("हो गया सर"))
        assertTrue(p.contains("और कुछ करना है सर?"))
        assertTrue(p.contains("do ALL of them first"))
        assertTrue(p.contains("WhatsApp") && p.contains("Flipkart") && p.contains("torch") && p.contains("alarm"))
        assertTrue(src("BrainManager.kt").contains("append(TaskFirst.prompt(f))"))
    }

    @Test fun silentVoiceFallsBackToLiveVoice() {
        val pipe = src("voice/engine/SpeechPipeline.kt")
        assertTrue(pipe.contains("hooks.onSilent(s.text)"))
        assertFalse(pipe.contains("hooks.phoneSay("))          // पुराना नियम: फ़ोन की अपनी आवाज़ नहीं
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("override fun onSilent("))
        assertTrue(g.contains("System.currentTimeMillis() < splitOffUntil"))
        assertTrue(g.contains("SilentVoice.resayNote("))
        assertEquals(10L * 60 * 1000, SilentVoice.OFF_MS)
        val n = SilentVoice.resayNote("हो गया सर, \"टॉर्च\" जला दी")
        assertTrue(n.contains("could NOT hear"))
        assertFalse(n.contains("\"टॉर्च\""))                    // अंदर के quotes JSON/वाक्य नहीं तोड़ते
    }

    @Test fun askDetection() {
        assertTrue(TaskFirst.isAsk("कौन सा वीडियो चलाऊँ?"))
        assertTrue(TaskFirst.isAsk("कितने बजे का अलार्म लगाऊँ"))
        assertFalse(TaskFirst.isAsk("ठीक है सर"))
        assertFalse(TaskFirst.isAsk(""))
    }
}
