package com.dk.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 27 नवंबर 2026 (मालिक का "Human-Like Natural Conversation" प्रॉम्प्ट): सिर्फ़ छूटी बातें, हर DK निर्देश में (छोटे Live में भी) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NaturalTalkNov27Test {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun onlyMissingParts() {
        val n = DkAiStyle.NATURAL
        for (k in listOf("repeated greetings", "explain fully", "never an interview", "just listen and acknowledge", "composed, clear, confident",
            "physical experiences", "beat 'a question after every reply'"))
            assertTrue("नहीं मिला: $k", n.contains(k))
        assertTrue(DkAiStyle.BLOCK.contains(DkAiStyle.MERGE + DkAiStyle.NATURAL + DkAiStyle.SOFT))
        // पहले से मौजूद - दोबारा नहीं जोड़ा
        assertTrue(DkPersona.MOOD_RULES.contains("drop ALL teasing and jokes"))
        assertFalse(n.contains("हम्म") || n.contains("laugh") || n.contains("interrupt"))
    }

    @Test fun inLiveFullAndLite_onlyDk() {
        assertTrue(BrainManager(c).liveSystemPrompt(withMemory = false).contains(DkAiStyle.NATURAL))
        Prefs.setLivePromptLite(c, true)
        val lite = BrainManager(c).liveSystemPrompt(withMemory = false)
        assertTrue(lite.contains(DkAiStyle.NATURAL))
        assertTrue("Live निर्देश ${lite.length}", lite.length + 120 < 29_000)
        Prefs.setLivePromptLite(c, false)
        Prefs.edit(c) { putBoolean(JarvisMode.PREF, true) }
        assertEquals("", DkAiStyle.block(c))
        Prefs.edit(c) { putBoolean(JarvisMode.PREF, false) }
    }
}
