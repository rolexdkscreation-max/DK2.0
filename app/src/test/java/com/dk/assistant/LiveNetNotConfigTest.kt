package com.dk.assistant

import com.dk.assistant.inspector.InspectorCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 (DKI-C7025E, 67 बार "Critical crash"): server का नाम "generativelanguage.googleapis.com" -
 * उसमें "language" शब्द. नेट की गलती (ConnectException / UnknownHost) setup की बनावट (CONFIG) मानी जाती थी,
 * hi-IN हमेशा के लिए बंद होता था, और Inspector इसे crash बताता था.
 */
class LiveNetNotConfigTest {
    // DKI-C7025E के असली संदेश (लॉग से)
    private val connect = "ConnectException: Failed to connect to generativelanguage.googleapis.com/[2001:4860:4802:32::a]:443"
    private val connectCut = "ConnectException: Failed to connect to generativelanguage.googleapis.com/[2001:4"
    private val dns = "UnknownHostException: generativelanguage.googleapis.com का पता नहीं मिला (फ़ोन DNS + DoH दोनों)"
    private val abort = "SocketException: Software caused connection abort"
    private val m = "gemini-2.5-flash-native-audio-preview-12-2025"

    @Test fun netErrorsWithServerNameAreNet_notConfig() {
        for (r in listOf(connect, connectCut, dns, abort)) {
            assertEquals(r, LiveRace.Fail.NET, LiveRace.classify(0, r))
            assertTrue(r, LiveRace.isNetError(r))
        }
    }

    @Test fun scrubRemovesHostAndIp() {
        val s = LiveRace.scrub(connect)
        assertFalse(s, s.contains("language"))
        assertFalse(s, s.contains("2001:4860"))
        assertFalse(LiveRace.scrub(dns).contains("language"))
    }

    @Test fun realSetupErrorsStillConfig() {
        assertEquals(LiveRace.Fail.CONFIG, LiveRace.classify(1007, "language_code is not supported for this model"))
        assertEquals(LiveRace.Fail.CONFIG, LiveRace.classify(1007, "Voice name Sulafat is not supported"))
        assertEquals(LiveRace.Fail.CONFIG, LiveRace.classify(1007, "Request contains an invalid argument."))
        assertEquals(LiveRace.Fail.KEY, LiveRace.classify(1007, "API key not valid. Please pass a valid API key."))
        assertFalse(LiveRace.isNetError("Request contains an invalid argument."))
    }

    @Test fun netErrorNeverTurnsOffHindiOrVoice() {
        val e = LiveVoice.start(m, "Sulafat", emptySet())
        for (r in listOf(connect, connectCut, dns, abort)) assertNull(r, LiveVoice.degrade(m, e, r, 0))
        // असली languageCode गलती पर अब भी hi-IN बंद होता है
        val l = LiveVoice.degrade(m, e, "language_code is not supported for this model", 1007)!!
        assertFalse(l.next.lang); assertEquals("$m|lang", l.offEntry)
    }

    @Test fun oneTimeRepairDropsOnlyLangEntries() {
        val off = setOf("$m|lang", "x|lang", "$m|aff", "$m|voice:Sulafat")
        assertEquals(setOf("$m|aff", "$m|voice:Sulafat"), LiveVoice.dropLangOff(off))
        assertTrue(LiveVoice.dropLangOff(emptySet()).isEmpty())
    }

    @Test fun inspectorReportsNetNotCrash() {
        val lines = listOf(
            "दौड़: [$m • key #3] 81 ms - setup की बनावट नहीं ली (code=0 $connectCut) - ठीक करके फिर",
            "दौड़: [$m • key #2] 84 ms - connection की गलती (code=0 $connectCut) - यह जोड़ी 2 मिनट नहीं, अगली तुरंत",
            "दौड़: [$m • key #2] 36 ms - connection की गलती (code=0 $dns) - यह जोड़ी 2 मिनट नहीं, अगली तुरंत",
        )
        for (l in lines) {
            val r = InspectorCore.ruleFor(l)!!
            assertEquals(l, "live_net", r.id)
            assertNotEquals(InspectorCore.Component.CRASH, r.component)
            assertEquals(InspectorCore.Severity.LOW, r.severity)
        }
        // असली exception अब भी crash
        assertEquals("crash", InspectorCore.ruleFor("कुछ java.lang.IllegalStateException: boom")!!.id)
    }

    @Test fun manyNetLinesNeverBecomeCritical() {
        val log = (0 until 20).joinToString("\n") { i ->
            "07:53:${(10 + i).toString().padStart(2, '0')}.000 दौड़: [$m • key #1] 84 ms - connection की गलती (code=0 $connectCut) - यह जोड़ी 2 मिनट नहीं, अगली तुरंत"
        }
        val f = InspectorCore.analyze(InspectorCore.parseLog(log))
        assertEquals(1, f.size)
        assertEquals("live_net", f[0].rule.id)
        assertTrue(f[0].severity.rank < InspectorCore.Severity.HIGH.rank)
    }
}
