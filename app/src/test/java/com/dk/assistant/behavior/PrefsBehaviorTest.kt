package com.dk.assistant.behavior

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrefsBehaviorTest {
    private lateinit var c: Context
    @Before fun setUp() { c = ApplicationProvider.getApplicationContext() }

    @Test fun defaults() {
        assertFalse(Prefs.dkOnly(c))
        assertTrue(Prefs.announceCalls(c))
        assertFalse(Prefs.ownerVoiceOn(c))
        assertEquals(Prefs.CALL_BUSY_DEFAULT, Prefs.callBusyMsg(c))
        assertEquals("DK", Prefs.assistantName(c))
        assertFalse("स्क्रीन देखना बिना आपकी हाँ के बंद", Prefs.screenshot(c))
    }

    @Test fun blankBusyMessageFallsBackToDefault() {
        Prefs.edit(c) { putString("call_busy_msg", "   ") }
        assertEquals(Prefs.CALL_BUSY_DEFAULT, Prefs.callBusyMsg(c))
        Prefs.edit(c) { putString("call_busy_msg", "मीटिंग में हूँ") }
        assertEquals("मीटिंग में हूँ", Prefs.callBusyMsg(c))
    }

    /** बहुत पुराना version: "api_key" / "model" → अब Gemini की key / model, पुराने नाम हट जाएँ */
    @Test fun oldGeminiKeyMovesToNewName() {
        c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit()
            .putString("api_key", "AIzaOLD").putString("model", "gemini-old").commit()
        Prefs.userName(c)    // कोई भी setting पढ़ो - बदलाव अपने-आप
        val raw = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
        assertFalse(raw.contains("api_key")); assertFalse(raw.contains("model"))
        assertEquals("AIzaOLD", Prefs.keyRaw(c, "gemini"))
    }

    /** API key लिखो → वापस वैसी ही पढ़ी जाए (ताले वाली परत बीच में हो तब भी) */
    @Test fun keyRoundTrip() {
        Prefs.edit(c) { putString("key_gemini", "AIzaNEW") }
        assertEquals("AIzaNEW", Prefs.keyRaw(c, "gemini"))
    }
}
