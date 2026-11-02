package com.dk.assistant.behavior

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.Prefs
import com.dk.assistant.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Settings: स्क्रीन पर switch दबाओ / लिखो → बाहर जाओ (onPause) → सेटिंग सच में सेव हुई?
 * पहले के tests सिर्फ़ यह देखते थे कि code में putBoolean("...") लिखा है; यह असली नतीजा देखता है।
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsSaveTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    private fun open(section: String): ActivityController<SettingsActivity> =
        Robolectric.buildActivity(SettingsActivity::class.java,
            Intent(c, SettingsActivity::class.java).putExtra("section", section)).setup()

    private fun leave(a: ActivityController<SettingsActivity>) { a.pause().stop().destroy() }

    @Test fun dkOnlySwitchIsSaved() {
        assertFalse(Prefs.dkOnly(c))
        val s = open("access")
        Ui.switch(s.get(), "सिर्फ़ DK बोलने पर सुनो").isChecked = true
        leave(s)
        assertTrue(Prefs.dkOnly(c))
        // दोबारा खोलो: switch चालू ही दिखे
        val again = open("access")
        assertTrue(Ui.switch(again.get(), "सिर्फ़ DK बोलने पर सुनो").isChecked)
    }

    @Test fun announceCallsCanBeTurnedOff() {
        assertTrue(Prefs.announceCalls(c))
        val s = open("access")
        Ui.switch(s.get(), "आने वाली कॉल बोलकर बताओ").isChecked = false
        leave(s)
        assertFalse(Prefs.announceCalls(c))
    }

    @Test fun profileNamesSavedAndBlankAssistantNameIsDK() {
        val s = open("profile")
        Ui.field(s.get(), "आपका नाम").setText("  दीपक  ")
        Ui.field(s.get(), "असिस्टेंट का नाम").setText("   ")
        leave(s)
        assertEquals("दीपक", Prefs.userName(c))
        assertEquals("DK", Prefs.assistantName(c))
    }

    @Test fun coderStepsAreKeptInRange() {
        var s = open("coder")
        Ui.field(s.get(), "एक काम में ज़्यादा से ज़्यादा कदम (3–30)").setText("99")
        leave(s)
        assertEquals(30, Prefs.coderMaxSteps(c))
        s = open("coder")
        Ui.field(s.get(), "एक काम में ज़्यादा से ज़्यादा कदम (3–30)").setText("1")
        leave(s)
        assertEquals(3, Prefs.coderMaxSteps(c))
    }

    @Test fun repoLinkBecomesOwnerSlashName() {
        val s = open("coder")
        Ui.field(s.get(), "Repo (owner/naam)").setText(" https://github.com/rolex/DK2.0.git/ ")
        Ui.field(s.get(), "GitHub Token").setText(" ghp_test123 ")
        leave(s)
        assertEquals("rolex/DK2.0", Prefs.ghRepo(c))
        assertEquals("ghp_test123", Prefs.ghToken(c))       // token वापस वैसा ही पढ़ा जाए (ताले वाली परत के बाद भी)
    }

    @Test fun computerStepsAreKeptInRange() {
        val s = open("computer")
        Ui.field(s.get(), "एक काम में ज़्यादा से ज़्यादा कदम (5–60)").setText("999")
        leave(s)
        assertEquals(60, Prefs.computerMaxSteps(c))
    }

    /** हर Settings हिस्सा बिना crash के खुले और बंद हो */
    @Test fun everySectionOpens() {
        val sections = listOf(null, "profile", "lang", "brain", "brain_live", "brain_chat", "brain_coder", "brain_pc",
            "brain_extra", "voice", "orb", "ui", "access", "perms", "update", "debug", "coder", "computer", "about")
        for (sec in sections) {
            val i = Intent(c, SettingsActivity::class.java).also { if (sec != null) it.putExtra("section", sec) }
            val a = try { Robolectric.buildActivity(SettingsActivity::class.java, i).setup() }
                catch (e: Throwable) { throw AssertionError("Settings \"$sec\" खुलते ही गड़बड़: $e", e) }
            assertFalse("Settings \"$sec\" खुद बंद हो गया", a.get().isFinishing)
            leave(a)
        }
    }
}
