package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 13 नवंबर 2026 (मालिक): "स्क्रीन वाला हटा दो, सिर्फ़ नोटिफिकेशन वाला रखो - ताला लगे, ऊपर से न दिखे, अंदर खोलें तब दिखे.
 * ऑल ऐप का भी रखो और सिंगल ऐप / एक-एक भी."
 */
class PrivacyNotifOnlyTest {
    private val own = "com.dk.assistant"
    private val wa = "com.whatsapp"
    private val ig = "com.instagram.android"

    private fun lock(scope: PrivacyCore.Scope, pkg: String, chosen: Set<String> = setOf(wa), on: Boolean = true,
                     ongoing: Boolean = false, call: Boolean = false, media: Boolean = false) =
        PrivacyCore.lockNotif(on, scope, pkg, own, chosen, ongoing, call, media)

    @Test fun allApps_locksEveryApp() {
        assertTrue(lock(PrivacyCore.Scope.ALL, wa))
        assertTrue(lock(PrivacyCore.Scope.ALL, ig))
        assertTrue(lock(PrivacyCore.Scope.ALL, ig, chosen = emptySet()))
    }

    @Test fun chosenApps_onlyThoseOneByOne() {
        assertTrue(lock(PrivacyCore.Scope.APPS, wa))
        assertFalse(lock(PrivacyCore.Scope.APPS, ig))
        assertTrue(lock(PrivacyCore.Scope.APPS, ig, chosen = setOf(wa, ig)))
        assertFalse(lock(PrivacyCore.Scope.APPS, wa, chosen = emptySet()))
    }

    @Test fun neverLocks_callsMediaOngoingDkSystemOrWhenOff() {
        for (s in PrivacyCore.Scope.values()) {
            assertFalse(lock(s, wa, on = false))
            assertFalse(lock(s, wa, call = true))
            assertFalse(lock(s, wa, media = true))
            assertFalse(lock(s, wa, ongoing = true))
            assertFalse(lock(s, own, chosen = setOf(own)))
            for (sys in PrivacyCore.SYSTEM) assertFalse(lock(s, sys, chosen = setOf(sys)))
        }
    }

    @Test fun scopeLabels() {
        assertEquals("सभी ऐप", PrivacyCore.Scope.ALL.hi)
        assertEquals("सिर्फ़ चुने ऐप", PrivacyCore.Scope.APPS.hi)
        assertEquals(PrivacyCore.Scope.APPS, PrivacyCore.Scope.of(null))   // नया = सिर्फ़ चुने ऐप (अपने आप सब बंद नहीं)
    }

    private fun src(p: String) = listOf(File(p), File("app/$p")).first { it.exists() }.readText()

    @Test fun screenShieldRemoved_notificationLockWired() {
        val p = src("src/main/java/com/dk/assistant/PrivacyScreen.kt")
        assertFalse(p.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse(p.contains("ShieldView"))
        assertTrue(p.contains("PrivacyCore.lockNotif(true, scope(c)"))
        assertTrue(p.contains("l.cancelNotification(sbn.key)"))
        assertTrue(p.contains("PrivacyLocked.opened(p)"))
        val s = src("src/main/java/com/dk/assistant/SettingsIsland.kt")
        assertFalse(s.contains("SeekBar"))
        assertFalse(s.contains("परदा"))
        assertTrue(s.contains("PrivacyCore.Scope.values()"))
        assertTrue(s.contains("button(\"सब चुनें\")") && s.contains("button(\"कोई नहीं\")"))
        assertTrue(s.contains("PrivacyScreen.setAppOn(this, a.pkg, v)"))
    }
}
