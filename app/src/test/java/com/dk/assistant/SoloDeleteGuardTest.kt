package com.dk.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 नवंबर 2026 (मालिक का screenshot): "push रोका: app/src/main/java/com/dk/assistant/ui/Wallpapers.kt is still used by 9 file(s)" -
 * "आगे करो" पर भी हर बार वही. जड़: Solo कोडर का DELETE बिना जाँच stage + checkpoint में सेव -> resume पर वही delete वापस -> वही push रोक.
 */
class SoloDeleteGuardTest {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun usedFile_deleteIsRefused_byTheSameCheckAsPush() {
        val ws = mapOf(
            "app/src/main/java/com/dk/assistant/ui/Wallpapers.kt" to "package x\nobject Wallpapers { fun all() = 1 }",
            "app/src/main/java/com/dk/assistant/ui/NeonTheme.kt" to "package x\nval w = Wallpapers.all()",
            "app/src/test/java/com/dk/assistant/CoderFastTest.kt" to "class T { val y = Wallpapers }",
        )
        val p = CodeGuard.deleteProblem("app/src/main/java/com/dk/assistant/ui/Wallpapers.kt", ws.values.first(), ws)
        assertNotNull(p)
        assertTrue(p!!.contains("is still used by 2 file(s)"))
        assertNull(CodeGuard.deleteProblem("a/Old.kt", "object OldThing", mapOf("a/Old.kt" to "object OldThing", "b.kt" to "val z = 1")))
    }

    @Test fun soloDelete_checkedBeforeStaging() {
        val st = src("SevenTeam.kt")
        assertTrue(st.contains("val block = CodeGuard.deleteProblem(path, cur, ws(), gone)"))
        assertTrue(st.contains("\"REFUSED \$path: NOT deleted. \$block\""))
    }

    @Test fun resume_dropsBlockedDeletes_keepsTheRest() = runBlocking {
        val staged = linkedMapOf<String, String?>("ui/Wallpapers.kt" to null, "Old.kt" to null, "A.kt" to "new code")
        val (keep, dropped) = SevenRunner.resumeDeleteBlocked(staged) { p -> if (p == "ui/Wallpapers.kt") "still used" else null }
        assertEquals(listOf("ui/Wallpapers.kt"), dropped)
        assertEquals(linkedMapOf<String, String?>("Old.kt" to null, "A.kt" to "new code"), keep)
        assertTrue(src("SevenRunner.kt").contains("resumeDeleteBlocked(resume?.staged.orEmpty())"))
    }
}
