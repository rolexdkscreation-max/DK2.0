package com.dk.assistant.behavior

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.SettingsActivity
import com.dk.assistant.voice.engine.LiveNativeEngine
import com.dk.assistant.voice.engine.GeminiTtsEngine
import com.dk.assistant.voice.engine.VoiceEngines
import com.dk.assistant.voice.engine.VoiceModeController
import com.dk.assistant.voice.engine.VoiceTune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** नवंबर 2026 (मालिक): Kokoro हटाया - डिफ़ॉल्ट "Direct Gemini Live Voice", बैकअप Gemini TTS - असली Settings स्क्रीन पर */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceModeBehaviorTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    private fun open() = Robolectric.buildActivity(SettingsActivity::class.java,
        Intent(c, SettingsActivity::class.java).putExtra("section", "voice")).setup()

    @Test fun freshInstallSpeaksWithDirectLive() {
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.activeId(c))
        assertTrue(VoiceModeController.directLive(c))
        // ऐप दोबारा खुले - तब भी Direct Live
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.activeId(c))
    }

    /** पुराना Kokoro डिफ़ॉल्ट वाला फ़ोन (अपडेट) = Direct Live; Kokoro की कॉपी फ़ाइलें मिटें */
    @Test fun oldKokoroChoiceMovesToDirectLive() {
        val dir = java.io.File(c.filesDir, "kokoro_model").apply { mkdirs() }
        java.io.File(dir, "x.bin").writeText("x")
        VoiceEngines.sp(c).edit().putString("active", VoiceEngines.OLD_KOKORO_ID).putBoolean(VoiceEngines.MIGRATE_KEY, true)
            .putBoolean("kokoro_default_v1", true).commit()
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.activeId(c))
        assertFalse(dir.exists())
    }

    /** migration के बाद यूज़र ने Gemini TTS चुना = ऐप दोबारा खुलने पर भी वही (ज़बरदस्ती Direct नहीं) */
    @Test fun userPickedGeminiTtsStays() {
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.activeId(c))
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceModeController.setDirectLive(c, false))
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.activeId(c))
    }

    @Test fun oldPiperAndEdgeChoiceMoveToDirectLive() {
        for (old in listOf(VoiceEngines.OLD_PIPER_ID, VoiceEngines.OLD_EDGE_ID)) {
            VoiceEngines.sp(c).edit().putString("active", old).putBoolean(VoiceEngines.MIGRATE_KEY, true)
                .putBoolean(VoiceEngines.DIRECT_KEY, true).commit()
            assertEquals(old, VoiceEngines.LIVE_NATIVE, VoiceEngines.activeId(c))
        }
    }

    @Test fun settingsShowOnlyDirectLiveAndGeminiTts() {
        val s = open().get()
        val live = Ui.switch(s, LiveNativeEngine.title)
        val tts = Ui.switch(s, GeminiTtsEngine.title)
        assertTrue(live.isChecked); assertFalse(tts.isChecked)
        assertFalse(Ui.hasText(s, "Kokoro"))
    }

    @Test fun directLiveSwitchOnScreenIsSavedAndOffGoesToGeminiTts() {
        val s = open()
        val live = Ui.switch(s.get(), LiveNativeEngine.title)
        val tts = Ui.switch(s.get(), GeminiTtsEngine.title)
        assertTrue(live.isChecked)
        live.isChecked = false
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.activeId(c))
        assertTrue(tts.isChecked)                                       // एक समय में एक
        s.pause().stop().destroy()
        // दोबारा खोलो: Gemini TTS चालू ही दिखे (सेव); Direct फिर चालू करो
        val again = open()
        assertTrue(Ui.switch(again.get(), GeminiTtsEngine.title).isChecked)
        Ui.switch(again.get(), LiveNativeEngine.title).isChecked = true
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.activeId(c))
    }

    @Test fun tuneIsSaved() {
        VoiceTune.setSpeed(c, 200); VoiceTune.setPitch(c, 50)
        assertEquals(200, VoiceTune.speedPct); assertEquals(50, VoiceTune.pitchPct)
        VoiceTune.reset(c)
    }
}
