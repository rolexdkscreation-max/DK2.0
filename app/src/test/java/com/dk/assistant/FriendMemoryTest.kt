package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** v3.45: सच्चा दोस्त - याददाश्त, निजी जानकारी कभी नहीं, त्योहार, खुद बात शुरू करना, मौसम */
class FriendMemoryTest {
    private val H = 3_600_000L
    private val D = 24 * H

    // ---------------- निजी जानकारी
    @Test fun sensitiveNeverSaved() {
        assertTrue(Sensitive.isSensitive("मेरा पासवर्ड abc123 है"))
        assertTrue(Sensitive.isSensitive("कार्ड नंबर 4111 1111 1111 1111"))
        assertTrue(Sensitive.isSensitive("आधार 1234 5678 9012"))
        assertTrue(Sensitive.isSensitive("PAN ABCDE1234F"))
        assertTrue(Sensitive.isSensitive("my upi pin is 4455"))
        assertTrue(Sensitive.isSensitive("OTP 556677 आया"))
        assertFalse(Sensitive.isSensitive("मेरी बहन का नाम प्रिया है"))
        assertFalse(Sensitive.isSensitive("मेरा मोबाइल 9876543210"))
        assertFalse(Sensitive.isSensitive("बाइक का नंबर MH12 AB 1234"))
        assertFalse(Sensitive.isSensitive("मेरा पिनकोड 422001 है"))
    }

    @Test fun redactKeepsTheRest() {
        assertEquals("कल इंटरव्यू है।", Sensitive.redact("कल इंटरव्यू है। मेरा पासवर्ड xyz है।"))
        assertEquals("खाते में पैसे डाले: [नंबर हटाया]", Sensitive.redact("खाते में पैसे डाले: 123456789012"))
    }

    // ---------------- याददाश्त
    @Test fun sessionSavesTimeSummaryAndLastSeen() {
        val b = FriendBook()
        b.addSession(1000, 1000 + 12 * 60_000, "बॉस ने DK ऐप पर काम किया। कार्ड 4111 1111 1111 1111", "खुश")
        assertEquals(1000 + 12 * 60_000L, b.lastSeen)
        assertEquals(12L, b.sessions[0].minutes)
        assertFalse(b.sessions[0].summary.contains("4111"))
        assertEquals("खुश", b.mood)
        val back = FriendBook.fromJson(b.toJson())
        assertEquals(b.sessions, back.sessions)
        assertEquals(b.lastSeen, back.lastSeen)
        assertEquals("खुश", back.mood)
    }

    @Test fun keepsLimitedSessions() {
        val b = FriendBook()
        repeat(FriendBook.MAX_SESSIONS + 5) { b.addSession(it.toLong(), it + 1L, "बात $it") }
        assertEquals(FriendBook.MAX_SESSIONS, b.sessions.size)
        assertEquals("बात ${FriendBook.MAX_SESSIONS + 4}", b.sessions.last().summary)
    }

    @Test fun unfinishedThreadsOpenAndResolve() {
        val b = FriendBook()
        b.updateThreads(listOf("कल इंटरव्यू है", "माँ की तबियत ठीक नहीं थी", "मेरा पासवर्ड 1234"), emptyList(), 10 * D)
        assertEquals(2, b.threads.size)   // पासवर्ड वाली बात नहीं
        b.updateThreads(emptyList(), listOf("इंटरव्यू हो गया"), 11 * D)
        assertEquals(listOf("माँ की तबियत ठीक नहीं थी"), b.threads.map { it.text })
        // 30 दिन पुरानी अपने-आप हटें
        b.updateThreads(emptyList(), emptyList(), 50 * D)
        assertTrue(b.threads.isEmpty())
    }

    @Test fun forgetRemovesEverywhere() {
        val b = FriendBook()
        b.addSession(0, 60_000, "बॉस ने इंटरव्यू की बात की। फिर क्रिकेट मैच देखा।")
        b.updateThreads(listOf("कल इंटरव्यू है"), emptyList(), 0)
        val n = b.forget("इंटरव्यू")
        assertTrue(n >= 2)
        assertTrue(b.threads.isEmpty())
        assertEquals("फिर क्रिकेट मैच देखा।", b.sessions[0].summary)
        assertEquals(0, b.forget(""))
    }

    @Test fun gapLikeAHuman() {
        val b = FriendBook()
        assertEquals("पहली मुलाकात", b.gapText(5))
        b.lastSeen = 0L + 1
        assertEquals("अभी थोड़ी देर पहले", b.gapText(1 + 5 * 60_000))
        assertEquals("5 घंटे पहले", b.gapText(1 + 5 * H))
        assertEquals("3 दिन पहले", b.gapText(1 + 3 * D + H))
    }

