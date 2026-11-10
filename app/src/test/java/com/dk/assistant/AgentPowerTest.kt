package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 (मालिक: "कोडिंग एजेंट को मेरे (Perplexity) जितना ताक़तवर बनाओ, DK भी वैसे ही test/build करके PR बनाए").
 * असली गलतियाँ: PR #252 (गायब resource + Lottie), PR #249 (test पास कराने को सीमा ढीली),
 * feature/dk-1110-0955 (ChatActivity में बनाए हुए नाम onPlus/isImage, CI 3 बार fail).
 */
class AgentPowerTest {
    // ---------------------------------------------------------------- CI: PR से पहले वही workflow जो unit tests चलाता है
    private val buildYml = """name: Build DK APK
on:
  push:
    branches: [ main ]
  pull_request:
  workflow_dispatch:
jobs:
  build:
    steps:
      - run: gradle assembleRelease
      - uses: reactivecircus/android-emulator-runner@v2
      - uses: softprops/action-gh-release@v2
"""
    private val checkYml = """name: PR build check
on:
  pull_request:
  workflow_dispatch:
jobs:
  check:
    steps:
      - run: gradle :app:testDebugUnitTest
      - run: gradle :app:assembleDebug
"""

    @Test fun dispatchable_prefersWorkflowWithUnitTests() {
        // पहले पहला मिला (build.yml, 13 मिनट, tests नहीं) चुना जाता था
        assertEquals("check.yml", Seven.dispatchable(listOf("build.yml" to buildYml, "check.yml" to checkYml)))
        assertEquals("check.yml", Seven.dispatchable(listOf("check.yml" to checkYml, "build.yml" to buildYml)))
        assertTrue(Seven.testScore(checkYml) >= 10)
        assertTrue(Seven.testScore(buildYml) < 10)
    }

    @Test fun dispatchable_onlyBuildWorkflow_stillChosen() {
        assertEquals("build.yml", Seven.dispatchable(listOf("build.yml" to buildYml)))
        assertNull(Seven.dispatchable(listOf("x.yml" to "on:\n  push:\n")))
    }

    @Test fun dispatchable_commentedTestLine_notCounted() {
        val fake = checkYml.replace("      - run: gradle :app:testDebugUnitTest", "#      - run: gradle :app:testDebugUnitTest")
        assertTrue(Seven.testScore(fake) < 10)
    }

    // ---------------------------------------------------------------- FixLoop: वही जगह बार-बार fail
    private val chat1 = "e: file:///home/runner/work/DK2.0/app/src/main/java/com/dk/assistant/ChatActivity.kt:412:18 Unresolved reference: onPlus"
    private val chat2 = "e: file:///home/runner/work/DK2.0/app/src/main/java/com/dk/assistant/ChatActivity.kt:418:30 Unresolved reference: isImage"
    private val tsv1 = "com.dk.assistant.UiTextTest > greetingTranslation FAILED\n    java.lang.AssertionError: duplicate key नमस्ते"
    private val tsv2 = "com.dk.assistant.UiTextTest > greetingTranslation FAILED\n    java.lang.AssertionError: duplicate key शुभ रात्रि"

    @Test fun sites_findFileAndTest() {
        assertTrue(FixLoop.sites(chat1).contains("file:ChatActivity.kt"))
        assertTrue(FixLoop.sites(tsv1).contains("test:UiTextTest.greetingTranslation"))
    }

    @Test fun sameSpot_differentWordsSamePlace() {
        assertTrue(FixLoop.similar(chat1, chat2))
        assertTrue(FixLoop.sameSpot(listOf(chat1, chat2)))
        assertTrue(FixLoop.sameSpot(listOf(tsv1, tsv2)))
        assertFalse(FixLoop.sameSpot(listOf(chat1, tsv1)))
        assertFalse(FixLoop.sameSpot(listOf(chat1)))
    }

    // ---------------------------------------------------------------- SafePr: पुराना test / सीमा ढीली
    private val testPath = "app/src/test/java/com/dk/assistant/FaceMatchTest.kt"
    private val oldTest = "class FaceMatchTest {\n    @Test fun still() {\n        assertFalse(FaceMatch.live(still))\n    }\n}\n"

