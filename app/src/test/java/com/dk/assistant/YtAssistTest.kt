package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 21 नवंबर 2026 (मालिक): YouTube वीडियो चलाओ + फुल स्क्रीन + ऐड आए तो स्किप - और बताओ क्या हुआ */
class YtAssistTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun extrasStripped() {
        val e = YtAssist.extras("डीके यूट्यूब पे मोटू पतलू का वीडियो चलाओ और उसे फुल स्क्रीन में कर देना")!!
        assertTrue(e.full); assertFalse(e.skipAd)
        assertEquals("डीके यूट्यूब पे मोटू पतलू का वीडियो चलाओ", e.text)
        val a = YtAssist.extras("यूट्यूब पर अरिजीत सिंह का गाना चलाओ, अगर कोई ऐड आ रहा है तो ऐड स्किप कर देना")!!
        assertTrue(a.skipAd); assertFalse(a.full)
        assertEquals("यूट्यूब पर अरिजीत सिंह का गाना चलाओ", a.text)
        val b = YtAssist.extras("play arijit singh songs on youtube in full screen and skip the ad")!!
        assertTrue(b.full && b.skipAd)
        assertEquals("play arijit singh songs on youtube", b.text)
        assertNull(YtAssist.extras("स्क्रीनशॉट लो"))
        assertNull(YtAssist.extras("यूट्यूब पर गाना चलाओ"))
        assertTrue(YtAssist.wantsFull("फुल स्क्रीन कर दो"))
    }

    @Test fun buttons() {
        assertTrue(YtAssist.isSkip("com.google.android.youtube:id/skip_ad_button", "Skip", null))
        assertTrue(YtAssist.isSkip(null, "विज्ञापन छोड़ें", null))
        assertFalse(YtAssist.isSkip("com.google.android.youtube:id/skip_ad_button", "5", null))
        assertTrue(YtAssist.isAd(null, "Ad · 0:15", null))
        assertFalse(YtAssist.isAd(null, "Adele - Hello", null))
    }

    @Test fun reportTellsTruth() {
        assertTrue(YtAssist.report(YtAssist.Ad.SKIPPED, true, true).contains("स्किप कर दिया"))
        assertTrue(YtAssist.report(YtAssist.Ad.NONE, false, false).contains("कोई ऐड नहीं आया"))
        assertTrue(YtAssist.report(YtAssist.Ad.NONE, true, false).contains("फुल स्क्रीन नहीं हो पाया"))
        val r = "PLAYING: x (verified: playing)" + YtAssist.report(YtAssist.Ad.SKIPPED, true, true)
        assertEquals("ऐड आया था - स्किप कर दिया, फुल स्क्रीन कर दिया", YtAssist.noteHi(r))
        assertEquals("", YtAssist.note("PLAYING: x"))
    }

    @Test fun wired() {
        assertTrue(src("DKAccessibilityService.kt").contains("r + ytAfterPlay(full)"))
        assertTrue(src("LocalRouter.kt").contains("ytExtras(raw, wakeWords, fg)"))
        assertTrue(src("LiveFast.kt").contains("YtAssist.note(r)"))
    }
}
