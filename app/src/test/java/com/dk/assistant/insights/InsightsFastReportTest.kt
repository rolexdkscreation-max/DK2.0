package com.dk.assistant.insights

import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक: "अभी भी रिव्यू में बहुत टाइम - क्यों? तुरंत करे"): रिपोर्ट की सीमा, छोटे जवाब, समय का सबूत */
class InsightsFastReportTest {
    private fun src(n: String): String {
        val p = "src/main/java/com/dk/assistant/$n"
        return listOf(File(p), File("app/$p")).first { it.exists() }.readText()
    }

    @Test fun reportIsBounded_andFallsBackToOwnReport() {
        assertTrue(InsightsSpeed.REPORT_WAIT_MS <= 40_000)
        assertTrue(InsightsSpeed.REPORT_TOKENS < 8192)
        assertTrue(InsightsSpeed.STEP_TOKENS < 2048)
        assertTrue(InsightsSpeed.TREND_WAIT_MS <= 10_000)
        val a = src("insights/InsightsAgent.kt")
        assertTrue(a.contains("withTimeoutOrNull(InsightsSpeed.REPORT_WAIT_MS) { job.await() }"))
        assertTrue(a.contains("if (!json && reportCut) return null"))
        assertTrue(a.contains("if (report.isBlank()) report = InsightsReport.fallback(all, analyses, trends)"))
        assertFalse(a.contains("maxTokens = 8192"))
    }

    @Test fun reportTriesFastModelFirst() {
        val m = InsightsSpeed.reportModels(listOf("lite", "x"), listOf("big", "x"))
        assertEquals(listOf("lite", "x", "big"), m)
    }

    @Test fun timingFooter_isShown() {
        assertEquals("समय: इंस्टाग्राम 48 सेकंड • रिपोर्ट 12 सेकंड • कुल 61 सेकंड",
            InsightsSpeed.timing(listOf("इंस्टाग्राम" to 47_600L, "रिपोर्ट" to 12_100L), 61_000))
        val a = src("insights/InsightsAgent.kt")
        assertTrue(a.contains("report += \"\\n\\n_\${timing}_\""))
    }

    @Test fun stepReply_noEmptyFieldsTemplate() {
        val obs = ScreenObservation(1, "com.instagram.android", (1..150).map { UiElement(it, text = "Item $it", bounds = Box(0, 10 * it, 1080, 10 * it + 9)) })
        val p = InsightsPlan.prompt(Platform.INSTAGRAM, false, obs, PlatformData(Platform.INSTAGRAM), emptySet(), emptyList(), 1, InsightsSpeed.MAX_STEPS, 3, 6, 0)
        assertFalse(p.contains("\"views\":\"\""))
        assertTrue(p.contains("OMIT every key that is not visible"))
        assertTrue(p.contains("comment_list"))
        assertTrue(p.contains("[100] Item 100") && !p.contains("[101] Item 101"))
    }
}
