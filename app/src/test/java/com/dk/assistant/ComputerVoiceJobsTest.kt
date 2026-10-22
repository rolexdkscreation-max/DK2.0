package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** आवाज़ से DK कंप्यूटर + restart के बाद भी चलने वाले तय काम */
class ComputerVoiceJobsTest {

    // ---------------------------------------------------------------- आवाज़ से काम पहचानना
    @Test fun namedComputerTask() {
        val c = ComputerVoice.parse("डीके कंप्यूटर, नासिक के 5 अच्छे स्कूल खोजो")!!
        assertFalse(c.open)
        assertEquals("नासिक के 5 अच्छे स्कूल खोजो", c.task)
        assertEquals("मौसम बताओ", ComputerVoice.parse("कंप्यूटर से मौसम बताओ")!!.task)
        assertEquals("prime numbers jodo", ComputerVoice.parse("DK Computer prime numbers jodo")!!.task)
    }

    @Test fun openOnly() {
        assertTrue(ComputerVoice.parse("डीके कंप्यूटर खोलो")!!.open)
        assertTrue(ComputerVoice.parse("dk computer")!!.open)
    }

    @Test fun researchAndMakeFileWithoutName() {
        val g = "भारत के 5 बड़े शहरों पर रिसर्च करके PDF बनाओ"
        assertEquals(g, ComputerVoice.parse(g)!!.task)
        assertEquals("मोबाइल की कीमतें खोजकर टेबल बना दो", ComputerVoice.parse("मोबाइल की कीमतें खोजकर टेबल बना दो")!!.task)
    }

    @Test fun normalCommandsStayNormal() {
        assertNull(ComputerVoice.parse("YouTube खोलो"))
        assertNull(ComputerVoice.parse("आज का मौसम क्या है"))
        assertNull(ComputerVoice.parse("डाउनलोड वाली फ़ाइल खोजो"))       // बनाओ नहीं = फ़ोन का काम
        assertNull(ComputerVoice.parse("रिसर्च करो कि चाँद कितना दूर है")) // सिर्फ़ जवाब बोलना = पुराना रास्ता
    }

    @Test fun yesNoForPermission() {
        assertEquals(true, ComputerVoice.yesNo("हाँ"))
        assertEquals(true, ComputerVoice.yesNo("हां भेज दो।"))
        assertEquals(false, ComputerVoice.yesNo("नहीं"))
        assertEquals(false, ComputerVoice.yesNo("रहने दो"))
        assertNull(ComputerVoice.yesNo("हाँ पर पहले मौसम बताओ"))
    }

    @Test fun spokenIsShortAndClean() {
        val t = "## नतीजा\n**नासिक** में आज 28°C है। हल्की बारिश हो सकती है। शाम तक बादल रहेंगे।\n" +
            "| शहर | तापमान |\n|---|---|\n- स्रोत: [IMD](https://imd.gov.in) https://x.y/z\n" + "बहुत लंबा वाक्य ".repeat(40)
        val s = ComputerVoice.spoken(t, files = 1)
        assertTrue(s, s.startsWith("नतीजा नासिक में आज 28°C है।"))
        assertFalse(s, s.contains("**") || s.contains("http") || s.contains("|") || s.contains("#"))
        assertTrue(s, s.endsWith("1 फ़ाइल बनी है, DK कंप्यूटर स्क्रीन में देखें।"))
        assertTrue(s, s.length < 380)
        assertEquals("DK कंप्यूटर ने काम पूरा कर दिया।", ComputerVoice.spoken("", 0))
        assertTrue(ComputerVoice.spoken("कौन सा शहर?", 0, question = true).contains("डीके कंप्यूटर, और फिर अपना जवाब"))
    }

    // ---------------------------------------------------------------- तय काम (restart के बाद भी)
    @Test fun jobsRoundTrip() {
        val jobs = listOf(ComputerJobs.Job(20000, 1_000L, "पानी पीने की याद"), ComputerJobs.Job(20001, 2_000L, "मौसम \"देखो\""))
        assertEquals(jobs, ComputerJobs.decode(ComputerJobs.encode(jobs)))
        assertTrue(ComputerJobs.decode(null).isEmpty())
        assertTrue(ComputerJobs.decode("टूटा").isEmpty())
        assertTrue(ComputerJobs.decode("[{\"id\":1,\"at\":0,\"task\":\"x\"},{\"id\":2,\"at\":5,\"task\":\"\"}]").isEmpty())
    }

    @Test fun nextIdIsFreeAndWraps() {
        assertEquals(20000, ComputerJobs.nextId(emptyList()))
        assertEquals(20006, ComputerJobs.nextId(listOf(ComputerJobs.Job(20005, 1, "a"))))
        assertEquals(20000, ComputerJobs.nextId(listOf(ComputerJobs.Job(29999, 1, "a"))))
        assertEquals(20002, ComputerJobs.nextId(listOf(ComputerJobs.Job(29999, 1, "a"), ComputerJobs.Job(20000, 1, "b"), ComputerJobs.Job(20001, 1, "c"))))
    }

    @Test fun planAfterRestart() {
        val now = 10_000_000_000L
        val future = ComputerJobs.Job(20000, now + 3_600_000L, "शाम का काम")
        val missed = ComputerJobs.Job(20001, now - 600_000L, "निकल गया")
        val tooOld = ComputerJobs.Job(20002, now - 8 * 24 * 3600_000L, "बहुत पुराना")
        val p = ComputerJobs.plan(listOf(future, missed, tooOld), now)
        assertEquals(2, p.size)
        assertEquals(future.at, p[0].fireAt); assertFalse(p[0].late)
        assertEquals(now + ComputerJobs.LATE_DELAY, p[1].fireAt); assertTrue(p[1].late)
    }
}
