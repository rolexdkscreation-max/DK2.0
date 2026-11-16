package com.dk.assistant.inspector

import com.dk.assistant.inspector.BetaCheck.Channel
import com.dk.assistant.inspector.BetaCheck.Kind
import com.dk.assistant.inspector.InspectorCore as I
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): हिस्सा 1 = version-चैनल ठप्पा, हिस्सा 2 = बीटा जाँच कार्ड */
class BetaCheckTest {

    private val h = 3_600_000L
    private val log = "10:00:01.200 Live: connection टूटा (1006)\n10:00:04.000 कुछ java.lang.IllegalStateException: boom 12345"
    private fun found() = I.analyze(I.parseLog(log))

    private fun inc(rule: String = "x", sev: I.Severity = I.Severity.HIGH, firstVer: String = "3.307/943", msg: String = "m") =
        I.Incident("DKI-1", "fp-$rule-$sev-$firstVer-$msg", rule, "t", I.Component.APP, sev, I.Certainty.CONFIRMED,
            1, 1, 1, I.Status.NEW, msg, emptyList(), emptyList(), "", "", "", "", "", firstVer = firstVer, firstChan = "beta")

    // ---------------------------------------------------------------- हिस्सा 1: ठप्पा

    @Test fun newIncidentGetsVersionAndChannelStamp() {
        val f = found(); assertTrue(f.isNotEmpty())
        val m = I.merge(emptyList(), f, 1000, "3.307/943", "beta")
        m.fresh.forEach {
            assertEquals("3.307/943", it.firstVer); assertEquals("beta", it.firstChan); assertEquals("3.307/943", it.lastVer)
        }
    }

    @Test fun laterVersionKeepsFirstStampAndUpdatesLast() {
        val a = I.merge(emptyList(), found(), 1000, "3.306/941", "stable")
        val b = I.merge(a.incidents, found(), 2000, "3.307/943", "beta")
        val x = b.incidents.first { it.lastSeen == 2000L }
        assertEquals("3.306/941", x.firstVer); assertEquals("stable", x.firstChan); assertEquals("3.307/943", x.lastVer)
    }

    @Test fun oldMergeSignatureStillWorks() {
        val m = I.merge(emptyList(), found(), 1000, "3.307/943")
        assertTrue(m.fresh.all { it.firstVer == "3.307/943" && it.firstChan == "" })
    }

    @Test fun stampSurvivesJsonAndOldJsonReadsBlank() {
        val m = I.merge(emptyList(), found(), 1000, "3.307/943", "beta")
        val back = I.listFromJson(I.listToJson(m.incidents))
        assertEquals(m.incidents.map { it.firstVer to it.firstChan }, back.map { it.firstVer to it.firstChan })
        val old = I.toJson(m.incidents.first()).apply { remove("fVer"); remove("fChan"); remove("lVer") }
        val r = I.fromJson(old); assertNotNull(r); assertEquals("", r!!.firstVer)
    }

    @Test fun verLabelAndReportShowVersion() {
        assertEquals("DK 3.307 (बीटा)", I.verLabel("3.307/943", "beta"))
        assertEquals("पता नहीं", I.verLabel("", "beta"))
        assertTrue(I.fullReport(inc()).contains("DK 3.307 (बीटा)"))
    }

    // ---------------------------------------------------------------- हिस्सा 2: बीटा जाँच

    @Test fun channelParsing() {
        assertEquals(Channel.BETA, Channel.of("beta")); assertEquals(Channel.STABLE, Channel.of(" Stable "))
        assertEquals(Channel.LOCAL, Channel.of(null)); assertEquals(Channel.LOCAL, Channel.of("x"))
    }

    @Test fun kindNewOldBack() {
        assertEquals(Kind.NEW, BetaCheck.kindOf(inc(), "3.307/943", false))
        assertEquals(Kind.OLD, BetaCheck.kindOf(inc(firstVer = "3.306/941"), "3.307/943", false))
        assertEquals(Kind.OLD, BetaCheck.kindOf(inc(firstVer = ""), "3.307/943", false))
        assertEquals(Kind.BACK, BetaCheck.kindOf(inc(firstVer = "3.306/941"), "3.307/943", true))
    }

    @Test fun crashFromOlderVersionIsOld() {
        val c = inc(rule = "app_crash_file", msg = "FATAL ऐप crash (version 3.306) boom")
        assertEquals(Kind.OLD, BetaCheck.kindOf(c, "3.307/943", false))
        assertEquals(Kind.NEW, BetaCheck.kindOf(c.copy(message = "FATAL ऐप crash (version 3.307)"), "3.307/943", false))
    }

    private fun session(start: Long = 0) = BetaCheck.ensure(null, "3.307", 943, Channel.BETA, start)

