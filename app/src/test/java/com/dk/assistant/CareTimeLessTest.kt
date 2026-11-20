package com.dk.assistant

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** 20 नवंबर 2026 (मालिक: "डिफ़ॉल्ट DK बहुत ज़्यादा टाइम बताता है"): समय पता रहे, पर बिना पूछे बार-बार न बोले */
class CareTimeLessTest {
    @Test fun timeOnlyWhenAsked() {
        val b = CareTime.block(1_763_660_000_000L, TimeZone.getTimeZone("Asia/Kolkata"))
        assertTrue(b.contains("do NOT say the time, the date or how late it is unless the user asks"))
        assertTrue(b.contains("Only now and then"))
        assertTrue(b.contains("Most replies need NO time talk at all"))
        assertTrue(b.contains("at most once in a conversation"))
        // पुराने नियम वही
        assertTrue(b.contains("at most ONE caring touch") && b.contains("never say good morning at night"))
        assertTrue(b.contains("Time questions: always use the CURRENT DATE AND TIME"))
    }
}
