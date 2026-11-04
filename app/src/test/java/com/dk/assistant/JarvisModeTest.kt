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

    /** मालिक (नवंबर 2026): "चार्ज मोड ऑन करो" = फ़ोन ने "जार्विस" को "चार्ज" सुना - तब भी JARVIS */
    @Test fun misheardJarvisStillSwitches() {
        for (t in listOf("चार्ज मोड ऑन करो", "डीके चार्ज मोड ऑन करो", "charge mode on karo", "जार्ज मोड ऑन करो", "जावेद मोड चालू करो",
            "जारवीस मोड ऑन कीजिए", "Jarvis mode on kar do", "switch to jarvis mode", "turn on jarvis mode", "जार्विस वाला मोड ऑन करो",
            "जार्विस मॉड ऑन करो", "डीके, जार्विस मोड ऑन करो।"))
            assertEquals(t, true, JarvisMode.parse(t))
        for (t in listOf("चार्ज मोड बंद करो", "चार्ज मोड ऑफ करो", "डिके मोड ऑन करो", "switch to dk mode", "turn on DK mode"))
            assertEquals(t, false, JarvisMode.parse(t))
    }

    /** मालिक: "स्विचिंग होनी चाहिए" - JARVIS को बुलाकर DK मोड माँगा = DK (पहले JARVIS ही रहता था) */
    @Test fun nameRightBeforeModeDecides() {
        assertEquals(false, JarvisMode.parse("जार्विस, डीके मोड ऑन करो"))
        assertEquals(false, JarvisMode.parse("जार्विस डीके मोड चालू करो"))
        assertEquals(false, JarvisMode.parse("चार्ज, डीके मोड ऑन करो"))
        assertEquals(true, JarvisMode.parse("डीके जार्विस मोड ऑन करो"))
    }

    @Test fun batteryAndOtherModesAreNotJarvis() {
        for (t in listOf("चार्जिंग मोड ऑन करो", "कितना चार्ज है", "फोन चार्ज हो रहा है", "डीके, डार्क मोड ऑन करो", "जार्विस डार्क मोड ऑन करो",
            "डीके फ्लाइट मोड ऑन करो", "चार्ज मोड ऑन मत करो", "डीके का मोड कैसा है"))
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
        assertTrue(JarvisMode.VOICE_STYLE.contains("British"))
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

    @Test fun jarvisVoiceLayer() {
        assertTrue(src("GeminiLivePlayer.kt").contains("if (JarvisFx.enabled(svc)) JarvisFx.process(bRaw) else bRaw"))
        assertTrue(src("GeminiLivePlayer.kt").contains("JarvisFx.reset()"))
        assertTrue(src("SettingsVoice.kt").contains("JarvisFx.PREF"))
        JarvisFx.reset()
        // चुप्पी = चुप्पी, input नहीं बदलता, लंबाई वही
        val silent = ByteArray(4800)
        assertTrue(JarvisFx.process(silent).all { it == 0.toByte() })
        val loud = ByteArray(4800) { i -> if (i % 2 == 0) 0xff.toByte() else 0x7f.toByte() }   // +32767 हर sample
        val copy = loud.copyOf()
        val out = JarvisFx.process(loud)
        assertEquals(loud.size, out.size)
        assertTrue(loud.contentEquals(copy))
        // कभी नहीं फटता (wrap होकर उल्टा नहीं)
        var i = 0
        while (i + 1 < out.size) { val v = ((out[i].toInt() and 0xff) or (out[i + 1].toInt() shl 8)).toShort().toInt(); assertTrue(v > -32768); i += 2 }
        // गूँज: एक click के 11 ms बाद भी आवाज़ (मशीनी परत)
        JarvisFx.reset()
        val click = ByteArray(2400); click[0] = 0x00; click[1] = 0x40
        val o = JarvisFx.process(click)
        val at11 = 24000 * 11 / 1000 * 2
        val v11 = ((o[at11].toInt() and 0xff) or (o[at11 + 1].toInt() shl 8)).toShort().toInt()
        assertTrue("echo $v11", v11 > 1000)
        JarvisFx.reset()
    }
}
