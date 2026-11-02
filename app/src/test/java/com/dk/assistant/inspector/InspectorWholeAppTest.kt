package com.dk.assistant.inspector

import com.dk.assistant.LiveSrc

import com.dk.assistant.inspector.InspectorCore as I
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): DK Inspector पूरे ऐप से जुड़ा, हर 20 मिनट जाँच, बग हटाना / सब साफ़,
 * हटाया हुआ फिर आए तो वापस दिखे, 24 घंटे शांत, 3 दिन बाद अपने-आप साफ़.
 */
class InspectorWholeAppTest {

    private val H = 3600_000L
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()

    private fun one(line: String, now: Long = 1_000L) =
        I.merge(emptyList(), I.analyze(I.parseLog(line)), now, "v1").incidents.single()

    @Test fun everyAppPartGetsItsOwnComponent() {
        val call = I.bugLine("call", "कॉल उठाना", "SecurityException", "no permission", "CallManager.kt:110")
        assertEquals("⚠ गलती [call] कॉल उठाना — SecurityException: no permission @ CallManager.kt:110", call)
        val cases = mapOf(
            "call" to I.Component.CALL, "connector" to I.Component.CONNECTOR, "update" to I.Component.UPDATE,
            "assistant" to I.Component.ASSISTANT, "notification" to I.Component.NOTIFICATION,
            "github" to I.Component.CODING_AGENT, "app" to I.Component.APP,
        )
        for ((area, comp) in cases) {
            val l = I.bugLine(area, "काम", "IllegalStateException", "boom", null)
            assertEquals(area, comp, I.ruleFor(l)?.component)
        }
        assertEquals(I.Component.CALL, one("10:00:00.000 $call").component)
        // अलग गलती = अलग bug; वही गलती दोबारा = वही bug
        val other = I.bugLine("call", "कॉल काटना", "NullPointerException", null, null)
        val f = I.analyze(I.parseLog("10:00:00.000 $call\n10:00:01.000 $call\n10:00:02.000 $other"))
        assertEquals(2, f.size); assertEquals(2, f.first().count)
    }

    @Test fun crashFileAndAndroidExitRecords() {
        val crash = "02 Nov 10:11:12 [DefaultDispatcher-worker-1] version 3.140\n" +
            "java.lang.IllegalStateException: boom\n\tat com.dk.assistant.CallManager.answer(CallManager.kt:105)\n\tat kotlin.x.y(Z.kt:1)"
        val e = I.crashEvent(crash)!!
        assertTrue(e, e.startsWith("FATAL ऐप crash (version 3.140) [DefaultDispatcher-worker-1]: java.lang.IllegalStateException: boom"))
        assertTrue(e, e.contains("CallManager.kt:105"))
        assertEquals("app_crash_file", I.ruleFor(e)?.id)
        assertNull(I.crashEvent(""))
        assertEquals("app_anr", I.ruleFor(I.exitEvent(6, "Input dispatching timed out")!!)?.id)
        assertEquals(I.Component.SYSTEM, I.ruleFor(I.exitEvent(3, null)!!)?.component)
        assertEquals("app_native_crash", I.ruleFor(I.exitEvent(5, "")!!)?.id)
        // crash (4) = crash फ़ाइल से; मालिक का बंद करना (10/11) / खुद बंद (1) = गलती नहीं
        assertNull(I.exitEvent(4, "x")); assertNull(I.exitEvent(10, null)); assertNull(I.exitEvent(1, null)); assertNull(I.exitEvent(11, null))
    }

    @Test fun fileFeedOnlyNewLinesInTimeOrder() {
        val text = "100\t10:00:00.000 a\n300\t10:00:02.000 c\nbad line\n200\t10:00:01.000 b\n"
        val (t, at) = I.feedSince(text, 100)
        assertEquals("10:00:01.000 b\n10:00:02.000 c", t)
        assertEquals(300, at)
        val (t2, at2) = I.feedSince(text, 300)
        assertEquals("", t2); assertEquals(300, at2)
        assertEquals(2, I.parseLog(t).size)
    }

