package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 (मालिक: "बात खत्म होते ही Piper की आवाज़ शुरू"): Piper पूरा टुकड़ा बनाकर ही बोलता है ->
 * पहला टुकड़ा छोटा, फिर धीरे-धीरे बड़ा. Gemini TTS / Worker वाला पुराना नाप बिल्कुल वही.
 */
class PiperFastStartTest {
    private fun local() = TextChunker().also { it.reset(TextChunker.LOCAL) }
    private val nums = "एक दो तीन चार पाँच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस बीस " +
        "इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस छत्तीस " +
        "सैंतीस अड़तीस उनतालीस चालीस इकतालीस बयालीस तैंतालीस चवालीस पैंतालीस छियालीस "

    @Test fun piperGetsSmallFirstPiece_othersUnchanged() {
        assertEquals(TextChunker.LOCAL, SpeechPipeline.profileFor(VoiceEngines.PIPER_TTS))
        assertEquals(TextChunker.STANDARD, SpeechPipeline.profileFor("gemini_tts"))
        assertEquals(TextChunker.STANDARD, TextChunker().profile)        // बिना reset(profile) = पुराना
        assertEquals(TextChunker.FIRST_MAX, TextChunker.STANDARD.firstMax)
        assertEquals(TextChunker.NEXT_MAX, TextChunker.STANDARD.secondMax)
    }

    @Test fun piperFirstForcedAtFiveWords_thenGrows() {
        val c = local()
        val out = c.feed(nums)
        assertEquals("एक दो तीन चार पाँच", out[0])
        assertEquals(10, TextChunker.words(out[1]))
        assertEquals(16, TextChunker.words(out[2]))
        assertEquals(16, TextChunker.words(out[3]))
    }

    @Test fun piperFirstCommaAfterThreeWords() {
        val c = local()
        assertTrue(c.feed("जी, ").isEmpty())                       // 1 शब्द पर अल्पविराम - अभी नहीं
        assertEquals(listOf("जी, मैं समझ गया,"), c.feed("मैं समझ गया, आप"))
        assertEquals(listOf("हाँ।"), local().feed("हाँ। आज"))       // पूरा वाक्य = तुरंत, एक शब्द भी
    }

    @Test fun piperStreamingKeepsAllText() {
        val text = "अरे वाह, आज तो मौसम बहुत सुहाना है। चलो बाहर घूमने चलते हैं, थोड़ी चाय भी पी लेंगे और फिर बाज़ार चलेंगे।"
        val c = local()
        val got = ArrayList<String>()
        text.chunked(4).forEach { got += c.feed(it) }
        c.flush()?.let { got += it }
        assertEquals(text.replace(Regex("\\s+"), " "), got.joinToString(" "))
        assertTrue(TextChunker.words(got.first()) <= TextChunker.LOCAL.firstMax)
    }

    @Test fun piperStallSpeaksSooner() {
        assertEquals(150L, TextChunker.LOCAL.stallMs)
        assertEquals(2, TextChunker.LOCAL.stallMin)
        assertEquals(250L, TextChunker.STANDARD.stallMs)
        val c = local(); c.feed("नमस्ते दोस्त")
        assertEquals("नमस्ते दोस्त", c.flushFirstIfStalled(TextChunker.LOCAL.stallMin))
    }

    /** हर अगला टुकड़ा पिछले से ज़्यादा से ज़्यादा ~2 गुना: पिछला बजते-बजते बन जाए (बीच में चुप्पी नहीं) */
    @Test fun piperPiecesGrowSlowlyNoGap() {
        val p = TextChunker.LOCAL
        assertTrue(p.firstMax <= 5)
        assertTrue(p.secondMax <= p.firstMax * 2)
        assertTrue(p.nextMax <= p.secondMax * 2)
        assertTrue(p.nextMax < TextChunker.NEXT_MAX)
    }
}
