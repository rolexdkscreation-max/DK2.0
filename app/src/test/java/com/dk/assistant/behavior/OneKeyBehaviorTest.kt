package com.dk.assistant.behavior

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.ApiScopes
import com.dk.assistant.CodingActivity
import com.dk.assistant.CoderSession
import com.dk.assistant.DKComposer
import com.dk.assistant.OneKeyGate
import com.dk.assistant.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "एक key से सब": key न डली हो तो भारी काम (कोडिंग, स्टोरी, कंप्यूटर, वेबसाइट) सच में रुकें; key डली हो या मोड बंद = चलें */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OneKeyBehaviorTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()
    private fun oneKey(on: Boolean) = Prefs.edit(c) { putBoolean(ApiScopes.ONE_KEY_ON, on) }

    @Before fun clean() { CoderSession.lines.clear() }

    @Test fun heavyWorkBlockedOnlyWhenOneKeyOn() {
        oneKey(true)
        assertTrue(Prefs.oneKeyOn(c))
        for (w in OneKeyGate.Work.values()) assertNotNull("${w.hi} एक key पर रुकना चाहिए", OneKeyGate.check(c, w))
        oneKey(false)
        for (w in OneKeyGate.Work.values()) assertNull("${w.hi} अलग keys पर चलना चाहिए", OneKeyGate.check(c, w))
    }

    /** नवंबर 2026 (मालिक): "एक key डालेगा तो सारे काम" - key डली हो तो कोई भी काम नहीं रुकता, सब उसी key से */
    @Test fun oneKeyWithKeyRunsAllWork() {
        Prefs.edit(c) { putBoolean(ApiScopes.ONE_KEY_ON, true); putString(ApiScopes.ONE_KEY, "AIzaTEST_one_key_all_work") }
        try {
            for (w in OneKeyGate.Work.values()) assertNull("${w.hi} एक key पर चलना चाहिए", OneKeyGate.check(c, w))
            for (s in listOf(ApiScopes.CODER, ApiScopes.PC)) assertEquals("AIzaTEST_one_key_all_work", Prefs.scopeKey(c, s, "gemini"))
            assertEquals("AIzaTEST_one_key_all_work", Prefs.forgeKey(c))          // वेबसाइट बिल्डर भी उसी key से
            Prefs.edit(c) { putString(Prefs.FORGE_KEY, "AIzaFORGE_own") }
            assertEquals("AIzaFORGE_own", Prefs.forgeKey(c))                      // अपनी key डली हो तो पहले वही
        } finally {
            Prefs.edit(c) { remove(ApiScopes.ONE_KEY); remove(Prefs.FORGE_KEY); putBoolean(ApiScopes.ONE_KEY_ON, false) }
        }
    }

    @Test fun codingScreenRefusesOnOneKey() {
        oneKey(true)
        val a = Robolectric.buildActivity(CodingActivity::class.java).setup().get()
        val composer = Ui.all(a).filterIsInstance<DKComposer>().first()
        composer.edt.setText("Settings में dark mode जोड़ो")
        composer.send.performClick()
        val last = CoderSession.lines.last()
        assertEquals("e", last.role)
        assertEquals(OneKeyGate.message(OneKeyGate.Work.CODING), last.text)
        assertTrue(CoderSession.lines.any { it.role == "u" && it.text == "Settings में dark mode जोड़ो" })
        assertTrue("काम शुरू नहीं होना चाहिए", !CoderSession.busy)
    }
}
