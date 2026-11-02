package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 (मालिक: "बात खत्म होते ही आवाज़ शुरू"): फ़ोन वाला इंजन (पहले Piper, अब Kokoro-82M) पूरा टुकड़ा
 * बनाकर ही बोलता है -> पहला टुकड़ा छोटा, फिर धीरे-धीरे बड़ा. Kokoro Piper से भारी है, इसलिए पहला टुकड़ा 4 शब्द तक.
 * Gemini TTS / Worker वाला पुराना नाप बिल्कुल वही. (पहले PiperFastStartTest - वही जाँचें अब Kokoro पर)
 */
class KokoroFastStartTest {
    private fun local() = TextChunker().also { it.reset(TextChunker.LOCAL) }
    private val nums = "एक दो तीन चार पाँच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस बीस " +
        "इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस छत्तीस " +
        "सैंतीस अड़तीस उनतालीस चालीस इकतालीस बयालीस तैंतालीस चवालीस पैंतालीस छियालीस "

    @Test fun kokoroGetsSmallFirstPiece_othersUnchanged() {
        assertEquals(TextChunker.LOCAL, SpeechPipeline.profileFor(VoiceEngines.KOKORO_TTS))
        assertEquals(TextChunker.STANDARD, SpeechPipeline.profileFor("gemini_tts"))
        assertEquals(TextChunker.STANDARD, TextChunker().profile)        // बिना reset(profile) = पुराना
        assertEquals(TextChunker.FIRST_MAX, TextChunker.STANDARD.firstMax)
        assertEquals(TextChunker.NEXT_MAX, TextChunker.STANDARD.secondMax)
    }

    /** Piper: 5/10/16 -> 7/12/18; Kokoro (भारी, ~1x real-time): 4/8/14 - मोड़ न मिले तो वहीं ज़बरदस्ती */
    @Test fun kokoroFirstForcedAtFourWords_thenGrows() {
        val c = local()
        val out = c.feed(nums)
        assertEquals("एक दो तीन चार", out[0])
        assertEquals(8, TextChunker.words(out[1]))
        assertEquals(14, TextChunker.words(out[2]))
        assertEquals(14, TextChunker.words(out[3]))
        assertEquals(4, out.size)
        assertEquals(6, TextChunker.words(c.flush()!!))         // बचा = आख़िरी टुकड़ा
    }

    /** मालिक: "रोबोट जैसा बोलता है" - ज़बरदस्ती कटाव "और / लेकिन / अगर" से पहले, या "है" के बाद */
    @Test fun kokoroForcedCutFallsOnNaturalBreak() {
        val c = local()
        val out = c.feed("जी हाँ, आज मौसम काफ़ी अच्छा रहेगा और दिन में धूप निकलेगी ")
        assertEquals("जी हाँ,", out[0])
        assertEquals("आज मौसम काफ़ी अच्छा रहेगा", out[1])
        assertTrue(out[2].startsWith("और दिन में"))
        val d = local().feed("मुझे लगता है आप आज शाम को ")
        assertEquals("मुझे लगता है", d[0])                          // "है" के बाद
        // बहुत पीछे (आधे से कम) का मोड़ नहीं - छोटे टुकड़े नहीं बनते
        assertEquals("एक और तीन चार", local().feed("एक और तीन चार पाँच ")[0])
    }

    @Test fun naturalCutOnlyForKokoro() {
        assertTrue(TextChunker.LOCAL.smartCut)
        assertFalse(TextChunker.STANDARD.smartCut)
        assertTrue("पर" !in TextChunker.BREAK_BEFORE && "तो" !in TextChunker.BREAK_BEFORE)   // "मेज़ पर", "मैं तो"
    }

    @Test fun kokoroFirstCommaAfterThreeWords() {
        val c = local()
        assertTrue(c.feed("जी, ").isEmpty())                       // 1 शब्द पर अल्पविराम - अभी नहीं
        assertEquals(listOf("जी, मैं समझ गया,"), c.feed("मैं समझ गया, आप"))
        assertEquals(listOf("हाँ।"), local().feed("हाँ। आज"))       // पूरा वाक्य = तुरंत, एक शब्द भी
    }

    @Test fun kokoroStreamingKeepsAllText() {
        val text = "अरे वाह, आज तो मौसम बहुत सुहाना है। चलो बाहर घूमने चलते हैं, थोड़ी चाय भी पी लेंगे और फिर बाज़ार चलेंगे।"
        val c = local()
        val got = ArrayList<String>()
        text.chunked(4).forEach { got += c.feed(it) }
        c.flush()?.let { got += it }
        assertEquals(text.replace(Regex("\\s+"), " "), got.joinToString(" "))
        assertTrue(TextChunker.words(got.first()) <= TextChunker.LOCAL.firstMax)
    }

    @Test fun kokoroStallSpeaksSooner() {
        assertEquals(150L, TextChunker.LOCAL.stallMs)
        assertEquals(2, TextChunker.LOCAL.stallMin)
        assertEquals(250L, TextChunker.STANDARD.stallMs)
        val c = local(); c.feed("नमस्ते दोस्त")
        assertEquals("नमस्ते दोस्त", c.flushFirstIfStalled(TextChunker.LOCAL.stallMin))
    }

    /** हर अगला टुकड़ा पिछले से ज़्यादा से ज़्यादा ~2 गुना: पिछला बजते-बजते बन जाए (बीच में चुप्पी नहीं) */
    @Test fun kokoroPiecesGrowSlowlyNoGap() {
        val p = TextChunker.LOCAL
        assertTrue(p.firstMax <= 4)                              // पहली आवाज़ जल्दी (Kokoro ~1x real-time)
        assertTrue(p.secondMax <= p.firstMax * 2)
        assertTrue(p.nextMax <= p.secondMax * 2)
        assertTrue(p.nextMax < TextChunker.NEXT_MAX)
    }
}
