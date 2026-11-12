package com.dk.assistant.insights

import org.junit.Assert.*
import org.junit.Test

/** नवंबर 2026: रिव्यू के असली आँकड़े सेव - बाद में "कितने फ़ॉलोअर / लास्ट वीडियो / सबसे पॉपुलर" का तुरंत जवाब */
class InsightsFactsTest {
    private val yt = PlatformData(Platform.YOUTUBE, listOf(
        Post(Platform.YOUTUBE, "नया वीडियो", date = "2026-11-10", views = 1200L),
        Post(Platform.YOUTUBE, "हिट वीडियो", date = "2026-10-01", views = 95000L),
    ), Account(followers = 5400L))

    @Test fun saveAndAnswer() {
        val j = InsightsFacts.toJson(listOf(yt), 1_000L)
        val s = InsightsFacts.fromJson(j)!!
        assertEquals(1, s.data.size)
        assertEquals(5400L, s.data[0].account.followers)
        val a = InsightsFacts.answer(s, emptyList(), 1_000L + 60_000L)
        assertTrue(a.startsWith("SAVED REVIEW NUMBERS"))
        assertTrue(a.contains("हिट वीडियो"))
        assertTrue(a.contains("सबसे पॉपुलर"))
        assertEquals("हिट वीडियो", InsightsFacts.top(yt.posts)?.title)
    }

    @Test fun noSavedOrOtherPlatform() {
        assertTrue(InsightsFacts.answer(null, emptyList(), 0L).startsWith("NO SAVED NUMBERS"))
        val s = InsightsFacts.fromJson(InsightsFacts.toJson(listOf(yt), 0L))
        assertTrue(InsightsFacts.answer(s, listOf(Platform.INSTAGRAM), 0L).startsWith("NO SAVED NUMBERS"))
        assertNull(InsightsFacts.fromJson("bad"))
    }

    @Test fun freshAndKeep() {
        val s = InsightsFacts.Saved(0L, listOf(yt))
        assertTrue(InsightsFacts.fresh(s, InsightsFacts.FRESH_MS - 1))
        assertFalse(InsightsFacts.fresh(s, InsightsFacts.FRESH_MS + 1))
        val ig = PlatformData(Platform.INSTAGRAM, account = Account(followers = 10L))
        assertEquals(2, InsightsFacts.keep(s, listOf(ig)).size)
        assertTrue(InsightsFacts.answer(s, emptyList(), InsightsFacts.FRESH_MS + 1).contains("older than 6 hours"))
    }

    @Test fun skipScreenWhenApiEnough() {
        val many = PlatformData(Platform.YOUTUBE, (1..8).map { Post(Platform.YOUTUBE, "v$it", views = it * 10L) }, Account(followers = 1L))
        assertFalse(InsightsFacts.screenNeeded(many, "यूट्यूब रिव्यू करो"))
        assertTrue(InsightsFacts.screenNeeded(many, "मोनेटाइजेशन भी देखो"))
        assertTrue(InsightsFacts.screenNeeded(yt, "रिव्यू"))
    }

    @Test fun backgroundTruth() {
        assertEquals(InsightsTruth.Fix.NONE, InsightsTruth.check("बैकग्राउंड में रिव्यू करो", "ठीक है, बैकग्राउंड में कर रही हूँ", true, true, bg = true))
        assertTrue(InsightsTruth.bgReply("", false).contains("NOT opening any app"))
        assertTrue(InsightsTruth.bgImpossible("Instagram", "2 घंटे पहले").contains("action=ask"))
    }
}
