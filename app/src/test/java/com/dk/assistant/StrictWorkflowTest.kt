package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * कोडिंग एजेंट का सख़्त तरीका: नई branch → PR → tests; पुराना फ़ीचर बदलना = पहले backup;
 * bug = पहले जड़; अस्थायी गलती = ऑटो-रिट्राई; fail = जड़ → fix → फिर test (अटके तो रुककर रिपोर्ट).
 */
class StrictWorkflowTest {

    private val at = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 22, 14, 30) }.time

    // ---- पुराना फ़ीचर बदलना/हटाना → backup
    @Test fun intentionalChange_detected() {
        assertTrue(SafePr.changesOldFeature("Anti-Theft Guard फ़ीचर हटा दो"))
        assertTrue(SafePr.changesOldFeature("पुराना orb डिज़ाइन बदलो"))
        assertTrue(SafePr.changesOldFeature("Remove the story writer screen"))
        assertTrue(SafePr.changesOldFeature("Google TTS की जगह Gemini आवाज़ लगाओ"))
        assertTrue(SafePr.removesOldFeature("Anti-Theft Guard फ़ीचर हटा दो"))
        assertFalse(SafePr.removesOldFeature("पुराना orb डिज़ाइन बदलो"))
    }

    @Test fun newFeature_isNotIntentionalChange() {
        assertFalse(SafePr.changesOldFeature("सेटिंग्स में डार्क मोड जोड़ो"))
        assertFalse(SafePr.changesOldFeature("Add a weather card on the main screen"))
    }

    @Test fun backupName_isSafeAndSeparate() {
        assertEquals("backup/dk-remove-story-writer-1022-1430", SafePr.backupName("Remove story writer", at))
        val b = SafePr.backupName("पुराना फ़ीचर हटाओ", at)
        assertTrue(b, Regex("^backup/dk-[a-z0-9-]+$").matches(b))
        assertFalse(SafePr.isBlocked(b, "main"))
    }

    // ---- bug = पहले जड़
    @Test fun fixTask_detected() {
        assertTrue(SafePr.isFixTask("BUILD FAILED on GitHub Actions"))
        assertTrue(SafePr.isFixTask("REVIEWER (round 1) found these problems"))
        assertTrue(SafePr.isFixTask("आवाज़ वाला बटन काम नहीं कर रहा"))
        assertTrue(SafePr.isFixTask("App crash ho raha hai settings kholne par"))
        assertTrue(SafePr.isFixTask("मैप में गलती है, ठीक करो"))
        assertFalse(SafePr.isFixTask("सेटिंग्स में डार्क मोड जोड़ो"))
    }

    // ---- पुराने tests चुपके से न हटें
    @Test fun testGuard_blocksDeletingOrWeakeningOldTests() {
        val path = "app/src/test/java/com/dk/assistant/SafePrTest.kt"
        val before = "class A {\n @Test fun a() {}\n @Test fun b() {}\n}"
        assertEquals(1, SafePr.testGuard(mapOf(path to null), mapOf(path to before), allowed = false).size)
        assertEquals(1, SafePr.testGuard(mapOf(path to "class A {\n @Test fun a() {}\n}"), mapOf(path to before), allowed = false).size)
        // test बढ़ाना / नई test फ़ाइल / non-test फ़ाइल = ठीक
        assertTrue(SafePr.testGuard(mapOf(path to "$before\n@Test fun c() {}"), mapOf(path to before), false).isEmpty())
        assertTrue(SafePr.testGuard(mapOf("app/src/test/java/X.kt" to "@Test fun x() {}"), mapOf("app/src/test/java/X.kt" to null), false).isEmpty())
        assertTrue(SafePr.testGuard(mapOf("app/src/main/java/A.kt" to null), mapOf("app/src/main/java/A.kt" to "class A"), false).isEmpty())
        // user ने खुद फ़ीचर हटाने को कहा = उसके tests हटाना जायज़
        assertTrue(SafePr.testGuard(mapOf(path to null), mapOf(path to before), allowed = true).isEmpty())
    }

    @Test fun prBody_mentionsBackupRootCauseAndOldFeatures() {
        val body = SafePr.prBody("• X हटाया", "D app/X.kt", emptyList(), "backup/dk-x-1022-1430", "X.kt:10 - null check missing")
        assertTrue(body.contains("backup/dk-x-1022-1430"))
        assertTrue(body.contains("root cause"))
        assertTrue(body.contains("पुराने features"))
        // पुराना तरीका (बिना backup) भी चलता है
        assertFalse(SafePr.prBody("s", "c", emptyList()).contains("बैकअप"))
    }

    // ---- ऑटो-रिट्राई
    @Test fun retry_onlyTransientErrors() {
        listOf(0, -1, 408, 500, 502, 503, 504).forEach { assertTrue("$it", Retry.isTransient(it)) }
        listOf(400, 401, 404, 409, 413, 422, 429).forEach { assertFalse("$it", Retry.isTransient(it)) }
        assertTrue(Retry.isTransient(403, "You have exceeded a secondary rate limit"))
        assertFalse(Retry.isTransient(403, "Resource not accessible by personal access token"))
        assertEquals(3, Retry.ATTEMPTS)
    }

    @Test fun retry_backoffGrows() {
        assertEquals(1000L, Retry.backoffMs(1))
        assertEquals(2000L, Retry.backoffMs(2))
        assertEquals(4000L, Retry.backoffMs(3))
        assertEquals(8000L, Retry.backoffMs(10))
    }

    @Test fun retry_networkExceptions() {
        assertTrue(Retry.isNetwork(java.net.SocketTimeoutException("timeout")))
        assertTrue(Retry.isNetwork(java.net.UnknownHostException("api.github.com")))
        assertFalse(Retry.isNetwork(IllegalStateException("x")))
    }

    @Test fun infraFailure_vsCodeFailure() {
        assertTrue(Retry.isInfraFailure("> Could not GET 'https://dl.google.com/x.pom'. Received status code 502 Bad Gateway"))
        assertTrue(Retry.isInfraFailure("##[error]The runner has received a shutdown signal."))
        assertFalse(Retry.isInfraFailure("e: file:///app/src/main/java/A.kt:12:5 Unresolved reference: foo"))
        assertFalse(Retry.isInfraFailure("SafePrTest > mainIsBlocked FAILED\n java.lang.AssertionError"))
        assertFalse(Retry.isInfraFailure(""))
    }

    // ---- जड़ → fix → फिर test, जब तक पास न हो (अटके तो रुको)
    @Test fun fixLoop_keepsGoingWhileErrorsChange() {
        assertEquals(FixLoop.Next.FIX_AGAIN, FixLoop.next(listOf("a")))
        assertEquals(FixLoop.Next.FIX_AGAIN, FixLoop.next(listOf("a", "b", "c", "d", "e")))
        assertEquals(FixLoop.Next.FIX_AGAIN, FixLoop.next(listOf("a", "a")))
    }

    @Test fun fixLoop_stopsWhenStuckOrCap() {
        assertEquals(FixLoop.Next.STOP_STUCK, FixLoop.next(listOf("x", "a", "a", "a")))
        assertEquals(FixLoop.Next.STOP_CAP, FixLoop.next((1..FixLoop.HARD_CAP).map { "e$it" }))
    }

    @Test fun fixLoop_signatureIgnoresLineNumbers() {
        val a = FixLoop.signature("e: file:///app/A.kt:12:5 Unresolved reference: foo")
        val b = FixLoop.signature("e: file:///app/A.kt:14:9 Unresolved reference: foo")
        assertEquals(a, b)
        assertTrue(FixLoop.signature("e: file:///app/A.kt:12:5 Unresolved reference: bar") != a)
    }
}
