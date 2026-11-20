package com.dk.assistant.voice.st3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 नवंबर 2026 (मालिक: "स्टोरी डबिंग में डर के, चिल्ला के, रो के बोल रहा है - सुनने में पता नहीं चलता, सब एकदम नॉर्मल"):
 * अब हर भाव के असली साधन - सुर, काँपना, चीख का खिंचाव, फुसफुसाहट, एक स्तर, और शब्दों में अटकना / टूटना.
 */
class St3EmotionTest {
    private val rate = St3Config.SAMPLE_RATE
    private fun tone(hz: Double = 150.0, amp: Float = 0.3f, sec: Double = 1.0) =
        FloatArray((rate * sec).toInt()) { (amp * kotlin.math.sin(2 * Math.PI * hz * it / rate)).toFloat() }
    private fun crossings(w: FloatArray) = (1 until w.size).count { (w[it - 1] < 0f) != (w[it] < 0f) }.toFloat() / w.size

    @Test fun level_sameLoudnessForEveryLine() {
        assertEquals(St3Emotion.TARGET_RMS, St3Emotion.rms(St3Emotion.level(tone(amp = 0.05f))), 0.005f)
        assertEquals(St3Emotion.TARGET_RMS, St3Emotion.rms(St3Emotion.level(tone(amp = 0.8f))), 0.005f)
    }

    @Test fun pitch_upRaisesToneSameShape() {
        val x = tone()
        val r = St3Emotion.pitchRatio(2f)
        val y = St3Emotion.resample(x, r)
        assertEquals(x.size / r, y.size.toFloat(), 2f)                     // इसलिए StoryVoice पहले speed / ratio से बनवाता है
        assertEquals(r, crossings(y) / crossings(x), 0.01f)                // सुर सचमुच ऊपर
        assertTrue(St3Emotion.pitchRatio(-2f) < 1f)
        assertEquals(St3Emotion.pitchRatio(St3Emotion.MAX_SEMITONES), St3Emotion.pitchRatio(9f), 1e-6f)
        assertTrue(St3Emotion.resample(x, 1f) === x)
    }

    @Test fun shake_wobblesButKeepsLength() {
        val x = St3Emotion.level(tone())
        val y = St3Emotion.shake(x, 0.7f, 6f)
        assertEquals(x.size, y.size)
        assertTrue(y.all { it.isFinite() && kotlin.math.abs(it) <= 1f })
        // लहराना: 50 ms टुकड़ों की ऊँचाई अब एक-सी नहीं
        val win = rate / 20
        val lv = (0 until y.size / win).map { b -> St3Emotion.rms(y.copyOfRange(b * win, (b + 1) * win)) }
        assertTrue(lv.max() / lv.min() > 1.2f)
        assertTrue(St3Emotion.shake(x, 0f) === x)
    }

    @Test fun strain_fullerNotLouderPeak() {
        val x = St3Emotion.level(tone())
        val y = St3Emotion.strain(x, 0.65f)
        val peak = { w: FloatArray -> w.maxOf { kotlin.math.abs(it) } }
        assertTrue(peak(y) <= peak(x) * 1.001f)
        assertTrue(St3Emotion.rms(y) / peak(y) > St3Emotion.rms(x) / peak(x))   // भरी हुई = चीख जैसी
    }

    @Test fun perform_shoutLouderThanWhisper_neverClips() {
        val x = tone()
        val shout = St3Emotion.perform(x, St3Story.mood("shouting"))
        val whisper = St3Emotion.perform(x, St3Story.mood("whispering"))
        val scared = St3Emotion.perform(x, St3Story.mood("scared"))
        assertTrue(St3Emotion.rms(shout) > St3Emotion.rms(whisper) * 2.5f)
        for (w in listOf(shout, whisper, scared)) assertTrue(w.all { it.isFinite() && kotlin.math.abs(it) <= 1f })
        assertTrue(St3Emotion.perform(FloatArray(0), St3Story.mood("sad")).isEmpty())
    }

    @Test fun moods_haveRealEmotion() {
        val shout = St3Story.mood("shouting")
        assertTrue(shout.pitch > 1f && shout.strain > 0.4f)
        val scared = St3Story.mood("scared")
        assertTrue(scared.pitch > 0f && scared.shake > 0.4f && scared.stutter)
        assertTrue(St3Story.mood("terrified").shake > scared.shake)
        assertTrue(St3Story.mood("काँपते हुए").shake > scared.shake)
        val cry = St3Story.mood("crying")
        assertTrue(cry.shake > 0.5f && cry.broken && cry.speed < 1f)
        assertTrue(St3Story.mood("sad").pitch < 0f)
        assertTrue(St3Story.mood("whispering").airy)
        assertTrue(St3Story.mood("happy").pitch > 1f && St3Story.mood("happy").gain > 1f)
        assertEquals(0f, St3Story.mood("").pitch, 0f)
        assertEquals(0f, St3Story.mood("calm storytelling", narrator = true).shake, 0f)
    }

    @Test fun act_stutterAndBrokenSpeech() {
        val scared = St3Story.mood("scared")
        assertEquals("मु… मुझे डर लग रहा है।", St3Story.act("मुझे डर लग रहा है।", scared))
        assertEquals("प… प्यार से बोलो।", St3Story.act("प्यार से बोलो।", scared))
        assertEquals("हाँ… मैं ठीक हूँ… सच में।", St3Story.act("हाँ, मैं ठीक हूँ, सच में।", St3Story.mood("crying")))
        assertEquals("हाँ, मैं ठीक हूँ।", St3Story.act("हाँ, मैं ठीक हूँ।", St3Story.mood("")))
        assertEquals("", St3Story.act("  ", scared))
        val full = St3Story.lineText(St3Story.act("मुझे बचाओ, कोई है?", scared), scared)
        assertTrue(full, full.startsWith("<breath> मु… मुझे"))
        assertFalse(full, full.contains("… <breath>"))                  // अटकने के बीच साँस नहीं
        assertTrue(St3Text.tagsWhole(full))
    }

    @Test fun storyVoiceUsesEmotion() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val s = java.io.File(root, "com/dk/assistant/StoryVoice.kt").readText()
        assertTrue(s.contains("St3Emotion.perform(w, m)"))
        assertTrue(s.contains("St3Story.act("))
        assertTrue(s.contains("m.speed / ratio"))
    }
}