    @Test fun promptBlockHasLastMeetingSummaryAndThreads() {
        val b = FriendBook()
        b.addSession(1_700_000_000_000, 1_700_000_600_000, "बॉस ने DK 2.0 के बारे में बताया", "उत्साहित")
        b.updateThreads(listOf("कल इंटरव्यू है"), emptyList(), 1_700_000_600_000)
        val p = b.promptBlock(1_700_000_600_000 + 3 * D, ZoneId.of("Asia/Kolkata"))
        assertTrue(p.contains("Last time you talked"))
        assertTrue(p.contains("3 दिन पहले"))
        assertTrue(p.contains("DK 2.0"))
        assertTrue(p.contains("कल इंटरव्यू है"))
        assertTrue(p.contains("उत्साहित"))
        assertTrue(FriendBook().promptBlock(0).contains("first conversation"))
    }

    // ---------------- त्योहार
    @Test fun festivals() {
        assertEquals("दिवाली", Festivals.today(LocalDate.of(2026, 11, 8))?.name)
        assertEquals("नवरात्रि", Festivals.today(LocalDate.of(2026, 10, 15))?.name)
        assertEquals("होली", Festivals.today(LocalDate.of(2027, 3, 23))?.name)
        assertEquals("स्वतंत्रता दिवस", Festivals.today(LocalDate.of(2030, 8, 15))?.name)
        assertNull(Festivals.today(LocalDate.of(2026, 10, 24)))
        // पिछली बार 15 अक्टूबर, आज 23 अक्टूबर 2026 -> दशहरा निकल गया
        assertEquals("दशहरा", Festivals.missed(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 23))?.name)
        assertNull(Festivals.missed(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23)))
    }

    // ---------------- खुद बात शुरू करना
    @Test fun partOfDayGreetAndSulk() {
        assertEquals("सुबह", FriendStyle.partOfDay(8))
        assertEquals("दोपहर", FriendStyle.partOfDay(13))
        assertEquals("शाम", FriendStyle.partOfDay(18))
        assertEquals("रात", FriendStyle.partOfDay(23))
        assertFalse(FriendStyle.shouldGreet(10 * 60_000, 0, 5 * 60_000))
        assertTrue(FriendStyle.shouldGreet(30 * 60_000, 0, 5 * 60_000))
        assertEquals(0, FriendStyle.sulk(3 * H))
        assertEquals(1, FriendStyle.sulk(22 * H))
        assertEquals(2, FriendStyle.sulk(3 * D))
    }

    @Test fun openerSulksWishesAndAsksAboutUnfinished() {
        val now = java.time.LocalDateTime.of(2026, 11, 8, 19, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val b = FriendBook()
        b.addSession(now - 3 * D - H, now - 3 * D, "बॉस ने DK 2.0 पर काम किया")
        b.updateThreads(listOf("कल इंटरव्यू है"), emptyList(), now - 3 * D)
        val o = FriendStyle.opener(19, LocalDate.of(2026, 11, 8), b, now, "नाशिक — अभी 24°C, हल्की बारिश", searchLocalNews = true)
        assertTrue(o.contains("शाम"))
        assertTrue(o.contains("दिवाली"))
        assertTrue(o.contains("3 दिन बाद"))
        assertTrue(o.contains("कल इंटरव्यू है"))
        assertTrue(o.contains("नाशिक"))
        assertTrue(o.contains("Google Search"))
        assertTrue(o.contains("ONE or TWO questions"))
        // पहली मुलाकात
        assertTrue(FriendStyle.opener(9, LocalDate.of(2026, 10, 24), FriendBook(), now, null, false).contains("very first conversation"))
    }

    @Test fun summaryParse() {
        val raw = "```json\n{\"summary\":\"बॉस ने इंटरव्यू की तैयारी की\",\"mood\":\"घबराए\",\"facts\":[\"बॉस नाशिक में रहते हैं\",\"पासवर्ड 1234\"],\"open\":[\"कल इंटरव्यू है\"],\"done\":[]}\n```"
        val s = FriendStyle.parseSummary(raw)
        assertNotNull(s)
        assertEquals("घबराए", s!!.mood)
        assertEquals(listOf("बॉस नाशिक में रहते हैं"), s.facts)
        assertEquals(listOf("कल इंटरव्यू है"), s.open)
        assertNull(FriendStyle.parseSummary("no json"))
        assertEquals("बॉस ने कहा: यूट्यूब खोलो / आज बहुत थक गया", FriendStyle.fallbackSummary(listOf("हाँ", "यूट्यूब खोलो", "आज बहुत थक गया", "ok")))
    }

    // ---------------- मौसम
    @Test fun weatherInHindi() {
        val j = """{"current":{"temperature_2m":27.4,"apparent_temperature":31.2,"weather_code":2},
            "daily":{"weather_code":[63],"temperature_2m_max":[31.0],"temperature_2m_min":[21.5],"precipitation_probability_max":[70]}}"""
        assertEquals("अभी 27°C (महसूस 31°C), हल्के बादल; आज 21–31°C, दिन में बारिश, बारिश की संभावना 70%", WeatherText.describe(j))
        assertNull(WeatherText.describe("{}"))
        assertTrue(WeatherText.url(19.99, 73.79).contains("latitude=19.990&longitude=73.790"))
    }
}
