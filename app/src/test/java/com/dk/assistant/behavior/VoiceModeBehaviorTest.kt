package com.dk.assistant.behavior

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.SettingsActivity
import com.dk.assistant.voice.engine.LiveNativeEngine
import com.dk.assistant.voice.engine.KokoroTtsEngine
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

/** नवंबर 2026: Kokoro डिफ़ॉल्ट (Piper हटाया) + "Direct Gemini Live Voice" स्विच - असली Settings स्क्रीन पर */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceModeBehaviorTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun kokoroWorks() { KokoroTtsEngine.nativeForTest = true }
    @After fun reset() { KokoroTtsEngine.nativeForTest = null }

    private fun open() = Robolectric.buildActivity(SettingsActivity::class.java,
        Intent(c, SettingsActivity::class.java).putExtra("section", "voice")).setup()

    @Test fun freshInstallSpeaksWithKokoro_directLiveOff() {
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.activeId(c))
        assertFalse(VoiceModeController.directLive(c))
        // ऐप दोबारा खुले (प्रक्रिया नई) - तब भी Kokoro
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.activeId(c))
    }

    /** मालिक (नवंबर 2026): डिफ़ॉल्ट Alpha पर ताला - यूज़र के radio दबाए बिना Beta / कोई और कभी नहीं */
    @Test fun alphaHardLocked_onlyUserPickChangesIt() {
        VoiceEngines.sp(c).edit().putString(KokoroTtsEngine.PREF_VOICE, "hf_beta").commit()   // बिना यूज़र (restore वगैरह)
        assertEquals("hf_alpha", KokoroTtsEngine.voice(c))
        val s = open().get()
        val rb = Ui.all(s).filterIsInstance<android.widget.RadioButton>()
        assertTrue(rb.first { it.text.toString() == "Alpha" }.isChecked)
        assertFalse(rb.first { it.text.toString() == "Beta" }.isChecked)
        rb.first { it.text.toString() == "Psi" }.performClick()                                  // यूज़र ने खुद चुनी
        assertEquals("hm_psi", KokoroTtsEngine.voice(c))
        assertTrue(VoiceEngines.sp(c).getBoolean(KokoroTtsEngine.PREF_PICKED, false))
    }

    /** Piper हटाया: पहले Piper चुना था = अब Kokoro (एक बार वाला migration हो चुका हो तब भी) */
    @Test fun oldPiperChoiceMovesToKokoro() {
        VoiceEngines.sp(c).edit().putString("active", VoiceEngines.OLD_PIPER_ID).putBoolean(VoiceEngines.MIGRATE_KEY, true)
            .putBoolean(VoiceEngines.KOKORO_KEY, true).commit()
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.activeId(c))
    }

    /** सेटिंग्स में सिर्फ़ 2 समूह और 4 नाम: [Female voices: Alpha, Beta] | [Male voices: Omega, Psi] */
    @Test fun settingsShowOnlyFourKokoroVoicesInTwoGroups() {
        val s = open().get()
        for (t in listOf("Female voices", "Male voices")) assertTrue(t, Ui.hasText(s, t))
        val radios = Ui.all(s).filterIsInstance<android.widget.RadioButton>().map { it.text.toString() }
        for (n in listOf("Alpha", "Beta", "Omega", "Psi")) assertTrue(n, n in radios)
        for (old in listOf("Priyamvada", "Pratham", "Lessac", "प्रियंवदा", "प्रथम")) assertFalse(old, radios.any { it.contains(old) })
        // Alpha पहले से चुनी
        assertTrue(Ui.all(s).filterIsInstance<android.widget.RadioButton>().first { it.text.toString() == "Alpha" }.isChecked)
    }

    @Test fun oldEdgeChoiceMovesToKokoro() {
        VoiceEngines.sp(c).edit().putString("active", VoiceEngines.OLD_EDGE_ID).putBoolean(VoiceEngines.MIGRATE_KEY, true)
            .putBoolean(VoiceEngines.KOKORO_KEY, true).commit()
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.activeId(c))
    }

    @Test fun directLiveSwitchOnScreenIsSavedAndOffReturnsToKokoro() {
        val s = open()
        val live = Ui.switch(s.get(), LiveNativeEngine.title)
        val kokoro = Ui.switch(s.get(), KokoroTtsEngine.title)
        assertTrue(kokoro.isChecked); assertFalse(live.isChecked)
        live.isChecked = true
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.activeId(c))
        assertFalse(kokoro.isChecked)                                   // एक समय में एक
        s.pause().stop().destroy()
        // दोबारा खोलो: Direct चालू ही दिखे (सेव)
        val again = open()
        val live2 = Ui.switch(again.get(), LiveNativeEngine.title)
        assertTrue(live2.isChecked)
        live2.isChecked = false
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.activeId(c))
        assertTrue(Ui.switch(again.get(), KokoroTtsEngine.title).isChecked)
    }

    @Test fun phoneWithoutKokoroKeepsDirectLive() {
        KokoroTtsEngine.nativeForTest = false
        // पहली बार: Kokoro न चले तो जो था वही (DK लाइव) - चुप नहीं
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.activeId(c))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceModeController.setDirectLive(c, false))
    }

    @Test fun kokoroVoiceAndTuneAreSaved() {
        assertEquals("hf_alpha", KokoroTtsEngine.voice(c))                          // शुरुआत = Female 'Alpha'
        KokoroTtsEngine.setVoice(c, "hm_omega")
        assertEquals("hm_omega", KokoroTtsEngine.voice(c))
        KokoroTtsEngine.setVoice(c, "नहीं-है")                                      // अनजान = अनदेखा, पिछली चुनी वही
        assertEquals("hm_omega", KokoroTtsEngine.voice(c))
        // पुरानी (Piper वाली) सेव आवाज़ = Alpha
        VoiceEngines.sp(c).edit().putString(KokoroTtsEngine.PREF_VOICE, "hi_IN-pratham-medium").commit()
        assertEquals(KokoroTtsEngine.DEFAULT_VOICE, KokoroTtsEngine.voice(c))
        VoiceTune.setSpeed(c, 200); VoiceTune.setPitch(c, 50)
        assertEquals(200, VoiceTune.speedPct); assertEquals(50, VoiceTune.pitchPct)
        VoiceTune.reset(c)
    }
}
