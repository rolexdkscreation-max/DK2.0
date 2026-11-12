package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक: "coding agent इतना slow क्यों, फटाक से और अच्छे से करे").
 * असली केस feature/dk-1112-1236: `com.dk.assistant.R.drawable.dk_wp_logo` (तस्वीर थी ही नहीं) -> 3 बेकार CI राउंड.
 */
class CoderFastTest {
    private fun src(n: String) = File("src/main/java/com/dk/assistant/$n").readText()

    private val wallPath = "app/src/main/java/com/dk/assistant/ui/Wallpapers.kt"
    private val values = "app/src/main/res/values/strings.xml" to "<resources><string name=\"app_name\">DK</string></resources>"
    private val skipped = (1..5).map { "app/src/main/res/drawable-nodpi/dk_wp_$it.webp" }.toSet()
    private val oldWall = """package com.dk.assistant.ui

object Wallpapers {
    data class Wall(val id: String, val res: Int)
    val BUILT_IN = listOf(
        Wall("wp_1", com.dk.assistant.R.drawable.dk_wp_1),
        Wall("wp_2", com.dk.assistant.R.drawable.dk_wp_2)
    )
}
"""
    private val badWall = oldWall.replace("    val BUILT_IN", "    val NEW = listOf(\n        Wall(\"wp_logo\", com.dk.assistant.R.drawable.dk_wp_logo)\n    )\n    val BUILT_IN")

    // ---------------------------------------------------------------- RefCheck: पूरा नाम वाला R
    @Test fun qualifiedR_missingDrawable_caughtBeforePush() {
        val all = mapOf(values, wallPath to badWall)
        val r = RefCheck.missingResources(mapOf(wallPath to badWall), { oldWall }, all, skipped, hint = true)
        assertEquals(r.toString(), 1, r.size)
        assertTrue(RefCheck.missingResources(mapOf(wallPath to badWall), { oldWall }, all, skipped)[0].contains("dk_wp_logo"))
        assertTrue(r[0], r[0].contains("@drawable/dk_wp_logo"))
        // मौजूद नाम सुझाए + binary की सच्चाई
        assertTrue(r[0], r[0].contains("dk_wp_1") && r[0].contains("dk_wp_5"))
        assertTrue(r[0], r[0].contains("png/webp/jpg"))
    }

    @Test fun qualifiedR_existingOrLibrary_ok() {
        val now = oldWall.replace("dk_wp_2)", "dk_wp_3)\n    val x = android.R.drawable.ic_menu_add\n    val y = androidx.appcompat.R.drawable.abc_vector_test")
        val r = RefCheck.missingResources(mapOf(wallPath to now), { oldWall }, mapOf(values, wallPath to now), skipped)
        assertTrue(r.toString(), r.isEmpty())
    }

    @Test fun ciMissing_tellsDebuggerTheRealCause() {
        val log = "e: file:///home/codespace/dk-run/$wallPath:6:47 Unresolved reference: dk_wp_logo"
        val m = RefCheck.ciMissing(log, mapOf(values, wallPath to badWall), skipped)
        assertTrue(m, m.startsWith("RESOURCE MISSING") && m.contains("R.drawable.dk_wp_logo") && m.contains("dk_wp_1"))
        // गलती resource की नहीं (कोई function) = कुछ नहीं
        assertEquals("", RefCheck.ciMissing("e: x.kt:1:1 Unresolved reference: doMagic", mapOf(values, wallPath to badWall), skipped))
    }

    // ---------------------------------------------------------------- BuildLog: Codespace के path
    @Test fun sandboxPaths_becomeRepoPaths() {
        val s = BuildLog.sites("e: file:///home/codespace/dk-run/$wallPath:25:66 Unresolved reference: dk_wp_logo")
        assertEquals(1, s.size)
        assertEquals(wallPath, s[0].path)
        assertEquals(25, s[0].line)
        assertEquals(wallPath, BuildLog.sites("e: /workspaces/DK2.0/$wallPath:3:1 Expecting member declaration")[0].path)
        // GitHub runner वाले पहले जैसे
        assertEquals(wallPath, BuildLog.sites("e: file:///home/runner/work/DK2.0/DK2.0/$wallPath:3:1 x")[0].path)
    }

