package com.dk.assistant

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v3.67: "यूट्यूब खोलो और उसपे पवन सिंह का सॉन्ग सर्च करके प्ले कर दो" = सीधे चलाओ, जल्दी */
class ActionFastTest {
    private fun act(t: String) = LocalRouter.route(t, listOf("डीके", "dk"), "")?.action

    @Test fun ownerSentencePlaysDirectly() {
        val a = act("यूट्यूब खोलो और उसपे पवन सिंह का सॉन्ग सर्च करके प्ले कर दो")!!
        assertEquals("youtube_play", a.optString("type"))
        assertEquals("पवन सिंह का सॉन्ग", a.optString("query"))
    }

    @Test fun songWordsAndOpenWordNotInQuery() {
        assertEquals("पवन सिंह का सॉन्ग", act("पवन सिंह का सॉन्ग प्ले करो")!!.optString("query"))
        val a = act("यूट्यूब खोलो पवन सिंह का गाना चलाओ")!!
        assertEquals("youtube_play", a.optString("type")); assertEquals("पवन सिंह का गाना", a.optString("query"))
        assertEquals("pawan singh ka song", act("youtube kholo aur pawan singh ka song chalao")!!.optString("query"))
    }

    @Test fun searchOnlyStaysSearch() {
        val a = act("यूट्यूब खोलो और उसपे पवन सिंह का सॉन्ग सर्च करो")!!
        assertEquals("youtube_search", a.optString("type")); assertEquals("पवन सिंह का सॉन्ग", a.optString("query"))
    }

    @Test fun firstVideoFoundEarlyAndAcrossChunks() {
        val pad = "x".repeat(32_768 - 20)
        val page = pad + "\"videoRenderer\":{\"videoId\":\"cQM55aOrZCg\"" + "y".repeat(500_000) + "\"videoRenderer\":{\"videoId\":\"zzzzzzzzzzz\""
        val b = Buffer().writeUtf8(page)
        assertEquals("cQM55aOrZCg", YtPage.firstVideoId(b))
        assert(b.size > 400_000)                       // बाकी पेज पढ़ा ही नहीं
        assertNull(YtPage.firstVideoId(Buffer().writeUtf8("<html>no results</html>")))
        assertNull(YtPage.firstVideoId(Buffer().writeUtf8("y".repeat(100_000) + "\"videoRenderer\":{\"videoId\":\"cQM55aOrZCg\""), maxBytes = 50_000))
    }
}
