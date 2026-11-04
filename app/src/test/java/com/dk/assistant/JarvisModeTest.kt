package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (नवंबर 2026): DK में JARVIS मोड - "जार्विस मोड ऑन करो" = JARVIS जैसी आवाज़/अंदाज़ (काम सारे वही),
 * "डीके मोड ऑन करो" = वापस DK; Settings में भी स्विच.
 */
class JarvisModeTest {
    private fun src(path: String) = listOf(File("src/main/java/com/dk/assistant/$path"), File("app/src/main/java/com/dk/assistant/$path")).first { it.exists() }.readText()

    @Test fun jarvisOnPhrases() {
        for (t in listOf("जार्विस मोड ऑन करो", "डीके जार्विस मोड ऑन करो", "DK, जार्विस मोड ऑन करो", "जार्विश मोड चालू करो",
            "Jarvis mode on", "jarvis mode on karo", "जार्विस मोड में आ जाओ", "जार्विस मोड स्टार्ट करो", "अपने डीके को जार्विस मोड ऑन करो"))
            assertEquals(t, true, JarvisMode.parse(t))
    }

    @Test fun dkModePhrases() {
        for (t in listOf("डीके मोड ऑन करो", "DK मोड ऑन करो", "dk mode on", "डी के मोड चालू करो", "जार्विस मोड बंद करो", "jarvis mode off", "डीके मोड में आ जाओ"))
            assertEquals(t, false, JarvisMode.parse(t))
    }

    @Test fun otherTalkIsNotACommand() {
        for (t in listOf("व्हाट्सएप खोलो", "जार्विस कौन है", "जार्विस मोड क्या है", "जार्विस मोड ऑन मत करो", "डार्क मोड ऑन करो",
            "ड्राइविंग मोड ऑन करो", "डीके गाना बजाओ", "", "आज मौसम कैसा है"))
            assertNull(t, JarvisMode.parse(t))
    }

    @Test fun quickAction() {
        val q = JarvisMode.quick(true)
        assertEquals(JarvisMode.TYPE, q.action.getString("type"))
        assertTrue(q.action.getBoolean("jarvis"))
        assertTrue(q.ack.contains("JARVIS"))
        assertFalse(JarvisMode.quick(false).action.getBoolean("jarvis"))
        assertTrue(JarvisMode.quick(false).ack.contains("DK"))
    }

    @Test fun personaKeepsAllWorkAndIsMale() {
        val b = JarvisMode.block("राहुल")
        assertTrue(b.contains("JARVIS"))
        assertTrue(b.contains("सर"))
        assertTrue(b.contains("राहुल सर"))
        assertTrue(b.contains("masculine"))
        assertTrue(b.contains("phone_task"))
        assertTrue(b.contains("ALL your abilities stay exactly the same"))
        assertFalse(JarvisMode.block("").contains("\" सर\""))
    }

    @Test fun voices() {
        assertEquals("Charon", JarvisMode.DEFAULT_VOICE)
        assertEquals(JarvisMode.DEFAULT_VOICE, JarvisMode.VOICES.first().first)
        // सब Gemini की पुरुष आवाज़ें
        assertTrue(JarvisMode.VOICES.all { it.first in VoiceCatalog.GEMINI_MALE })
        assertTrue(JarvisMode.VOICE_STYLE.contains("JARVIS"))
        assertTrue(JarvisMode.VOICE_STYLE.contains("male"))
        assertEquals(JarvisMode.VOICE_STYLE, GeminiTts.styleText(JarvisMode.STYLE_ID))
    }

    @Test fun wiring() {
        val p = src("Prefs.kt")
        assertTrue(p.contains("if (JarvisMode.on(c)) return JarvisMode.voice(c)"))
        assertTrue(p.contains("if (JarvisMode.on(c)) JarvisMode.STYLE_ID else"))
        val bm = src("BrainManager.kt")
        assertEquals(3, Regex(Regex.escape("if (JarvisMode.on(ctx)) append(JarvisMode.block(Prefs.userName(ctx))) else append(CalmPersona.block())")).findAll(bm).count())
        assertTrue(bm.contains("!JarvisMode.on(ctx) && GirlfriendPersona.female("))
        assertTrue(src("LocalRouter.kt").contains("JarvisMode.parse(raw)?.let { return Split(JarvisMode.quick(it), null) }"))
        assertTrue(src("LiveFast.kt").contains("setOf(JarvisMode.TYPE,"))
        assertTrue(src("DKAccessibilityService.kt").contains("JarvisMode.set(this, a.optBoolean(\"jarvis\", true))"))
        assertTrue(src("JarvisMode.kt").contains("VoiceEngines.liveChanged()"))
        assertTrue(src("voice/engine/LiveNativeEngine.kt").contains("JarvisMode.VOICE_STYLE"))
        assertTrue(src("voice/engine/GeminiTtsEngine.kt").contains("JarvisMode.VOICE_STYLE"))
        val s = src("SettingsVoice.kt")
        assertTrue(s.contains("JARVIS मोड चालू (बंद = DK मोड)"))
        assertTrue(s.contains("JarvisMode.set(this, on)"))
        assertTrue(src("SettingsSearch.kt").contains("E(\"voice\", \"JARVIS मोड\""))
    }
}
