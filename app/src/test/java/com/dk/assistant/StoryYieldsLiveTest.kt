package com.dk.assistant

import com.dk.assistant.voice.st3.St3Load
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 20 नवंबर 2026 (मालिक का 16:20 लॉग): कहानी की आवाज़ (फ़ोन पर, 15 लाइन, 10 कदम) बातचीत के बीच उसी मॉडल पर बनी =
 * DK के जवाब में 5-8 s चुप्पी, पहली आवाज़ 8.9 s. अब DK बोल रहा हो तो कहानी की अगली लाइन रुकती है.
 */
class StoryYieldsLiveTest {
    @Before fun clean() { St3Load.reset() }
    @After fun after() { St3Load.reset() }

    private fun src(p: String): String {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        return java.io.File(root, p).readText()
    }

    @Test fun storyWaitsWhileDkSpeaks() {
        val now = 1_000_000L
        St3Load.mark(now + 5_000)
        assertTrue(St3Load.shouldDefer(now, 0, StoryVoice.STORY_DEFER_MS))                          // DK बोल रहा = रुको
        assertFalse(St3Load.shouldDefer(now, StoryVoice.STORY_DEFER_MS, StoryVoice.STORY_DEFER_MS))  // हद पूरी = आगे
        assertFalse(St3Load.shouldDefer(now + 6_000, 0, StoryVoice.STORY_DEFER_MS))                 // चुप = कहानी चले
    }

    @Test fun wired() {
        val sv = src("com/dk/assistant/StoryVoice.kt")
        val loop = sv.substring(sv.indexOf("private suspend fun renderOffline"))
        val wait = loop.indexOf("St3Load.shouldDefer(")
        assertTrue(wait > 0 && wait < loop.indexOf("St3Engine.renderLine("))
    }
}
