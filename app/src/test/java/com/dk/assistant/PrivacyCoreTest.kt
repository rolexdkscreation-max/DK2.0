package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 12 नवंबर 2026: Samsung S26 Ultra जैसी प्राइवेसी स्क्रीन के नियम */
class PrivacyCoreTest {
    private val own = "com.dk.assistant"
    private val wa = "com.whatsapp"

    @Test fun shield_scope() {
        assertFalse(PrivacyCore.shieldNow(false, PrivacyCore.Scope.ALL, wa, setOf(wa)))
        assertTrue(PrivacyCore.shieldNow(true, PrivacyCore.Scope.ALL, "com.any.app", emptySet()))
        assertTrue(PrivacyCore.shieldNow(true, PrivacyCore.Scope.APPS, wa, setOf(wa)))
        assertFalse(PrivacyCore.shieldNow(true, PrivacyCore.Scope.APPS, "com.android.chrome", setOf(wa)))
        assertFalse(PrivacyCore.shieldNow(true, PrivacyCore.Scope.APPS, null, setOf(wa)))
    }

    @Test fun hideNotif_onlyChosen_neverCallsMediaOngoingOrDk() {
        fun h(pkg: String, ongoing: Boolean = false, call: Boolean = false, media: Boolean = false, hide: Boolean = true, on: Boolean = true) =
            PrivacyCore.hideNotif(on, hide, pkg, own, setOf(wa, own), ongoing, call, media)
        assertTrue(h(wa))
        assertFalse(h("com.instagram.android"))
        assertFalse(h(own))
        assertFalse(h(wa, ongoing = true)); assertFalse(h(wa, call = true)); assertFalse(h(wa, media = true))
        assertFalse(h(wa, hide = false)); assertFalse(h(wa, on = false))
    }

    @Test fun popupShield() {
        assertTrue(PrivacyCore.shieldPopup(true, true, wa, own, false))
        assertFalse(PrivacyCore.shieldPopup(true, false, wa, own, false))
        assertFalse(PrivacyCore.shieldPopup(true, true, own, own, false))
        assertFalse(PrivacyCore.shieldPopup(true, true, wa, own, true))
    }

    @Test fun strength_and_alpha() {
        assertEquals(PrivacyCore.MIN_STRENGTH, PrivacyCore.clampStrength(0))
        assertEquals(PrivacyCore.MAX_STRENGTH, PrivacyCore.clampStrength(500))
        for (p in PrivacyCore.MIN_STRENGTH..PrivacyCore.MAX_STRENGTH) {
            assertTrue(PrivacyCore.alphaEdge(p) > PrivacyCore.alphaCenter(p))        // किनारे ज़्यादा गहरे
            assertTrue(PrivacyCore.alphaCenter(p) < 160)                              // सामने से पढ़ा जा सके
            assertTrue(PrivacyCore.alphaEdge(p) <= 245)
        }
        assertTrue(PrivacyCore.alphaCenter(80) > PrivacyCore.alphaCenter(20))
    }

    @Test fun parse_join_roundTrip() {
        val s = PrivacyCore.parse(" com.whatsapp, ,bad,com.google.android.youtube ")
        assertEquals(setOf(wa, "com.google.android.youtube"), s)
        assertEquals(s, PrivacyCore.parse(PrivacyCore.join(s)))
        assertEquals(PrivacyCore.Scope.APPS, PrivacyCore.Scope.of(null))
        assertEquals(PrivacyCore.Scope.ALL, PrivacyCore.Scope.of("all"))
    }

    private fun src(p: String) = listOf(File(p), File("app/$p")).first { it.exists() }.readText()

    @Test fun wired() {
        val a = src("src/main/java/com/dk/assistant/DKAccessibilityService.kt")
        assertTrue(a.contains("PrivacyScreen.start(this)") && a.contains("PrivacyScreen.onEvent(this, event)"))
        val n = src("src/main/java/com/dk/assistant/DKNotifListener.kt")
        assertTrue(n.contains("PrivacyScreen.onPosted(this, it)") && n.contains("PrivacyScreen.hidByUs(it)"))
        assertTrue(SetSrc.all().contains("secGuard(); secPrivacy()"))
        assertTrue(SettingsSearch.ENTRIES.any { it.section == "guard" && it.card == "प्राइवेसी स्क्रीन (बगल से न दिखे)" })
    }
}
