package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.68: streaming जवाब -> बोलने के टुकड़े (पहला जल्दी, 5-8 शब्द) */
class TextChunkerTest {
    @Test fun firstSentenceGoesImmediately() {
        val c = TextChunker()
        assertEquals(listOf("हाँ, बिल्कुल।"), c.feed("हाँ, बिल्कुल। मैं"))
    }

    @Test fun firstCommaAfterFiveWords() {
        val c = TextChunker()
        assertTrue(c.feed("मुझे भी, ").isEmpty())                 // 2 शब्द पर अल्पविराम - अभी नहीं
        val out = c.feed("तुम्हारी बहुत याद आई, सच में")
        assertEquals(listOf("मुझे भी, तुम्हारी बहुत याद आई,"), out)
    }

    @Test fun firstForcedAtEightWords() {
        val c = TextChunker()
        val out = c.feed("एक दो तीन चार पाँच छह सात आठ नौ दस")
        assertEquals("एक दो तीन चार पाँच छह सात आठ", out.first())
    }

    @Test fun streamingTokensProduceSameChunks() {
        val text = "अरे वाह, आज तो मौसम बहुत सुहाना है। चलो बाहर घूमने चलते हैं, थोड़ी चाय भी पी लेंगे।"
        val c = TextChunker()
        val got = ArrayList<String>()
        text.chunked(3).forEach { got += c.feed(it) }
        c.flush()?.let { got += it }
        assertEquals(text.replace(Regex("\\s+"), " "), got.joinToString(" "))
        assertTrue(TextChunker.words(got.first()) <= TextChunker.FIRST_MAX)
    }

    @Test fun decimalNotCut() {
        val c = TextChunker()
        assertTrue(c.feed("कीमत 3.").isEmpty())
        assertTrue(c.feed("5 लाख").isEmpty())
        assertEquals("कीमत 3.5 लाख", c.flush())
    }

    @Test fun markdownRemovedAndStall() {
        val c = TextChunker()
        c.feed("**नमस्ते** दोस्त कैसे")
        assertEquals("नमस्ते दोस्त कैसे", c.flushFirstIfStalled(3)!!.replace(Regex("\\s+"), " "))
        assertNull(c.flushFirstIfStalled(3))
    }
}