    @Test fun newVersionStartsFreshSession() {
        val s = BetaCheck.record(session(start = 5), listOf(inc()), emptySet(), "3.307/943", 10)
        assertEquals(1, s.items.size)
        assertEquals(s, BetaCheck.ensure(s, "3.307", 943, Channel.BETA, 99))
        assertTrue(BetaCheck.ensure(s, "3.308", 944, Channel.BETA, 99).items.isEmpty())
    }

    @Test fun readyOnlyWhenNoCrashNoNewSeriousAnd24h() {
        val now = 30 * h
        assertTrue(BetaCheck.verdict(session(start = 1), now).ready)
        assertFalse(BetaCheck.verdict(session(start = now - 2 * h), now).ready)
        val serious = BetaCheck.record(session(start = 1), listOf(inc()), emptySet(), "3.307/943", now)
        val v = BetaCheck.verdict(serious, now)
        assertFalse(v.ready); assertEquals(1, v.newSerious); assertTrue(v.headline.contains("रोकें"))
        val crash = BetaCheck.record(session(start = 1), listOf(inc(rule = "app_native_crash", firstVer = "3.306/941")), emptySet(), "3.307/943", now)
        assertEquals(1, BetaCheck.verdict(crash, now).crashes); assertFalse(BetaCheck.verdict(crash, now).ready)
    }

    @Test fun oldAndMediumDoNotBlock() {
        val now = 30 * h
        val s = BetaCheck.record(session(start = 1),
            listOf(inc(firstVer = "3.306/941"), inc(sev = I.Severity.MEDIUM)), emptySet(), "3.307/943", now)
        val v = BetaCheck.verdict(s, now)
        assertTrue(v.ready); assertEquals(1, v.old); assertEquals(1, v.newMedium)
        assertTrue(BetaCheck.summary(s, v, listOf("a")).contains("मध्यम"))
    }

    @Test fun newOrBackStaysEvenIfLaterSeenAsOld() {
        val a = BetaCheck.record(session(), listOf(inc(firstVer = "3.306/941")), setOf("fp-x-HIGH-3.306/941-m"), "3.307/943", 1)
        val b = BetaCheck.record(a, listOf(inc(firstVer = "3.306/941")), emptySet(), "3.307/943", 2)
        assertEquals(Kind.BACK, b.items.single().kind); assertEquals(2, b.items.single().hits)
    }

    @Test fun checklistFromReleaseNotes() {
        val c = BetaCheck.checklist("नया:\n• DK Live WhatsApp भेजने से पहले पूछे\n- डिलीट पर कार्ड\n• छोटे सुधार और बेहतर स्थिरता")
        assertEquals(listOf("DK Live WhatsApp भेजने से पहले पूछे", "डिलीट पर कार्ड"), c)
        assertEquals(2, BetaCheck.checklist(null).size)
    }

    @Test fun sessionJsonRoundTripAndToggle() {
        val s = BetaCheck.toggle(BetaCheck.record(session(5), listOf(inc()), emptySet(), "3.307/943", 9), 1, true)
        assertEquals(s, BetaCheck.fromJson(BetaCheck.toJson(s)))
        assertTrue(BetaCheck.toggle(s, 1, false).checked.isEmpty())
        assertEquals(null, BetaCheck.fromJson("खराब"))
    }

    @Test fun reportHasBetaLabelAndHidesSecrets() {
        val s = BetaCheck.record(session(1), listOf(inc(msg = "key=AIzaSyA1234567890abcdefghijklmnop")), emptySet(), "3.307/943", 2)
        val v = BetaCheck.verdict(s, 2)
        val body = BetaCheck.reportBody(s, v, listOf("a", "b"), "Phone")
        assertFalse(body.contains("AIzaSyA1234567890"))
        assertTrue(body.contains("DK 3.307"))
        val url = BetaCheck.issueUrl("o/r", BetaCheck.reportTitle(s, v), body)
        assertTrue(url.startsWith("https://github.com/o/r/issues/new?labels=beta&"))
        assertFalse(url.contains("beta-blocker"))
    }

    // ---------------------------------------------------------------- जुड़ाव

    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()

    @Test fun wiring() {
        assertTrue(File("build.gradle.kts").readText().contains("DK_CHANNEL"))
        val insp = src("inspector/Inspector.kt")
        assertTrue(insp.contains("BetaCheckStore.channelName()")); assertTrue(insp.contains("BetaCheckStore.record("))
        val act = src("SettingsActivity.kt")
        assertTrue(act.contains("secUpdate(); secBetaCheck()")); assertTrue(act.contains("secBetaCheck(); secDebug()"))
        assertTrue(src("SettingsBeta.kt").contains("BetaCheck.Channel.BETA) return"))
    }
}
