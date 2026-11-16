package com.dk.assistant.inspector

import com.dk.assistant.inspector.InspectorCore as I
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक: "beta-gate बनाओ"): ⛔ रिपोर्ट = beta-blocker label, खुला रहे तो main का merge रुका */
class BetaGateTest {

    private val h = 3_600_000L
    private fun inc(rule: String, sev: I.Severity, firstVer: String = "3.307/943") =
        I.Incident("DKI-$rule", "fp-$rule-$sev", rule, "t", I.Component.APP, sev, I.Certainty.CONFIRMED,
            1, 1, 1, I.Status.NEW, "m", emptyList(), emptyList(), "", "", "", "", "", firstVer = firstVer)

    private fun verdict(vararg xs: I.Incident): BetaCheck.Verdict {
        val s = BetaCheck.record(BetaCheck.ensure(null, "3.307", 943, BetaCheck.Channel.BETA, 1), xs.toList(), emptySet(), "3.307/943", 2)
        return BetaCheck.verdict(s, 2 * h)
    }

    @Test fun blockerOnlyForCrashOrNewSerious() {
        assertTrue(BetaCheck.isBlocker(verdict(inc("x", I.Severity.HIGH))))
        assertTrue(BetaCheck.isBlocker(verdict(inc("app_crash_file", I.Severity.HIGH, firstVer = "3.306/941"))))
        assertFalse(BetaCheck.isBlocker(verdict(inc("x", I.Severity.MEDIUM))))
        assertFalse(BetaCheck.isBlocker(verdict(inc("x", I.Severity.CRITICAL, firstVer = "3.306/941"))))
        // सिर्फ़ "24 घंटे नहीं हुए" = रुको, पर beta-blocker नहीं
        val wait = verdict(); assertFalse(wait.ready); assertFalse(BetaCheck.isBlocker(wait))
    }

    @Test fun blockerReportAsksForBetaBlockerLabel() {
        val u = BetaCheck.issueUrl("o/r", "t", "b", blocker = true)
        assertTrue(u.startsWith("https://github.com/o/r/issues/new?labels=beta%2Cbeta-blocker&"))
        assertEquals(BetaCheck.issueUrl("o/r", "t", "b"), BetaCheck.issueUrl("o/r", "t", "b", blocker = false))
    }

    @Test fun titleCarriesVersionForWorkflowLabel() {
        val s = BetaCheck.ensure(null, "3.307", 943, BetaCheck.Channel.BETA, 1)
        assertTrue(Regex("^\\[बीटा ([0-9][0-9.]*)\\]").containsMatchIn(BetaCheck.reportTitle(s, BetaCheck.verdict(s, 2))))
    }

    @Test fun settingsCardPassesBlocker() {
        val src = File("src/main/java/com/dk/assistant/SettingsBeta.kt").readText()
        assertTrue(src.contains("blocker = BetaCheck.isBlocker(v)"))
    }

    @Test fun workflowBlocksOnOpenBetaBlockerAndRechecks() {
        val y = File("../.github/workflows/beta-gate.yml").readText()
        assertTrue(y.contains("  beta-gate:"))                       // required check का नाम
        assertTrue(y.contains("--label beta-blocker --state open"))
        assertTrue(y.contains("exit 1"))
        assertTrue(y.contains("gh run rerun"))                       // issue बंद = PR का check फिर चले
        assertTrue(y.contains("TITLE: \${{ github.event.issue.title }}"))   // title env में, script में सीधे नहीं
        assertFalse(y.contains("contents: write"))
    }
}