    // ---------------------------------------------------------------- FixAim: गलत जगह का सुधार push नहीं
    private val errLog = "e: file:///home/codespace/dk-run/$wallPath:6:47 Unresolved reference: dk_wp_logo"

    @Test fun fixAim_commentOnlyOrOtherLines_missed() {
        // असली केस: comment + दूसरी लाइन बदली, गलती वाली लाइन वैसी की वैसी
        val fix = badWall.replace("object Wallpapers {", "/** थीम वाला */\nobject Wallpapers {").replace("dk_wp_2)", "dk_wp_2) // q") + "val q = 100\n"
        val m = FixAim.missed(errLog, mapOf(wallPath to badWall), mapOf(wallPath to fix))
        assertNotNull(m)
        assertTrue(m!!, m.startsWith("FIX MISSED THE ERROR") && m.contains("$wallPath:6"))
        val onlyComment = badWall.replace("object Wallpapers {", "// नया comment\nobject Wallpapers {")
        assertTrue(FixAim.missed(errLog, mapOf(wallPath to badWall), mapOf(wallPath to onlyComment))!!.contains("सिर्फ़ comment"))
    }

    @Test fun fixAim_realFix_notBlocked() {
        val ok = badWall.replace("dk_wp_logo", "dk_wp_1")
        assertNull(FixAim.missed(errLog, mapOf(wallPath to badWall), mapOf(wallPath to ok)))
        // नाम कहीं और बनाया (दूसरी फ़ाइल) = पक्का नहीं कह सकते -> push होने दो
        val xml = "app/src/main/res/drawable/dk_wp_logo.xml"
        assertNull(FixAim.missed(errLog, mapOf(wallPath to badWall), mapOf(wallPath to badWall, xml to "<vector/>")))
        // test fail (compile नहीं) = यह जाँच नहीं
        assertNull(FixAim.missed("com.dk.assistant.XTest > a FAILED\n    java.lang.AssertionError: x\n        at com.dk.assistant.XTest.a(XTest.kt:5)",
            mapOf(wallPath to badWall), mapOf(wallPath to badWall + "\n// x")))
        // उसी फ़ाइल में गायब नाम बनाया (लाइन दूर) = निशाने पर
        val fnLog = "e: $wallPath:6:9 Unresolved reference: makeWall"
        val withUse = badWall.replace("Wall(\"wp_logo\", com.dk.assistant.R.drawable.dk_wp_logo)", "makeWall()")
        val defined = withUse.replace("    data class Wall", "    fun makeWall() = Wall(\"x\", 0)\n    data class Wall")
        assertNull(FixAim.missed(fnLog, mapOf(wallPath to withUse), mapOf(wallPath to defined)))
    }

    @Test fun fixAim_wiredBeforePush() {
        val t = src("SevenTeam.kt")
        assertTrue(t.contains("if (fast) failBase?.let { fb -> if (failLogCi.isNotBlank()) FixAim.missed(failLogCi, fb, staged)"))
        assertTrue(t.contains("RefCheck.missingResources(staged, { p -> orig[p] }, all, skipped, hint = true)"))
        assertTrue(t.contains("failLogCi = r.log"))
        assertTrue(t.contains("RefCheck.ciMissing(log, ws()"))
        assertTrue(t.contains("BuildLog.clean(it).trim()"))
    }

    // ---------------------------------------------------------------- छोटा context, समय-हिसाब, perf
    @Test fun leanBudget() {
        assertEquals(CoderSpeed.LEAN_BUDGET, CoderSpeed.lean(CoderContext.GEMINI_BUDGET))
        assertEquals(60_000, CoderSpeed.lean(60_000))
        assertTrue(src("SevenTeam.kt").contains("private val lean get() = CoderSpeed.lean(big)"))
        // एक पूरी फ़ाइल पहले जितनी ही (कोडर को अधूरी नहीं)
        assertTrue(src("SevenTeam.kt").contains("private val fileChars get() = maxOf(FILE_CHARS, big / 4)"))
    }

