package com.dk.assistant

import com.dk.assistant.Seven.Agent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 📒 काम-कॉपी: एक बार पढ़ा कोड + हुआ/बाकी PR के विवरण में - अगला राउंड वही कोड शुरू से नहीं पढ़ता */
class WorkNoteTest {

    private val src = (1..50).joinToString("\n") { "line $it" }

    @Test fun readRanges_mergeAndReset_whenFileChanges() {
        val n = WorkNote()
        n.read("a/Foo.kt", src, 1, 10)
        n.read("a/Foo.kt", src, 8, 20)
        n.read("a/Foo.kt", src, 30, 35)
        assertEquals(listOf(1 to 20, 30 to 35), n.files["a/Foo.kt"]!!.ranges)
        assertEquals(50, n.files["a/Foo.kt"]!!.lines)
        assertFalse(n.changed("a/Foo.kt", src))
        val changed = src + "\nnew line"
        assertTrue(n.changed("a/Foo.kt", changed))
        n.read("a/Foo.kt", changed, 40, 45)           // बदली फ़ाइल: पुरानी पढ़ाई बेकार
        assertEquals(listOf(40 to 45), n.files["a/Foo.kt"]!!.ranges)
    }

    @Test fun aiNotes_onlyForFilesReallyRead() {
        val n = WorkNote()
        n.read("a/Foo.kt", src)
        n.about(mapOf("a/Foo.kt" to "  fun bar() line 12   - इसी में बटन  ", "x/Never.kt" to "अंदाज़ा"))
        assertEquals("fun bar() line 12 - इसी में बटन", n.files["a/Foo.kt"]!!.about)
        assertNull(n.files["x/Never.kt"])
    }

    @Test fun doneAndTodo_moveForward() {
        val n = WorkNote()
        n.plan(listOf("A.kt — edit: x", "B.kt — edit: y"))
        n.did("A.kt — edit: x")
        assertEquals(listOf("B.kt — edit: y"), n.todo)
        assertEquals(listOf("A.kt — edit: x"), n.done)
        n.did("A.kt — edit: x")                        // दोबारा = एक ही बार
        assertEquals(1, n.done.size)
    }

    @Test fun markdown_roundTripsThroughPrBody_andKeepsOtherText() {
        val n = WorkNote("Teacher mode का build ठीक करो")
        n.read("app/Main.kt", src, 1, 30); n.about(mapOf("app/Main.kt" to "chipTeacher यहीं जुड़ता है (line 75)"))
        n.plan(listOf("layout — edit: chip जोड़ो")); n.did("जड़ मिली: chipTeacher layout में नहीं")
        n.state("build/tests fail ✗", "fail की जड़ ठीक करना")
        val md = n.markdown()
        assertTrue(md, md.contains("📒") && md.contains("app/Main.kt") && md.contains("हो चुका") && md.contains("बाकी") && md.contains("fail की जड़"))

        val human = "## क्या बदला\nकिसी और का लिखा विवरण"
        val body = WorkNote.inBody(human, md)
        assertTrue(body.startsWith(human))
        val back = WorkNote.fromBody(body)!!
        assertEquals(n.task, back.task)
        assertEquals(listOf(1 to 30), back.files["app/Main.kt"]!!.ranges)
        assertEquals("chipTeacher यहीं जुड़ता है (line 75)", back.files["app/Main.kt"]!!.about)
        assertEquals(n.done, back.done); assertEquals(n.todo, back.todo); assertEquals(n.status, back.status)

        // दूसरी बार: सिर्फ़ काम-कॉपी बदलती है, बाकी विवरण वैसा ही, काम-कॉपी एक ही
        n.did("layout — edit: chip जोड़ो")
        val body2 = WorkNote.inBody(body, n.markdown())
        assertTrue(body2.startsWith(human))
        assertEquals(1, Regex(Regex.escape(WorkNote.BEGIN)).findAll(body2).count())
        assertTrue(WorkNote.fromBody(body2)!!.todo.isEmpty())
        assertNull(WorkNote.fromBody("कोई नोट नहीं"))
    }

