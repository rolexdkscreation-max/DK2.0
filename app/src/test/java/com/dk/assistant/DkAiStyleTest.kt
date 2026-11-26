package com.dk.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.voice.engine.VoiceTune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 26 नवंबर 2026 (मालिक के दो DK AI प्रॉम्प्ट + बातचीत का temperature 0.75):
 * Live / Gemini TTS / ऑफ़लाइन आवाज़ के निर्देशों में, पुराने नियम वैसे ही, Live के छोटे निर्देश 29,000 से कम.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DkAiStyleTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun ownersWordsVerbatim() {
        for (k in listOf("असली इंसान की तरह बात करने वाली दोस्त और कंपैनियन", "1 से 2 लाइन", "\"हम्म...\"", "\"पूरा दिन गायब रहे... काम में बिज़ी थे या मुझे भूल गए थे?\"",
            "एक छोटा-सा, पर्सनल सवाल", "पिछली यादें"))
            assertTrue("नहीं मिला: $k", DkAiStyle.COMPANION.contains(k))
        for (k in listOf("समझदार, वफ़ादार", "\"सर\" या \"बॉस\" और \"आप\"", "जबरदस्ती \"हम्म\" या \"अरे\" मत लगाओ",
            "\"आपने कैमरा तो बंद कर दिया सर... पर मेरी नज़र से कैसे बचेंगे?\"", "\"काम हो गया है सर... वैसे आपने ऐसा क्यों किया?\""))
            assertTrue("नहीं मिला: $k", DkAiStyle.ASSISTANT.contains(k))
        assertTrue(DkAiStyle.BLOCK.contains(DkAiStyle.COMPANION) && DkAiStyle.BLOCK.contains(DkAiStyle.ASSISTANT) && DkAiStyle.BLOCK.contains(DkAiStyle.MERGE))
        // पुराने नियम नहीं टूटते: गर्लफ्रेंड का संबोधन, ईमानदारी वाला जवाब
        assertTrue(DkAiStyle.MERGE.contains("never सर/बॉस") && DkAiStyle.MERGE.contains("honest-answer rule"))
    }

    @Test fun inLiveAndOfflineVoicePrompts_onlyForDk() {
        val bm = src("BrainManager.kt")
        assertEquals(4, Regex(Regex.escape("DkPersona.finalBlock(ctx, Prefs.userName(ctx))).append(DkAiStyle.block(ctx))")).findAll(bm).count())
        assertTrue(bm.contains("append(DkPersona.sayRule(ctx)).append(DkAiStyle.sayRule(ctx))"))
        assertTrue(DkAiStyle.block(c).contains("DK AI - OWNER'S OWN PROMPT"))
        Prefs.edit(c) { putBoolean(JarvisMode.PREF, true) }
        assertEquals("", DkAiStyle.block(c)); assertEquals("", DkAiStyle.sayRule(c))
        Prefs.edit(c) { putBoolean(JarvisMode.PREF, false) }
        // Live (लाइव के पूरे और छोटे निर्देश) में
        assertTrue(BrainManager(c).liveSystemPrompt(withMemory = false).contains(DkAiStyle.ASSISTANT))
        Prefs.setLivePromptLite(c, true)
        val lite = BrainManager(c).liveSystemPrompt(withMemory = false)
        assertTrue(lite.contains(DkAiStyle.COMPANION))
        assertTrue("Live निर्देश ${lite.length}", lite.length + 120 < 29_000)
    }

    @Test fun geminiTtsStyle() {
        val s = DkPersona.ttsStyle(c, "")
        assertTrue(s.contains(DkAiStyle.TTS) && s.contains("calm, soothing"))
        assertEquals(DkAiStyle.tts(s), s)                                      // दोबारा नहीं जुड़ता
        assertTrue(SpeakingStyle.tts(CalmPersona.ttsStyle(s)).contains("\"...\""))  // 590 की सीमा में भी कटता नहीं
    }

    @Test fun temperatureDefaultAndSetting() {
        VoiceTune.load(c)
        assertEquals(75, VoiceTune.DEFAULT_TEMP)
        assertEquals(0.75, VoiceTune.temperature(), 0.0001)
        assertTrue(VoiceTune.fmtTemp(75).startsWith("0.75"))
        VoiceTune.setTemp(c, 20); assertEquals(0.20, VoiceTune.temperature(), 0.0001)
        VoiceTune.setTemp(c, 999); assertEquals(VoiceTune.MAX_TEMP, VoiceTune.tempPct)
        VoiceTune.setTemp(c, VoiceTune.DEFAULT_TEMP)
        assertTrue(src("GeminiLiveSetup.kt").contains(".put(\"temperature\", com.dk.assistant.voice.engine.VoiceTune"))
        assertTrue(src("AiClients.kt").contains(".put(\"temperature\", com.dk.assistant.voice.engine.VoiceTune.temperature())"))
        assertTrue(src("voice/engine/VoiceEngineCard.kt").contains("VoiceTune.setTemp(a, v)"))
    }
}