    @Test fun oldAssertChanged_blocked_newAssertAllowed() {
        val changed = oldTest.replace("assertFalse(FaceMatch.live(still))", "assertTrue(FaceMatch.live(still))")
        assertEquals(1, SafePr.oldAssertsChanged(mapOf(testPath to changed), mapOf(testPath to oldTest), false).size)
        val added = oldTest.replace("    }\n}", "    }\n    @Test fun moving() { assertTrue(FaceMatch.live(moving)) }\n}")
        assertTrue(SafePr.oldAssertsChanged(mapOf(testPath to added), mapOf(testPath to oldTest), false).isEmpty())
        assertTrue(SafePr.oldAssertsChanged(mapOf(testPath to changed), mapOf(testPath to oldTest), true).isEmpty())
    }

    @Test fun goalposts_pr249_minVarianceLoosened() {
        val p = "app/src/main/java/com/dk/assistant/FaceMatch.kt"
        val failed = mapOf(p to "object FaceMatch {\n    private const val minVariance = 0.0001f\n}\n")
        val now = mapOf(p to "object FaceMatch {\n    private const val minVariance = 0.00001f\n}\n")
        val r = SafePr.goalposts(failed, now, "चेहरा पहचान ठीक करो")
        assertEquals(1, r.size)
        assertTrue(r[0], r[0].contains("minVariance"))
        // user ने खुद यही सीमा बदलने को कहा = छूट
        assertTrue(SafePr.goalposts(failed, now, "minVariance कम करो").isEmpty())
        assertEquals("0.0001f", SafePr.limits(failed.values.first()!!)["minVariance"])
    }

    @Test fun goalposts_normalFile_noTestTouched_allowed() {
        val p = "app/src/main/java/com/dk/assistant/Weather.kt"
        val failed = mapOf(p to "object Weather { val maxDays = 5 }")
        val now = mapOf(p to "object Weather { val maxDays = 7 }")
        assertTrue(SafePr.goalposts(failed, now, "मौसम ठीक करो").isEmpty())
        val withTest = now + ("app/src/test/java/WeatherTest.kt" to "b")
        assertEquals(1, SafePr.goalposts(failed + ("app/src/test/java/WeatherTest.kt" to "a"), withTest, "मौसम ठीक करो").size)
    }

    // ---------------------------------------------------------------- RefCheck: PR #252 जैसी गलतियाँ
    private val values = "app/src/main/res/values/strings.xml" to "<resources><string name=\"app_name\">DK</string></resources>"
    private val layoutPath = "app/src/main/res/layout/activity_chat.xml"
    private val oldLayout = "<LinearLayout xmlns:android=\"x\"><ImageView android:src=\"@drawable/ic_mic\"/></LinearLayout>"
    private val mic = "app/src/main/res/drawable/ic_mic.xml" to "<vector/>"

    @Test fun missingResources_pr252() {
        val now = oldLayout.replace("<ImageView", "<ImageView android:src=\"@drawable/ic_add\"/><com.airbnb.lottie.LottieAnimationView app:lottie_rawRes=\"@raw/energy_orb\"/><ImageView")
        val all = mapOf(values, mic, layoutPath to now)
        val r = RefCheck.missingResources(mapOf(layoutPath to now), { if (it == layoutPath) oldLayout else null }, all)
        assertEquals(1, r.size)
        assertTrue(r[0], r[0].contains("@drawable/ic_add") && r[0].contains("@raw/energy_orb"))
        assertFalse(r[0], r[0].contains("ic_mic"))
    }

    @Test fun missingResources_binaryPngAndLibraryPrefix_ok() {
        val now = oldLayout.replace("<ImageView", "<ImageView android:src=\"@drawable/wall_1\" android:background=\"@drawable/abc_btn\"/><ImageView")
        val all = mapOf(values, mic, layoutPath to now)
        val r = RefCheck.missingResources(mapOf(layoutPath to now), { oldLayout }, all, setOf("app/src/main/res/drawable-nodpi/wall_1.png"))
        assertTrue(r.toString(), r.isEmpty())
    }

    @Test fun missingResources_truncatedRepo_skipped() {
        val now = oldLayout.replace("@drawable/ic_mic", "@drawable/ic_gayab")
        val all = mapOf(values, mic, layoutPath to now)
        assertTrue(RefCheck.missingResources(mapOf(layoutPath to now), { oldLayout }, all, setOf(GitHub.TRUNCATED)).isEmpty())
    }

    @Test fun missingResources_kotlinR() {
        val p = "app/src/main/java/com/dk/assistant/ChatActivity.kt"
        val old = "package com.dk.assistant\nclass ChatActivity { fun a() = R.drawable.ic_mic }\n"
        val now = old.replace("fun a()", "fun b() = R.string.gayab_text\n    fun a()")
        val r = RefCheck.missingResources(mapOf(p to now), { old }, mapOf(values, mic, p to now))
        assertEquals(1, r.size)
        assertTrue(r[0].contains("@string/gayab_text"))
    }