    @Test fun clockPerAgentAndBuild() {
        var t = 0L
        val c = CoderSpeed.Clock { t }
        val a = c.start(); t += 90_000; c.done(a, "कोडर")
        val b = c.start(); t += 30_000; c.done(b, "प्लानर")
        val d = c.start(); t += 5 * 60_000; c.built(d)
        assertEquals(90_000L, c.byWho["कोडर"])
        assertTrue(c.top().startsWith("कोडर 1 मिनट 30s"))
        val r = c.report()
        assertTrue(r, r.contains("किसने कितना") && r.contains("build/tests के 1 राउंड: 5 मिनट 0s"))
    }

    @Test fun perfQuick_smallCleanChangeOnly() {
        val a = (1..50).joinToString("\n") { "line $it" }
        assertTrue(CoderSpeed.perfQuick(listOf(Triple("A.kt", a, a.replace("line 7", "line seven")))))
        assertFalse(CoderSpeed.perfQuick(listOf(Triple("A.kt", null, a))))                 // नई फ़ाइल
        assertFalse(CoderSpeed.perfQuick(listOf(Triple("A.kt", a, a + "\n" + (1..60).joinToString("\n") { "new $it" }))))
        assertFalse(CoderSpeed.perfQuick(emptyList()))
    }

    // ---------------------------------------------------------------- मॉडल: छोटे काम Flash-Lite पर
    @Test fun liteAgents() {
        for (a in TeamModels.LITE) assertTrue(a.name, TeamModels.useLite(a, true, GeminiModels.CODER, false))
        // code लिखने/जाँचने/समझने वाले कभी Lite नहीं
        for (a in listOf(Seven.Agent.CODER, Seven.Agent.DEBUGGER, Seven.Agent.PLANNER, Seven.Agent.REVIEWER, Seven.Agent.QA,
            Seven.Agent.MANAGER, Seven.Agent.EXPLAINER)) assertFalse(a.name, TeamModels.useLite(a, true, GeminiModels.CODER, false))
        // बंद / user का अपना हल्का मॉडल / मज़बूत मॉडल लगा = Lite नहीं
        assertFalse(TeamModels.useLite(Seven.Agent.GIT, false, GeminiModels.CODER, false))
        assertFalse(TeamModels.useLite(Seven.Agent.GIT, true, "gemini-3.6-flash", false))
        assertFalse(TeamModels.useLite(Seven.Agent.GIT, true, GeminiModels.CODER, true))
        // Lite न चले तो 3.8 Flash अपने-आप (chain में)
        assertTrue(GeminiModels.CODER in GeminiModels.LIGHT_CHAIN)
        val r = src("SevenRunner.kt")
        assertEquals(3, Regex("""\.lite\(Prefs\.coderLiteSide\(c\)\)""").findAll(r).count())
        assertTrue(r.contains("ai.withModel(GeminiModels.LIGHT, GeminiModels.LIGHT_CHAIN)"))
        assertTrue(src("SettingsCoder.kt").contains("Prefs.coderLiteSide(this)"))
        assertTrue(src("SettingsActivity.kt").contains("Prefs.CODER_LITE_SIDE"))
    }

    // ---------------------------------------------------------------- merge के बाद अगला काम तुरंत
    @Test fun mergeReleaseInBackground() {
        val t = src("CoderTeam.kt")
        assertTrue(t.contains("mergeAndRelease(c, a, p, background = true)"))
        assertTrue(t.contains("deployJob = CoderSession.scope.launch"))
        assertTrue(t.contains("CoderSession.activeAgent = Seven.Member.GIT_RELEASE"))
        // रिपो डॉक्टर को पक्का नतीजा चाहिए - वहाँ पहले जैसा (इंतज़ार करके)
        assertTrue(src("RepoDoctorRunner.kt").contains("CoderTeam.mergeAndRelease(c, a, p)"))
        assertTrue(MergeBg.note("main").contains("नया काम दे सकते हैं"))
    }
}
