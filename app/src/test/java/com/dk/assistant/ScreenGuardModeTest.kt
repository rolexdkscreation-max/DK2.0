package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): "गार्ड मोड" का नाम "स्क्रीन गार्ड"; SMS में सटीक (GPS) लोकेशन, फ़ोन मालिक के पास लौटने तक
 * बार-बार; मालिक का चेहरा पहली बार सेट करने की जगह (Settings → स्क्रीन गार्ड) ताकि कैमरा मालिक को देखकर सायरन बंद करे;
 * DK लाइव से चालू (बंद सिर्फ़ मालिक: फ़ोन खोलना / चेहरा / गुप्त शब्द).
 */
class ScreenGuardModeTest {

    // ------------------------------------------------------------ नाम

    @Test fun newName_everywhere() {
        assertEquals("स्क्रीन गार्ड", GuardRules.NAME_HI)
        assertTrue(GuardRules.ARM_SAY.startsWith("स्क्रीन गार्ड"))
        assertTrue(GuardRules.armSay(0, true).startsWith("स्क्रीन गार्ड 5 सेकंड"))
        assertTrue(GuardRules.smsText("x", "अभी", 1.0, 2.0).startsWith("DK स्क्रीन गार्ड:"))
        assertEquals("स्क्रीन गार्ड", SettingsSearch.SECTIONS["guard"])
        assertTrue(SettingsSearch.search("स्क्रीन गार्ड").any { it.section == "guard" })
        assertTrue(SettingsSearch.search("गार्ड मोड").any { it.section == "guard" })      // पुराना नाम भी खोज में
    }

    @Test fun voice_newAndOldName() {
        listOf("स्क्रीन गार्ड चालू करो", "DK स्क्रीन गार्ड ऑन करो", "screen guard on", "स्क्रीन गार्ड लगाओ", "गार्ड मोड चालू करो")
            .forEach { assertEquals(it, GuardRules.Cmd.On(0), GuardRules.parse(it)) }
        assertEquals(GuardRules.Cmd.On(60), GuardRules.parse("1 घंटे के लिए स्क्रीन गार्ड चालू करो"))
        assertTrue(GuardRules.parse("स्क्रीन गार्ड बंद करो") is GuardRules.Cmd.Off)
        assertNull(GuardRules.parse("स्क्रीन गार्ड क्या है"))
        assertNull(GuardRules.parse("स्क्रीन गार्ड चालू मत करो"))
        // "स्क्रीन देखना चालू करो" (स्क्रीन शेयर) गार्ड नहीं
        assertNull(GuardRules.parse("स्क्रीन देखना चालू करो"))
        // फ़ोन लॉक वाले वाक्य से नहीं टकराता
        assertNull(PhoneLockVoice.match("स्क्रीन गार्ड बंद करो", needName = false, c = PhoneLockVoice.Cfg(lockOn = true, unlockOn = true,
            lock = PhoneLockVoice.list(PhoneLockVoice.DEFAULT_LOCK), unlock = PhoneLockVoice.list(PhoneLockVoice.DEFAULT_UNLOCK))))
    }

    // ------------------------------------------------------------ सटीक लोकेशन

    private fun fix(acc: Float, gps: Boolean, age: Long = 0, lat: Double = 20.0, lon: Double = 73.8) = GuardRules.Fix(lat, lon, acc, gps, age)

    @Test fun pickBest_prefersMostAccurateFresh() {
        val tower = fix(1_800f, gps = false, lat = 20.1)
        val gps = fix(8f, gps = true, lat = 20.0011)
        assertEquals(gps, GuardRules.pickBest(listOf(tower, gps)))
        // बराबर सटीक = GPS वाली
        assertTrue(GuardRules.pickBest(listOf(fix(10f, false), fix(10f, true)))!!.gps)
        // पुरानी (फ़ोन की याद वाली "उधर की") लोकेशन कभी नहीं
        assertEquals(tower, GuardRules.pickBest(listOf(tower, fix(3f, true, age = 10 * 60_000L))))
        assertNull(GuardRules.pickBest(listOf(fix(3f, true, age = GuardRules.LOC_MAX_AGE_MS + 1))))
        // बेकार
        assertNull(GuardRules.pickBest(listOf(fix(0f, true), fix(Float.NaN, true), fix(5f, true, lat = 0.0, lon = 0.0))))
        assertNull(GuardRules.pickBest(emptyList()))
    }

    @Test fun goodEnough_onlyCloseGps() {
        assertTrue(GuardRules.goodEnough(fix(12f, true)))
        assertFalse(GuardRules.goodEnough(fix(12f, false)))       // नेटवर्क वाली पर इंतज़ार जारी (GPS आ सकती है)
        assertFalse(GuardRules.goodEnough(fix(60f, true)))
        assertFalse(GuardRules.goodEnough(null))
    }

    @Test fun smsText_exactLink_accuracy_updates() {
        val m = GuardRules.smsText("फ़ोन उठाया", "9 नव, 02:10 PM", 19.9975123, 73.7898456, 7.4f, 0, true)
        assertTrue(m, m.contains("q=19.99751,73.78985"))           // 5 दशमलव (~1 मीटर)
        assertTrue(m, m.contains("सटीक लोकेशन") && m.contains("±7 मीटर") && m.contains("GPS"))
        val u = GuardRules.smsText("अपडेट", "अभी", 19.99, 73.78, 1_500f, 3, false)
        assertTrue(u, u.contains("लोकेशन अपडेट #3") && u.contains("±1.5 किमी") && u.contains("GPS नहीं मिला"))
        assertTrue(GuardRules.smsText("x", "अभी", null, null).contains("लोकेशन नहीं मिली"))
        // पुराना तरीका (4 चीज़ें) चलता रहे
        assertTrue(GuardRules.smsText("x", "अभी", 1.0, 2.0).contains("maps.google.com/?q=1.00000,2.00000"))
    }

