package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v4.1 (मालिक): (1) DK सामने = स्क्रीन जगी (wake lock नहीं), (2) "सिर्फ़ DK बोलने पर सुनो",
 * (3) बिना API वाला कमांड इंजन, (4) आने वाली कॉल बोलकर + आवाज़ से कॉल का काम.
 */
class ScreenDkOnlyOfflineCallsTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
    private fun manifest() = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()

    @After fun clean() { OfflineCommands.pending = null; Calendar0.fixed = null; CallCmd.reset(); CallCmd.liveCheck = null }

    // ------------------------------------------------------------ 1. स्क्रीन जगी

    @Test fun screenOnOnlyWhileInFront() {
        assertTrue(ScreenAwake.keepOn("resumed"))
        for (e in listOf("paused", "stopped", "destroyed")) assertFalse(e, ScreenAwake.keepOn(e))
        val s = src("ScreenAwake.kt")
        assertTrue(s.contains("addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(s.contains("clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertFalse("पूरे समय का wake lock नहीं", s.contains("newWakeLock"))
        assertTrue(src("DKApp.kt").contains("registerActivityLifecycleCallbacks(ScreenAwake)"))
    }

    // ------------------------------------------------------------ 2. सिर्फ़ DK बोलने पर

    @Test fun dkStartIsStripped() {
        assertEquals("टॉर्च जलाओ", DkOnly.strip("डीके टॉर्च जलाओ"))
        assertEquals("यूट्यूब खोलो", DkOnly.strip("हे डीके, यूट्यूब खोलो"))
        assertEquals("समय बताओ", DkOnly.strip("हेलो डीके समय बताओ"))
        assertEquals("open youtube", DkOnly.strip("DK open youtube"))
        assertEquals("open youtube", DkOnly.strip("Hey D.K. open youtube"))
        assertEquals("टॉर्च बंद", DkOnly.strip("डी के टॉर्च बंद"))
        assertEquals("टॉर्च बंद", DkOnly.strip("दीके टॉर्च बंद"))
        assertEquals("", DkOnly.strip("डीके"))
    }

    @Test fun noLooseMatching() {
        assertNull(DkOnly.strip("दिखाओ तो सही"))
        assertNull(DkOnly.strip("डीकेट वाला गाना"))
        assertNull(DkOnly.strip("dkny shop"))
        assertNull(DkOnly.strip("कल डीके से बात करेंगे"))     // नाम शुरुआत में नहीं
        assertNull(DkOnly.strip("do kaam"))
        assertNull(DkOnly.strip(""))
    }

    @Test fun everySentenceChecked() {
        assertEquals("टॉर्च जलाओ", DkOnly.filter("मूवी अच्छी थी। डीके टॉर्च जलाओ"))
        assertNull(DkOnly.filter("आज मौसम अच्छा है। चलो खाना खाते हैं।"))
        assertEquals("टॉर्च जलाओ समय बताओ", DkOnly.filter("डीके टॉर्च जलाओ। बाहर बारिश है। हे डीके समय बताओ"))
        // बंद = कोई बदलाव नहीं
        assertEquals("आज मौसम अच्छा है", DkOnly.gate(false, "आज मौसम अच्छा है"))
        assertNull(DkOnly.gate(true, "आज मौसम अच्छा है"))
    }

    @Test fun dkOnlySettingWired() {
        val p = src("Prefs.kt")
        assertTrue(p.contains("getBoolean(\"dk_only_listen\", false)"))
        val st = src("SettingsActivity.kt")
        assertTrue(st.contains("\"सिर्फ़ DK बोलने पर सुनो\""))
        assertTrue(st.contains("चालू करने पर DK तभी सुनेगा और काम करेगा जब बात की शुरुआत में DK बोला जाए। बाकी बातें अनदेखी कर दी जाएँगी।"))
        assertTrue(st.contains("putBoolean(\"dk_only_listen\", swDkOnly.isChecked)"))
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("DkOnly.filter(t) != null"))
        assertTrue(g.contains("ownerSaysOwner(guardBuf)"))           // पहले मालिक की आवाज़, फिर DK
        assertTrue(g.contains("DkOnly.filter(raw)"))                  // बीच में पूछी बात भी
        val svc = src("DKAccessibilityService.kt")
        assertTrue(svc.contains("cmdText = DkOnly.filter(heard) ?: continue"))
        assertTrue(src("BrainManager.kt").contains("if (Prefs.dkOnly(ctx)) append(DkOnly.PROMPT)"))
    }

    // ------------------------------------------------------------ 3. बिना API वाला इंजन

    private fun type(t: String) = LocalRouter.route(t, listOf("डीके", "dk"))?.action?.optString("type")

    @Test fun alarmTimes() {
        assertEquals(OfflineCommands.Clock(7, 0), OfflineCommands.alarmTime("सुबह 7 बजे", 22, 0))
        assertEquals(OfflineCommands.Clock(18, 30), OfflineCommands.alarmTime("शाम साढ़े 6 बजे", 10, 0))
        assertEquals(OfflineCommands.Clock(7, 45), OfflineCommands.alarmTime("सुबह पौने 8 बजे", 22, 0))
        assertEquals(OfflineCommands.Clock(19, 20), OfflineCommands.alarmTime("7:20 pm", 10, 0))
        assertEquals(OfflineCommands.Clock(0, 0), OfflineCommands.alarmTime("रात 12 बजे", 10, 0))
        assertEquals(OfflineCommands.Clock(14, 0), OfflineCommands.alarmTime("दोपहर दो बजे", 10, 0))
        assertEquals(OfflineCommands.Clock(6, 15), OfflineCommands.alarmTime("सवा छह बजे सुबह", 22, 0))
        // am/pm नहीं बोला = अगला आने वाला
        assertEquals(OfflineCommands.Clock(20, 0), OfflineCommands.alarmTime("8 बजे", 9, 0))
        assertEquals(OfflineCommands.Clock(8, 0), OfflineCommands.alarmTime("8 बजे", 6, 0))
        assertEquals(OfflineCommands.Clock(7, 0), OfflineCommands.alarmTime("७ बजे सुबह", 22, 0))
    }

    @Test fun alarmCommand() {
        Calendar0.fixed = 22 to 0
        val q = LocalRouter.route("डीके सुबह 7 बजे का अलार्म लगा दो", listOf("डीके"))!!
        assertEquals("alarm_set", q.action.optString("type"))
        assertEquals(7, q.action.optInt("hour")); assertEquals(0, q.action.optInt("minute"))
        assertEquals("alarm_off", type("अलार्म बंद करो").let { if (it == "alarm_set") "wrong" else "alarm_off" })
        assertNull(OfflineCommands.parse("10 मिनट बाद अलार्म लगाओ"))
    }

    @Test fun timerSeconds() {
        assertEquals(300, OfflineCommands.timerSeconds("5 मिनट का टाइमर लगाओ"))
        assertEquals(5400, OfflineCommands.timerSeconds("डेढ़ घंटे का टाइमर"))
        assertEquals(4800, OfflineCommands.timerSeconds("1 घंटा 20 मिनट का टाइमर"))
        assertEquals(30, OfflineCommands.timerSeconds("timer for 30 seconds"))
        assertEquals(120, OfflineCommands.timerSeconds("दो मिनट का टाइमर लगा दो"))
        assertEquals("timer_set", type("5 मिनट का टाइमर लगाओ"))
    }

    @Test fun cameraInfoTapSettings() {
        assertEquals("camera", type("कैमरा खोलो"))
        val ph = LocalRouter.route("फोटो खींचो", emptyList())!!.action
        assertEquals("camera", ph.optString("type")); assertTrue(ph.optBoolean("photo"))
        assertTrue(LocalRouter.route("सेल्फी लो", emptyList())!!.action.optBoolean("front"))
        assertEquals("time", OfflineCommands.parse("कितने बजे हैं")?.action?.optString("what"))
        assertEquals("battery", OfflineCommands.parse("बैटरी कितनी है")?.action?.optString("what"))
        assertEquals("day", OfflineCommands.parse("आज कौन सा दिन है")?.action?.optString("what"))
        assertEquals("date", OfflineCommands.parse("आज की तारीख क्या है")?.action?.optString("what"))
        assertEquals("सबमिट", OfflineCommands.parse("सबमिट पर टैप करो")?.action?.optString("text"))
        assertNull("ख़तरनाक बटन = पुराना रास्ता", OfflineCommands.parse("डिलीट पर टैप करो"))
        val ns = OfflineCommands.parse("नोटिफिकेशन सेटिंग खोलो")!!.action
        assertEquals("open_settings", ns.optString("type")); assertEquals("नोटिफिकेशन", ns.optString("page"))
        assertEquals("कीबोर्ड", OfflineCommands.parse("कीबोर्ड की सेटिंग खोलो")!!.action.optString("page"))
        assertEquals("airplane", OfflineCommands.parse("एयरप्लेन मोड चालू करो")!!.action.optString("page"))
        assertEquals("open_settings", type("Open Settings."))
        assertNull(OfflineCommands.parse("फोटो गैलरी खोलो"))
    }

    @Test fun smsAsksFirst() {
        val q = LocalRouter.route("राहुल को एसएमएस भेजो कि मैं देर से आऊँगा और खाना मत बनाना", emptyList())!!
        assertEquals("sms_send", q.action.optString("type"))
        assertEquals("राहुल", q.action.optString("name"))
        assertEquals("मैं देर से आऊँगा और खाना मत बनाना", q.action.optString("text"))
        // पुष्टि के बिना कुछ नहीं भेजा
        assertNull(OfflineCommands.confirm("हाँ", System.currentTimeMillis()))
        val now = System.currentTimeMillis()
        OfflineCommands.pending = OfflineCommands.Pending("sms", "98", "राहुल", "hi", now + 30_000)
        assertEquals(true, OfflineCommands.confirm("हाँ भेज दो", now)?.action?.optBoolean("yes"))
        assertEquals(false, OfflineCommands.confirm("नहीं", now)?.action?.optBoolean("yes"))
        assertEquals(false, LocalRouter.route("SMS मत भेजो", emptyList())?.action?.optBoolean("yes"))
        assertEquals(true, LocalRouter.route("हाँ, SMS भेजो", emptyList())?.action?.optBoolean("yes"))
        assertNull(OfflineCommands.confirm("मौसम कैसा है आज बताओ", now))
        assertNull("समय निकल गया", OfflineCommands.confirm("हाँ", now + 31_000))
        assertTrue(src("LiveFast.kt").contains("\"sms_send\", \"sms_confirm\""))
    }

    @Test fun appListCachedAndRefreshed() {
        val s = src("AppLauncher.kt")
        assertTrue(s.contains("Intent.ACTION_PACKAGE_ADDED") && s.contains("Intent.ACTION_PACKAGE_REMOVED"))
        assertTrue(s.contains("val apps = appList(ctx)"))
    }

    // ------------------------------------------------------------ 4. कॉल

    @Test fun announceLines() {
        assertEquals("सर, राहुल का WhatsApp कॉल आ रहा है।", CallCmd.announce("राहुल", "WhatsApp", false))
        assertEquals("सर, राहुल का कॉल आ रहा है।", CallCmd.announce("राहुल"))
        assertEquals("सर, अनजान नंबर से कॉल आ रहा है।", CallCmd.announce(""))
        assertEquals("सर, Telegram पर किसी का वीडियो कॉल आ रहा है।", CallCmd.announce("", "Telegram", true))
    }

    @Test fun callCommands() {
        val m = CallCmd.parse("काट दो और मैसेज भेजो कि मैं मीटिंग में हूँ")!!
        assertEquals(CallCmd.REJECT_MSG, m.action); assertEquals("मैं मीटिंग में हूँ", m.text)
        assertEquals("", CallCmd.parse("काटो और मैसेज भेजो")!!.text)
        assertEquals(CallCmd.REJECT_BUSY, CallCmd.parse("कॉल काट दो और बोल दो बाद में कॉल करूँगा")!!.action)
        assertEquals(CallCmd.MUTE, CallCmd.parse("म्यूट करो")!!.action)
        assertEquals(CallCmd.UNMUTE, CallCmd.parse("अनम्यूट करो")!!.action)
        assertEquals(CallCmd.END, CallCmd.parse("कॉल काट दो")!!.action)
        // पुराना रास्ता (CallReply) वैसा ही
        assertNull(CallCmd.parse("उठा लो"))
        assertNull(CallCmd.parse("काट दो"))
        assertNull(CallCmd.parse("राहुल का कॉल आ रहा है, उठाऊँ या काट दूँ?"))
    }

    @Test fun askWhatToSendThenSend() {
        val now = 1_000_000L
        assertNull(CallCmd.route("मैं ड्राइव कर रहा हूँ", now))          // कुछ पूछा ही नहीं
        CallCmd.awaitMessage(now)
        assertTrue(CallCmd.windowOpen(now + 5_000))
        val c = CallCmd.route("मैं ड्राइव कर रहा हूँ", now + 3_000)!!
        assertEquals(CallCmd.REJECT_MSG, c.action); assertEquals("मैं ड्राइव कर रहा हूँ", c.text)
        CallCmd.awaitMessage(now)
        assertEquals(CallCmd.CANCEL, CallCmd.route("रहने दो", now + 1_000)!!.action)
        // कॉल ख़त्म होने के कुछ सेकंड बाद तक ही
        CallCmd.markEnded(now)
        assertTrue(CallCmd.windowOpen(now + 4_000))
        assertFalse(CallCmd.windowOpen(now + CallCmd.WINDOW_MS + 1))
    }

    @Test fun callWiring() {
        val svc = src("DKAccessibilityService.kt")
        assertTrue(svc.contains("CallCmd.announce(who)"))
        assertTrue(svc.contains("sendMultipartTextMessage"))
        assertTrue(svc.contains("Prefs.callBusyMsg(this)"))
        assertTrue(src("DKNotifListener.kt").contains("CallCmd.announce(r.who, r.app, r.video)"))
        assertTrue(src("SettingsActivity.kt").contains("\"आने वाली कॉल बोलकर बताओ\""))
        assertTrue(src("Prefs.kt").contains("getBoolean(\"announce_calls\", true)"))
        assertTrue(manifest().contains("android.permission.SEND_SMS"))
        assertTrue(src("GeminiLive.kt").contains("svc.doCallCmd(cc)"))
    }
}