    @Test fun dismissedBugComesBackAsReopened() {
        val line = "10:00:00.000 " + I.bugLine("update", "नया version download", "IOException", "disk full", null)
        val f = I.analyze(I.parseLog(line))
        val i = I.merge(emptyList(), f, 1_000, "v1").incidents.single()
        val gone = I.dismiss(listOf(i), i.id.lowercase(), 2_000)
        assertEquals(I.Status.DISMISSED, gone.single().status)
        assertFalse(I.visible(gone.single()))
        assertFalse(I.shouldNotify(gone.single(), 2_000))
        // नहीं दिखा = हटाया ही रहता है
        assertEquals(I.Status.DISMISSED, I.merge(gone, emptyList(), 3_000, "v1").incidents.single().status)
        // फिर आया = "फिर से आया", सूची में और DK Live फिर बताए
        val m = I.merge(gone, f, 4_000, "v1")
        val back = m.incidents.single()
        assertEquals(I.Status.REOPENED, back.status); assertEquals(1, m.reopened.size); assertEquals(1, back.reopenCount)
        assertTrue(back.resolvedNote.contains("आपने हटाया था"))
        assertEquals(0L, back.dismissedAt)
        assertTrue(I.visible(back))
        // एक-दो बार वाली छोटी गलती Live नहीं बोलता (3 बार / गंभीर हो तब) - वही पुराना नियम
        assertTrue(I.shouldNotify(back.copy(count = 3), 4_000))
    }

    @Test fun quietAfter24hPurgedAfter3Days_codingAgentBugsUntouched() {
        val i = one("10:00:00.000 " + I.bugLine("call", "कॉल उठाना", "SecurityException", null, null))
        var xs = I.merge(listOf(i), emptyList(), 1_000 + 23 * H, "v1").incidents
        assertEquals(I.Status.NEW, xs.single().status)
        xs = I.merge(xs, emptyList(), 1_000 + 24 * H, "v1").incidents
        assertEquals(I.Status.QUIET, xs.single().status)
        assertFalse(I.shouldNotify(xs.single(), 1_000 + 24 * H))
        // शांत भी फिर आए तो वापस
        val again = I.merge(xs, I.analyze(I.parseLog("10:00:00.000 " + I.bugLine("call", "कॉल उठाना", "SecurityException", null, null))), 1_000 + 25 * H, "v1")
        assertEquals(I.Status.REOPENED, again.incidents.single().status)
        // 3 दिन (आख़िरी बार दिखने से) = सूची से हटा
        xs = I.merge(xs, emptyList(), 1_000 + 72 * H - 1, "v1").incidents
        assertEquals(1, xs.size)
        xs = I.merge(xs, emptyList(), 1_000 + 72 * H, "v1").incidents
        assertTrue(xs.isEmpty())
        // Coding Agent के पास वाला (FIXING) 24 घंटे में शांत नहीं होता, न साफ़ होता है
        val fixing = i.copy(status = I.Status.FIXING, fixVersion = "v1")
        val kept = I.merge(listOf(fixing), emptyList(), 1_000 + 100 * H, "v1").incidents.single()
        assertEquals(I.Status.FIXING, kept.status)
        // हल हुआ + हटाया भी 3 दिन बाद साफ़
        val res = i.copy(status = I.Status.RESOLVED, resolvedAt = 10 * H)
        assertTrue(I.purge(res, 10 * H + 72 * H)); assertFalse(I.purge(res, 10 * H + 71 * H))
        val dis = i.copy(status = I.Status.DISMISSED, dismissedAt = 10 * H)
        assertTrue(I.purge(dis, 10 * H + 72 * H)); assertFalse(I.purge(dis, 10 * H + 1))
    }