    @Test fun missingViews_customViewWithoutClass() {
        val kt = "app/src/main/java/com/dk/assistant/Orb.kt" to "package com.dk.assistant\nclass OrbView\n"
        val now = oldLayout.replace("<ImageView", "<com.dk.assistant.EnergyOrbView android:id=\"@+id/orb\"/><com.dk.assistant.OrbView/><ImageView")
        val r = RefCheck.missingViews(mapOf(layoutPath to now), { oldLayout }, mapOf(kt, layoutPath to now))
        assertEquals(1, r.size)
        assertTrue(r[0].contains("EnergyOrbView"))
        assertFalse(r[0].contains("com.dk.assistant.OrbView,"))
    }

    @Test fun deadNewCode_unusedNewClass() {
        val newP = "app/src/main/java/com/dk/assistant/OrbHelper.kt"
        val c = "package com.dk.assistant\nclass OrbHelper { fun go() {} }\n"
        val all = mapOf(newP to c, "app/src/main/java/com/dk/assistant/Main.kt" to "class Main")
        assertEquals(1, RefCheck.deadNewCode(mapOf(newP to c), { null }, all).size)
        val used = all + ("app/src/main/java/com/dk/assistant/Main.kt" to "class Main { val h = OrbHelper() }")
        assertTrue(RefCheck.deadNewCode(mapOf(newP to c), { null }, used).isEmpty())
    }

    @Test fun unaskedDependency_lottieBlocked_askedAllowed() {
        val g = "app/build.gradle.kts"
        val old = "dependencies {\n    implementation(\"androidx.core:core-ktx:1.13.1\")\n}\n"
        val now = old.replace("}\n", "    implementation(\"com.airbnb.android:lottie:6.4.0\")\n}\n")
        assertEquals(1, RefCheck.unaskedDependencies(mapOf(g to now), { old }, "ऊर्जा वाला गोला बनाओ").size)
        assertTrue(RefCheck.unaskedDependencies(mapOf(g to now), { old }, "Lottie से गोला बनाओ").isEmpty())
        assertTrue(RefCheck.unaskedDependencies(mapOf(g to now), { old }, "कोई library जोड़कर गोला बनाओ").isEmpty())
    }

    // ---------------------------------------------------------------- SevenSymbols: बनाए हुए नाम (feature/dk-1110-0955)
    private val composer = "package com.dk.assistant\nclass Composer {\n    var onSend: (() -> Unit)? = null\n    fun clear() {}\n}\nenum class Kind { A }\n"
    private val prep = "package com.dk.assistant\nobject MediaPrep {\n    class Prepared(val bytes: ByteArray, val mime: String)\n    fun prepare(p: String): Prepared = Prepared(ByteArray(0), \"x\")\n}\n"
    private val chatOld = "package com.dk.assistant\nclass ChatActivity {\n    val composer = Composer()\n    fun start() {\n        composer.onSend = { }\n    }\n}\n"

    @Test fun symbols_inventedPropertyAssignment_caught() {
        val after = chatOld.replace("        composer.onSend = { }", "        composer.onSend = { }\n        composer.onPlus = { }")
        val repo = mapOf("a/Composer.kt" to composer, "a/ChatActivity.kt" to after)
        val m = SevenSymbols.check("a/ChatActivity.kt", chatOld, after, repo, whole = true)
        assertTrue(m.toString(), m.any { it.name.contains("onPlus") && it.sure })
        assertFalse(m.toString(), m.any { it.name.contains("onSend") })
    }

    @Test fun symbols_returnTypeInferred_propertyCaught() {
        val after = chatOld.replace("    fun start() {", "    fun pick(p: String) {\n        val prep = MediaPrep.prepare(p)\n        val ok = prep.isImage\n        val m = prep.mime\n    }\n    fun start() {")
        val repo = mapOf("a/Composer.kt" to composer, "a/MediaPrep.kt" to prep, "a/ChatActivity.kt" to after)
        val m = SevenSymbols.check("a/ChatActivity.kt", chatOld, after, repo, whole = true)
        assertTrue(m.toString(), m.any { it.name.contains("isImage") && it.sure })
        assertFalse(m.toString(), m.any { it.name.contains("mime") })
    }

