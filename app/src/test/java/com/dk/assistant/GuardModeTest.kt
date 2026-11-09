package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * नवंबर 2026 मालिक: "कोई और फ़ोन छुए / उठाए तो DK बोले, ज़ोर का सायरन - 'यह इसका फ़ोन नहीं है' -
 * मालिक बोले / खोले तो सायरन बंद, और फ़ोन अपने-आप लॉक।" (गार्ड मोड)
 */
class GuardModeTest {

    // ------------------------------------------------------------ बोलकर चालू / बंद

    @Test fun armByVoice() {
        listOf("DK गार्ड मोड चालू करो", "गार्ड मोड ऑन करो", "डीके गार्ड मोड ऑन कर दो", "guard mode on", "DK guard mode chalu karo",
            "सिक्योरिटी मोड चालू करो", "गार्ड मोड लगाओ", "एंटी थेफ्ट मोड चालू करो", "गार्ड मोड करो").forEach {
            assertEquals(it, GuardRules.Cmd.On(0), GuardRules.parse(it))
        }
    }

    @Test fun armWithTimer() {
        assertEquals(GuardRules.Cmd.On(60), GuardRules.parse("1 घंटे के लिए गार्ड मोड चालू करो"))
        assertEquals(GuardRules.Cmd.On(120), GuardRules.parse("दो घंटे के लिए गार्ड मोड ऑन करो"))
        assertEquals(GuardRules.Cmd.On(30), GuardRules.parse("30 मिनट के लिए गार्ड मोड चालू करो"))
        assertEquals(GuardRules.Cmd.On(90), GuardRules.parse("डेढ़ घंटे के लिए गार्ड मोड चालू करो"))
        assertEquals(GuardRules.Cmd.On(30), GuardRules.parse("आधे घंटे के लिए गार्ड मोड चालू करो"))
        assertEquals(0, GuardRules.minutes("गार्ड मोड चालू करो"))
    }

    @Test fun disarmByVoice() {
        listOf("गार्ड मोड बंद करो", "DK guard mode off", "गार्ड मोड हटाओ", "सिक्योरिटी मोड बंद कर दो").forEach {
            assertTrue(it, GuardRules.parse(it) is GuardRules.Cmd.Off)
        }
        // बंद पहले: "चालू है तो बंद करो"
        assertTrue(GuardRules.parse("गार्ड मोड चालू है तो बंद करो") is GuardRules.Cmd.Off)
    }

    @Test fun notForOtherThings() {
        listOf("गार्ड मोड चालू मत करो", "गार्ड मोड कैसे चालू करें", "गार्ड मोड क्या है", "गार्ड मोड चालू नहीं करना",
            "फ़ोन लॉक करो", "अलार्म बंद करो", "सायरन बंद करो", "वॉल्यूम बढ़ाओ", "इंस्टाग्राम खोलो", "security guard ki job", "").forEach {
            assertNull(it, GuardRules.parse(it))
        }
    }

    @Test fun sirenOffOnlyWhileAlarming() {
        assertNull(GuardRules.parse("सायरन बंद करो", alarming = false))
        assertTrue(GuardRules.parse("सायरन बंद करो", alarming = true) is GuardRules.Cmd.Off)
        // घड़ी वाला अलार्म (AlarmOff) कभी नहीं छिनेगा
        assertNull(GuardRules.parse("अलार्म बंद करो", alarming = true))
    }

    @Test fun secretCode() {
        assertTrue(GuardRules.codeOk("नीला आसमान", "नीला आसमान"))
        assertTrue(GuardRules.codeOk("DK नीला आसमान, बंद करो", "नीला  आसमान"))
        assertTrue(GuardRules.codeOk("Blue Sky", "blue sky"))
        assertFalse(GuardRules.codeOk("लाल आसमान", "नीला आसमान"))
        // खाली / बहुत छोटा शब्द = कभी नहीं (वरना हर बात से बंद)
        assertFalse(GuardRules.codeOk("कुछ भी", ""))
        assertFalse(GuardRules.codeOk("ab cd", "ab"))
    }

    // ------------------------------------------------------------ हिलना / उठाना

    private fun still(m: GuardRules.Motion, from: Long, to: Long, x: Double = 0.0, y: Double = 0.0, z: Double = 9.81): Boolean {
        var t = from; var hit = false
        while (t <= to) { if (m.feed(x, y, z, t)) hit = true; t += 20 }
        return hit
    }

    @Test fun stillPhoneNeverRings() {
        GuardRules.Sense.values().forEach { s ->
            val m = GuardRules.Motion(s)
            // थोड़ा शोर (sensor noise) भी
            var t = 0L; var hit = false
            while (t < 30_000) { if (m.feed(0.02 * sin(t / 37.0), 0.02 * cos(t / 53.0), 9.81, t)) hit = true; t += 20 }
            assertFalse(s.name, hit)
            assertTrue(m.ready)
        }
    }

    @Test fun liftingRings() {
        GuardRules.Sense.values().forEach { s ->
            val m = GuardRules.Motion(s)
            assertFalse(still(m, 0, 2_000))
            // फ़ोन उठाकर तिरछा (40°)
            val a = Math.toRadians(40.0)
            assertTrue(s.name, still(m, 2_020, 3_500, 0.0, 9.81 * sin(a), 9.81 * cos(a)))
            assertTrue(m.lastWhy.isNotBlank())
        }
    }

    @Test fun joltRings() {
        val m = GuardRules.Motion(GuardRules.Sense.HIGH)
        assertFalse(still(m, 0, 2_000))
        var t = 2_020L; var hit = false
        while (t < 2_600) { if (m.feed(if ((t / 20) % 2 == 0L) 4.0 else -4.0, 0.0, 9.81, t)) hit = true; t += 20 }
        assertTrue(hit)
    }

