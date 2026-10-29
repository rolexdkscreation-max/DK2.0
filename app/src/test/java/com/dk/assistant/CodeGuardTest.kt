package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v4.4: एजेंट ने पूरी Prefs.kt / SettingsActivity.kt / ui_en.tsv हटा दीं और डिबगर 7 बार टुकड़े जोड़ता रहा - अब पक्की जाँच */
class CodeGuardTest {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    private val prefs = "package x\nobject Prefs {\n" + (1..80).joinToString("\n") { "    fun key$it(c: Context): String = \"\"" } + "\n    fun ghToken(c: Context): String = \"\"\n}\n"
    private val ws = mapOf(
        "app/src/main/java/x/Prefs.kt" to prefs,
        "app/src/main/java/x/Main.kt" to "class Main { fun a() = Prefs.ghToken(this) }",
        "app/src/main/java/x/UiText.kt" to "object UiText { val f = \"ui_en.tsv\" }",
        "app/src/main/java/x/Lonely.kt" to "class Lonely { fun z() = 1 }",
        "app/src/main/assets/ui_en.tsv" to "a\tb",
    )

    @Test fun usedFileCannotBeDeleted() {
        assertEquals(listOf("app/src/main/java/x/Main.kt"), CodeGuard.users("app/src/main/java/x/Prefs.kt", prefs, ws))
        assertNotNull(CodeGuard.deleteProblem("app/src/main/java/x/Prefs.kt", prefs, ws))
        assertEquals(listOf("app/src/main/java/x/UiText.kt"), CodeGuard.users("app/src/main/assets/ui_en.tsv", "a\tb", ws))
        assertNull(CodeGuard.deleteProblem("app/src/main/java/x/Lonely.kt", ws["app/src/main/java/x/Lonely.kt"], ws))
        // जो फ़ाइल इस्तेमाल करती थी वह भी साथ में हट रही = ठीक
        assertNull(CodeGuard.deleteProblem("app/src/main/java/x/Prefs.kt", prefs, ws, setOf("app/src/main/java/x/Main.kt")))
    }

    @Test fun shrinkDetected() {
        assertTrue(CodeGuard.shrunk(prefs, "object Prefs {\n fun ghToken() = 1\n}"))
        assertFalse(CodeGuard.shrunk(prefs, prefs.replace("    fun key1(c: Context): String = \"\"\n", "")))
        assertFalse(CodeGuard.shrunk("a\nb\nc", "a"))   // छोटी फ़ाइल
    }

    @Test fun buildErrorsTracedToDeletedFile() {
        val log = "e: file:///w/app/src/main/java/x/Main.kt:3:20 Unresolved reference 'ghToken'.\n" +
            "e: file:///w/app/src/main/java/x/Hey.kt:9:5 Unresolved reference: key7\n"
        assertEquals(setOf("ghToken", "key7"), CodeGuard.unresolved(log))
        val ch = listOf(
            CodeGuard.Change("app/src/main/java/x/Prefs.kt", "modified", 28, 80, CodeGuard.lines(prefs)),   // दोबारा टुकड़ों में बनी
            CodeGuard.Change("app/src/main/java/x/Main.kt", "modified", 2, 1, 1),
        )
        assertTrue(ch[0].damaged); assertFalse(ch[1].damaged)
        val d = CodeGuard.damage(log, ch) { if (it.endsWith("Prefs.kt")) prefs else null }
        assertEquals(1, d.size)
        assertEquals("app/src/main/java/x/Prefs.kt", d[0].path)
        assertTrue(d[0].names.containsAll(listOf("ghToken", "key7")))
        val note = CodeGuard.damageNote(d, "main")
        assertTrue(note.contains("ROOT CAUSE")); assertTrue(note.contains("RESTORED exactly")); assertTrue(note.contains("Do NOT re-create"))
        val f = CodeGuard.footprint(ch + CodeGuard.Change("app/src/main/assets/ui_en.tsv", "removed", 0, 1195, 1195), "main")
        assertTrue(f.indexOf("ui_en.tsv: DELETED") in 0 until f.indexOf("Main.kt"))
        assertTrue(f.contains("!! app/src/main/java/x/Prefs.kt"))
        // कोई नुकसान नहीं = कुछ वापस नहीं
        assertTrue(CodeGuard.damage("e: Unresolved reference 'other'", ch) { prefs }.isEmpty())
    }

    @Test fun flippedConditionCaught() {
        val before = "fun a() {\n    if (!inSeg) start()\n    if (x == 1) go()\n    val y = 2\n}"
        val after = "fun a() {\n    if (inSeg) start()\n    if (x != 1) go()\n    val y = 3\n}"
        val f = CodeGuard.flips(before, after)
        assertEquals(2, f.size)
        assertTrue(f[0].contains("if (!inSeg) start()"))
        assertTrue(CodeGuard.flips(before, before).isEmpty())
        assertTrue(CodeGuard.flips(before, before.replace("start()", "begin()")).isEmpty())
    }

    @Test fun wiredIntoAgents() {
        val ca = src("CodingAgent.kt")
        assertTrue(ca.contains("deleteBlock(path, cur)?.let { return \"ERROR: NOT deleted. \$it\" }"))
        assertTrue(ca.contains("CodeGuard.shrunk(old, content)"))
        assertTrue(ca.contains("push रोका"))
        assertTrue(ca.contains("CoderContext.compact(msgs, budget, CoderContext.KEEP_TAIL"))
        val st = src("SevenTeam.kt")
        assertTrue(st.contains("CodeGuard.deleteProblem(s.file, cur, ws(), gone)"))
        assertTrue(st.contains("files.restoreDamaged(t.log)"))
        assertTrue(st.contains("files.footprint()"))
        assertTrue(st.contains("CodeGuard.flips(cur, after)"))
        assertTrue(src("CoderTeam.kt").contains("a.restoreDamaged(b.log)"))
        assertTrue(src("SevenRunner.kt").contains("override suspend fun restoreDamaged"))
        assertTrue(SevenDebug.EXTRA.contains("NEVER re-create its pieces one by one"))
        assertTrue(Seven.Prompts.PLANNER.contains("NEVER plan \"delete\" for a shared file"))
        assertTrue(Seven.Prompts.REVIEWER.contains("CONDITION FLIPPED"))
    }

    @Test fun readMuchMore() {
        assertTrue(CoderContext.budgetFor(false, "llama-3.3-70b") >= 200_000)
        assertTrue(CoderContext.budgetFor(true, "gemini-3.8-pro") >= 500_000)
        assertEquals(100_000, CoderContext.readChars(500_000))
        assertEquals(CoderContext.CHUNK_CHARS, CoderContext.readChars(10_000))
    }
}