    @Test fun symbols_realNamesAndBuiltins_pass() {
        val after = chatOld.replace("        composer.onSend = { }", "        composer.onSend = { }\n        composer.clear()\n        val n = Kind.A.name\n        val s = listOf(1).size")
        val repo = mapOf("a/Composer.kt" to composer, "a/ChatActivity.kt" to after)
        val m = SevenSymbols.check("a/ChatActivity.kt", chatOld, after, repo, whole = true)
        assertTrue(m.toString(), m.none { it.sure })
    }

    @Test fun symbols_dataClassCopy_andOutsideImport_noFalseAlarm() {
        val spec = "package com.dk.assistant\ndata class Spec(val a: Int)\nclass Request(val text: String)\n"
        val old = "package com.dk.assistant\nimport okhttp3.Request\nclass Net {\n    fun go(s: Spec) {\n    }\n}\n"
        val after = old.replace("    fun go(s: Spec) {\n", "    fun go(s: Spec) {\n        val t = s.copy(a = 2)\n        val r = Request.Builder().url(\"x\").build()\n")
        val m = SevenSymbols.check("a/Net.kt", old, after, mapOf("a/Spec.kt" to spec, "a/Net.kt" to after), whole = true)
        assertTrue(m.toString(), m.none { it.sure })
    }

    // ---------------------------------------------------------------- बड़ा काम: चरणों में
    @Test fun parsePlan_moreAndOverflowSteps() {
        val steps = (1..12).joinToString(",") { "{\"file\":\"a/F$it.kt\",\"action\":\"edit\",\"change\":\"step $it\"}" }
        val p = Seven.parsePlan("{\"steps\":[$steps],\"more\":\"settings screen बाकी\"}")!!
        assertEquals(Seven.MAX_STEPS, p.steps.size)
        assertTrue(p.more, p.more.startsWith("settings screen बाकी"))
        assertTrue(p.more, p.more.contains("a/F11.kt") && p.more.contains("a/F12.kt"))
        val small = Seven.parsePlan("{\"steps\":[{\"file\":\"a.kt\",\"change\":\"x\"}]}")!!
        assertEquals("", small.more)
    }

    // ---------------------------------------------------------------- कोडर खुद ढूँढे/पढ़े
    @Test fun coderLookup_detected_onlyWithoutEdit() {
        assertTrue(Seven.isLookup("{\"search\":[\"onPlus\",\"Composer\"]}"))
        assertTrue(Seven.isLookup("{\"read\":[\"app/src/main/java/com/dk/assistant/Composer.kt\"]}"))
        assertFalse(Seven.isLookup("FILE: a.kt\n<<<FIND\nx\nFIND>>>\n<<<REPLACE\ny\nREPLACE>>>"))
        assertFalse(Seven.isLookup("FILE: a.kt\n<<<FILE\n{\"read\":[\"x\"]}\nFILE>>>"))
        assertFalse(Seven.isLookup("कुछ नहीं"))
    }

    // ---------------------------------------------------------------- मज़बूत मॉडल का दायरा
    private fun agentOf(m: Seven.Member) = Seven.Agent.values().first { it.member == m }

    @Test fun strongScope_gatesDefault_coreOld_all() {
        assertEquals(StrongAi.SCOPE_GATES, StrongAi.scope(null))
        assertEquals(StrongAi.SCOPE_GATES, StrongAi.scope("galat"))
        val rev = agentOf(Seven.Member.REVIEWER); val qa = agentOf(Seven.Member.TESTER); val mgr = agentOf(Seven.Member.MANAGER)
        val coder = agentOf(Seven.Member.CODER); val git = agentOf(Seven.Member.GIT_RELEASE)
        for (a in listOf(rev, qa, mgr, coder)) assertEquals(a.name, "strong", TeamModels.pick(a, "fast", "light", "strong", StrongAi.SCOPE_GATES))
        assertEquals("light", TeamModels.pick(git, "fast", "light", "strong", StrongAi.SCOPE_GATES))
        // core = पुराना बर्ताव
        assertEquals("fast", TeamModels.pick(rev, "fast", "light", "strong", StrongAi.SCOPE_CORE))
        assertEquals("strong", TeamModels.pick(coder, "fast", "light", "strong", StrongAi.SCOPE_CORE))
        assertEquals("strong", TeamModels.pick(git, "fast", "light", "strong", StrongAi.SCOPE_ALL))
        // key नहीं = पहले जैसा
        assertEquals("fast", TeamModels.pick<String>(rev, "fast", "light", null, StrongAi.SCOPE_ALL))
    }

    @Test fun strongChoices_newestModels() {
        assertEquals("gpt-6-astra", StrongAi.choice("openai").model)
        assertTrue(StrongAi.choice("anthropic").label.contains("claude-fable-5-2"))
    }
}
