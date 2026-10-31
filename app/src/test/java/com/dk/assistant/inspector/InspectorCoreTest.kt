package com.dk.assistant.inspector

import com.dk.assistant.inspector.InspectorCore as I
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InspectorCoreTest {

    private val log = """
10:00:00.100 Live: जुड़ रहा हूँ
10:00:01.200 Live: connection टूटा (1006) key=AIzaSyA1234567890abcdefghijklmnop
10:00:02.300 Live: caption: मेरा नाम राज है
10:00:03.300 Live: connection टूटा (1011)
10:00:04.000 कुछ java.lang.IllegalStateException: boom 12345
10:00:05.000 Live: पढ़ने की गलती: 0
10:00:06.000 Live: देरी के हिस्से: कुल 3200ms = बोलना-खत्म-पहचान 400ms + generation(+network) 2500ms + playback 300ms [RTT~120ms] • सबसे बड़ा हिस्सा: generation/network
""".trim()

    @Test fun parseKeepsOrderAndTimestamps() {
        val ev = I.parseLog(log + "\n   at com.dk.Foo.bar(Foo.kt:10)")
        assertEquals(7, ev.size)
        assertEquals("10:00:00.100", ev.first().time)
        assertEquals("10:00:06.000", ev.last().time)
        assertTrue("बिना-समय वाली लाइन पिछली में जुड़े", ev.last().msg.contains("Foo.kt:10"))
        assertEquals((0 until 7).toList(), ev.map { it.idx })
    }

    @Test fun secretsAndConversationAreHidden() {
        val s = I.redact("key=AIzaSyA1234567890abcdefghijklmnop token: ghp_abcdefghijklmnopqrstuvwxyz12 Bearer abc.def.ghi123 sk-or-v1-abcdefghijklmnopqrstuv caption: मेरा पासवर्ड 1234")
        assertFalse(s.contains("AIza")); assertFalse(s.contains("ghp_")); assertFalse(s.contains("abc.def")); assertFalse(s.contains("sk-or"))
        assertFalse(s.contains("पासवर्ड"))
        assertTrue(s.contains("«छिपाया»")); assertTrue(s.contains("«बातचीत छिपाई»"))
    }

    @Test fun repeatedFailureGroupedWithStableFingerprintAndId() {
        val f = I.analyze(I.parseLog(log))
        val drop = f.first { it.rule.id == "live_conn_drop" }
        assertEquals(2, drop.count)
        assertEquals("live_conn_drop", drop.fingerprint)
        assertEquals(I.bugId("live_conn_drop"), I.bugId("live_conn_drop"))
        assertTrue(I.bugId("live_conn_drop").matches(Regex("DKI-[0-9A-F]{6}")))
        assertTrue(drop.evidence.none { it.contains("AIza") })
        // "गलती: 0" गलती नहीं
        assertTrue(f.none { it.message.contains("पढ़ने की गलती: 0") })
        // exception: अंक बदलें तो भी वही fingerprint
        assertEquals(I.normalize("X IllegalStateException boom 12"), I.normalize("X IllegalStateException boom 99"))
    }

    @Test fun alreadyReadLinesAreNotCountedAgain() {
        val ev = I.parseLog(log)
        assertTrue(I.analyze(ev, ev.map { I.lineKey(it) }.toSet()).isEmpty())
    }

    @Test fun latencyNeverInventsMissingStages() {
        val ev = I.parseLog(log)
        val l = I.latency(listOf(I.Lat("live", 900, 900, 1200)), I.parseBreakdowns(ev))
        val stt = l.stages.first { it.name.startsWith("STT") }
        assertNull(stt.ms)
        assertTrue(stt.show().endsWith(I.NOT_AVAILABLE))
        assertEquals(2500L, l.stages.first { it.name.startsWith("AI generation") }.ms)
        assertTrue(l.slowest!!.startsWith("AI generation"))
        // कोई माप नहीं = सब NOT AVAILABLE, कोई चेतावनी नहीं
        val none = I.latency(emptyList(), emptyList())
        assertTrue(none.stages.all { it.show().endsWith(I.NOT_AVAILABLE) })
        assertNull(none.warning)
        assertTrue(I.answer("देरी क्यों", I.Snapshot(emptyList(), none, null, true, emptyList(), 0)).contains(I.NOT_AVAILABLE))
    }

    @Test fun slowTurnsGiveOnlyPerformanceWarning() {
        val l = I.latency(listOf(I.Lat("live", 4000, 4000, 7000)), emptyList())
        assertNotNull(l.warning)
        assertEquals(I.Certainty.PERF, l.warning!!.rule.certainty)
    }

    @Test fun liveStatsFindings() {
        val st = I.parseStats("मॉडल: x • जुड़ा: हाँ • गिरे: 0 • मिक गैप: 4 • पढ़ने की गलती: 2 • दोबारा जुड़ा: 1 • बीच में टोकना: सिर्फ़ पक्के शब्द (असली 1, शोर अनदेखा 2)")
        assertEquals(2, st.int("पढ़ने की गलती"))
        val f = I.liveFindings(st, micPermission = false, hasKey = true)
        assertTrue(f.any { it.rule.id == "live_mic_permission" && it.severity == I.Severity.CRITICAL })
        assertTrue(f.any { it.rule.id == "mic_read_error" })
        assertTrue(f.none { it.rule.id == "live_conn_drop" }) // 1 बार दोबारा जुड़ना = सामान्य
    }

    @Test fun suspectedIsNeverReportedAsConfirmed() {
        val f = I.analyze(I.parseLog("10:00:00.000 Gemini ने आवाज़ नहीं सुनी"))
        assertEquals(I.Certainty.SUSPECTED, f.single().rule.certainty)
        val m = I.merge(emptyList(), f, 1, "v1")
        val r = I.fullReport(m.incidents.single())
        assertTrue(r.contains("Suspected cause"))
        assertFalse(r.contains("Root cause (लॉग से पक्का)"))
    }

    @Test fun lifecycleNewInvestigatingResolvedReopened() {
        val f = I.analyze(I.parseLog(log)).filter { it.rule.id == "live_conn_drop" }
        var m = I.merge(emptyList(), f, 1_000, "v1")
        assertEquals(1, m.fresh.size); assertEquals(I.Status.NEW, m.incidents.single().status)
        m = I.merge(m.incidents, f, 2_000, "v1")
        assertTrue(m.fresh.isEmpty()); assertEquals(I.Status.INVESTIGATING, m.incidents.single().status); assertEquals(4, m.incidents.single().count)
        // Coding Agent को दिया (FIXING, fix से पहले का version v1)
        var xs = m.incidents.map { it.copy(status = I.Status.FIXING, fixVersion = "v1") }
        // वही version = कभी हल नहीं
        var t = 2_000L
        repeat(5) { t += I.RESOLVE_QUIET_MS; xs = I.merge(xs, emptyList(), t, "v1").incidents }
        assertEquals(I.Status.FIXING, xs.single().status)
        // नया version, पर सिर्फ़ 2 शांत जाँच = अभी नहीं
        xs = xs.map { it.copy(quietChecks = 0) }
        xs = I.merge(xs, emptyList(), t + 1, "v2").incidents; xs = I.merge(xs, emptyList(), t + 2, "v2").incidents
        assertEquals(I.Status.FIXING, xs.single().status)
        xs = I.merge(xs, emptyList(), t + 3, "v2").incidents
        assertEquals(I.Status.RESOLVED, xs.single().status)
        // फिर आया = REOPENED, नया bug नहीं
        m = I.merge(xs, f, t + 10, "v2")
        assertEquals(I.Status.REOPENED, m.incidents.single().status)
        assertEquals(1, m.reopened.size); assertTrue(m.fresh.isEmpty())
        assertEquals(1, m.incidents.single().reopenCount)
    }

    @Test fun notifyOnlyOnceForUnchangedIssue() {
        val f = I.analyze(I.parseLog(log)).filter { it.rule.id == "live_conn_drop" }
        val i = I.merge(emptyList(), f, 1_000, "v1").incidents.single()
        assertTrue(I.shouldNotify(i))
        val told = i.copy(notifiedAt = 1_500, notifiedSeverity = i.severity.rank, status = I.Status.AWAITING_APPROVAL)
        assertFalse(I.shouldNotify(told))
        assertFalse(I.shouldNotify(I.merge(listOf(told), f, 2_000, "v1").incidents.single()))
        // हल्की (LOW) गड़बड़ी पर Live को परेशान नहीं करना
        val low = I.merge(emptyList(), I.analyze(I.parseLog("10:00:00.000 कुछ गड़बड़ हुई")), 1, "v1").incidents.single()
        assertFalse(I.shouldNotify(low))
    }

    @Test fun reportHasEveryRequiredField() {
        val i = I.merge(emptyList(), I.analyze(I.parseLog(log)), 1_000, "v1").incidents.first()
        val r = I.fullReport(i)
        listOf("Bug ID", "Title", "Detection timestamp", "Affected component", "Severity", "Actual error message", "Relevant log entries",
            "Event sequence", "cause", "Evidence", "User impact", "Recommended solution", "Required code changes", "Testing procedure", "Status")
            .forEach { assertTrue("रिपोर्ट में '$it' नहीं", r.contains(it)) }
        assertFalse(r.contains("AIza"))
        val task = I.coderTask(i)
        assertTrue(task.contains("पहले खुद कोड पढ़कर वजह पक्की करो"))
        assertTrue(task.contains("merge मत करो"))
    }

    @Test fun answersUseRealDataOrSayWhatIsMissing() {
        val ev = I.parseLog(log)
        val inc = I.merge(emptyList(), I.analyze(ev), 1_000, "v1").incidents
        val a = I.answer("Live कनेक्शन क्यों टूट रहा है?", I.Snapshot(inc, null, null, true, ev, 1_000))
        assertTrue(a.contains(I.bugId("live_conn_drop"))); assertTrue(a.contains("2 बार"))
        val miss = I.answer("माइक में दिक्कत?", I.Snapshot(emptyList(), null, null, true, ev, 0))
        assertTrue(miss.contains("पक्का जवाब देने के लिए चाहिए"))
        val byId = I.answer("${I.bugId("live_conn_drop")} समझाओ", I.Snapshot(inc, null, null, true, ev, 0))
        assertTrue(byId.startsWith("DK INSPECTOR BUG REPORT"))
    }

    @Test fun jsonRoundTrip() {
        val inc = I.merge(emptyList(), I.analyze(I.parseLog(log)), 1_000, "v1").incidents
        assertEquals(inc, I.listFromJson(I.listToJson(inc)))
        assertTrue(I.listFromJson("टूटा json").isEmpty())
    }

    // ---------------- Android हिस्सा: source से जाँच (सिर्फ़ पढ़ना, अलग-थलग, मंज़ूरी)
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()

    @Test fun inspectorIsReadOnlyIsolatedAndApprovalGated() {
        val s = src("inspector/Inspector.kt")
        assertTrue(s.contains("DKLog.all()")); assertTrue(s.contains("GeminiLive.stats")); assertTrue(s.contains("LatencyBoard.all()"))
        assertFalse("Inspector लॉग नहीं मिटाता", s.contains("DKLog.clear")); assertFalse(s.contains("LatencyBoard.clear"))
        assertFalse("DKLog.listener नहीं छीनता", s.contains("DKLog.listener"))
        assertTrue(s.contains("if (!approved) return \"NOT SENT"))
        assertTrue(s.contains("AlarmManager.ELAPSED_REALTIME")); assertTrue(s.contains("setInexactRepeating"))
        assertTrue(src("DKApp.kt").contains("Inspector.schedule"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains(".inspector.InspectorReceiver"))
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("\"dk_inspector\" -> com.dk.assistant.inspector.Inspector.liveTool(svc, args)"))
        assertTrue("dk_inspector" in com.dk.assistant.LeakedCall.TOOLS)
        val set = src("SettingsActivity.kt")
        assertTrue(set.contains("Run Deep Inspection")); assertTrue(set.contains("Ask DK Inspector"))
        // पुराने तीनों Debug हिस्से वैसे ही
        listOf("Gemini Live हाल", "देरी का हिसाब", "\"लॉग\"").forEach { assertTrue(set.contains(it)) }
    }
}