    @Test fun resendSchedule_untilOwnerBack() {
        assertEquals(2 * 60_000L, GuardRules.nextLocDelayMs(1))
        assertEquals(2 * 60_000L, GuardRules.nextLocDelayMs(14))
        assertEquals(5 * 60_000L, GuardRules.nextLocDelayMs(15))
        assertEquals(15 * 60_000L, GuardRules.nextLocDelayMs(60))
        // पूरे शेड्यूल में कम से कम ~24 घंटे की लोकेशन
        val total = (1 until GuardRules.LOC_MAX_SMS).sumOf { GuardRules.nextLocDelayMs(it) }
        assertTrue(total >= 20 * 3_600_000L)
        assertTrue(GuardRules.LOC_MIN_GAP_MS < GuardRules.nextLocDelayMs(1))
    }

    // ------------------------------------------------------------ मालिक का चेहरा

    @Test fun faceCheck_needsEverything_andSaysWhy() {
        assertTrue(GuardRules.faceCheck(true, true, true, true))
        assertNull(GuardRules.faceWhyNot(true, true, true, true))
        assertFalse(GuardRules.faceCheck(true, false, true, true))
        assertTrue(GuardRules.faceWhyNot(true, false, true, true)!!.contains("मालिक का चेहरा सेट नहीं"))
        assertNotNull(GuardRules.faceWhyNot(true, true, false, true))
        assertTrue(GuardRules.faceWhyNot(true, true, true, false)!!.contains("कैमरे"))
        assertFalse(GuardRules.faceCheck(false, true, true, true))
        assertTrue(GuardRules.OWNER_HITS >= 2)
    }

    // ------------------------------------------------------------ जोड़ (source)

    private fun src(name: String): String {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant/$name"), File(d, "app/src/main/java/com/dk/assistant/$name"))
                .firstOrNull { it.exists() }?.let { return it.readText() }
            d = d.parentFile
        }
        error("$name नहीं मिला")
    }

    @Test fun ownerFaceSetup_inScreenGuardSettings() {
        val g = src("SettingsGuard.kt")
        assertTrue(g.contains("card(\"मालिक का चेहरा\""))
        assertTrue(g.contains("GuardFaceSetup.dialog(this, enroll = true)") && g.contains("GuardFaceSetup.dialog(this, enroll = false)"))
        val f = src("GuardFaceSetup.kt")
        assertTrue(f.contains("FaceAI.save(") && f.contains("FaceAI.download(") && f.contains("FaceAI.forget("))
        assertTrue(SettingsSearch.ENTRIES.any { it.section == "guard" && it.card == "मालिक का चेहरा" })
        // पुराना \"चेहरा पहचान\" अलग section वापस नहीं (मालिक ने हटवाया था)
        assertTrue("face" !in SettingsSearch.SECTIONS)
    }

    @Test fun alarmScreen_recognisesOwner() {
        val a = src("GuardAlarmActivity.kt")
        assertTrue(a.contains("GuardRules.faceCheck(") && a.contains("GuardRules.faceWhyNot("))
        assertTrue(a.contains("ownerHits >= GuardRules.OWNER_HITS") && a.contains("Guard.disarm(this, \"मालिक का चेहरा पहचाना"))
    }

    @Test fun preciseLocation_andRepeat_wired() {
        val g = src("Guard.kt")
        assertTrue(g.contains("suspend fun preciseFix("))
        assertTrue(g.contains("LocationManager.GPS_PROVIDER") && g.contains("requestLocationUpdates("))
        assertFalse("SMS में अब पहली आई (टावर वाली) लोकेशन नहीं", g.contains("LocalInfo.fresh(c, 12_000)"))
        assertFalse(g.contains("getLastKnownLocation"))
        assertTrue(g.contains("GuardRules.pickBest(") && g.contains("GuardRules.nextLocDelayMs("))
        assertTrue(g.contains("startTracking(app, why)"))
        // मालिक लौटा (disarm) = लोकेशन भेजना बंद; restart के बाद जारी
        assertTrue(g.contains("locJob?.cancel(); locJob = null"))
        assertTrue(g.contains("putBoolean(P_TRACK, false)") && g.contains("Prefs.bool(app, P_TRACK, false)"))
    }

    @Test fun dkLive_canOnlyTurnOn() {
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains("put(\"name\", \"screen_guard\")"))
        assertTrue(src("GeminiLiveTools.kt").contains("\"screen_guard\" -> Guard.liveTool(svc, args.optInt(\"minutes\", 0))"))
        val g = src("Guard.kt")
        val tool = g.substringAfter("fun liveTool(").substringBefore("\n\n")
        assertTrue(tool.contains("arm(svc"))
        assertFalse("DK लाइव का tool बंद नहीं कर सकता", tool.contains("disarm") || tool.contains("offByVoice"))
        assertTrue(LiveConnect.features(autoOn = false, autoKinds = emptyList(), oneKey = false).contains("screen_guard"))
    }
}
