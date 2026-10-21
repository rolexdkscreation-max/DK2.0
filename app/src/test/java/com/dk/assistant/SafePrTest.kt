package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SafePrTest {

    private val at = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 21, 19, 5) }.time

    @Test fun branchName_englishTask() =
        assertEquals("feature/dk-add-dark-mode-switch-1021-1905", SafePr.branchName("Add dark mode switch in Settings", at))

    @Test fun branchName_hindiTask_onlyTime() =
        assertEquals("feature/dk-1021-1905", SafePr.branchName("सेटिंग्स में डार्क मोड जोड़ो", at))

    @Test fun branchName_isGitSafe() {
        val b = SafePr.branchName("Fix: crash!! @ Login / Signup ### v2", at)
        assertTrue(b, Regex("^feature/dk-[a-z0-9-]+$").matches(b))
    }

    @Test fun mainIsBlocked() {
        assertTrue(SafePr.isBlocked("main", "main"))
        assertTrue(SafePr.isBlocked("master", "develop"))
        assertTrue(SafePr.isBlocked("develop", "develop"))
        assertFalse(SafePr.isBlocked("feature/dk-x-1021-1905", "main"))
    }

    @Test fun protectedFiles() {
        assertTrue(SafePr.isProtected(".github/workflows/build.yml"))
        assertTrue(SafePr.isProtected("/AGENTS.md"))
        assertTrue(SafePr.isProtected("app/release.jks"))
        assertTrue(SafePr.isProtected("gradle.properties"))
        assertFalse(SafePr.isProtected("app/src/main/java/com/dk/assistant/Speaker.kt"))
        assertEquals(listOf(".github/workflows/check.yml"), SafePr.protectedIn(listOf("README.md", ".github/workflows/check.yml")))
    }

    @Test fun projectContext_readsRulesWorkflowsAndAgents() {
        val ctx = SafePr.projectContext(mapOf(
            "AGENTS.md" to "never push to main",
            ".github/workflows/check.yml" to "on: pull_request",
            "app/src/main/java/com/dk/assistant/CodingAgent.kt" to "class CodingAgent",
            "app/src/main/java/com/dk/assistant/Speaker.kt" to "class Speaker"))
        assertTrue(ctx.contains("never push to main"))
        assertTrue(ctx.contains(".github/workflows/check.yml"))
        assertTrue(ctx.contains("CodingAgent.kt"))
        assertFalse(ctx.contains("Speaker.kt"))
    }

    // ---- आवाज़ से "PR merge कर दो"
    @Test fun mergeIntent_hindiWithPr() {
        assertEquals(true, SafePr.mergeIntent("PR merge कर दो"))
        assertEquals(true, SafePr.mergeIntent("कोडिंग एजेंट, पीआर मर्ज कर दो।"))
        assertEquals(true, SafePr.mergeIntent("PR #12 को main में merge करो"))
    }

    @Test fun mergeIntent_shortCommand() {
        assertEquals(true, SafePr.mergeIntent("merge कर दो"))
        assertEquals(true, SafePr.mergeIntent("मर्ज करो"))
        assertEquals(true, SafePr.mergeIntent("merge karo"))
    }

    @Test fun mergeIntent_negative() {
        assertEquals(false, SafePr.mergeIntent("PR अभी merge मत करो"))
        assertEquals(false, SafePr.mergeIntent("merge nahi karo"))
    }

    @Test fun mergeIntent_notAMergeCommand() {
        assertNull(SafePr.mergeIntent("Settings में dark mode switch जोड़ो"))
        assertNull(SafePr.mergeIntent("merge sort वाला function बनाओ"))
        assertNull(SafePr.mergeIntent("जैसी आपकी मर्ज़ी"))
        assertNull(SafePr.mergeIntent("दो lists को merge करने वाला code लिखो"))
    }
}
