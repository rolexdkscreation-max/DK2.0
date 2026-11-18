package com.dk.assistant.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 18 नवंबर 2026 (मालिक): मालिक वाले ईमेल से लॉगिन = पहले "Welcome Boss"; लॉगिन वाले हिस्से में मालिक/बॉस वाली कोई बात नहीं */
class WelcomeBossTest {
    private fun src(f: String) = listOf(File("src/main/java/com/dk/assistant/$f"), File("app/src/main/java/com/dk/assistant/$f"))
        .first { it.exists() }.readText()

    @Test fun onlyWhenBecomingOwner() {
        assertTrue(WelcomeBoss.due(wasOwner = false, nowOwner = true))
        assertFalse(WelcomeBoss.due(wasOwner = true, nowOwner = true))
        assertFalse(WelcomeBoss.due(wasOwner = false, nowOwner = false))
        assertTrue(WelcomeBoss.TEXT == "Welcome Boss")
    }

    @Test fun wiredEverywhere() {
        assertTrue(src("backend/BackendApi.kt").contains("WelcomeBoss.noteStatus(c, wasOwner"))
        assertTrue(src("MainActivity.kt").contains("WelcomeBoss.showIfDue(this)"))
        assertTrue(src("SettingsLicense.kt").contains("WelcomeBoss.show(this)"))
        assertTrue(src("onboard/OnboardingActivity.kt").contains("WelcomeBoss.show(this)"))
    }

    @Test fun loginCardSaysNothingAboutOwner() {
        val s = src("SettingsLicense.kt")
        val login = s.substringAfter("private fun SettingsActivity.loginBody").substringBefore("if (!googleLoginFailed) return")
        assertFalse(login.contains("मालिक")); assertFalse(login.contains("Boss"))
        assertFalse(com.dk.assistant.LOGIN_HINT.contains("मालिक"))
        assertTrue("मालिक को 10 मिनट वाली बात नहीं", s.contains("if (BackendSession.owner(this)) OWNER_LINE"))
    }
}
