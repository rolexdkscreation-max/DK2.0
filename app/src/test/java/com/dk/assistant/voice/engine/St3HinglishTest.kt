package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026 (मालिक): बातचीत वाली ऑफ़लाइन आवाज़ (Supertonic 3) भी हिंग्लिश के हिंदी शब्द देवनागरी में बोले */
class St3HinglishTest {
    @Test fun romanHindiBecomesDevanagari() {
        val out = St3VoiceEngine.speakable("main theek hoon, aap kaise ho?")
        assertTrue(out, out.contains("ठीक") && out.contains("हूँ") && out.contains("आप") && out.contains("कैसे"))
        assertFalse(out, out.contains("theek") || out.contains("hoon"))
    }

    @Test fun sameAsAnnouncementPath() {
        val t = "Haan ji, aapka reminder set ho gaya hai."
        assertEquals(St3VoiceEngine.speakable(t),
            com.dk.assistant.voice.st3.St3Text.onlyTags(HinglishText.toSpeakable(t)))
        assertTrue(St3VoiceEngine.speakable(t).contains("reminder"))       // English शब्द वैसे ही
    }

    @Test fun englishAndDevanagariUnchanged_tagsKept() {
        assertEquals("This is the main point.", St3VoiceEngine.speakable("This is the main point."))
        assertEquals("नमस्ते, मैं डीके हूँ।", St3VoiceEngine.speakable("नमस्ते, मैं डीके हूँ।"))
        val tag = St3VoiceEngine.speakable("<laugh> haan yaar, bahut accha hai > ")
        assertTrue(tag, tag.startsWith("<laugh>") && tag.contains("बहुत") && !tag.endsWith(">"))
    }
}
