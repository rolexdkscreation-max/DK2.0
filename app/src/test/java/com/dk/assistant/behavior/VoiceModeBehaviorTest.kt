package com.dk.assistant.behavior

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.SettingsActivity
import com.dk.assistant.voice.engine.LiveNativeEngine
import com.dk.assistant.voice.engine.PiperTtsEngine
import com.dk.assistant.voice.engine.VoiceEngines
import com.dk.assistant.voice.engine.VoiceModeController
import com.dk.assistant.voice.engine.VoiceTune
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** नवंबर 2026: Piper डिफ़ॉल्ट + "Direct Gemini Live Voice" स्विच - असली Settings स्क्रीन पर */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceModeBehaviorTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun piperWorks() { PiperTtsEngine.nativeForTest = true }
    @After fun reset() { PiperTtsEngine.nativeForTest = null }

    private fun open() = Robolectric.buildActivity(SettingsActivity::class.java,
        Intent(c, SettingsActivity::class.java).putExtra("section", "voice")).setup()

    @Test fun freshInstallSpeaksWithPiper_directLiveOff() {
        assertEquals(VoiceEngines.PIPER_TTS, VoiceEngines.activeId(c))
        assertFalse(VoiceModeController.directLive(c))
        // ऐप दोबारा खुले (प्रक्रिया नई) - तब भी Piper
        assertEquals(VoiceEngines.PIPER_TTS, VoiceEngines.activeId(c))
    }

    @Test fun oldEdgeChoiceMovesToPiper() {
        VoiceEngines.sp(c).edit().putString("active", VoiceEngines.OLD_EDGE_ID).putBoolean(VoiceEngines.MIGRATE_KEY, true)
            .putBoolean(VoiceEngines.PIPER_KEY, true).commit()
        assertEquals(VoiceEngines.PIPER_TTS, VoiceEngines.activeId(c))
    }

    @Test fun directLiveSwitchOnScreenIsSavedAndOffReturnsToPiper() {
        val s = open()
        val live = Ui.switch(s.get(), LiveNativeEngine.title)
        val piper = Ui.switch(s.get(), PiperTtsEngine.title)
        assertTrue(piper.isChecked); assertFalse(live.isChecked)
        live.isChecked = true
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.activeId(c))
        assertFalse(piper.isChecked)                                   // एक समय में एक
        s.pause().stop().destroy()
        // दोबारा खोलो: Direct चालू ही दिखे (सेव)
        val again = open()
        val live2 = Ui.switch(again.get(), LiveNativeEngine.title)
        assertTrue(live2.isChecked)
        live2.isChecked = false
        assertEquals(VoiceEngines.PIPER_TTS, VoiceEngines.activeId(c))
        assertTrue(Ui.switch(again.get(), PiperTtsEngine.title).isChecked)
    }

    @Test fun phoneWithoutPiperKeepsDirectLive() {
        PiperTtsEngine.nativeForTest = false
        // पहली बार: Piper न चले तो जो था वही (DK लाइव) - चुप नहीं
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.activeId(c))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceModeController.setDirectLive(c, false))
    }

    @Test fun piperVoiceAndTuneAreSaved() {
        PiperTtsEngine.setVoice(c, "hi_IN-pratham-medium")
        assertEquals("hi_IN-pratham-medium", PiperTtsEngine.voice(c))
        PiperTtsEngine.setVoice(c, "नहीं-है")
        assertEquals(PiperTtsEngine.DEFAULT_VOICE, PiperTtsEngine.voice(c))       // ग़लत नाम = डिफ़ॉल्ट
        VoiceTune.setSpeed(c, 200); VoiceTune.setPitch(c, 50)
        assertEquals(200, VoiceTune.speedPct); assertEquals(50, VoiceTune.pitchPct)
        VoiceTune.reset(c)
    }
}
