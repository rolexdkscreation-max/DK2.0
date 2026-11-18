package com.dk.assistant

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * 18 नवंबर 2026 (मालिक): "Google लॉगिन पर एक ही ईमेल दिखता है, फ़ोन के बाकी खाते नहीं".
 * जड़: AuthorizationClient.authorize() बिना खाते के = Play services चुपचाप पहला/पिछला खाता.
 * अब: पहले Android का खाता-चुनने वाला popup (सारे Google खाते), फिर वही खाता setAccount से.
 */
class GoogleAccountPickerTest {
    private fun src(f: String) = listOf(File("src/main/java/com/dk/assistant/$f"), File("app/src/main/java/com/dk/assistant/$f"))
        .first { it.exists() }.readText()

    @Test fun authUsesChosenAccount() {
        val g = src("connect/GoogleAuth.kt")
        assertTrue(g.contains("setAccount(android.accounts.Account("))
        assertTrue(g.contains("newChooseAccountIntent"))
        assertTrue("एक खाता हो तब भी popup (दूसरा जोड़ सकें)", g.contains("arrayOf(GOOGLE_TYPE),\n        true,"))
        assertTrue(g.contains("KEY_ACCOUNT_NAME"))
    }

    @Test fun settingsLoginShowsPickerFirst() {
        val s = src("SettingsLicense.kt")
        val login = s.substringAfter("private fun SettingsActivity.googleLogin()").substringBefore("\n}\n")
        assertTrue(login.contains("chooseAccountIntent"))
        assertFalse("सीधा token नहीं - पहले खाता चुनो", login.contains("tokenBlocking"))
        assertTrue(s.contains("RC_LICENSE_PICK"))
        assertTrue(s.contains("tokenBlocking(this, BackendSession.GOOGLE_SCOPES, account)"))
    }

    @Test fun onboardingLoginShowsPickerFirst() {
        val o = src("onboard/OnboardingActivity.kt")
        assertTrue(o.contains("GoogleAuth.chooseAccountIntent()"))
        assertTrue(o.contains("RC_PICK"))
        assertTrue(o.contains("GOOGLE_SCOPES, account)"))
    }
}
