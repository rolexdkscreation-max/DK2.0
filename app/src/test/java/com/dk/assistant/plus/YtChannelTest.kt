package com.dk.assistant.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 15 नवंबर 2026: YouTube चैनल जाँच - handle/ID पहचान और जवाब पढ़ना */
class YtChannelTest {
    private val ID = "UCabcdefghijklmnopqrstuv"

    @Test fun parse_handle_id_link() {
        assertEquals("dkcreation", YtChannel.parse("@dkcreation").handle)
        assertEquals("dk.creation", YtChannel.parse("https://www.youtube.com/@dk.creation/videos").handle)
        assertEquals(ID, YtChannel.parse("https://youtube.com/channel/$ID").id)
        assertEquals(ID, YtChannel.parse(ID).id)
        assertEquals("dkcreation", YtChannel.parse("dkcreation").handle)
        assertFalse(YtChannel.parse("").ok)
        assertFalse(YtChannel.parse("कुछ भी नहीं ?").ok)
    }

    @Test fun channelsUrl_usesForHandleOrId() {
        assertTrue(YtChannel.channelsUrl(YtChannel.Who("dk", null), "K").contains("forHandle=%40dk"))
        assertTrue(YtChannel.channelsUrl(YtChannel.Who(null, ID), "K").contains("&id=$ID"))
    }

    @Test fun apiParsers() {
        val ch = YtChannel.parseChannels("""{"items":[{"id":"$ID","snippet":{"title":"DK"},"statistics":{"subscriberCount":"125000","viewCount":"9000000","videoCount":"42"},"contentDetails":{"relatedPlaylists":{"uploads":"UUx"}}}]}""")!!
        assertEquals(125000L, ch.subs); assertEquals(42L, ch.videos); assertEquals("UUx", ch.uploads)
        val hidden = YtChannel.parseChannels("""{"items":[{"id":"x","statistics":{"hiddenSubscriberCount":true,"subscriberCount":"0"}}]}""")!!
        assertEquals(null, hidden.subs)
        assertEquals(null, YtChannel.parseChannels("""{"items":[]}"""))
        assertEquals(listOf("v1" to "T"), YtChannel.parsePlaylist("""{"items":[{"snippet":{"title":"T","resourceId":{"videoId":"v1"}}}]}"""))
        val v = YtChannel.parseVideos("""{"items":[{"id":"v1","snippet":{"title":"T","publishedAt":"2026-11-10T00:00:00Z"},"statistics":{"viewCount":"10","likeCount":"2","commentCount":"1"}}]}""")
        assertEquals("2026-11-10", v[0].published); assertEquals(1L, v[0].comments)
        val c = YtChannel.parseComments("""{"items":[{"snippet":{"topLevelComment":{"snippet":{"authorDisplayName":"A","textOriginal":"nice &amp; good"}}}}]}""")
        assertEquals(listOf("A: nice & good"), c)
        assertEquals("आज की मुफ़्त सीमा खत्म (कल फिर)", YtChannel.apiError(403, """{"error":{"errors":[{"reason":"quotaExceeded"}]}}"""))
    }

    @Test fun noKeyParsers() {
        val html = """<meta property="og:title" content="DK &amp; Co"> ..."externalId":"$ID"... "content":"1.2M subscribers" """
        assertEquals(ID, YtChannel.pageChannelId(html)); assertEquals("DK & Co", YtChannel.pageTitle(html)); assertEquals("1.2M subscribers", YtChannel.pageSubs(html))
        val xml = """<feed><title>DK</title><entry><yt:videoId>abc</yt:videoId><title>V1</title><published>2026-11-12T10:00:00+00:00</published>
            <media:starRating count="7" average="5"/><media:statistics views="321"/></entry></feed>"""
        val (t, vids) = YtChannel.parseRss(xml)
        assertEquals("DK", t); assertEquals("abc", vids[0].id); assertEquals(321L, vids[0].views); assertEquals(7L, vids[0].likes)
    }

    @Test fun text_andNumbers() {
        assertEquals("1.2 लाख", YtChannel.num(120000)); assertEquals("1.5 करोड़", YtChannel.num(15_000_000)); assertEquals("-", YtChannel.num(null))
        val r = YtChannel.Report("DK", ID, null, "1.2M subscribers", null, null, emptyList(), emptyList(), withKey = false)
        val s = YtChannel.text(r)
        assertTrue(s.contains("1.2M subscribers") && s.contains("YouTube Data API key"))
        assertTrue(com.dk.assistant.SecretNames.isSecret(YtChannel.KEY))
    }
}
