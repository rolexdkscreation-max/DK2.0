package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 22 नवंबर 2026 (मालिक): DK लाइव बंद हो तब भी notification बोलकर बताओ */
class NotifSpeakTest {
    private val NR = NotifSpeakRules

    private fun src(name: String): String {
        var d: java.io.File? = java.io.File("").absoluteFile
        while (d != null) {
            listOf(java.io.File(d, "src/main/java/com/dk/assistant/$name"), java.io.File(d, "app/src/main/java/com/dk/assistant/$name"))
                .firstOrNull { it.exists() }?.let { return it.readText() }
            d = d.parentFile
        }
        error("$name नहीं मिला")
    }

    private fun why(on: Boolean = true, live: Boolean = false, call: Boolean = false, msgOnly: Boolean = true, isMsg: Boolean = true,
                    silent: Boolean = false, respect: Boolean = true, night: Boolean = false, nightQuiet: Boolean = true, hidden: Boolean = false) =
        NR.skipWhy(on, live, call, msgOnly, isMsg, silent, respect, night, nightQuiet, hidden)

    @Test fun speaksOnlyWhenLiveOff() {
        assertNull(why())
        assertEquals("सेटिंग बंद", why(on = false))
        assertEquals("DK लाइव चालू - वहीं बताया जाता है", why(live = true))      // Live में पहले जैसा (दोबारा नहीं)
        assertEquals("कॉल चल रही", why(call = true))
        assertEquals("DK Privacy में छिपा ऐप", why(hidden = true))
    }

    @Test fun filters() {
        assertEquals("मैसेज वाला नहीं", why(isMsg = false))
        assertNull(why(isMsg = false, msgOnly = false))
        assertEquals("फ़ोन साइलेंट / DND", why(silent = true))
        assertNull(why(silent = true, respect = false))
        assertEquals("रात का चुप समय", why(night = true))
        assertNull(why(night = true, nightQuiet = false))
    }

    @Test fun nightHours() {
        assertTrue(NR.night(23)); assertTrue(NR.night(0)); assertTrue(NR.night(6))
        assertFalse(NR.night(7)); assertFalse(NR.night(14)); assertFalse(NR.night(22))
    }

    @Test fun lines() {
        assertEquals("राहुल का WhatsApp पर मैसेज आया है — कब मिलोगे?", NR.line(NotifSpeakRules.Item("WhatsApp", "राहुल", "कब मिलोगे?"), true))
        assertEquals("राहुल का WhatsApp पर मैसेज आया है।", NR.line(NotifSpeakRules.Item("WhatsApp", "राहुल", "कब मिलोगे?"), false))
        assertEquals("Gmail का notification आया है।", NR.line(NotifSpeakRules.Item("Gmail", "Gmail", ""), true))
        // OTP कभी ज़ोर से नहीं
        val otp = NR.line(NotifSpeakRules.Item("Messages", "HDFC", "Your OTP is 482913"), true)
        assertFalse(otp.contains("482913")); assertTrue(otp.contains("कोड या निजी"))
        val long = NR.line(NotifSpeakRules.Item("Telegram", "अमित", "बहुत ".repeat(60)), true)
        assertTrue(long.length < 200 && long.endsWith("…"))
    }

    @Test fun batchAndRepeat() {
        val items = (1..5).map { NotifSpeakRules.Item("WhatsApp", "दोस्त $it", "हाय") }
        val s = NR.speech(items, true)
        assertTrue(s.startsWith("5 नए notification।") && s.contains("दोस्त 3") && !s.contains("दोस्त 4") && s.endsWith("और 2 बाकी हैं।"))
        assertEquals("", NR.speech(emptyList(), true))
        val said = mapOf(NR.sayKey("WhatsApp", "राहुल") to 1_000L)
        assertTrue(NR.recentlySaid("WhatsApp", "राहुल", 1_000 + NR.SAME_GAP_MS - 1, said))
        assertFalse(NR.recentlySaid("WhatsApp", "राहुल", 1_000 + NR.SAME_GAP_MS, said))
        assertFalse(NR.recentlySaid("WhatsApp", "अमित", 2_000, said))
    }

    @Test fun wiredAndInVoiceSettings() {
        val v = SetSrc.all().substringAfter("fun SettingsActivity.secVoice() {").substringBefore("fun SettingsActivity.secDkMemory() {")
        assertTrue(v.contains("card(\"Notification बोलकर बताओ (DK लाइव बंद हो तब भी)\""))
        assertEquals("voice", SettingsSearch.ENTRIES.first { it.card == "Notification बोलकर बताओ (DK लाइव बंद हो तब भी)" }.section)
        val l = src("DKNotifListener.kt")
        assertTrue(l.contains("if (push && isNew && !DKState.live)") && l.contains("NotifSpeak.onPosted("))
        assertFalse(NotifSpeakRules.PREF_ON.isBlank())
    }
}
