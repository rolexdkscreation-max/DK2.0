package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): ✕ दबाने तक जुड़ा रहे - resumption, goAway, बीच का text बंद, backoff, फ़र्क ✕ और कटने में */
class LiveStayTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name")).first { it.exists() }.readText()

    @Test fun backoff_1_2_4_8_15_withJitter_neverGivesUp() {
        assertEquals(250L, LiveStay.backoffMs(0, 0.0))
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L), (1..7).map { LiveStay.backoffMs(it, 0.0) })
        assertEquals(1_200L, LiveStay.backoffMs(1, 1.0))                    // +20% random तक
        assertTrue((1..100).all { LiveStay.backoffMs(it, 1.0) <= 18_000L })
        val g = LiveSrc.all()
        assertFalse(g.contains("Gemini Live बार-बार टूट रहा है - नेटवर्क देखें"))
        assertTrue(g.contains("नेटवर्क कमज़ोर है - पीछे से जोड़ रहा हूँ"))
    }

    @Test fun noReplyLimitAtLeast6s() {
        assertTrue(LiveStay.NO_REPLY_MS >= 6_000L)
        assertTrue(LiveSrc.all().contains("ReplyWatchdog(timeoutMs = LiveStay.NO_REPLY_MS"))
    }

    @Test fun midTalkText_offByDefault_andOnlyWhenQuiet() {
        val g = LiveSrc.all()
        assertTrue(g.contains("textRescue = LiveStay.textRescueOn(svc)"))
        assertTrue(src("LiveStay.kt").contains("Prefs.bool(c, PREF_TEXT_RESCUE, false)"))
        // चारों बचाव-text flag के पीछे
        assertTrue(g.contains("caption text नहीं भेजा (बचाव-text बंद)"))
        assertTrue(g.contains("वही बात text में दोबारा नहीं भेजी (बचाव-text बंद)"))
        assertTrue(g.contains("टूटने से पहले की बात text में दोबारा नहीं भेजी (बचाव-text बंद)"))
        assertTrue(g.contains("बीच में पकड़ी बात (caption) text में नहीं भेजी"))
        // ज़रूरी SYSTEM NOTE सिर्फ़ चुप्पी में
        assertTrue(g.contains("internal suspend fun waitQuiet(maxMs: Long): Boolean"))
        assertFalse(g.contains("withTimeoutOrNull(15_000) { while (isPlaying() || generating || toolJobs.isNotEmpty()) delay(150) }"))
    }

    @Test fun shortLivedAfterText_noResend() {
        assertTrue(LiveStay.skipResend(1_500, 800))
        assertFalse(LiveStay.skipResend(5_000, 800))
        assertFalse(LiveStay.skipResend(1_500, 5_000))
        assertFalse(LiveStay.skipResend(1_500, -1))
        val t = SentTrace(clock = { 10_000L })
        assertEquals(-1L, t.lastTextAgo())
    }

    @Test fun goAway_prepareBeforeBreak_withHandle() {
        assertEquals(10_000L, LiveStay.timeLeftMs("10s"))
        assertEquals(9_500L, LiveStay.timeLeftMs("9.5s"))
        assertEquals(-1L, LiveStay.timeLeftMs(""))
        assertTrue(LiveStay.useParked(null, "h2", true))
        assertTrue(LiveStay.useParked("h1", "h1", true))
        assertFalse(LiveStay.useParked("h1", "h2", true))      // तैयार होने के बाद नई बात = ताज़ा handle से
        assertTrue(LiveStay.useParked("h1", "h2", false))      // पुराना मर गया = तैयार वाला तुरंत
        val g = LiveSrc.all()
        assertTrue(g.contains("o.optJSONObject(\"goAway\")?.let { onGoAway(it.optString(\"timeLeft\")) }"))
        assertTrue(g.contains("startRace(curKeys, listOf(m) + curModels.filter { it != m }, live = false, resumeId = LiveRace.Combo(m, k).id)"))
        // resumption + compression बने रहें
        assertTrue(g.contains(".put(\"sessionResumption\", if (resume && handle != null) JSONObject().put(\"handle\", handle) else JSONObject())"))
        assertTrue(g.contains(".put(\"contextWindowCompression\", JSONObject().put(\"slidingWindow\", JSONObject()))"))
        assertTrue(g.contains("if (it.optBoolean(\"resumable\") && it.optString(\"newHandle\").isNotBlank()) handle = it.optString(\"newHandle\")"))
        // handle वाली जोड़ी दौड़ में सबसे पहले
        assertTrue(g.contains("queue.add(0, queue.removeAt(it))"))
    }

    @Test fun userCloseIs1000() {
        assertTrue(LiveSrc.all().contains("w.close(1000, \"bye\")"))
    }

    @Test fun voiceTuneResetTo100Once() {
        assertTrue(src("voice/engine/VoiceTune.kt").contains("tune_reset_nov26"))
    }
}