    @Test fun beforeSettleNothing() {
        val m = GuardRules.Motion(GuardRules.Sense.HIGH)
        assertFalse(m.feed(5.0, 5.0, 5.0, 0))
        assertFalse(m.feed(0.0, 9.0, 1.0, 500))
        assertFalse(m.ready)
    }

    // ------------------------------------------------------------ लॉक स्क्रीन पर क्या खुला

    @Test fun powerMenuVsPinPad() {
        assertTrue(GuardRules.isPowerMenu(listOf("Power off", "Restart", "Emergency")))
        assertTrue(GuardRules.isPowerMenu(listOf("पावर बंद", "रीस्टार्ट")))
        // PIN वाले खाने में भी "Emergency call" लिखा होता है - वह पावर मेनू नहीं
        val pin = listOf("Enter PIN", "Emergency call", "1", "2", "3")
        assertFalse(GuardRules.isPowerMenu(pin))
        assertTrue(GuardRules.isBouncer(pin))
        assertTrue(GuardRules.isBouncer(listOf("Draw your pattern")))
        assertFalse(GuardRules.isBouncer(listOf("12:30", "Monday")))
        assertTrue(GuardRules.isQuickSettings(listOf("Airplane mode", "Mobile data", "Wi-Fi")))
        assertFalse(GuardRules.isQuickSettings(listOf("Wi-Fi")))
    }

    // ------------------------------------------------------------ सायरन / SMS / बोली

    @Test fun sirenSound() {
        val p = GuardRules.sirenPcm(24_000, 1_000)
        assertEquals(24_000, p.size)
        assertTrue(p.maxOf { it.toInt() } > 20_000)
        assertTrue(p.minOf { it.toInt() } < -20_000)
    }

    @Test fun smsAndNumbers() {
        val s = GuardRules.smsText("फ़ोन उठाया", "10:30 PM", 19.99761, 73.78977)
        assertTrue(s.contains("https://maps.google.com/?q=19.99761,73.78977"))
        assertTrue(GuardRules.smsText("x", "y", null, null).contains("लोकेशन नहीं मिली"))
        assertTrue(GuardRules.validNumber("+91 98765 43210"))
        assertTrue(GuardRules.validNumber("9876543210"))
        assertFalse(GuardRules.validNumber("12345"))
        assertFalse(GuardRules.validNumber("abc"))
    }

    @Test fun timerAndSay() {
        assertFalse(GuardRules.expired(0, 999_999))
        assertTrue(GuardRules.expired(1_000, 1_000))
        assertFalse(GuardRules.expired(2_000, 1_000))
        assertEquals("1 घंटे", GuardRules.label(60))
        assertEquals("1 घंटे 30 मिनट", GuardRules.label(90))
        assertTrue(GuardRules.armSay(60, true).startsWith("1 घंटे के लिए स्क्रीन गार्ड"))   // नवंबर 2026: नाम बदला (मालिक)
        assertTrue(GuardRules.armSay(0, false).contains("गुप्त शब्द"))
        assertTrue(GuardRules.SAY.contains("यह फ़ोन आपका नहीं है"))
    }

    // ------------------------------------------------------------ जोड़ (बाकी कुछ नहीं बदला)

    private fun src(name: String): String {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant/$name"), File(d, "app/src/main/java/com/dk/assistant/$name"))
                .firstOrNull { it.exists() }?.let { return it.readText() }
            d = d.parentFile
        }
        error("$name नहीं मिला")
    }

    @Test fun wiredIn() {
        val router = src("LocalRouter.kt")
        assertTrue(router.contains("GuardRules.parse(raw, Guard.alarming)"))
        assertTrue(router.contains("GuardRules.codeOk(raw, Guard.codeNow)"))
        val svc = src("DKAccessibilityService.kt")
        assertTrue(svc.contains("GuardRules.TYPE -> Guard.run(this, a)"))
        assertTrue(svc.contains("Guard.onEvent(this, event)"))
        assertTrue(svc.contains("Guard.resume(this)"))
        assertTrue(svc.contains("Guard.heardStop(this@DKAccessibilityService, heard)"))
        // गार्ड चालू = DK आवाज़ से फ़ोन नहीं खोलेगा
        assertTrue(src("DKAccessibilityScreen.kt").contains("Guard.blocksUnlock() ?:"))
        assertTrue(src("LiveFast.kt").contains("GuardRules.TYPE"))
        assertTrue(src("voice/HeyDk.kt").contains("GuardRules.SAY"))
        val manifest = src("../../../../AndroidManifest.xml")
        assertTrue(manifest.contains(".GuardAlarmActivity"))
        val set = SetSrc.all()
        assertTrue(set.contains("\"guard\" -> { title.text = \"स्क्रीन गार्ड\""))   // नवंबर 2026: नाम बदला (मालिक)
        assertTrue(set.contains("secGuard()"))
        assertTrue(SettingsSearch.SECTIONS.containsKey("guard"))
    }

    @Test fun alarmKeepsMediaAndRestores() {
        val g = src("Guard.kt")
        // सिर्फ़ अलार्म वाली आवाज़ (STREAM_ALARM) - गाने / मीडिया की आवाज़ नहीं छूता, बाद में पहले जैसा
        assertTrue(g.contains("STREAM_ALARM"))
        assertFalse(g.contains("STREAM_MUSIC"))
        assertTrue(g.contains("fun restore") || g.contains("restore("))
        // फ़ोन में PIN न हो और गुप्त शब्द भी न हो = चालू नहीं (वरना कोई भी बंद कर दे)
        assertTrue(g.contains("secure("))
    }
}
