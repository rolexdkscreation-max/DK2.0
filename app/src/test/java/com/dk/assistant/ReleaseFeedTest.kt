package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.64: API की सीमा (403) पर releases.atom से अपडेट मिलना */
class ReleaseFeedTest {
    // github.com/rolexdkscreation-max/DK2.0/releases.atom का असली हिस्सा (छोटा किया)
    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-US">
  <title>Release notes from DK2.0</title>
  <entry>
    <id>tag:github.com,2008:Repository/1426798723/v169</id>
    <updated>2026-10-24T19:27:15Z</updated>
    <link rel="alternate" type="text/html" href="https://github.com/rolexdkscreation-max/DK2.0/releases/tag/v169"/>
    <title>DK 3.63</title>
    <content type="html">&lt;h2&gt;TYPE: software&lt;br&gt;
VERSION: 3.63&lt;/h2&gt;
&lt;p&gt;• DK 3.61: बैकग्राउंड गहरा ग्रेफ़ाइट (&lt;a class=&quot;issue-link&quot; href=&quot;https://github.com/rolexdkscreation-max/DK2.0/pull/77&quot;&gt;#77&lt;/a&gt;)&lt;/p&gt;</content>
  </entry>
  <entry>
    <id>tag:github.com,2008:Repository/1426798723/v167</id>
    <link rel="alternate" type="text/html" href="https://github.com/rolexdkscreation-max/DK2.0/releases/tag/v167"/>
    <title>DK 3.62</title>
    <content type="html">x</content>
  </entry>
</feed>"""

    @Test fun newestEntry() {
        val e = ReleaseFeed.parseAtom(feed)!!
        assertEquals("v169", e.tag)
        assertEquals("DK 3.63", e.title)
        assertEquals("https://github.com/rolexdkscreation-max/DK2.0/releases/tag/v169", e.page)
        val lines = e.body.lines()
        assertTrue(lines.contains("TYPE: software"))
        assertTrue(lines.contains("VERSION: 3.63"))
        assertTrue(e.body.contains("बैकग्राउंड गहरा ग्रेफ़ाइट (#77)"))
        assertFalse(e.body.contains("<"))
    }

    @Test fun apkUrlMatchesWorkflowAsset() {
        assertEquals("https://github.com/rolexdkscreation-max/DK2.0/releases/download/v169/DK.apk",
            ReleaseFeed.apkUrl(Updater.REPO, "v169"))
    }

    @Test fun badFeed() {
        assertNull(ReleaseFeed.parseAtom(""))
        assertNull(ReleaseFeed.parseAtom("<feed></feed>"))
        assertNull(ReleaseFeed.parseAtom("<html>rate limited</html>"))
    }

    @Test fun fallbackOnlyForLimitsAndServerErrors() {
        for (c in listOf(401, 403, 429, 500, 502, 503)) assertTrue(ReleaseFeed.shouldFallback(c))
        for (c in listOf(200, 304, 404, 422)) assertFalse(ReleaseFeed.shouldFallback(c))
    }

    @Test fun clearHindiReason() {
        assertTrue(ReleaseFeed.friendly(403).contains("थोड़ी देर"))
        assertTrue(ReleaseFeed.friendly(429).contains("थोड़ी देर"))
        assertEquals("इंटरनेट नहीं मिला", ReleaseFeed.friendly(0))
        assertTrue(ReleaseFeed.friendly(503).contains("503"))
    }
}
