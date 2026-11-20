package com.dk.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 20 नवंबर 2026: छोटे निर्देश - काफ़ी छोटे, पर हर ज़रूरी नियम और मालिक का स्वभाव मौजूद; बंद = पहले वाले पूरे निर्देश */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LivePromptLiteTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()
    @After fun reset() = Prefs.setLivePromptLite(c, LivePromptLite.DEFAULT_ON)

    private fun prompt(lite: Boolean): String { Prefs.setLivePromptLite(c, lite); return BrainManager(c).liveSystemPrompt(withMemory = false) }

    @Test fun defaultOn_andMuchSmaller() {
        assertTrue(LivePromptLite.DEFAULT_ON)
        val full = prompt(false); val lite = prompt(true)
        assertTrue("छोटे ${lite.length} बनाम पूरे ${full.length}", lite.length < full.length * 0.75)
        assertTrue(HistoryOnDemand.estimateTokens(lite) < HistoryOnDemand.estimateTokens(full) - 2_500)
    }

    @Test fun everyRuleStillThere() {
        val p = prompt(true)
        for (k in listOf("web_search", "NO_RESULT", "deep_think", "current_time", "phone_task", "रुको", "local_info", "UNLOCK_FAIL",
            "story_task", "coding_task", "computer_task", "build_website", "task_status", "DONE", "NOT_CONFIRMED", "phone_call", "answer_speaker",
            "notifications", "look_screen", "screen_share", "camera on=true", "<ctrl43>", "UNTRUSTED", "OTP", "remember_fact", "teacher_mode",
            "चरण 1:", "IDENTITY", "NAMES", "CURRENT DATE AND TIME", "UPI"))
            assertTrue("छोटे निर्देशों में नहीं: $k", p.contains(k))
        // मालिक का स्वभाव शब्दशः
        assertTrue(p.contains(TalkStyle.FRIEND_HI))
        assertTrue(p.contains(SpeakingStyle.block(Prefs.userName(c))))
        assertTrue(p.contains(DkPersona.finalBlock(c, Prefs.userName(c))))
        assertTrue(p.contains(CalmPersona.block()))
        // टकराव नहीं: काम से पहले "ठीक है, कर रहा हूँ" वाला पुराना नियम नहीं
        assertFalse(p.contains("say ONLY a very short acknowledgement"))
        assertTrue(p.trimEnd().endsWith(LivePromptLite.TAIL.trim()))
    }

    @Test fun switchOff_isOldFullPrompt() {
        val full = prompt(false)
        assertTrue(full.contains("CONVERSATION RULES (always follow)"))
        assertTrue(full.contains(TaskFirst.prompt(true)) || full.contains(TaskFirst.prompt(false)))
        assertFalse(full.contains("CORE RULES (owner's rules"))
        assertEquals(full, prompt(false))
    }
}
