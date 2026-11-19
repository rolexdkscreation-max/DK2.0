package com.dk.assistant.voice.engine

import com.dk.assistant.SplitRescue
import com.dk.assistant.voice.st3.St3Synth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 19 नवंबर 2026 (मालिक): फ़ोन पर बनने वाली आवाज़ अब आवाज़-इंजनों की सूची में - चुनो तो बातचीत उसी से */
class St3EngineWiringTest {
    @Test fun engineIsSplit_offByDefault_onlyFemaleVoices() {
        assertTrue(St3VoiceEngine.split)                     // Gemini Live लिखे, यह बोले
        assertFalse(St3VoiceEngine.defaultOn)                // पुराना डिफ़ॉल्ट (DK लाइव) वही
        assertEquals(listOf("F1", "F2", "F3", "F4", "F5"), St3VoiceEngine.voices().map { it.first })
        assertFalse(St3VoiceEngine.title.contains("Supertonic"))   // पुराने हटाए इंजन वाली जाँच से टकराव नहीं
    }

    @Test fun chunksAreSentencePieces_notWordByWord() {
        val p = SpeechPipeline.profileFor(St3VoiceEngine.ID)
        assertEquals(TextChunker.ST3, p)
        assertTrue(p.firstSoft >= 4 && p.firstMax <= 8)      // ~25-40 अक्षर
        assertTrue(p.nextSoft >= 8)                          // ~60 अक्षर अल्पविराम पर
        // पुराने इंजनों के नाप वही
        assertEquals(TextChunker.FAST_NET, SpeechPipeline.profileFor(VoiceEngines.GEMINI_TTS))
        assertEquals(TextChunker.STANDARD, SpeechPipeline.profileFor(VoiceEngines.LIVE_NATIVE))
        val c = TextChunker().apply { reset(TextChunker.ST3) }
        val out = c.feed("नमस्ते, मैं आपकी कैसे मदद कर सकती हूँ आज? ") + listOfNotNull(c.flush())
        assertTrue(out.toString(), out.first().split(' ').size >= 3)
    }

    @Test fun laughBreathSighTagsStayWhole() {
        val c = TextChunker().apply { reset(TextChunker.ST3) }
        val out = ArrayList<String>()
        for (w in "ठीक है, समझ गई। <la|ugh> चलो **अब** शुरू करते हैं, <breath> पहले पानी पी लो।".split(" ", "|")) out += c.feed(if (w.startsWith("<la")) w else "$w ")
        c.flush()?.let { out += it }
        val all = out.joinToString(" ")
        assertTrue(all, all.contains("<laugh>") && all.contains("<breath>") && !all.contains("*"))
        out.forEach { assertTrue(it, com.dk.assistant.voice.st3.St3Text.tagsWhole(it)) }
        // पुराने इंजन: पहले जैसा (markdown का > भी मिटे)
        assertEquals("  a", TextChunker.clean("> a"))
        assertEquals("<sigh> हाँ a", com.dk.assistant.voice.st3.St3Text.onlyTags("<sigh> हाँ > a <"))
    }

    @Test fun liveDoesNotTalkOverTheNewVoiceTooEarly() {
        // पुराने नाप (Gemini TTS) बिल्कुल वही
        assertTrue(SplitRescue.firstTooSlow(now = 1_900, liveSince = 1_000, ttsStarted = false, open = true))
        assertTrue(SplitRescue.stuck(SplitRescue.FIRST_STUCK_MS, first = true))
        // नई आवाज़: 900 ms पर नहीं, सच में अटके तब
        assertFalse(SplitRescue.firstTooSlow(now = 1_900, liveSince = 1_000, ttsStarted = false, open = true, local = true))
        assertTrue(SplitRescue.firstTooSlow(now = 1_000 + SplitRescue.LOCAL_FIRST_BUDGET_MS, liveSince = 1_000, ttsStarted = false, open = true, local = true))
        assertFalse(SplitRescue.stuck(SplitRescue.FIRST_STUCK_MS, first = true, local = true))
        assertTrue(SplitRescue.stuck(SplitRescue.LOCAL_NEXT_STUCK_MS, first = false, local = true))
    }

    @Test fun resampleTo24k_keepsSpeech_dropsHiss() {
        val n = 44_100
        val tone = { hz: Double -> FloatArray(n) { (0.5 * Math.sin(2 * Math.PI * hz * it / 44_100.0)).toFloat() } }
        fun rms(a: FloatArray) = Math.sqrt(a.drop(200).dropLast(200).map { it.toDouble() * it }.average())
        val low = St3Synth.to24k(tone(440.0)); val high = St3Synth.to24k(tone(16_000.0))
        assertEquals(24_000, low.size)
        assertTrue(rms(low) > 0.3)                           // बोली वाली आवाज़ बची
        assertTrue(rms(high) < 0.05)                         // 12 kHz से ऊपर (aliasing) हटा
        assertEquals(0, St3Synth.to24k(FloatArray(0)).size)
        assertEquals(24_000 / 5 * 2, St3Synth.SILENCE_24K_BYTES)
    }

    @Test fun wiredIntoListAndPipeline() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val list = java.io.File(root, "com/dk/assistant/voice/engine/VoiceEngines.kt").readText()
        assertTrue(list.contains("\"com.dk.assistant.voice.engine.St3VoiceEngine\""))
        val eng = java.io.File(root, "com/dk/assistant/voice/engine/St3VoiceEngine.kt").readText()
        assertTrue(eng.contains("St3Engine.synthBlocking("))       // बातचीत में वही मॉडल जो टेस्ट में
        assertTrue(eng.contains("override fun stop() { St3Engine.stop() }"))   // बीच में टोको = तुरंत चुप
    }
}