    @Test fun clearAllAndLater() {
        val a = one("10:00:00.000 " + I.bugLine("call", "कॉल उठाना", "SecurityException", null, null))
        val b = one("10:00:00.000 " + I.bugLine("connector", "Gmail", "IllegalStateException", null, null))
        val c = b.copy(id = "DKI-FFFFFF", fingerprint = "x", status = I.Status.FIXING)
        val all = I.clearAll(listOf(a, b, c), 5_000)
        assertTrue(all.all { it.status == I.Status.DISMISSED && it.dismissedAt == 5_000L })
        assertTrue(all.none { I.visible(it) })
        val l = I.later(listOf(a.copy(notifiedAt = 0, count = 3)), a.id, 1_000).single()
        assertEquals(1_000 + I.LATER_MS, l.snoozeUntil)
        assertFalse(I.shouldNotify(l, 1_000 + I.LATER_MS - 1))
        assertTrue(I.shouldNotify(l, 1_000 + I.LATER_MS))
    }

    @Test fun jsonKeepsSnoozeAndDismiss() {
        val i = one("10:00:00.000 " + I.bugLine("call", "x", "E", null, null)).copy(status = I.Status.DISMISSED, dismissedAt = 77, snoozeUntil = 99)
        val back = I.listFromJson(I.listToJson(listOf(i))).single()
        assertEquals(I.Status.DISMISSED, back.status); assertEquals(77L, back.dismissedAt); assertEquals(99L, back.snoozeUntil)
        val q = I.listFromJson(I.listToJson(listOf(i.copy(status = I.Status.QUIET)))).single()
        assertEquals(I.Status.QUIET, q.status)
    }

    @Test fun askIgnoresQuietAndDismissed() {
        val i = one("10:00:00.000 " + I.bugLine("call", "x", "E", null, null)).copy(status = I.Status.DISMISSED)
        val s = I.Snapshot(listOf(i), null, null, null, emptyList(), 0)
        assertFalse(I.answer("क्या गड़बड़ है", s).contains(i.id))
    }

    @Test fun wiredIntoWholeApp_every20Minutes_liveAsksCoderRemoveOrLater() {
        val ins = src("inspector/Inspector.kt")
        assertTrue(ins.contains("SCAN_MS = 20 * 60_000L"))
        assertTrue(ins.contains("setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + SCAN_MS, SCAN_MS, pi)"))
        assertTrue(ins.contains("getHistoricalProcessExitReasons"))
        assertTrue(ins.contains("last_crash.txt"))
        assertTrue(ins.contains("DKBug.logFile") && ins.contains("DKBug.bugFile"))
        assertTrue(ins.contains("Coding Agent को दे दूँ, हटा दूँ, या बाद में बताऊँ"))
        for (a in listOf("\"dismiss\" ->", "\"later\" ->", "\"clear_all\" ->")) assertTrue(a, ins.contains(a))
        val acc = com.dk.assistant.AccSrc.all()
        assertTrue(acc.contains("Inspector.startTimer(this)") && acc.contains("Inspector.stopTimer()"))
        assertTrue(src("DKApp.kt").contains("DKBug.init(this)"))
        assertTrue(src("DKLog.kt").contains("DKBug.logFile?.append"))
        val gl = LiveSrc.all()
        assertTrue(gl.contains(".put(\"send_to_coder\").put(\"dismiss\").put(\"later\").put(\"clear_all\")"))
        val card = src("inspector/InspectorCard.kt")
        assertTrue(card.contains("\"सब साफ़ करें\"") && card.contains("\"हटाएँ\"") && card.contains("InspectorCore.visible(it)"))
        // हर हिस्सा अपनी गलती बताता है
        for ((f, area) in listOf("CallManager.kt" to "call", "Updater.kt" to "update", "UpdateJob.kt" to "update",
                "connect/ConnectorRunner.kt" to "connector", "connect/GoogleAuth.kt" to "connector", "DKNotifListener.kt" to "notification",
                "DKVoiceInteractionSessionService.kt" to "assistant", "DKRecognitionService.kt" to "assistant", "WaCall.kt" to "call"))
            assertTrue(f, src(f).contains("DKBug.report(\"$area\""))
        assertNotNull(I.RULES.firstOrNull { it.id == "area_call" })
        assertEquals("area_call", I.RULES.first().id)
    }
}
