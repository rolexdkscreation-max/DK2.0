package com.dk.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 25 नवंबर 2026 (मालिक: "भारी चीज़ ऐप के अंदर मत लगाओ, internet से डाउनलोड वाले लगाओ - नियम साफ़ लिख दो"):
 * जानी-मानी भारी libraries APK में न आएँ, और नियम AGENTS.md + ऐप के कोडिंग एजेंटों में लिखा रहे.
 */
class ApkSizeRuleTest {
    private fun root(p: String) = listOf(File("../$p"), File(p)).first { it.exists() }.readText()
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    private val heavy = listOf(
        "com.google.mlkit", "org.tensorflow", "com.google.mediapipe", "org.opencv", "opencv",
        "ffmpeg", "sherpa", "k2-fsa", "vosk", "org.pytorch", "tflite",
    )

    @Test fun noHeavyLibraryInGradle() {
        val deps = root("app/build.gradle.kts").lines().map { it.trim() }
            .filter { !it.startsWith("//") && Regex("""^(implementation|api|runtimeOnly)\(""").containsMatchIn(it) }
        for (d in deps) for (h in heavy) assertTrue("भारी library APK में: $d (AGENTS.md §8 - internet से download करो)", !d.contains(h, true))
        val onnx = deps.filter { it.contains("onnxruntime") }
        assertTrue("onnxruntime सिर्फ़ एक (Supertonic वाला)", onnx.size <= 1)
    }

    @Test fun ruleWrittenForEveryAgent() {
        val a = root("AGENTS.md")
        assertTrue(a.contains("APK का साइज़") && a.contains("internet से download"))
        assertTrue(src("CodingAgentPrompt.kt").contains("APK SIZE RULE"))
        assertTrue(src("SevenCore.kt").contains("APK SIZE RULE"))
    }

    @Test fun uiTranslateHasNoSdk() {
        val s = src("UiMt.kt")
        assertTrue(!s.contains("com.google.mlkit") && s.contains("translate.googleapis.com"))
    }

    @Test fun webTrHelpers() {
        assertEquals("Hello\nBye", WebTr.parse("""[[["Hello\n","नमस्ते\n",null],["Bye","अलविदा",null]],null,"hi"]"""))
        assertEquals("", WebTr.parse("not json"))
        assertEquals(listOf("A", "B"), WebTr.split("A\n B ", 2))
        assertNull(WebTr.split("A B", 2))
        assertEquals("zh-CN", WebTr.code("zh")); assertEquals("fr", WebTr.code("fr"))
        val parts = WebTr.parts(List(40) { "यह एक लंबी हिंदी लाइन है" })
        assertTrue(parts.size > 1 && parts.flatten() == (0 until 40).toList())
    }
}
