package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v3.89: CI log से असली एरर/जगहें, runner path -> रिपो path, काम-कॉपी फ़ोन में (PR #105 जैसे log पर) */
class BuildLogTest {
    private val compileLog = """
2026-10-27T14:21:52.3295950Z w: file:///home/runner/work/DK2.0/DK2.0/app/src/main/java/com/dk/assistant/avatar/AvatarView.kt:105:13 Variable 'dt' is never used
2026-10-27T14:21:52.7122054Z > Task :app:compileDebugJavaWithJavac NO-SOURCE
2026-10-27T14:21:52.7972890Z > Task :app:processDebugJavaRes
2026-10-27T14:22:03.1008592Z e: file:///home/runner/work/DK2.0/DK2.0/app/src/test/java/com/dk/assistant/FaceMatchTest.kt:61:32 Unresolved reference: MatchResult
2026-10-27T14:22:03.1011386Z e: file:///home/runner/work/DK2.0/DK2.0/app/src/test/java/com/dk/assistant/FaceMatchTest.kt:68:32 Unresolved reference: MatchResult
2026-10-27T14:22:03.1012662Z > Task :app:compileDebugUnitTestKotlin FAILED
2026-10-27T14:22:03.1014400Z e: file:///home/runner/work/DK2.0/DK2.0/app/src/test/java/com/dk/assistant/FaceMatchTest.kt:76:32 Unresolved reference: MatchResult
2026-10-27T14:22:03.1988203Z FAILURE: Build failed with an exception.
""".trimIndent()

    private val testLog = """
2026-10-27T14:40:43.9548083Z > Task :app:testDebugUnitTest
2026-10-27T14:40:43.9608087Z com.dk.assistant.FaceMatchTest > testEvaluateMatchNoStoredTarget FAILED
2026-10-27T14:40:43.9625529Z     java.lang.AssertionError: expected:<NO_TARGET> but was:<NO_MATCH>
2026-10-27T14:40:43.9625529Z         at org.junit.Assert.fail(Assert.java:89)
2026-10-27T14:40:43.9625529Z         at com.dk.assistant.FaceMatchTest.testEvaluateMatchNoStoredTarget(FaceMatchTest.kt:68)
2026-10-27T14:40:46.8540195Z 774 tests completed, 1 failed
""".trimIndent()

    private val keys = listOf("app/src/test/java/com/dk/assistant/FaceMatchTest.kt", "app/src/main/java/com/dk/assistant/FaceMatch.kt",
        "app/src/main/java/com/dk/assistant/FaceRecognitionEngine.kt")

    @Test fun compileSitesWithRepoPaths() {
        val s = BuildLog.sites(compileLog)
        assertEquals(listOf(61, 68, 76), s.map { it.line })
        assertTrue(s.all { it.path == "app/src/test/java/com/dk/assistant/FaceMatchTest.kt" && it.kind == "compile" })
        assertTrue(s[0].msg.contains("MatchResult"))
    }

    @Test fun warningsAreNotErrors() = assertTrue(BuildLog.sites(compileLog).none { it.path.contains("AvatarView") })

    @Test fun testFailureSite() {
        val s = BuildLog.sites(testLog)
        assertEquals(1, s.size)
        assertEquals("FaceMatchTest.kt", s[0].path); assertEquals(68, s[0].line); assertEquals("test", s[0].kind)
        assertEquals("app/src/test/java/com/dk/assistant/FaceMatchTest.kt", BuildLog.resolve(s[0].path, keys))
    }

    @Test fun shortAssertFormatToo() {
        val s = BuildLog.sites("com.x.FooTest > bar FAILED\n    java.lang.AssertionError at FooTest.kt:12\n")
        assertEquals(12, s.single().line)
    }

    @Test fun digestPutsErrorsFirstAndDropsNoise() {
        val d = BuildLog.digest(compileLog)
        assertTrue(d.startsWith("ERROR LOCATIONS"))
        assertTrue(d.contains("app/src/test/java/com/dk/assistant/FaceMatchTest.kt:61"))
        assertFalse(d.contains("/home/runner/work"))
        assertFalse(d.contains("processDebugJavaRes"))
        assertFalse(d.contains("Variable 'dt'"))
        assertTrue(d.contains("compileDebugUnitTestKotlin FAILED"))
    }

    @Test fun digestKeepsAssertionDetail() {
        val d = BuildLog.digest(testLog)
        assertTrue(d.contains("expected:<NO_TARGET> but was:<NO_MATCH>"))
        assertTrue(d.contains("FaceMatchTest.kt:68"))
    }

    @Test fun resolveRunnerAndFileUrlPaths() {
        assertEquals(keys[0], SevenDebug.resolve("file:///home/runner/work/DK2.0/DK2.0/app/src/test/java/com/dk/assistant/FaceMatchTest.kt", keys))
        assertEquals(keys[1], SevenDebug.resolve("/some/abs/prefix/app/src/main/java/com/dk/assistant/FaceMatch.kt", keys))
        assertEquals(keys[1], SevenDebug.resolve("FaceMatch.kt", keys))
        assertNull(SevenDebug.resolve("Nope.kt", keys))
    }

    @Test fun windowsMerge() {
        assertEquals(listOf(31..106), BuildLog.windows(listOf(61, 68, 76), 400, 30))
        assertEquals(listOf(1..31, 170..230), BuildLog.windows(listOf(200, 1), 230, 30))
    }

    @Test fun errorCoverPushesBackOnWrongFile() {
        val sites = BuildLog.sites(compileLog)
        val r: (String) -> String? = { BuildLog.resolve(it, keys) }
        val wrong = JSONObject().put("where", "app/src/main/java/com/dk/assistant/FaceRecognitionEngine.kt:65")
            .put("files", JSONArray().put("app/src/main/java/com/dk/assistant/FaceRecognitionEngine.kt"))
        assertNotNull(ErrorCover.miss(wrong, sites, r))
        val right = JSONObject().put("where", "app/src/main/java/com/dk/assistant/FaceMatch.kt:5")
            .put("files", JSONArray().put("app/src/main/java/com/dk/assistant/FaceMatch.kt").put("FaceMatchTest.kt"))
        assertNull(ErrorCover.miss(right, sites, r))
        assertNull(ErrorCover.miss(wrong, emptyList(), r))
    }

    @Test fun localNotesSaveLoadAndThrottle() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wn" + System.nanoTime())
        assertEquals("o_DK2.0__feature_dk-x.md", LocalNotes.fileName("o/DK2.0", "feature/dk-x"))
        assertNull(LocalNotes.load(dir, "o/r", "b"))
        assertTrue(LocalNotes.save(dir, "o/r", "b", "**हालत:** कोड लिखा\nx"))
        assertEquals("**हालत:** कोड लिखा\nx", LocalNotes.load(dir, "o/r", "b"))
        assertEquals(1, LocalNotes.all(dir).size)
        val a = "**हालत:** कोड लिखा\n- 1"; val b = "**हालत:** कोड लिखा\n- 1\n- 2"; val c = "**हालत:** टेस्ट फ़ेल\n- 1"
        assertTrue(LocalNotes.prDue(a, null, 0, 0))                      // पहली बार
        assertFalse(LocalNotes.prDue(b, a, 0, 60_000))                   // सिर्फ़ एक कदम जुड़ा, 1 मिनट
        assertTrue(LocalNotes.prDue(b, a, 0, LocalNotes.PR_EVERY_MS))    // 3 मिनट बाद
        assertTrue(LocalNotes.prDue(c, a, 0, 1_000))                     // हालत बदली = तुरंत
        assertFalse(LocalNotes.prDue(a, a, 0, 10 * 60_000L))             // कुछ नहीं बदला
        dir.deleteRecursively()
    }

    @Test fun workNoteRoundTripFromLocalCopy() {
        val n = WorkNote("काम"); n.state("कोड लिखा"); n.did("एक")
        val md = n.markdown()
        assertEquals("कोड लिखा", WorkNote.fromBody(md)?.status)
    }
}
