package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 नवंबर 2026 (मालिक): FRIDAY / VENOM के साथ नया "हमदर्द" मोड - दिल से जुड़ना, कोमल आवाज़, मनाना, अगला सवाल खुद,
 * देर रात / लंबी बात / खाने के समय एक बार सलाह (बार-बार समय नहीं), याद चुपचाप.
 */
class HamdardModeTest {
    private fun src(n: String): String {
        val f = listOf("src/main/java/com/dk/assistant/$n", "app/src/main/java/com/dk/assistant/$n").map { java.io.File(it) }.first { it.exists() }
        return f.readText()
    }

    @Test fun voiceCommandsAndOldModesSame() {
        assertEquals(AssistantPersona.Cmd.On(AssistantPersona.A.HAMDARD), AssistantPersona.parse("हमदर्द मोड ऑन करो"))
        assertEquals(AssistantPersona.Cmd.On(AssistantPersona.A.HAMDARD), AssistantPersona.parse("DK हमदर्दी मोड चालू करो"))
        assertEquals(AssistantPersona.Cmd.On(AssistantPersona.A.HAMDARD), AssistantPersona.parse("hamdard mode on"))
        assertEquals(AssistantPersona.Cmd.Off, AssistantPersona.parse("हमदर्द मोड बंद करो"))
        assertNull(AssistantPersona.parse("हमदर्द मोड क्या है"))
        // पुराने वैसे ही
        assertEquals(AssistantPersona.Cmd.On(AssistantPersona.A.FRIDAY), AssistantPersona.parse("फ्राइडे मोड ऑन करो"))
        assertEquals(AssistantPersona.Cmd.On(AssistantPersona.A.VENOM), AssistantPersona.parse("venom mode on"))
        assertFalse(AssistantPersona.A.HAMDARD.male)
        assertEquals(AssistantPersona.A.HAMDARD, AssistantPersona.of("hamdard"))
        assertTrue(AssistantPersona.quick(AssistantPersona.Cmd.On(AssistantPersona.A.HAMDARD)).ack.contains("हमदर्द मोड चालू"))
        assertEquals("hamdard", DkSettings.find("हमदर्द मोड")?.id)
    }

    @Test fun promptHasOwnersWordsAndRules() {
        val b = AssistantPersona.blockFor(AssistantPersona.A.HAMDARD, "राहुल")
        assertTrue(b.contains("HAMDARD") || b.contains("हमदर्द"))
        assertTrue(b.contains("Your name stays DK"))
        assertTrue(b.contains(AssistantPersona.HAMDARD_OWNER))                       // मालिक के शब्द शब्दशः
        assertTrue(AssistantPersona.HAMDARD_OWNER.contains("हल्की मुस्कान या कोमलता") && AssistantPersona.HAMDARD_OWNER.contains("भावनात्मक मोड़ पर हल्की धीमी-तीव्रता"))
        for (k in listOf("मनाना", "ONE gentle follow-up", "DK CARE CLOCK", "remember_fact", "NEVER say", "Tele-MANAS 14416", "जी राहुल"))
            assertTrue(k, b.contains(k))
        assertTrue(AssistantPersona.HAMDARD_STYLE.contains("smile") && AssistantPersona.HAMDARD_STYLE.contains("stress on the key words"))
        // FRIDAY / VENOM के block वैसे ही
        assertTrue(AssistantPersona.blockFor(AssistantPersona.A.VENOM, "बॉस").contains("your name is VENOM"))
    }

    @Test fun careClockOnlyWhenReallyLong() {
        val none = emptySet<String>()
        // दिन: 1 घंटा, डेढ़ घंटा = कुछ नहीं (मालिक: "ये नहीं कि एक घंटे, दो घंटे")
        assertNull(HamdardClock.due(60, 16, none)); assertNull(HamdardClock.due(110, 17, none))
        assertEquals("long0", HamdardClock.due(125, 17, none))
        assertNull(HamdardClock.due(150, 17, setOf("long0")))                         // बार-बार नहीं
        assertEquals("long1", HamdardClock.due(185, 18, setOf("long0")))
        // देर रात
        assertNull(HamdardClock.due(30, 23, none))
        assertEquals("late0", HamdardClock.due(50, 23, none)); assertEquals("late0", HamdardClock.due(46, 1, none))
        assertNull(HamdardClock.due(90, 0, setOf("late0")))
        assertEquals("late1", HamdardClock.due(106, 0, setOf("late0")))
        // खाना
        assertEquals("lunch", HamdardClock.due(45, 13, none)); assertNull(HamdardClock.due(45, 13, setOf("lunch")))
        assertEquals("dinner", HamdardClock.due(41, 20, none)); assertNull(HamdardClock.due(20, 20, none))
        assertNull(HamdardClock.due(300, 10, setOf("long0", "long1", "long2", "long3")))
        assertEquals("long3", HamdardClock.due(300, 10, setOf("long0", "long1", "long2")))
    }

    @Test fun spanAndNote() {
        assertEquals("45 मिनट", HamdardClock.spanHi(45))
        assertEquals("करीब 1 घंटा", HamdardClock.spanHi(62))
        assertEquals("करीब डेढ़ घंटा", HamdardClock.spanHi(90))
        assertEquals("करीब 2 घंटे", HamdardClock.spanHi(118))
        assertEquals("करीब ढाई घंटे", HamdardClock.spanHi(150))
        assertEquals("करीब साढ़े 3 घंटे", HamdardClock.spanHi(210))
        val n = HamdardClock.note("late0", 50, "रात 11:50 बजे")
        assertTrue(n.startsWith("SYSTEM NOTE (DK CARE CLOCK") && n.contains("50 मिनट") && n.contains("sleep") && n.contains("only this once"))
        assertTrue(HamdardClock.note("lunch", 45, "दोपहर 1:20 बजे").contains("lunch"))
    }

    @Test fun sessionResetsAfterLongSilence() {
        val t0 = 1_000_000_000L
        for (m in 0..30 step 10) HamdardClock.touch(t0 + m * 60_000L)                  // हर 10 मिनट बात = एक ही बातचीत
        assertEquals(30, HamdardClock.minutes(t0 + 30 * 60_000L))
        HamdardClock.touch(t0 + 60 * 60_000L)                                           // 30 मिनट चुप्पी = नई बातचीत
        assertEquals(0, HamdardClock.minutes(t0 + 60 * 60_000L))
    }

    @Test fun wiredIntoLiveAndSettings() {
        val care = src("GeminiLiveCare.kt")
        assertTrue(care.contains("AssistantPersona.active(svc) != AssistantPersona.A.HAMDARD"))
        assertTrue(care.contains("waitQuiet(60_000)") && care.contains("HamdardClock.mark(key)"))
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("val care = launch(Dispatchers.IO) { careLoop() }") && live.contains("care.cancel()"))
        assertTrue(src("AssistantPersona.kt").contains("A.HAMDARD -> com.dk.assistant.avatar.MoodState.voiceStyle(HAMDARD_STYLE)"))
        assertTrue(src("DkSettingsApply.kt").contains("\"friday\", \"venom\", \"hamdard\" ->"))
        assertTrue(src("SettingsVoice.kt").contains("hint(box, \"हमदर्द: DK आपसे दिल से जुड़ती है"))
    }
}
