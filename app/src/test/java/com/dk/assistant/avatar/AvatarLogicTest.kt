package com.dk.assistant.avatar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.71: भाव-टैग और भाव की आवाज़ (लड़की अवतार के चित्र वाले tests अवतार के साथ हटे) */
class AvatarLogicTest {
    @After fun clean() = MoodState.reset()

    @Test fun tagSplit() {
        assertEquals(Mood.HAPPY to "अच्छा, सुनो।", EmotionTag.split("[खुश] अच्छा, सुनो।"))
        assertEquals(Mood.SAD, EmotionTag.split("[उदास] ओह।").first)
        assertEquals(Mood.EMOTIONAL, EmotionTag.split(" [भावुक] हाँ।").first)
        assertEquals(Mood.ANGRY, EmotionTag.split("[गुस्सा]नहीं।").first)
        assertEquals(null to "अच्छा, सुनो।", EmotionTag.split("अच्छा, सुनो।"))
        // अनजान ब्रैकेट = text जैसा का तैसा
        assertEquals(null to "[नोट] यह", EmotionTag.split("[नोट] यह"))
    }

    @Test fun filterTagAcrossChunks() {
        var got: Mood? = null
        val f = EmotionTag.Filter { got = it }
        assertEquals("", f.push("[ख"))
        assertEquals("", f.push("ुश"))
        assertEquals("अच्छा, ", f.push("] अच्छा, "))
        assertEquals(Mood.HAPPY, got)
        assertEquals("सुनो।", f.push("सुनो।"))
        assertEquals("", f.finish())
    }

    @Test fun filterNoTagPassesImmediately() {
        var got: Mood? = null
        val f = EmotionTag.Filter { got = it }
        assertEquals("अच्छा", f.push("अच्छा"))
        assertEquals(Mood.NORMAL, got)
        assertNull(f.mood)
    }

    @Test fun filterUnclosedBracketReleasedAtLimitOrFinish() {
        val f = EmotionTag.Filter()
        assertEquals("", f.push("[यह"))
        assertEquals("[यह", f.finish())
        val g = EmotionTag.Filter()
        assertEquals("[" + "क".repeat(30), g.push("[" + "क".repeat(30)))
    }

    @Test fun filterStripsRepeatedTagLater() {
        val f = EmotionTag.Filter()
        f.push("[उदास] ओह।")
        assertEquals("ठीक है।", f.push(" [उदास] ठीक है।").trim())
        assertEquals("सामान्य बात (जैसे यह)", f.push("सामान्य बात (जैसे यह)"))
    }

    @Test fun moodReturnsToNormalAfterHold() {
        MoodState.set(Mood.ANGRY)
        assertEquals(Mood.ANGRY, MoodState.current(1000))
        MoodState.replyDone(1000)
        assertEquals(Mood.ANGRY, MoodState.current(1000 + MoodState.HOLD_MS - 1))
        assertEquals(Mood.NORMAL, MoodState.current(1000 + MoodState.HOLD_MS + 1))
    }

    @Test fun speakingDelaysReturn() {
        MoodState.set(Mood.SAD)
        MoodState.replyDone(1000)
        MoodState.stillSpeaking(9000)
        assertEquals(Mood.SAD, MoodState.current(9000 + MoodState.HOLD_MS - 1))
    }

    @Test fun voiceStyleAddsMood() {
        assertEquals("मीठी आवाज़", MoodState.voiceStyle("मीठी आवाज़"))
        MoodState.set(Mood.SAD)
        assertTrue(MoodState.voiceStyle("मीठी आवाज़").contains("धीमी"))
        assertTrue(MoodState.voiceStyle("").contains("नरम"))
        MoodState.set(Mood.ANGRY)
        assertTrue(MoodState.voiceStyle("").contains("तेज़"))
        MoodState.set(Mood.HAPPY)
        assertTrue(MoodState.voiceStyle("").contains("हँसमुख"))
        // Edge: शब्दों से गति/पिच सच में बदले
        MoodState.set(Mood.SAD)
        assertTrue(com.dk.assistant.voice.engine.EdgeStyle.adjust(MoodState.voiceStyle("")).first < 0)
        MoodState.set(Mood.ANGRY)
        assertTrue(com.dk.assistant.voice.engine.EdgeStyle.adjust(MoodState.voiceStyle("")).first > 0)
    }

    @Test fun guessForNativeVoice() {
        assertEquals(Mood.SAD, EmotionGuess.of("माफ़ कीजिए, यह नहीं हो पाया"))
        assertEquals(Mood.HAPPY, EmotionGuess.of("वाह, बधाई हो"))
        assertEquals(Mood.NORMAL, EmotionGuess.of("कल मौसम साफ़ रहेगा"))
    }

    @Test fun moodNoteOnlyAsksForTag() {
        val n = com.dk.assistant.GeminiLive.MOOD_TAG_NOTE
        assertTrue(n.contains("हर जवाब की शुरुआत में एक भाव-टैग लिखो"))
        for (t in listOf("[खुश]", "[उदास]", "[गुस्सा]", "[भावुक]")) { assertTrue(n.contains(t)); assertFalse(EmotionTag.split("$t x").first == null) }
    }
}
