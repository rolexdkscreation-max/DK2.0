package com.dk.assistant.insights

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** सोशल रिव्यू: आवाज़ / DK लाइव / सेटिंग्स से सच में जुड़ा है (source-check) */
class InsightsWiringTest {
    private fun src(n: String): String {
        val p = "src/main/java/com/dk/assistant/$n"
        return listOf(File(p), File("app/$p")).first { it.exists() }.readText()
    }

    @Test fun agentRunner_startsReview_beforeTaskSense() {
        val s = src("AgentRunner.kt")
        val i = s.indexOf("InsightsVoice.parse(goal)")
        assertTrue(i > 0)
        assertTrue(s.contains("InsightsAgent.start(svc, req"))
        val t = s.indexOf("TaskSense", i)
        assertTrue("TaskSense से पहले", t < 0 || t > i)
    }

    @Test fun localRouter_doesNotJustOpenTheApp() {
        val s = src("LocalRouter.kt")
        val split = s.indexOf("fun split(")
        val hook = s.indexOf("InsightsVoice.parse(raw) != null) return Split(null, raw)")
        assertTrue(split in 1 until hook)
    }

    @Test fun liveTool_declared_handled_listed() {
        assertTrue(src("GeminiLiveSetup.kt").contains("\"name\", \"social_insights\""))
        assertTrue(src("GeminiLiveTools.kt").contains("\"social_insights\" -> com.dk.assistant.insights.InsightsAgent.liveTool(svc, args)"))
        assertTrue(src("LeakedCall.kt").contains("\"social_insights\""))
        assertTrue(src("LiveConnect.kt").contains("social_insights"))
        assertTrue(src("GeminiLive.kt").contains("InsightsAgent.liveNote("))
    }

    @Test fun settings_section_present() {
        val a = src("SettingsActivity.kt")
        assertTrue(a.contains("\"social\" -> { title.text = \"सोशल रिव्यू\""))
        assertTrue(a.contains("Triple(\"social\", \"सोशल रिव्यू\""))
        assertTrue(com.dk.assistant.SettingsSearch.search("रील").any { it.section == "social" })
        assertTrue(com.dk.assistant.SettingsSearch.search("यूट्यूब स्टूडियो").any { it.section == "social" })
    }

    @Test fun reviewIsReadOnly_promptSaysSo() {
        val s = src("insights/ScreenCollector.kt")
        assertTrue(s.contains("You only READ - never post, delete, edit, like, follow, buy, boost"))
    }

    @Test fun youtube_hasBothApiAndScreen() {
        val c = src("connect/ConnectorLogic.kt")
        assertTrue(c.contains("yt-analytics.readonly"))
        assertTrue(src("connect/ConnectorRunner.kt").contains("fun youtubeInsights"))
        assertTrue(src("insights/InsightsCore.kt").contains("com.google.android.apps.youtube.creator"))
    }
}
