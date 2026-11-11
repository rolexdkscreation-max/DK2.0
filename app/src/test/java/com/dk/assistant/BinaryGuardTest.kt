package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026: feature/dk-1110-2225 पर PR चेक 6 बार लगातार fail - कोडिंग एजेंट ने dk_theme_bg.webp को text से
 * दोबारा लिखकर तोड़ा, फिर 3 खाली commit से वही build दोबारा चलाया. ये test पक्का करते हैं कि अब ऐसा नहीं होगा.
 */
class BinaryGuardTest {
    private val wall = "app/src/main/res/drawable-nodpi/dk_theme_bg.webp"

    // असली CI log (run 42869257448) का हिस्सा - timestamp के साथ
    private val log = """
        2026-11-10T17:55:44.5966394Z com.dk.assistant.CoderHeaderTaskTest > newDefaultWallpaperNotOldBlue FAILED
        2026-11-10T17:55:44.5967544Z     java.lang.AssertionError
        2026-11-10T17:55:44.5971985Z         at com.dk.assistant.CoderHeaderTaskTest.newDefaultWallpaperNotOldBlue(CoderHeaderTaskTest.kt:36)
        2026-11-10T17:55:50.0000000Z 1840 tests completed, 1 failed
    """.trimIndent()

    private val testSrc = """
        @Test fun newDefaultWallpaperNotOldBlue() {
            val f = File("src/main/res/drawable-nodpi/dk_theme_bg.webp")
            assertTrue(f.isFile && f.length() in 40_000..200_000)
        }
    """.trimIndent()

    @Test fun binaryPathsDetected() {
        assertTrue(BinaryGuard.isBinary(wall))
        assertTrue(BinaryGuard.isBinary("a/b/icon.PNG"))
        assertTrue(BinaryGuard.isBinary("app/libs/x.jar"))
        assertFalse(BinaryGuard.isBinary("app/src/main/java/com/dk/assistant/ui/Wallpapers.kt"))
        assertFalse(BinaryGuard.isBinary("app/src/main/res/drawable/bg.xml"))
        assertFalse(BinaryGuard.isBinary("README"))
    }

    @Test fun binaryWritesBlockedButCodeAndDeletesKept() {
        val ch = mapOf(wall to "RIFFv   WEBPVP8L", "app/src/main/java/A.kt" to "class A", "old.png" to null)
        assertEquals(setOf(wall), BinaryGuard.blockedWrites(ch))
        assertEquals(setOf("app/src/main/java/A.kt", "old.png"), BinaryGuard.safeChanges(ch).keys)
    }

    @Test fun failingTestPointsToDamagedWallpaper() {
        assertEquals(listOf("com.dk.assistant.CoderHeaderTaskTest"), BinaryGuard.failedTestClasses(log))
        val paths = listOf("app/src/test/java/com/dk/assistant/CoderHeaderTaskTest.kt", wall, "app/src/main/res/drawable-nodpi/other.webp")
        val src = { p: String -> if (p.endsWith("CoderHeaderTaskTest.kt")) testSrc else null }
        assertEquals(listOf(wall), BinaryGuard.culprits(log, listOf(wall, "app/src/main/res/drawable-nodpi/other.webp"), src, paths))
        // इस काम ने कोई binary नहीं बदली = कुछ वापस नहीं
        assertTrue(BinaryGuard.culprits(log, emptyList(), src, paths).isEmpty())
    }

    @Test fun noChangeKeepsSameFailureSignature() {
        // खाली सुधार का log = पिछला fail; FixLoop को "वही गलती" दिखे ताकि 3 बार में रुककर रिपोर्ट
        assertEquals(FixLoop.signature(log), FixLoop.signature(BinaryGuard.NO_CHANGE + "\n\n" + log))
    }

    @Test fun defaultWallpaperIsRealWebp() {
        val f = File("src/main/res/drawable-nodpi/dk_theme_bg.webp")
        val b = f.readBytes()
        assertTrue(String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP")
        // RIFF size = फ़ाइल - 8 (text बनकर टूटी फ़ाइल में यह मेल नहीं खाता)
        val riff = (b[4].toInt() and 255) or ((b[5].toInt() and 255) shl 8) or ((b[6].toInt() and 255) shl 16) or ((b[7].toInt() and 255) shl 24)
        assertEquals(b.size - 8, riff)
    }

    @Test fun guardsWiredIn() {
        val gh = File("src/main/java/com/dk/assistant/GitHub.kt").readText()
        assertTrue(gh.contains("val changes = BinaryGuard.safeChanges(changes0)"))
        assertTrue(gh.contains("if (tree == baseTree) throw NoChange("))
        assertTrue(gh.contains("fun restoreFromBase("))
        val ca = File("src/main/java/com/dk/assistant/CodingAgent.kt").readText()
        assertTrue(ca.contains("if (content != null && BinaryGuard.isBinary(path))"))
        assertTrue(ca.contains("BinaryGuard.blockedWrites(staged).forEach { staged.remove(it) }"))
        assertTrue(ca.contains("fun restoreBinaries(log: String)"))
        val sr = File("src/main/java/com/dk/assistant/SevenRunner.kt").readText()
        assertTrue(sr.contains("if (failedSha.isNotBlank() && a.lastSha == failedSha)"))
        assertTrue(sr.contains("a.restoreBinaries(log)"))
        val st = File("src/main/java/com/dk/assistant/SevenTeam.kt").readText()
        assertTrue(st.contains("if (s.action != \"delete\" && BinaryGuard.isBinary(s.file))"))
    }
}