    @Test fun prompt_listsStudiedFiles_andMarksChanged() {
        val n = WorkNote("काम")
        n.read("a/Foo.kt", src, 1, 20); n.about(mapOf("a/Foo.kt" to "fun bar() line 12"))
        n.read("a/Bar.kt", "x\ny")
        val p = n.forPrompt(mapOf("a/Foo.kt" to src, "a/Bar.kt" to "x\ny\nz"))
        assertTrue(p, p.contains("FILES ALREADY STUDIED") && p.contains("a/Foo.kt (50 lines, read 1-20): fun bar() line 12"))
        assertTrue(p, p.contains("a/Bar.kt") && p.lines().first { it.contains("a/Bar.kt") }.contains("CHANGED"))
        assertFalse(p.lines().first { it.contains("a/Foo.kt") }.contains("CHANGED"))
        assertEquals("", WorkNote().forPrompt(emptyMap()))
    }

    @Test fun staysSmall() {
        val n = WorkNote()
        for (i in 0 until 100) n.read("f$i.kt", "x")
        assertEquals(WorkNote.MAX_FILES, n.files.size)
        for (i in 0 until 100) n.did("step $i")
        assertEquals(WorkNote.MAX_LIST, n.done.size)
        assertTrue(n.markdown().length < 30_000)
    }

    // ------------------------------------------------------------ टीम के साथ
    class NoteGit(private val prBody: String) : TeamGit {
        val saved = mutableListOf<String>()
        override suspend fun backup(task: String) = "backup/dk-1"
        override suspend fun branch(task: String) = "feature/dk-n"
        override suspend fun pushAndTest(commit: String, title: String, summary: String, rootCause: String, onStep: (String) -> Unit) =
            TeamTest(true, "", "https://ci/1")
        override suspend fun ready() = "https://github.com/u/r/pull/7"
        override val prUrl = "https://github.com/u/r/pull/7"
        override suspend fun saveNote(markdown: String) { saved.add(markdown) }
        override suspend fun loadNote() = prBody
    }

    @Test fun team_readsOldNoteFirst_andSavesProgressToPr() = runBlocking {
        val calc = "package app\n\nfun add(a: Int, b: Int): Int {\n    return a + b\n}\n"
        val old = WorkNote("पिछला राउंड").apply {
            read("src/Calc.kt", calc); about(mapOf("src/Calc.kt" to "add() line 3")); did("पहले वाला कदम")
        }
        val git = NoteGit("## क्या बदला\nx\n\n" + old.markdown())
        val ai = SevenTeamTest.FakeAi(
            Agent.MANAGER to """{"kind":"feature","brief":"add sub()","acceptance":[]}""",
            Agent.PLANNER to """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"add sub function","anchor":"fun add"}],"notes":{"src/Calc.kt":"add() line 3, sub() नीचे"}}""",
            Agent.CODER to "FILE: src/Calc.kt\n<<<FIND\n    return a + b\n}\nFIND>>>\n<<<REPLACE\n    return a + b\n}\n\nfun sub(a: Int, b: Int): Int {\n    return a - b\n}\nREPLACE>>>",
            Agent.REVIEWER to """{"pass":true,"problems":[]}""",
            Agent.GIT to """{"commit":"Add sub()","title":"sub()"}""")
        val events = mutableListOf<String>()
        val team = SevenTeam(ai, MemoryFiles(mapOf("src/Calc.kt" to calc)), git) { _, t -> events.add(t) }
        val out = team.run("Calc में sub() जोड़ो")
        assertEquals(true, out.testOk)
        // प्लानर ने बिना फ़ाइल पढ़े सीधे प्लान दिया - पुराना नोट उसके सामने था
        assertEquals(1, ai.calls.count { it == Agent.PLANNER })
        assertTrue(events.any { it.contains("📒 PR की काम-कॉपी मिली: 1 फ़ाइलों") })
        assertTrue(git.saved.isNotEmpty())
        val last = WorkNote.fromBody(git.saved.last())!!
        assertTrue(last.done.contains("पहले वाला कदम"))
        assertTrue(last.done.any { it.startsWith("src/Calc.kt — edit") })
        assertTrue(last.todo.isEmpty())
        assertEquals("add() line 3, sub() नीचे", last.files["src/Calc.kt"]!!.about)
        assertTrue(last.status, last.status.contains("काम पूरा"))
    }
}
