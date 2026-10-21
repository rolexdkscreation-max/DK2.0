package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
