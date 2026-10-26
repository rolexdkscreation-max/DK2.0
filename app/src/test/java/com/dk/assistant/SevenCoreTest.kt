package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SevenCoreTest {
    private val src = "fun a() {\n    println(1)\n}\n\nfun b() {\n    println(1)\n}\n"
    private val step = Seven.Step("A.kt", "edit", "change a")

    // ---- मोड
    @Test fun mode_decisions() {
        assertEquals(Seven.Mode.NORMAL, Seven.decideMode(false, null, "", null, false).mode)
        assertEquals(Seven.Mode.NORMAL, Seven.decideMode(true, true, "", null, false).mode)          // रिपो नहीं चुना
        assertEquals(Seven.Mode.NORMAL, Seven.decideMode(true, true, "u/r", true, true).mode)       // खुद नॉर्मल चुना
        assertEquals(Seven.Mode.GITHUB, Seven.decideMode(true, true, "u/r", true, false).mode)
        val badTok = Seven.decideMode(true, false, "u/r", null, false)
        assertTrue(badTok.blocked); assertTrue(badTok.problem.contains("टोकन")); assertTrue(badTok.problem.contains("नॉर्मल मोड"))
        val noRepo = Seven.decideMode(true, true, "u/gone", false, false)
        assertTrue(noRepo.blocked); assertTrue(noRepo.problem.contains("not found"))
    }

    @Test fun kind() {
        assertEquals(Seven.Kind.BUG, Seven.kindOf("bug", "x"))
        assertEquals(Seven.Kind.QUESTION, Seven.kindOf("question", "x"))
        assertEquals(Seven.Kind.FEATURE, Seven.kindOf("feature", "डार्क मोड जोड़ो"))
        assertEquals(Seven.Kind.BUG, Seven.kindOf(null, "ऐप crash हो रहा है"))
        assertEquals(Seven.Kind.FEATURE, Seven.kindOf(null, "नया बटन जोड़ो"))
    }

    // ---- FIND/REPLACE पढ़ना
    @Test fun parse_findReplace() {
        val e = Seven.parseEdit("FILE: A.kt\n<<<FIND\n    println(1)\n}\n\nfun b() {\nFIND>>>\n<<<REPLACE\n    println(2)\n}\n\nfun b() {\nREPLACE>>>", "A.kt")
        assertTrue(e.errors.isEmpty())
        assertEquals(1, e.pairs.size)
        assertEquals("    println(1)\n}\n\nfun b() {", e.pairs[0].first)
    }

    @Test fun parse_replaceWithoutFind_isError() {
        val e = Seven.parseEdit("<<<REPLACE\nx\nREPLACE>>>", "A.kt")
        assertTrue(e.errors.any { it.contains("REPLACE बिना FIND") })
    }

    @Test fun parse_findWithoutReplace_unclosed_empty() {
        assertTrue(Seven.parseEdit("<<<FIND\nx\nFIND>>>", "A.kt").errors.any { it.contains("REPLACE नहीं आया") })
        assertTrue(Seven.parseEdit("<<<FIND\nx\n", "A.kt").errors.any { it.contains("बंद नहीं") })
        assertTrue(Seven.parseEdit("मैंने बदल दिया", "A.kt").errors.isNotEmpty())
    }

    @Test fun parse_fullFile_delete_emptyReplace() {
        val f = Seven.parseEdit("FILE: n.js\n<<<FILE\nconsole.log(1)\nFILE>>>", "n.js")
        assertEquals("console.log(1)", f.full)
        assertTrue(Seven.parseEdit("DELETE: old.kt", "old.kt").delete)
        val r = Seven.parseEdit("<<<FIND\nfun a() {}\nFIND>>>\n<<<REPLACE\nREPLACE>>>", "A.kt")
        assertEquals("", r.pairs[0].second)
    }

    // ---- रिव्यूअर की पक्की जाँच
    @Test fun check_findMustMatchExactlyOnce() {
        val notFound = Seven.check(Seven.parseEdit("<<<FIND\nfun c() {\nFIND>>>\n<<<REPLACE\nx\nREPLACE>>>", "A.kt"), step, src, false)
        assertFalse(notFound.ok); assertTrue(notFound.findMiss)
        val twice = Seven.check(Seven.parseEdit("<<<FIND\n    println(1)\nFIND>>>\n<<<REPLACE\n    println(2)\nREPLACE>>>", "A.kt"), step, src, false)
        assertFalse(twice.ok); assertTrue(twice.problems[0].contains("2 जगह"))
        val spaces = Seven.check(Seven.parseEdit("<<<FIND\nfun a() {\n  println(1)\nFIND>>>\n<<<REPLACE\nfun a() {\n  println(3)\nREPLACE>>>", "A.kt"), step, src, false)
        assertFalse(spaces.ok); assertTrue(spaces.problems[0].contains("line 1"))   // पहली लाइन मिली पर spaces अलग
        val ok = Seven.check(Seven.parseEdit("<<<FIND\nfun a() {\n    println(1)\nFIND>>>\n<<<REPLACE\nfun a() {\n    println(3)\nREPLACE>>>", "A.kt"), step, src, false)
        assertTrue(ok.ok); assertTrue(ok.content!!.contains("println(3)")); assertTrue(ok.content!!.contains("fun b()"))
    }

    @Test fun check_syntaxAndRules() {
        val brokenBrace = Seven.check(Seven.parseEdit("<<<FIND\nfun a() {\nFIND>>>\n<<<REPLACE\nfun a() {{\nREPLACE>>>", "A.kt"), step, src, false)
        assertFalse(brokenBrace.ok); assertTrue(brokenBrace.problems[0].contains("syntax"))
        val wrongFile = Seven.check(Seven.parseEdit("FILE: B.kt\n<<<FIND\nfun a() {\nFIND>>>\n<<<REPLACE\nfun z() {\nREPLACE>>>", "A.kt"), step, src, false)
        assertFalse(wrongFile.ok)
        // मौजूदा फ़ाइल को पूरी दोबारा लिखना सिर्फ़ मैनेजर के कहने पर
        val full = Seven.parseEdit("FILE: A.kt\n<<<FILE\n$src\nfun c() {}\nFILE>>>", "A.kt")
        assertFalse(Seven.check(full, step, src, allowRewrite = false).ok)
        assertTrue(Seven.check(full, step, src, allowRewrite = true).ok)
        // rewrite में आधी फ़ाइल गायब = रोको
        val cut = Seven.parseEdit("FILE: A.kt\n<<<FILE\nfun a() {}\nFILE>>>", "A.kt")
        assertFalse(Seven.check(cut, step, src + src + src, allowRewrite = true).ok)
        // नई फ़ाइल: <<<FILE ज़रूरी, FIND नहीं
        assertFalse(Seven.check(Seven.parseEdit("FILE: N.kt\n<<<FIND\nx\nFIND>>>\n<<<REPLACE\ny\nREPLACE>>>", "N.kt"), Seven.Step("N.kt", "create", "new"), null, false).ok)
        val tooMany = (1..4).joinToString("\n") { "<<<FIND\nfun a() {\nFIND>>>\n<<<REPLACE\nfun a() {\nREPLACE>>>" }
        assertFalse(Seven.check(Seven.parseEdit(tooMany, "A.kt"), step, src, false).ok)
    }

    @Test fun strategy_switchesOnRepeatedFindMissOrSameError() {
        assertEquals(Seven.Strategy.FIND, Seven.strategy(listOf("x"), 1, 500, true))
        assertEquals(Seven.Strategy.REWRITE, Seven.strategy(listOf("x", "y"), 2, 500, true))
        assertEquals(Seven.Strategy.REWRITE, Seven.strategy(listOf("same", "same"), 0, 500, true))
        assertEquals(Seven.Strategy.FIND, Seven.strategy(listOf("same", "same"), 2, Seven.REWRITE_MAX_CHARS + 1, true))  // बहुत बड़ी फ़ाइल
        assertEquals(Seven.Strategy.FIND, Seven.strategy(listOf("same", "same"), 2, 0, false))
    }

    // ---- JSON / प्लान
    @Test fun json_andPlan() {
        assertEquals("bug", Seven.json("ठीक है ```json\n{\"kind\":\"bug\",\"x\":\"a } b\"}\n```")!!.optString("kind"))
        assertNull(Seven.json("कुछ नहीं"))
        val p = Seven.parsePlan("""{"steps":[{"file":"./a/B.kt","action":"EDIT","change":"x"},{"file":"","change":"y"}],"risks":["r"]}""")!!
        assertEquals(1, p.steps.size); assertEquals("a/B.kt", p.steps[0].file); assertEquals("edit", p.steps[0].action)
        assertEquals(listOf("a.kt", "b.kt"), Seven.planReads("""{"read":["a.kt","b.kt"]}"""))
        assertNull(Seven.planReads("""{"steps":[]}"""))
        val many = (1..15).joinToString(",") { """{"file":"f$it.kt","change":"c"}""" }
        assertEquals(Seven.MAX_STEPS, Seven.parsePlan("""{"steps":[$many]}""")!!.steps.size)
    }

    // ---- नॉर्मल मोड
    @Test fun filesFromMessage_names() {
        val msg = "यह मेरा कोड है, बग ढूँढो\nFile: app.js\n```js\nconst x = 1\n```\n```python main.py\nprint(1)\n```\n```css\nh1{}\n```"
        val f = Seven.filesFromMessage(msg)
        assertEquals(listOf("app.js", "main.py", "code1.css"), f.keys.toList())
        assertEquals("const x = 1", f["app.js"])
        assertTrue(Seven.filesFromMessage("सिर्फ़ बात").isEmpty())
    }

    @Test fun formatFiles_eachWithName() {
        val t = Seven.formatFiles(linkedMapOf("index.html" to "<h1>x</h1>", "old.js" to null))
        assertTrue(t.contains("📄 index.html\n```html\n<h1>x</h1>\n```"))
        assertTrue(t.contains("🗑 old.js"))
    }

    // ---- syntax
    @Test fun syntax_languages() {
        assertNull(Syntax.check("a.kt", "val s = \"a{\" + '}'\nfun x() { }"))
        assertNotNull(Syntax.check("a.kt", "fun x() {"))
        assertNull(Syntax.check("a.js", "const t = `a ${'$'}{b} }`; function f() { return '{' }"))
        assertNotNull(Syntax.check("a.js", "function f() { return [1, 2 }"))
        assertNull(Syntax.check("a.py", "# comment (\nd = {'a': [1, 2]}\ns = '''\n(\n'''"))
        assertNotNull(Syntax.check("a.json", "{\"a\": }"))
        assertNotNull(Syntax.check("a.xml", "<a><b></a>"))
        assertNull(Syntax.check("a.html", "<div>"))                     // HTML ढीला - नहीं रोकते
        assertNotNull(Syntax.check("a.kt", "fun x() {}\n<<<FIND"))
    }

    @Test fun report_format() {
        val r = Seven.report("बटन जोड़ो", Seven.Mode.GITHUB, listOf("A.kt" to "बटन जोड़ा"),
            listOf(Seven.Bug("crash", "A.kt:3", "null", "check जोड़ा")), true, "", "https://pr/1", "backup/dk-1")
        listOf("काम क्या था", "क्या हुआ", "फ़ाइल: A.kt", "3) बग / समस्या", "जगह: A.kt:3", "कैसे ठीक किया", "एजेंट की जाँच: ✅ पास",
            "कृपया PR की समीक्षा करें").forEach { assertTrue(it, r.contains(it)) }
        val n = Seven.report("x", Seven.Mode.NORMAL, emptyList(), emptyList(), false, "e", "", "")
        assertFalse(n.contains("merge")); assertTrue(n.contains("❌ फ़ेल")); assertTrue(n.contains("वास्तविक APK build")); assertTrue(n.contains("क्या काम दिया था"))
    }

    @Test fun everyAgentHasOwnShortPrompt() {
        val all = Seven.Agent.values().map { Seven.Prompts.of(it) }
        assertEquals(11, all.distinct().size)          // 7 पुराने + प्रॉम्प्ट इंजीनियर, परफॉरमेंस, QA, डिप्लॉयमेंट
        Seven.Agent.values().forEach { assertTrue(it.name, Seven.Prompts.of(it).contains("ONLY job")) }
        assertTrue(Seven.Prompts.CODER.contains("NEVER send REPLACE without FIND"))
    }

    @Test fun dispatchableWorkflow_onlyPrCheckThatCanRunByHand() {
        val check = "on:\n  pull_request:\n  workflow_dispatch:\njobs: {}"
        val build = "on:\n  push:\n    branches: [main]\n  workflow_dispatch:\n"
        val prOnly = "on:\n  pull_request:\n# workflow_dispatch:\n"
        assertEquals("check.yml", Seven.dispatchable(listOf("build.yml" to build, "check.yml" to check)))
        assertNull(Seven.dispatchable(listOf("build.yml" to build, "pr.yml" to prOnly)))
    }

    @Test fun report_neverDoneBeforeCheckPasses_alwaysPrAndCheck() {
        val fail = Seven.report("x", Seven.Mode.GITHUB, emptyList(), emptyList(), false, "e: X.kt:3", "", "b", Seven.CheckState.FAIL, "https://ci/9")
        assertFalse(fail.contains("✅ काम पूरा हुआ"))
        assertTrue(fail.contains("Pull Request: अभी नहीं बना")); assertTrue(fail.contains("GitHub Actions: ❌ फ़ेल")); assertTrue(fail.contains("Build/check लिंक: https://ci/9"))
        val running = Seven.report("x", Seven.Mode.GITHUB, emptyList(), emptyList(), null, "", "https://pr/1", "b", Seven.CheckState.RUNNING)
        assertFalse(running.contains("✅ काम पूरा हुआ")); assertTrue(running.contains("⏳")); assertFalse(running.contains("merge कर दूँ"))
        val noPr = Seven.report("x", Seven.Mode.GITHUB, emptyList(), emptyList(), true, "", "", "b", Seven.CheckState.PASS)
        assertFalse(noPr.contains("✅ काम पूरा हुआ"))
        val ok = Seven.report("x", Seven.Mode.GITHUB, emptyList(), emptyList(), true, "", "https://pr/1", "b", Seven.CheckState.PASS, "https://ci/1")
        assertTrue(ok.contains("✅ सत्यापन के अनुसार काम पूरा")); assertTrue(ok.contains("Pull Request: https://pr/1")); assertTrue(ok.contains("GitHub Actions: ✅ पास")); assertTrue(ok.contains("Build/check लिंक: https://ci/1"))
    }
}
