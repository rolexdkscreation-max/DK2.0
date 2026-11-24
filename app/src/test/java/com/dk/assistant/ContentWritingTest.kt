package com.dk.assistant

import com.dk.assistant.ContentPlan.Type
import com.dk.assistant.plus.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** 24 नवंबर 2026 (मालिक: DK CONTENT WRITING - Story Writer इसी में, कहानी का तरीका बिल्कुल वैसा ही) */
class ContentWritingTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun storyPrompt_exactlyUnchanged() {
        val h = MessageDigest.getInstance("SHA-256").digest(StoryPrompt.SYSTEM.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("893ecde7a996dce821e004e8a2e9ae865863e303051381558f099e3a97de2849", h)
        assertEquals(140, StoryPlan.WORDS_PER_MIN)
        assertEquals(listOf(10, 15, 20, 25, 30), StoryPlan.LENGTHS)
    }

    @Test fun story_opensOldStoryWriter() {
        val a = src("ContentWriterActivity.kt")
        assertTrue(a.contains("if (t == ContentPlan.Type.STORY) { startActivity(Intent(this, StoryWriterActivity::class.java)); return }"))
        // StoryWriter की अपनी फ़ाइलें इस काम में नहीं छुई गईं - उनमें ContentPlan का नाम नहीं
        assertFalse(src("StoryWriter.kt").contains("ContentPlan"))
        assertFalse(src("StoryWriterActivity.kt").contains("ContentPlan"))
    }

    @Test fun defaults_factsAndScript() {
        assertTrue(Type.FACTS.defSec in 30..45)
        assertEquals(60, Type.SCRIPT.defSec)
    }

    @Test fun asksOnlyMissing() {
        assertNotNull(ContentPlan.missing(Type.FACTS, "", false))
        assertNull(ContentPlan.missing(Type.FACTS, "समुद्र", false))
        assertNotNull(ContentPlan.missing(Type.IMAGE, "", false))
        assertNull(ContentPlan.missing(Type.IMAGE, "", true))
        assertNotNull(ContentPlan.missing(Type.FIX, " ", false))
    }

    @Test fun prompt_hasHookCtaCuriosityTruth() {
        val s = ContentPlan.SYSTEM
        for (w in listOf("HOOK", "CURIOSITY GAP", "CTA", "simple spoken Hindi", "only true", "never repeat")) assertTrue(w, s.contains(w))
        val r = ContentPlan.request(Type.FACTS, "समुद्र", 40, listOf("पुराना हुक"))
        assertTrue(r.contains("40-second") && r.contains("समुद्र") && r.contains("पुराना हुक"))
    }

    @Test fun duration_estimate() {
        val t = (1..140).joinToString(" ") { "शब्द" }
        assertEquals(60, ContentPlan.estimateSec(t))
        assertEquals("लगभग 30 सेकंड", ContentPlan.durationHi(30))
        assertEquals("लगभग 1 मिनट", ContentPlan.durationHi(62))
    }

    @Test fun clean_removesLabels() {
        assertEquals("क्या आप जानते हैं?\nफॉलो करें", ContentPlan.clean("**Hook:** क्या आप जानते हैं?\nCTA: फॉलो करें"))
    }

    @Test fun flag_offByDefault_rowSwapsOnlyWhenOn() {
        assertFalse(FeatureFlags.byKey(FeatureFlags.CONTENT_WRITING)!!.default)
        assertTrue(FeatureFlags.ready(FeatureFlags.CONTENT_WRITING))
        val s = src("SettingsActivity.kt")
        assertTrue(s.contains("Triple(\"story\", \"DK कंटेंट राइटिंग\""))
        assertTrue(s.contains("else Triple(\"story\", \"स्टोरी राइटर एजेंट\""))
        assertTrue(s.contains("ContentWriterActivity::class.java else StoryWriterActivity::class.java"))
        assertTrue(listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText().contains(".ContentWriterActivity"))
    }
}
