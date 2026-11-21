package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 21 नवंबर 2026 (मालिक): "DK स्कैन खोलो" और "YouTube … फुल स्क्रीन / ऐड स्किप" - फ़ोन पर सीधे (धीमे AI रास्ते पर नहीं) */
class ScanYoutubeRouteTest {
    private val wake = listOf("डीके", "दीके", "डी के", "dk", "d k")
    private fun act(t: String, fg: String = "") = LocalRouter.route(t, wake, fg)?.action

    @Test fun scanIsLensDirect() {
        val a = act("डीके स्कैन खोलो")!!
        assertEquals(LensCmd.TYPE, a.optString("type"))
        assertFalse(a.optBoolean("front"))
        assertTrue(act("डीके स्कैन सामने की तरफ करके खोलो")!!.optBoolean("front"))
        assertFalse(act("डीके स्कैन बैक करके खोलो")!!.optBoolean("front"))
    }

    @Test fun youtubeFullScreenStaysLocal() {
        val a = act("डीके यूट्यूब पर अरिजीत सिंह का गाना चलाओ और उसे फुल स्क्रीन में कर देना")!!
        assertEquals("youtube_play", a.optString("type"))
        assertTrue(a.optBoolean("full"))
        assertFalse(a.optString("query").contains("फुल"))
        val b = act("यूट्यूब पर अरिजीत सिंह का गाना चलाओ, अगर कोई ऐड आ रहा है तो ऐड स्किप कर देना")!!
        assertEquals("youtube_play", b.optString("type"))
        assertFalse(b.optString("query").contains("ऐड"))
    }

    @Test fun aloneOnYoutube() {
        assertEquals("yt_full", act("फुल स्क्रीन कर दो", LocalRouter.YT_PKG)!!.optString("type"))
        assertEquals("yt_skip_ad", act("ऐड स्किप करो", LocalRouter.YT_PKG)!!.optString("type"))
        assertNotEquals("yt_full", act("फुल स्क्रीन कर दो")?.optString("type"))
    }
}
