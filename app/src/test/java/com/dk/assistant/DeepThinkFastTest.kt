package com.dk.assistant

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 20 नवंबर 2026 (मालिक: Live में जवाब 11-13 s): deep_think तेज़ - Groq पहले, Z.ai 3 s, तीनों एक साथ, आम बात पर नहीं */
class DeepThinkFastTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun order_groqFirst() {
        assertEquals(listOf("groq", "zai", "nvidia"), LiveTextBrain.order { "k" })
        assertEquals(listOf("zai"), LiveTextBrain.order { if (it == "zai") "k" else "" })
        assertEquals("openai/gpt-oss-120b", Providers.byId("groq")!!.defaultModel)
    }

    @Test fun zai_threeSeconds() {
        assertEquals(3_000L, LiveTextBrain.stepFor("zai", 8_000))
        assertEquals(2_000L, LiveTextBrain.stepFor("zai", 2_000))
        assertEquals(8_000L, LiveTextBrain.stepFor("groq", 8_000))
        assertEquals(8_000L, LiveTextBrain.stepFor("nvidia", 8_000))
    }

    @Test fun firstOf_fastestWins() = runBlocking {
        val t0 = System.currentTimeMillis()
        val r = ThinkTime.firstOf(listOf(3_000L, 3_000L, 3_000L)) { i ->
            when (i) { 0 -> { Thread.sleep(2_000); "slow" }; 1 -> { delay(50); "fast" }; else -> null }
        }
        assertEquals(1 to "fast", r)
        assertTrue(System.currentTimeMillis() - t0 < 1_500)
        // सब फेल / खाली = null; समय पूरा = null
        assertNull(ThinkTime.firstOf(listOf(500L, 500L)) { i -> if (i == 0) null else throw IllegalStateException("x") })
        assertNull(ThinkTime.firstOf(listOf(100L)) { Thread.sleep(1_000); "late" })
        assertNull(ThinkTime.firstOf(emptyList<Long>()) { "x" })
    }

    @Test fun casualTalk_noDeepThink() {
        for (q in listOf("कैसे हो", "क्या हालचाल है भाई", "मैं आज बहुत उदास हूँ", "मुझे अकेला लग रहा है", "hello", "धन्यवाद", "अच्छा ठीक है"))
            assertTrue(q, LiveTextBrain.casual(q))
        for (q in listOf("245 * 37 कितना होगा", "iPhone और Samsung में क्या अंतर है, तुलना करके बताओ",
                "सूरज पूर्व से क्यों उगता है विस्तार से समझाओ", "मेरे लिए 3 महीने का पढ़ाई का प्लान बनाओ", "write code for binary search in kotlin"))
            assertFalse(q, LiveTextBrain.casual(q))
        val t = src("GeminiLiveTools.kt")
        assertTrue(t.contains("\"deep_think\" -> if (LiveTextBrain.casual(args.optString(\"question\"))) LiveTextBrain.CASUAL_NOTE"))
        assertTrue(src("GeminiLiveSetup.kt").contains("NEVER for normal chat, greetings, हालचाल"))
    }

    @Test fun dkBrief_sentWithQuestion() {
        assertTrue(LiveTextBrain.SYSTEM.contains(LiveTextBrain.DK_BRIEF))
        val lines = LiveTextBrain.DK_BRIEF.lines().filter { it.matches(Regex("^\\d\\..*")) }
        assertTrue(lines.size in 5..6)
        assertTrue(LiveTextBrain.DK_BRIEF.contains("never say you do not know"))
    }
}
