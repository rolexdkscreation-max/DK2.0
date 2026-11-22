package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 22 नवंबर 2026 (मालिक): "I love you" = "I love you too"; तारीफ़ = ख़ुश होकर, शरमाकर, स्वभाव के हिसाब से */
class LoveYouTooTest {

    @Test fun everyPersonaSaysLoveYouToo() {
        for (p in DkPersona.P.values()) {
            val f = DkPersona.finalFor(p, "धीरज")
            assertTrue(p.name, f.contains("\"I love you\" = reply \"I love you too"))
            assertTrue(p.name, f.contains("Praise = happy, shy thanks"))
            assertTrue(p.name, f.contains("this personality's way"))
        }
    }

    @Test fun proStaysGraceful() {
        assertTrue(DkPersona.affection(DkPersona.P.PRO, "सर").contains("graceful, not romantic"))
        assertFalse(DkPersona.affection(DkPersona.P.GIRLFRIEND, "धीरज").contains("not romantic"))
        assertTrue(DkPersona.affection(DkPersona.P.GIRLFRIEND, "धीरज").contains("I love you too धीरज"))
    }

    @Test fun girlfriendPromptStillSmall() {
        assertTrue(DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "धीरज").length < 3_500)
        assertTrue(DkPersona.LOVE_RULES.contains("THANK YOU"))
    }

    @Test fun offlineLoveAndPraise() {
        val x = OfflineTalk.Ctx(user = "राज", me = "DK", hour = 13, seed = 1)
        assertTrue(OfflineTalk.casual("आई लव यू", x)!!.startsWith("I love you too राज"))
        assertTrue(OfflineTalk.casual("I love you DK", x)!!.contains("I love you too"))
        val praise = OfflineTalk.casual("तुम बहुत अच्छी हो", x)!!
        assertTrue(praise, praise.contains("थैंक यू"))
        assertTrue(OfflineTalk.casual("well done", x)!!.contains("थैंक यू"))
    }
}
