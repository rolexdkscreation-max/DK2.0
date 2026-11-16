package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 16 नवंबर 2026 (ऑडिट P0-3): आधी लिखी / टूटी फ़ाइल से यादें चुपचाप नहीं मिटतीं */
class SafeFileTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun write_thenRead_keepsBackupOfPrevious() {
        val f = File(tmp.root, "dk_facts.json")
        SafeFile.write(f, "[\"एक\"]")
        assertEquals("[\"एक\"]", SafeFile.read(f))
        assertFalse(SafeFile.bak(f).exists())                 // पहली बार कोई पुरानी नहीं
        SafeFile.write(f, "[\"एक\",\"दो\"]")
        assertEquals("[\"एक\",\"दो\"]", SafeFile.read(f))
        assertEquals("[\"एक\"]", SafeFile.bak(f).readText())   // पिछली सही हालत बची
        assertFalse(SafeFile.tmp(f).exists())
    }

    @Test fun halfWrittenFile_recoversFromBak() {
        val f = File(tmp.root, "dk_facts.json")
        SafeFile.write(f, "[\"बाइक MH12\"]"); SafeFile.write(f, "[\"बाइक MH12\",\"माँ का जन्मदिन\"]")
        f.writeText("[\"बाइक MH12\",\"माँ का ज")             // बीच में बैटरी गई
        assertEquals("[\"बाइक MH12\"]", SafeFile.read(f))
        f.delete()                                             // rename के बीच में ऐप मरा (असली फ़ाइल नहीं)
        assertEquals("[\"बाइक MH12\"]", SafeFile.read(f))
    }

    @Test fun nothingValid_isNull_andDeleteRemovesBackupToo() {
        val f = File(tmp.root, "x.json")
        assertNull(SafeFile.read(f))
        f.writeText("{टूटा"); assertNull(SafeFile.read(f))
        SafeFile.write(f, "{\"a\":1}"); SafeFile.write(f, "{\"a\":2}")
        SafeFile.delete(f)
        assertFalse(f.exists() || SafeFile.bak(f).exists() || SafeFile.tmp(f).exists())
        assertNull(SafeFile.read(f))                           // "सब मिटाओ" के बाद .bak से वापस नहीं आता
    }

    @Test fun validJson_rules() {
        assertTrue(SafeFile.validJson("[]")); assertTrue(SafeFile.validJson(" {\"a\":[1,2]} "))
        for (bad in listOf("", "  ", "[1,2", "{\"a\":", "hello", "null")) assertFalse(bad, SafeFile.validJson(bad))
    }

    @Test fun importantFiles_useSafeFile() {
        for (n in listOf("Memory.kt", "CoderHistory.kt", "ComputerPast.kt", "LearnedWays.kt")) {
            val s = src(n)
            assertTrue(n, s.contains("SafeFile.write(") && s.contains("SafeFile.read("))
            assertFalse(n, Regex("""\.writeText\(""").containsMatchIn(s))
        }
        assertTrue(src("ComputerPast.kt").contains("SafeFile.delete(file(c))"))
        assertTrue(src("CoderHistory.kt").contains("SafeFile.delete(linesFile(c, id))"))
    }
}
