package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** मालिक (नवंबर 2026): पेमेंट ऐप खुलते ही फ़ोन कंट्रोल अपने-आप बंद, बाहर आते ही चालू - सारे पेमेंट ऐप अपने-आप. */
class PayGuardTest {
    private fun src(name: String) = listOf(File("src/main/$name"), File("app/src/main/$name")).first { it.exists() }.readText()

    @Test fun detectsPaymentApps() {
        val apps = listOf(
            PayGuardRules.App("com.phonepe.app", "PhonePe", false),
            PayGuardRules.App("com.google.android.apps.nbu.paisa.user", "Google Pay", true),
            PayGuardRules.App("com.some.bank", "My Bank", false),
            PayGuardRules.App("com.x.random", "ABC UPI", false),
            PayGuardRules.App("com.whatsapp", "WhatsApp", true),
            PayGuardRules.App("com.android.camera", "Camera", false),
            PayGuardRules.App("com.google.android.youtube", "YouTube", false),
            PayGuardRules.App("com.android.vending", "Google Play Store", false),
            PayGuardRules.App("com.dk.assistant", "DK Pay", true),
        )
        val got = PayGuardRules.detect(apps, "com.dk.assistant").map { it.pkg }.toSet()
        assertEquals(setOf("com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "com.some.bank", "com.x.random", "com.whatsapp"), got)
    }

    @Test fun chatAndShoppingAppsOffByDefault() {
        assertFalse(PayGuardRules.defaultOn("com.whatsapp"))
        assertFalse(PayGuardRules.defaultOn("in.amazon.mShop.android.shopping"))
        assertTrue(PayGuardRules.defaultOn("com.phonepe.app"))
        assertTrue(PayGuardRules.defaultOn("net.one97.paytm"))
    }

    @Test fun pauseOnlyForGuardedApp() {
        val g = setOf("com.phonepe.app")
        assertTrue(PayGuardRules.shouldPause(true, "com.phonepe.app", g))
        assertFalse(PayGuardRules.shouldPause(false, "com.phonepe.app", g))
        assertFalse(PayGuardRules.shouldPause(true, "com.whatsapp", g))
        assertFalse(PayGuardRules.shouldPause(true, null, g))
    }

    @Test fun resumeAfterLeaving() {
        val g = setOf("com.phonepe.app"); val own = "com.dk.assistant"
        assertFalse(PayGuardRules.shouldResume("com.phonepe.app", 0, 60_000, g, own))           // अभी पेमेंट में
        assertFalse(PayGuardRules.shouldResume("com.android.intentresolver", 0, 60_000, g, own)) // पल भर वाला
        assertFalse(PayGuardRules.shouldResume("com.launcher", 1_000, 2_000, g, own))           // अभी-अभी बाहर
        assertTrue(PayGuardRules.shouldResume("com.launcher", 1_000, 1_000 + PayGuardRules.RESUME_AFTER_MS, g, own))
        assertTrue(PayGuardRules.shouldResume(own, 1_000, 1_001, g, own))                       // DK खोला = तुरंत
        assertFalse(PayGuardRules.shouldResume(null, 0, 60_000, g, own))
    }

    @Test fun launcherTapMatchesLabel() {
        val labels = mapOf("com.phonepe.app" to "PhonePe", "net.one97.paytm" to "Paytm")
        assertEquals("com.phonepe.app", PayGuardRules.tappedPkg(" phonepe ", labels))
        assertNull(PayGuardRules.tappedPkg("Camera", labels))
        assertNull(PayGuardRules.tappedPkg(null, labels))
    }

    @Test fun noteTextsInHindi() {
        assertTrue(PayGuardText.note(true, true, true).contains("अपने-आप चालू"))
        assertTrue(PayGuardText.note(false, false, false).contains("टैप"))
    }

    @Test fun wiredIntoServiceAndManifest() {
        val svc = src("java/com/dk/assistant/DKAccessibilityService.kt")
        assertTrue(svc.contains("PayGuard.onEvent(this, event)"))
        assertTrue(svc.contains("PayGuard.onServiceBack(this)"))
        assertTrue(src("java/com/dk/assistant/A11yWatch.kt").contains("PayGuard.recover(app)"))
        val m = src("AndroidManifest.xml")
        assertTrue(m.contains("android.permission.PACKAGE_USAGE_STATS"))
        assertTrue(m.contains("android:scheme=\"upi\""))
        assertTrue(m.contains(".PayGuardReceiver"))
        assertTrue(src("res/xml/accessibility_config.xml").contains("typeViewClicked"))
        assertTrue(src("java/com/dk/assistant/SettingsActivity.kt").contains("secAccess(); secPayGuard()"))
    }
}
