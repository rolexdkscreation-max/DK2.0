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
        assertTrue(p.firstSoft >= 3 && p.firstMax <= 7)      // ~20-40 अक्षर, जल्दी शुरू
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
        // 19 नवंबर 2026 (मालिक: "चुना, फिर भी Gemini बोलता है"): फ़ोन वाली आवाज़ से समय-दौड़ नहीं - कितनी भी देर हो
        assertFalse(SplitRescue.firstTooSlow(now = 60_000, liveSince = 1_000, ttsStarted = false, open = true, local = true))
        assertFalse(SplitRescue.stuck(SplitRescue.NEXT_STUCK_MS * 3, first = true, local = true))
        assertFalse(SplitRescue.stuck(SplitRescue.NEXT_STUCK_MS * 3, first = false, local = true))
        // सच में अटके (मॉडल रुका) तब ही Live
        assertTrue(SplitRescue.stuck(SplitRescue.LOCAL_STUCK_MS, first = true, local = true))
    }

    @Test fun firstPieceOfEachAnswerIsFaster_andInOrder() {
        assertTrue(St3VoiceEngine.FIRST_STEPS in 5..6)       // 4 पर शब्द धुँधले (Whisper से जाँचा)
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val pipe = java.io.File(root, "com/dk/assistant/voice/engine/SpeechPipeline.kt").readText()
        assertTrue(pipe.contains("if (e.id == St3VoiceEngine.ID) St3VoiceEngine.newAnswer()"))
        assertTrue(pipe.contains("e.id != St3VoiceEngine.ID && VoiceEngines.isFailed(e.id)"))
        val eng = java.io.File(root, "com/dk/assistant/voice/st3/St3Engine.kt").readText()
        assertTrue(eng.contains("ReentrantLock(true)"))      // पहला टुकड़ा पहले बने
        val live = java.io.File(root, "com/dk/assistant/GeminiLiveRescue.kt").readText()
        assertTrue(live.contains("SplitRescue.firstTooSlow(now, since, started, open, local)"))
    }

    @Test fun slowPhoneGetsFewerSteps_fastPhoneKeepsSetting() {
        assertEquals(8, St3VoiceEngine.autoSteps(8, 0.0))      // अभी नापा नहीं
        assertEquals(8, St3VoiceEngine.autoSteps(8, 0.03))     // तेज़ फ़ोन: 8 कदम = 0.24 (आधे से कम) - Settings वाला
        assertEquals(7, St3VoiceEngine.autoSteps(8, 0.1))      // 0.7 / 0.1 = 7
        assertEquals(5, St3VoiceEngine.autoSteps(8, 0.15))     // धीमा: 5 से नीचे नहीं...
        assertEquals(5, St3VoiceEngine.autoSteps(8, 0.25))     // ...बहुत धीमा हो तब भी 5 (19 नवंबर 2026, मालिक: 4 पर रोबोटिक गूंज)
        assertEquals(3, St3VoiceEngine.autoSteps(3, 0.25))     // खुद कम रखा तो वही
        assertEquals(0.5, St3VoiceEngine.learn(0.0, 0.5), 1e-9)
        assertEquals(0.6 * 0.1 + 0.4 * 0.2, St3VoiceEngine.learn(0.1, 0.2), 1e-9)
    }

    @Test fun noPauseAfterFirstSentence() {
        // 19 नवंबर 2026 (मालिक: "पहला वाक्य बोलकर रुकता है"): दूसरा टुकड़ा भी छोटा + जल्दी वाले कदम, और शुरू में एक आगे तैयार
        assertTrue(TextChunker.ST3.secondMax <= 8)
        // कदम बारी पर: आगे बहुत आवाज़ तैयार = Settings वाले (साफ़); कम = 5 तक, उससे नीचे नहीं
        assertEquals(8, St3VoiceEngine.leadSteps(8, leadMs = 6_000, perStepRtf = 0.05, estAudioMs = 2_000.0))
        assertEquals(6, St3VoiceEngine.leadSteps(8, leadMs = 1_600, perStepRtf = 0.1, estAudioMs = 2_000.0))   // (1360 - 120) / 200 = 6
        assertEquals(5, St3VoiceEngine.leadSteps(8, leadMs = 300, perStepRtf = 0.1, estAudioMs = 2_000.0))
        assertEquals(8, St3VoiceEngine.leadSteps(8, leadMs = 0, perStepRtf = 0.0, estAudioMs = 2_000.0))      // अभी नापा नहीं
        assertTrue(SpeechPipeline.PREROLL_MS in 300L..1_000L)
        assertEquals(St3VoiceEngine.MIN_AUTO_STEPS, St3VoiceEngine.FIRST_STEPS)
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val pipe = java.io.File(root, "com/dk/assistant/voice/engine/SpeechPipeline.kt").readText()
        assertTrue(pipe.contains("if (firstPending && engine?.id == St3VoiceEngine.ID) preroll(s)"))   // सिर्फ़ फ़ोन वाली आवाज़
    }

    @Test fun nextPieceStartsBeforeSentenceEnds_onlyForSt3() {
        assertTrue(TextChunker.ST3.refillMs in 800L..2_500L)
        assertEquals(0L, TextChunker.STANDARD.refillMs)
        assertEquals(0L, TextChunker.FAST_NET.refillMs)
        val c = TextChunker().apply { reset(TextChunker.ST3) }
        assertEquals(null, c.flushIfAtLeast(3))                // पहला टुकड़ा अभी नहीं निकला - यह रास्ता नहीं
        c.feed("नमस्ते जी, आज हम बात करते हैं ")
        c.flush()
        c.feed("कि मौसम ")
        assertEquals(null, c.flushIfAtLeast(3))                // 2 शब्द - अभी नहीं
        c.feed("कैसा रहेगा ")
        assertEquals("कि मौसम कैसा रहेगा", c.flushIfAtLeast(3))   // वाक्य पूरा होने का इंतज़ार नहीं
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
