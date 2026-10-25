package com.dk.assistant.avatar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v3.71: लड़की अवतार - भाव-टैग, मुँह, पलक, हवा, चित्र का नक्शा */
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

    @Test fun moodToFace() {
        assertEquals(Face.NORMAL, Mood.HAPPY.face)
        assertEquals(Face.SAD, Mood.SAD.face)
        assertEquals(Face.SAD, Mood.EMOTIONAL.face)
        assertEquals(Face.ANGRY, Mood.ANGRY.face)
        assertEquals(Face.NORMAL, Mood.NORMAL.face)
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
        assertEquals(Face.ANGRY, MoodState.face(1000))
        MoodState.replyDone(1000)
        assertEquals(Face.ANGRY, MoodState.face(1000 + MoodState.HOLD_MS - 1))
        assertEquals(Face.NORMAL, MoodState.face(1000 + MoodState.HOLD_MS + 1))
    }

    @Test fun speakingDelaysReturn() {
        MoodState.set(Mood.SAD)
        MoodState.replyDone(1000)
        MoodState.stillSpeaking(9000)
        assertEquals(Face.SAD, MoodState.face(9000 + MoodState.HOLD_MS - 1))
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

    @Test fun mouthLevels() {
        val m = MouthMapper()
        assertEquals(Mouth.BASE, m.update(0.02f, 40, Face.NORMAL))
        var r = Mouth.BASE
        repeat(10) { r = m.update(0.25f, 40, Face.NORMAL) }
        assertEquals(Mouth.HALF, r)
        repeat(10) { r = m.update(0.9f, 40, Face.NORMAL) }
        assertEquals(Mouth.WIDE, r)
        // आवाज़ बंद = तुरंत base
        assertEquals(Mouth.BASE, m.update(null, 40, Face.NORMAL))
    }

    @Test fun mouthSmoothsSingleSpike() {
        val m = MouthMapper()
        m.update(0f, 40, Face.NORMAL)
        // एक अकेला 20 ms का झटका सीधे wide नहीं
        assertTrue(m.update(1f, 10, Face.NORMAL) != Mouth.WIDE)
    }

    @Test fun sadAngryNeverWide() {
        for (f in listOf(Face.SAD, Face.ANGRY)) {
            val m = MouthMapper()
            var r = Mouth.BASE
            repeat(20) { r = m.update(1f, 40, f) }
            assertEquals(Mouth.HALF, r)
        }
    }

    @Test fun faceFiles() {
        assertEquals(listOf("face_normal_base", "face_normal_half", "face_normal_wide", "face_normal_blink"), Face.NORMAL.files())
        assertEquals(3, Face.SAD.files().size)
        assertEquals(3, Face.ANGRY.files().size)
    }

    @Test fun blinkEvery3to5s() {
        val b = BlinkClock(Random(7))
        var t = 0L
        val starts = ArrayList<Long>()
        val lens = ArrayList<Long>()
        var was = false; var run = 0L
        while (t < 60_000) {
            val c = b.closed(t)
            if (c && !was) starts.add(t)
            if (c) run += 10 else if (was) { lens.add(run); run = 0 }
            was = c; t += 10
        }
        assertTrue(starts.size in 11..21)
        for (i in 1 until starts.size) assertTrue((starts[i] - starts[i - 1]) in 3000..5200)
        // हर पलक ~120 ms
        assertTrue(lens.isNotEmpty() && lens.all { it in 100..140 })
    }

    @Test fun windAlwaysMovesNeverWild() {
        val w = Wind(Random(3))
        var t = 0L; var last = w.phase
        var minS = 1f; var maxS = 0f
        while (t < 120_000) {
            w.step(t, 16); t += 16
            assertTrue(w.phase > last); last = w.phase
            minS = minOf(minS, w.strength); maxS = maxOf(maxS, w.strength)
        }
        assertTrue(minS > 0.05f)
        assertTrue(maxS <= 1f)
        assertTrue(maxS - minS > 0.2f)     // कभी तेज़, कभी ठहरी
    }

    @Test fun hairWeightZeroOnFaceAndShoulders() {
        // चेहरा, गर्दन, सीना
        for (v in listOf(0.2f, 0.3f, 0.4f, 0.6f, 0.8f)) assertEquals(0f, AvatarGeometry.hairWeight(0.5f, v), 0.001f)
        // सिर की ऊँचाई पर बाल भी स्थिर
        assertEquals(0f, AvatarGeometry.hairWeight(0.2f, 0.25f), 0.001f)
        // कंधे का बाहरी किनारा
        assertTrue(AvatarGeometry.hairWeight(0.05f, 0.6f) < 0.05f)
        // नीचे की ओर बढ़ता है (सिरों पर सबसे ज़्यादा)
        val a = AvatarGeometry.hairWeight(0.25f, 0.5f)
        val b = AvatarGeometry.hairWeight(0.25f, 0.7f)
        val c = AvatarGeometry.hairWeight(0.25f, 0.88f)
        assertTrue(a < b && b < c)
        assertTrue(c > 0.4f)
        // दोनों तरफ़ बराबर
        assertEquals(AvatarGeometry.hairWeight(0.25f, 0.8f), AvatarGeometry.hairWeight(0.75f, 0.8f), 0.001f)
    }

    @Test fun windOffsetZeroWhereWeightZero() {
        val (dx, dy) = AvatarGeometry.windOffset(0.5f, 0.35f, 1.3, 1f)
        assertEquals(0f, dx, 0f); assertEquals(0f, dy, 0f)
        val (ex, _) = AvatarGeometry.windOffset(0.22f, 0.86f, 1.3, 1f)
        assertTrue(kotlin.math.abs(ex) < AvatarGeometry.IMG_W * 0.03f)
    }

    @Test fun faceMaskFeathered() {
        assertEquals(1f, AvatarGeometry.faceAlpha(0.5f, 0.5f), 0.001f)
        assertEquals(0f, AvatarGeometry.faceAlpha(0f, 0.5f), 0.001f)
        assertEquals(0f, AvatarGeometry.faceAlpha(0.5f, 1f), 0.001f)
        val mid = AvatarGeometry.faceAlpha(0.95f, 0.5f)
        assertTrue(mid > 0f && mid < 1f)
        // कोने पूरे पारदर्शी (बाल नहीं आते)
        assertEquals(0f, AvatarGeometry.faceAlpha(0.02f, 0.02f), 0.001f)
    }

    @Test fun faceRectInsideImageAndSkinOnly() {
        assertTrue(AvatarGeometry.FACE_L >= 280 && AvatarGeometry.FACE_R <= 740)
        assertTrue(AvatarGeometry.FACE_T >= 280 && AvatarGeometry.FACE_B <= 640)
        assertTrue(AvatarGeometry.FACE_L < AvatarGeometry.FACE_R && AvatarGeometry.FACE_T < AvatarGeometry.FACE_B)
    }

    @Test fun fitCentersWholeImage() {
        val (s, l, t) = AvatarGeometry.fit(1080, 2400)
        assertEquals(1080f / 1024, s, 0.001f); assertEquals(0f, l, 0.01f)
        assertTrue(t > 0)                                   // ऊपर-नीचे काली पट्टी
        val (s2, l2, t2) = AvatarGeometry.fit(2400, 1080)   // आड़ा
        assertEquals(1080f / 1536, s2, 0.001f); assertEquals(0f, t2, 0.01f)
        assertEquals((2400 - 1024 * s2) / 2, l2, 0.01f)
        assertEquals(Triple(0f, 0f, 0f), AvatarGeometry.fit(0, 100))
    }

    @Test fun sampleSizeSavesMemory() {
        assertEquals(1, AvatarGeometry.sampleSize(1024, 1536, 1080, 2400))
        assertEquals(2, AvatarGeometry.sampleSize(1024, 1536, 480, 720))
        assertEquals(4, AvatarGeometry.sampleSize(1024, 1536, 200, 300))
        assertEquals(1, AvatarGeometry.sampleSize(1024, 1536, 0, 0))
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
