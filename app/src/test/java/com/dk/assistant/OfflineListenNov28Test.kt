package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 28 नवंबर 2026 (मालिक का लॉग): नेट गया तो UI अनुवाद हर ~7s सारे AI आज़माता रहा; जुड़ते ही DK ने पूँछ वाला
 * पुराना सवाल खुद खोजा; सत्र अटक कर टूटा तो आपकी बात नए connection पर गई ही नहीं.
 */
class OfflineListenNov28Test {
    private fun src(name: String): String =
        (File("src/main/java/com/dk/assistant/$name").takeIf { it.exists() } ?: File("app/src/main/java/com/dk/assistant/$name")).readText()

    @Test fun noInternetMeansNoAiStorm() {
        assertTrue(UiMt.offline(java.net.UnknownHostException("x")))
        assertTrue(UiMt.offline(java.net.ConnectException("x")))
        assertFalse("धीमा नेट = इंटरनेट है", UiMt.offline(java.net.SocketTimeoutException("x")))
        val s = src("UiMt.kt")
        assertTrue(s.contains("if (offline(e)) { webOffline = true; return out }"))
        assertTrue(s.indexOf("if (webOffline) {") in 1 until s.indexOf("got.putAll(ai(lang, left))"))
    }

    @Test fun tailIsOverAndOldAskIgnored() {
        val now = 1_000_000_000L
        val asked = listOf(HistoryOnDemand.Turn("d", "हाँ बोलिए", now - 60_000), HistoryOnDemand.Turn("u", "realme 12 pro plus का दाम बताओ", now - 30_000))
        val t = HistoryOnDemand.tail(asked, now)
        assertTrue(t.contains("already over") && t.contains("never answer, search or act on it again"))
        assertTrue(t.contains("realme 12 pro plus का दाम बताओ ${HistoryOnDemand.OLD_ASK}"))
        val answered = asked.reversed().mapIndexed { i, x -> x.copy(time = now - (2 - i) * 10_000L) }
        assertFalse("आख़िरी लाइन DK की = निशान नहीं", HistoryOnDemand.tail(answered, now).contains(HistoryOnDemand.OLD_ASK))
    }

    @Test fun stuckSessionResendsRescueText() {
        assertEquals("क्या कुछ बताओ", LiveRepeat.rescueFor(" क्या कुछ बताओ ", 1_000, 7_000))
        assertEquals("20s बाद नहीं", "", LiveRepeat.rescueFor("क्या कुछ बताओ", 1_000, 1_000 + LiveRepeat.RESCUE_KEEP_MS + 1))
        assertEquals("", LiveRepeat.rescueFor("क्या", 0, 7_000))
        val live = LiveSrc.all()
        assertTrue(live.contains(".ifBlank { LiveRepeat.rescueFor(rescueText, rescueAt, dropAt) }"))
        assertTrue(live.contains("if (ok) { stalledN++; repeatGuard.onResent(now); rescueText = t; rescueAt = now }"))
    }
}
