package com.dk.assistant.behavior

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.PhoneLockStore
import com.dk.assistant.PhoneLockVoice
import com.dk.assistant.SecretCodec
import com.dk.assistant.SecretNames
import com.dk.assistant.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** फ़ोन लॉक / अनलॉक: Settings में डालो → सच में सेव, PIN ताले में, गलत-गिनती पर रुकना */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhoneLockBehaviorTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()
    private fun raw() = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)

    /** नकली ताला (Robolectric में Android Keystore नहीं) */
    private object Fake : SecretCodec {
        var broken = false
        override fun seal(plain: String) = if (broken) null else SecretNames.PREFIX + plain.reversed().map { it + 1 }.joinToString("")
        override fun open(sealed: String) = sealed.removePrefix(SecretNames.PREFIX).map { it - 1 }.joinToString("").reversed()
    }

    @Before fun setUp() { Fake.broken = false; PhoneLockStore.codec = Fake }
    @After fun tearDown() { PhoneLockStore.codec = com.dk.assistant.KeystoreCodec; PhoneLockVoice.cfg = PhoneLockVoice.Cfg() }

    @Test fun offByDefault() {
        assertFalse(PhoneLockStore.lockOn(c)); assertFalse(PhoneLockStore.unlockOn(c)); assertFalse(PhoneLockStore.hasSecret(c))
        PhoneLockStore.reload(c)
        assertNull(PhoneLockVoice.match("हे DK फोन लॉक करो", true))
    }

    @Test fun pinStaysLockedAndComesBackSame() {
        assertTrue(PhoneLockStore.saveSecret(c, PhoneLockVoice.Kind.PIN, "482913"))
        val stored = raw().getString(PhoneLockStore.K_SECRET, null)!!
        assertTrue(SecretNames.isSealed(stored))
        assertFalse("PIN सादा नहीं दिखना चाहिए", stored.contains("482913"))
        assertEquals("482913", PhoneLockStore.secret(c))
        assertEquals(PhoneLockVoice.Kind.PIN, PhoneLockStore.kind(c))
    }

    @Test fun noLockNoSave() {
        Fake.broken = true
        assertFalse(PhoneLockStore.saveSecret(c, PhoneLockVoice.Kind.PIN, "1234"))
        assertNull(raw().getString(PhoneLockStore.K_SECRET, null))
        assertFalse(PhoneLockStore.hasSecret(c))
    }

    @Test fun badShapesNotSaved() {
        assertFalse(PhoneLockStore.saveSecret(c, PhoneLockVoice.Kind.PIN, "12"))
        assertFalse(PhoneLockStore.saveSecret(c, PhoneLockVoice.Kind.PATTERN, "1"))
        assertFalse(PhoneLockStore.hasSecret(c))
    }

    @Test fun unlockNeedsSavedPin() {
        PhoneLockStore.saveSettings(c, lockOn = true, unlockOn = true, names = "मायरा", lockWords = "", unlockWords = "")
        assertTrue(PhoneLockStore.lockOn(c))
        assertFalse("PIN के बिना अनलॉक चालू नहीं", PhoneLockStore.unlockOn(c))
        assertTrue(PhoneLockStore.saveSecret(c, PhoneLockVoice.Kind.PATTERN, "14789"))
        PhoneLockStore.saveSettings(c, lockOn = true, unlockOn = true, names = "मायरा, जारवी", lockWords = "", unlockWords = "")
        assertTrue(PhoneLockStore.unlockOn(c))
        assertEquals(PhoneLockVoice.Act.UNLOCK, PhoneLockVoice.match("हे जारवी फोन अनलॉक करो", true))
        assertEquals(PhoneLockVoice.Act.LOCK, PhoneLockVoice.match("हे मायरा फोन लॉक करो", true))
        assertEquals(PhoneLockVoice.Act.LOCK, PhoneLockVoice.match("डीके फोन लॉक करो", true))   // DK के अपने नाम भी
    }

    @Test fun stopsAfterWrongTries() {
        PhoneLockStore.saveSecret(c, PhoneLockVoice.Kind.PIN, "1234")
        PhoneLockStore.saveSettings(c, true, true, "DK", "", "")
        assertTrue(PhoneLockStore.canEnter(c))
        repeat(PhoneLockStore.MAX_FAILS) { PhoneLockStore.failed(c) }
        assertFalse("बार-बार गलत PIN = फ़ोन लंबे समय बंद - DK रुके", PhoneLockStore.canEnter(c))
        PhoneLockStore.saveSecret(c, PhoneLockVoice.Kind.PIN, "5678")
        assertTrue(PhoneLockStore.canEnter(c))
    }

    @Test fun clearRemovesPinAndUnlock() {
        PhoneLockStore.saveSecret(c, PhoneLockVoice.Kind.PIN, "1234")
        PhoneLockStore.saveSettings(c, true, true, "DK", "", "")
        PhoneLockStore.clearSecret(c)
        assertFalse(PhoneLockStore.hasSecret(c)); assertFalse(PhoneLockStore.unlockOn(c))
        assertNull(raw().getString(PhoneLockStore.K_SECRET, null))
    }

    private fun open() = Robolectric.buildActivity(SettingsActivity::class.java,
        Intent(c, SettingsActivity::class.java).putExtra("section", "phonelock")).setup()

    /**
     * v4.6 मालिक: "लॉक-अनलॉक में टेक्स्ट हटाकर वॉइस रिकॉर्डिंग" - नाम/वाक्य लिखने के खाने नहीं, 30 सेकंड रिकॉर्डिंग का बटन.
     * पहले सेव किए नाम/वाक्य मिटते नहीं (रिकॉर्डिंग न हो तब तक पीछे से चलते हैं).
     */
    @Test fun settingsScreenVoiceRecordingNoTextFields() {
        PhoneLockStore.saveSettings(c, false, false, "मायरा, श्री", "सो जाओ, फोन लॉक करो", "")
        val s = open()
        val a = s.get()
        assertFalse(Ui.hasText(a, "बुलाने के नाम"))
        assertFalse(Ui.hasText(a, "लॉक करने के लिए क्या बोलें"))
        assertTrue(Ui.hasText(a, "30 सेकंड रिकॉर्ड करें"))
        Ui.switch(a, "आवाज़ से फ़ोन लॉक करो").isChecked = true
        s.pause().stop().destroy()
        assertTrue(PhoneLockStore.lockOn(c))
        assertEquals("मायरा, श्री", PhoneLockStore.names(c))
        assertEquals(PhoneLockVoice.Act.LOCK, PhoneLockVoice.match("हे श्री सो जाओ", true))
    }

    @Test fun turningUnlockOnAsksForPin() {
        val s = open()
        val a = s.get()
        val sw = Ui.switch(a, "आवाज़ से फ़ोन अनलॉक करो (PIN / पैटर्न DK डालेगा)")
        sw.isChecked = true
        val d = ShadowDialog.getLatestDialog()
        assertNotNull("अनलॉक चालू करते ही PIN डालने का खाना खुले", d)
        assertTrue(d.isShowing)
        d.dismiss()           // रद्द
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse("PIN नहीं डाला = अनलॉक बंद ही रहे", sw.isChecked)
    }
}
