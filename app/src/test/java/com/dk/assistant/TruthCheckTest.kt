package com.dk.assistant

import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.ui.HomeTabs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 नवंबर 2026 (मालिक): "काम नहीं हुआ फिर भी 'हाँ हो गया'; कोई कॉल नहीं फिर भी 'कॉल आ रहा है' - झूठा दावा ना करे,
 * दोबारा चेक करे" + "अलग 5-टैब वाली नीचे की पट्टी".
 */
class TruthCheckTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    // ---------------------------------------------------------------- कॉल का दावा
    @Test fun claimsCall_realClaims() {
        assertTrue(TruthCheck.claimsCall("राहुल का कॉल आ रहा है, उठाऊँ या काट दूँ?"))
        assertTrue(TruthCheck.claimsCall("बॉस, फ़ोन बज रहा है।"))
        assertTrue(TruthCheck.claimsCall("Rahul is calling you."))
        assertTrue(TruthCheck.claimsCall("ठीक है। मम्मी का कॉल आ रहा है।"))
    }

    @Test fun claimsCall_notClaims() {
        assertFalse(TruthCheck.claimsCall("अभी कोई कॉल नहीं आ रहा"))
        assertFalse(TruthCheck.claimsCall("राहुल का कॉल आया था"))
        assertFalse(TruthCheck.claimsCall("क्या कोई कॉल आ रहा है?"))
        assertFalse(TruthCheck.claimsCall("No call is ringing right now."))
        assertFalse(TruthCheck.claimsCall("आज मौसम अच्छा है।"))
        assertFalse(TruthCheck.claimsCall("राहुल की missed call है"))
    }

    @Test fun falseCall_onlyWhenPhoneSaysNoCall() {
        val now = 1_000_000L
        val s = "राहुल का कॉल आ रहा है।"
        assertFalse("घंटी बज रही = सच", TruthCheck.falseCall(s, true, false, now - 2_000, 0, now))
        assertFalse("कॉल चल रही", TruthCheck.falseCall(s, false, true, 0, 0, now))
        assertTrue("कोई कॉल नहीं, कोई ख़बर नहीं = झूठ", TruthCheck.falseCall(s, false, false, 0, 0, now))
        assertFalse("घंटी अभी-अभी बंद (4s तक बात रास्ते में)", TruthCheck.falseCall(s, false, false, now - 9_000, now - 1_000, now))
        assertTrue("घंटी बंद हुए 10s", TruthCheck.falseCall(s, false, false, now - 30_000, now - 10_000, now))
        assertFalse("दावा ही नहीं", TruthCheck.falseCall("अभी कोई कॉल नहीं आ रहा।", false, false, 0, 0, now))
    }

    @Test fun callGoneNote_saysStopped() {
        assertTrue(TruthCheck.callGoneNote("राहुल", TruthCheck.CallEnd.ENDED).contains("STOPPED ringing"))
        assertTrue(TruthCheck.callGoneNote("", TruthCheck.CallEnd.ANSWERED).contains("picked up"))
        assertTrue(TruthCheck.falseCallNote("कॉल आ रहा है").contains("NO call is ringing"))
    }

    @Test fun appCall_missedAndStale() {
        assertTrue(TruthCheck.missedCall("Missed voice call", "Rahul"))
        assertTrue(TruthCheck.missedCall("राहुल", "छूटी हुई कॉल"))
        assertFalse(TruthCheck.missedCall("Rahul", "Incoming voice call"))
        val now = 10_000_000L
        assertTrue(TruthCheck.freshRing(0L, now))                 // पता नहीं = पुराना व्यवहार
        assertTrue(TruthCheck.freshRing(now - 5_000, now))
        assertFalse(TruthCheck.freshRing(now - 120_000, now))     // 2 मिनट पुराना = घंटी कब की बंद
    }

    // ---------------------------------------------------------------- काम का दावा
    @Test fun notConfirmed_isNotDone() {
        assertTrue(TruthCheck.notConfirmed("NOT_CONFIRMED: कॉल अभी भी चल रही है"))
        assertTrue(TruthCheck.notConfirmed("opened com.whatsapp (not confirmed on screen)"))
        assertFalse(TruthCheck.notConfirmed("call kaat di (verified)"))
        assertTrue(TruthCheck.saysFailed("ऐप नहीं खुला"))
        assertTrue(TruthCheck.saysFailed("Timeout waiting for screen"))
        assertFalse(TruthCheck.saysFailed("DONE: speaker on"))
        assertFalse(LiveFast.ok("open_app", "opened com.whatsapp (not confirmed on screen)"))
        assertTrue(LiveFast.ok("open_app", "opened com.whatsapp"))
        assertTrue(DoneGate.notDoneYet("NOT_CONFIRMED: स्पीकर नहीं बदला"))
        assertTrue(LiveFast.quickResult(QuickCommands.Quick(org.json.JSONObject().put("type", "end_call"), "कॉल काट दिया"),
            "NOT_CONFIRMED: कॉल अभी भी चल रही है", false).startsWith("NOT_CONFIRMED"))
    }

    @Test fun honestSay_neverDone() {
        val a = TruthCheck.honestSay("end_call", "NOT_CONFIRMED: कॉल अभी भी चल रही है - कटी नहीं")
        assertTrue(a.startsWith("मैंने दोबारा चेक किया") && a.contains("कटी नहीं"))
        assertTrue(TruthCheck.honestSay("open_app", "opened x (not confirmed on screen)").contains("नहीं दिखा"))
        for (t in listOf("open_app", "speaker", "x")) assertFalse(TruthCheck.honestSay(t, "NOT_CONFIRMED").contains("हो गया"))
    }

    @Test fun twice_rechecksOnce() = runBlocking {
        var n = 0
        assertEquals(true to false, TruthCheck.twice(1, 1) { n++; true })
        n = 0
        assertEquals(true to true, TruthCheck.twice(1, 1) { n++ > 0 })
        assertEquals(false to true, TruthCheck.twice(1, 1) { false })
    }

    @Test fun doneGate_newActionWords() {
        for (s in listOf("कल सुबह का रिमाइंडर लगा दो", "स्क्रीनशॉट ले लो", "फ़ोन लॉक कर दो", "स्पीकर चालू करो", "पिज़्ज़ा ऑर्डर कर दो"))
            assertTrue(s, DoneGate.isAction(s))
        assertFalse(DoneGate.isAction("आज मौसम कैसा है"))
        assertFalse(DoneGate.isAction("तुम्हारा नाम बताओ यार"))
    }

    // ---------------------------------------------------------------- जुड़ाव (source)
    @Test fun wiring_callGoneAndGuard() {
        assertTrue(src("DKAccessibilityCalls.kt").contains("gemLive.noteCallGone(whoNote"))
        assertTrue(src("GeminiLiveServer.kt").contains("falseCallCheck(it)"))
        assertTrue(src("GeminiLiveTruth.kt").contains("\"turnComplete\", false"))
        assertTrue(src("AppCall.kt").contains("TruthCheck.missedCall(title, text)"))
        assertTrue(src("DKNotifListener.kt").contains("sbn.postTime"))
        assertTrue(src("BrainManager.kt").contains("append(TruthCheck.PROMPT)"))
        assertTrue(src("AgentRunner.kt").contains("TruthCheck.honestSay(type, r)"))
        val svc = src("DKAccessibilityService.kt")
        assertTrue(svc.contains("सर, मैंने दोबारा देखा - कॉल अभी कटी नहीं"))
        assertTrue(src("CallReply.kt").contains("TruthCheck.notConfirmed(r) -> \"NOT DONE"))
        val calls = src("DKAccessibilityCalls.kt")
        assertTrue(calls.contains("call kaat di (verified)") && calls.contains("calls.speakerIsOn()"))
    }

    // ---------------------------------------------------------------- 5-टैब पट्टी
    @Test fun homeTabs_fiveInOrder() {
        assertEquals(listOf("होम", "स्कैन", "माइक", "चैट", "मेमोरी"), HomeTabs.order())
        assertEquals(HomeTabs.Tab.MIC, HomeTabs.TABS[2])                    // माइक बीच में
        val f = FeatureFlags.byKey(FeatureFlags.HIDE_HOME_TABS)!!
        assertFalse("छुपाने वाला स्विच शुरू में बंद = पट्टी दिखे", f.default)
        val it = DkSettings.ITEMS.first { it.id == "home_tabs" }
        assertTrue(it.inverted && it.store == DkSettings.STORE_FLAGS)
        assertTrue(DkSettings.isOn(it, false))                                // pref false = पट्टी चालू
    }

    @Test fun homeTabs_wired() {
        val scr = src("dkai/DkaiScreen.kt")
        assertTrue(scr.contains("buildInput(); buildTabs()"))
        assertTrue(scr.contains("HomeTabs.Tab.SCAN -> { DkaiUi.closeTop(root); hideKeyboard(); if (!host.tab(t))") && scr.contains("HomeTabs.Tab.MIC -> micTap()"))
        assertTrue(src("ChatActivity.kt").contains(".putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SCAN)"))
        val main = src("MainActivity.kt")
        assertTrue(main.contains("HomeTabs.Tab.CHAT -> { openChat(); true }"))
        assertTrue(main.contains("HomeTabs.Tab.MEMORY -> { startActivity(Intent(this, MemoryActivity::class.java)); true }"))
        assertTrue(main.contains("dkai?.syncTabs()"))
        assertTrue(src("SettingsActivity.kt").contains("secHomeTabs()"))
    }
}
