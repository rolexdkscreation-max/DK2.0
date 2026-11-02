package com.dk.assistant

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026: DK लाइव (आवाज़ से आवाज़) के बीच "फोन लॉक / अनलॉक करो" - तुरंत, एक बार, बीच में रद्द नहीं।
 * और release workflow: एक version की दो release नहीं।
 */
class PhoneLockLiveTest {
    private val L = PhoneLockVoice.Act.LOCK
    private val U = PhoneLockVoice.Act.UNLOCK
    private val wake = listOf("डीके", "दीके", "डी के", "dk", "d k")
    private fun q(type: String) = QuickCommands.Quick(JSONObject().put("type", type), "")

    private fun srcDir(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant"), File(d, "app/src/main/java/com/dk/assistant"))
                .firstOrNull { File(it, "PhoneUnlocker.kt").exists() }?.let { return it }
            d = d.parentFile
        }
        error("PhoneUnlocker.kt नहीं मिला")
    }

    @After fun reset() { PhoneLockVoice.cfg = PhoneLockVoice.Cfg() }

    // ---------------------------------------------------------------- Live में कौन-सा वाक्य तुरंत
    @Test fun liveShortPhrases_workEvenWhenSettingsOff() {
        PhoneLockVoice.cfg = PhoneLockVoice.Cfg()      // Settings में बंद (पहले जैसा) - फिर भी Live में आम वाक्य
        assertEquals(L, PhoneLockVoice.instant("फोन लॉक करो", wake))
        assertEquals(L, PhoneLockVoice.instant("डीके फोन लॉक करो", wake))
        assertEquals(L, PhoneLockVoice.instant("DK फ़ोन लॉक कर दो", wake))
        assertEquals(U, PhoneLockVoice.instant("डीके फोन अनलॉक करो", wake))
        assertEquals(U, PhoneLockVoice.instant("फोन अनलॉक कर दो", wake))
        assertEquals(U, PhoneLockVoice.instant("unlock the phone", wake))
    }

    @Test fun liveCustomNamesAndPhrasesFromSettings() {
        PhoneLockVoice.cfg = PhoneLockVoice.Cfg(true, true, PhoneLockVoice.list("मायरा"), PhoneLockVoice.list("ताला लगाओ"), PhoneLockVoice.list("दरवाज़ा खोलो"))
        assertEquals(L, PhoneLockVoice.instant("मायरा ताला लगाओ", wake))
        assertEquals(U, PhoneLockVoice.instant("मायरा दरवाज़ा खोलो", wake))
        assertEquals(L, PhoneLockVoice.instant("फोन लॉक करो", wake))       // आम वाक्य भी
    }

    @Test fun liveDoesNotFireOnLongTalkOrNo() {
        assertNull(PhoneLockVoice.instant("फोन लॉक मत करो", wake))
        assertNull(PhoneLockVoice.instant("व्हाट्सएप लॉक करो", wake))
        assertNull(PhoneLockVoice.instant("मेरे दोस्त ने कहा कि फोन लॉक करना चाहिए क्या", wake))
        // DK की अपनी आवाज़ (\"फ़ोन लॉक कर दिया\") = हुक्म नहीं
        assertNull(PhoneLockVoice.instant("फ़ोन लॉक कर दिया", wake))
        assertNull(PhoneLockVoice.instant("ठीक है फोन अनलॉक कर रहा हूँ", wake))
    }

    @Test fun liveDkOnlyMode_needsName() {
        assertNull(PhoneLockVoice.instant("फोन लॉक करो", wake, needName = true))
        assertEquals(L, PhoneLockVoice.instant("डीके फोन लॉक करो", wake, needName = true))
    }

    // ---------------------------------------------------------------- दोहराव
    @Test fun lockRepeat_shortWindow_otherTasksSame() {
        val m = LiveFast.Memo()
        assertTrue(m.begin(q("lock"), 0))
        assertFalse("उसी पल दो रास्तों से = एक बार", m.begin(q("lock"), 1_000))
        // पहले 15s तक दोबारा \"फोन लॉक करो\" बेकार था
        assertTrue(m.begin(q("lock"), LiveFast.Memo.LOCK_BEGIN_MS + 1))
        val t = q("torch")
        assertTrue(m.begin(t, 100_000)); assertFalse(m.begin(t, 105_000))
    }

    @Test fun geminiPhoneTask_reusesRunningUnlock() {
        val m = LiveFast.Memo()
        val u = q("unlock")
        assertTrue(m.begin(u, 0))
        assertNull("PIN डल रहा है - Gemini इंतज़ार करे, दोबारा PIN नहीं", m.same(u, 2_000))
        m.finish(u, "phone unlock ho gaya")
        assertEquals("phone unlock ho gaya", m.same(u, 3_000))
        assertEquals(LiveFast.Memo.NOT_SAME, m.same(u, LiveFast.Memo.LOCK_SAME_MS + 1))
        assertEquals(LiveFast.Memo.NOT_SAME, m.same(q("lock"), 3_000))
        assertTrue(PhoneLockVoice.isLockType("lock")); assertTrue(PhoneLockVoice.isLockType("unlock")); assertFalse(PhoneLockVoice.isLockType("torch"))
    }

    // ---------------------------------------------------------------- source: रास्ते सच में जुड़े
    @Test fun liveWiring() {
        val g = LiveSrc.all()
        assertTrue(g.contains("internal fun GeminiLive.runLockNow("))
        assertTrue("caption आते ही", g.contains("runLockNow(PhoneLockVoice.quick(act), t, \"caption\")"))
        assertTrue("बोलना खत्म पर, 15s दोहराव-रोक से पहले", g.indexOf("runLockNow(PhoneLockVoice.quick(act), b, \"बोलना खत्म\")") in 0 until g.indexOf("if (!routeDedupe.fresh(b, System.currentTimeMillis())) return@launch"))
        // अलग job - fastJob रद्द होने पर आधा PIN नहीं
        val run = g.substring(g.indexOf("internal fun GeminiLive.runLockNow("))
        assertTrue(run.contains("svc.scope.launch {"))
    }

    @Test fun unlockIsSingleAndNotCancellable() {
        val a = AccSrc.all()
        assertTrue(a.contains("PhoneUnlocker.gate.withLock { withContext(NonCancellable) { unlockNow() } }"))
        assertTrue(a.contains("UnlockWakeActivity.open(this)"))
        val u = File(srcDir(), "PhoneUnlocker.kt").readText()
        assertTrue(u.contains("withContext(NonCancellable) { type(svc, km, kind, secret, screen) }"))
        assertTrue(PhoneUnlocker.KEY_GAP_MS <= 60)
        val w = File(srcDir(), "UnlockWakeActivity.kt").readText()
        assertTrue(w.contains("requestDismissKeyguard"))
        assertTrue(w.contains("setShowWhenLocked(true)") && w.contains("setTurnScreenOn(true)"))
        val man = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(man.contains("android:name=\".UnlockWakeActivity\""))
        assertTrue(man.contains("android.permission.TURN_SCREEN_ON"))
    }

    // ---------------------------------------------------------------- release: एक version एक बार
    @Test fun releaseWorkflow_noDuplicateVersion() {
        val wf = listOf(File("../.github/workflows/build.yml"), File(".github/workflows/build.yml")).first { it.exists() }.readText()
        assertTrue(wf.contains("group: \${{ (github.event_name == 'push' && github.ref == 'refs/heads/main') && 'dk-release-main'"))
        assertTrue(wf.contains("cancel-in-progress: false"))
        assertTrue(wf.contains("grep -qxF \"DK \${VERSION_NAME}\""))
        assertTrue(wf.indexOf("grep -qxF \"DK \${VERSION_NAME}\"") < wf.indexOf("gh release create"))
        assertNotEquals(-1, wf.indexOf("sort -V | tail -1)\" != \"\$VERSION_NAME\""))
    }
}
