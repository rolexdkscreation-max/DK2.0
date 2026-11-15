package com.dk.assistant.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 15 नवंबर 2026 (हिस्सा 4): SOS - नंबर सूची, 5 सेकंड, क्रम, इजाज़त */
class SosContactsTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()
    private val R = Sos.Rules

    @Test fun addEditDelete() {
        var l = R.add(emptyList(), "98765 43210")
        assertEquals(listOf("9876543210"), l)
        l = R.add(l, "9876543210"); assertEquals(1, l.size)               // दोहराव नहीं
        l = R.add(l, "123"); assertEquals(1, l.size)                      // ग़लत नंबर नहीं
        l = R.add(l, "+91 98765 00000"); assertEquals(listOf("9876543210", "+919876500000"), l)
        l = R.replace(l, 0, "9000000001"); assertEquals("9000000001", l[0])
        l = R.remove(l, 1); assertEquals(listOf("9000000001"), l)
        var full = emptyList<String>(); for (i in 1..7) full = R.add(full, "900000000$i")
        assertEquals(Sos.MAX_CONTACTS, full.size)
    }

    @Test fun fiveSecondsAndStopRule() {
        assertEquals(5, R.COUNTDOWN_SEC)
        assertTrue(R.countdownSpeech().contains("5 सेकंड"))
        assertTrue(R.stopOnAnswer(callAll = false))
        assertFalse(R.stopOnAnswer(callAll = true))
        assertTrue(R.EMPTY_SPEECH.startsWith("पहले emergency नंबर जोड़ो"))
        assertFalse(FeatureFlags.byKey(FeatureFlags.SOS_CALL_ALL)!!.default)
        assertTrue(FeatureFlags.SOS_CALL_ALL in FeatureFlags.READY)
    }

    @Test fun orderLocationMessagesThenCalls() {
        val s = src("plus/Sos.kt")
        val loc = s.indexOf("LocNow.get(c, 10_000L)"); val sms = s.indexOf("sendMultipartTextMessage")
        val wa = s.indexOf("api.whatsapp.com"); val call = s.indexOf("Intent.ACTION_CALL")
        assertTrue(loc in 1 until sms && sms < wa && wa < call)
        assertTrue(s.contains("PlusVoice.say(app, Rules.EMPTY_SPEECH)"))
    }

    @Test fun settingsCard() {
        val s = src("SettingsExtra.kt")
        assertTrue(s.contains("card(\"इमरजेंसी SOS\""))
        for (w in listOf("Contacts से चुनें", "नंबर टाइप करें", "बदलें", "हटाएँ", "SOS अभी शुरू करें", "रद्द करें", "READ_PHONE_STATE", "READ_CONTACTS", "SOS_CALL_ALL"))
            assertTrue(w, s.contains(w))
        assertTrue(src("SettingsActivity.kt").contains("sosPicked(requestCode, resultCode, data)"))
    }
}
