package com.dk.assistant.dkai

import com.dk.assistant.Mode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 13 नवंबर 2026 (मालिक): वॉइस-कॉल स्क्रीन - तीन हालत, टेक्स्ट बॉक्स, लाइव सर्च कार्ड */
class DkaiVoiceCallTest {
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").let { if (it.exists()) it else File("app/src/main/java/com/dk/assistant/$p") }.readText()

    @Test fun onlyThreeCallStates() {
        assertEquals(3, DkaiCore.Call.values().size)
        fun st(mode: Mode, live: Boolean, muted: Boolean, work: Boolean, conn: Boolean) = DkaiCore.callState(DkaiCore.voiceStatus(mode, live, muted, work, conn))
        assertEquals(DkaiCore.Call.LISTENING, st(Mode.LISTENING, true, false, false, true))
        assertEquals(DkaiCore.Call.SPEAKING, st(Mode.SPEAKING, true, false, false, true))
        assertEquals(DkaiCore.Call.PROCESSING, st(Mode.WORKING, true, false, true, true))
        assertEquals(DkaiCore.Call.PROCESSING, st(Mode.IDLE, true, false, false, false))   // जुड़ रहा
        assertEquals(DkaiCore.Call.LISTENING, st(Mode.LISTENING, true, true, false, true))  // म्यूट भी सुनने वाली हालत
        assertEquals("PROCESSING", DkaiCore.Call.PROCESSING.label)
        // पुराना बैज-लॉजिक वैसा ही
        assertEquals(DkaiCore.Status.MUTED, DkaiCore.voiceStatus(Mode.LISTENING, true, true, false, true))
    }

    @Test fun boxShowsOnlyWhoIsTalking() {
        val start = 1_000L
        // सुन रहा: यूज़र की लाइव बात (DK के आख़िरी जवाब के बाद वाली)
        var t = DkaiCore.callText(DkaiCore.Call.LISTENING, false, "आज का मौसम…", 5_000, "", "पुराना जवाब", 4_000, start)
        assertEquals("Listening…", t.title); assertEquals("आज का मौसम", t.body); assertFalse(t.placeholder)
        // यूज़र की बात DK के जवाब से पुरानी = नहीं दिखे
        t = DkaiCore.callText(DkaiCore.Call.LISTENING, false, "पुरानी बात", 3_000, "", "जवाब", 4_000, start)
        assertTrue(t.placeholder); assertEquals("Tap to stop", t.body)
        // बोलना खत्म = बॉक्स साफ़
        t = DkaiCore.callText(DkaiCore.Call.PROCESSING, false, "आज का मौसम", 5_000, "", "", 0, start)
        assertTrue(t.placeholder); assertEquals("Processing…", t.title)
        // DK बोल रहा = सिर्फ़ DK की बात
        t = DkaiCore.callText(DkaiCore.Call.SPEAKING, false, "आज का मौसम", 5_000, "आज धूप रहेगी", "", 0, start)
        assertEquals("Speaking…", t.title); assertEquals("आज धूप रहेगी", t.body)
        t = DkaiCore.callText(DkaiCore.Call.SPEAKING, false, "", 0, "", "", 0, start)
        assertEquals("Here's what I found for you", t.body)
        // म्यूट
        t = DkaiCore.callText(DkaiCore.Call.LISTENING, true, "कुछ…", 5_000, "", "", 0, start)
        assertEquals("Microphone is muted.", t.title)
    }

    @Test fun searchCardUsesRealSitesOneByOne() {
        DkaiSearchLive.cancel()
        assertFalse(DkaiSearchLive.active(10))
        DkaiSearchLive.found(listOf("aaj.tv"), 10)                       // बिना खोज शुरू हुए कुछ नहीं
        assertFalse(DkaiSearchLive.snap(10).on)
        DkaiSearchLive.start("pakistan news", 1_000)
        var s = DkaiSearchLive.snap(1_100)
        assertTrue(s.on); assertFalse(s.found); assertEquals(0, s.visited)
        DkaiSearchLive.found(listOf("https://www.aaj.tv/news/1", "www.youtube.com", "aaj.tv", "Wikipedia"), 2_000)
        s = DkaiSearchLive.snap(2_000)
        assertEquals(listOf("aaj.tv", "youtube.com", "Wikipedia"), s.sites)
        assertEquals(1, s.visited)
        assertEquals(2, DkaiSearchLive.snap(2_000 + DkaiSearchLive.STEP_MS).visited)
        assertEquals(3, DkaiSearchLive.snap(2_000 + DkaiSearchLive.STEP_MS * 10).visited)   // असली गिनती से ज़्यादा नहीं
        assertFalse(DkaiSearchLive.snap(1_000 + DkaiSearchLive.MAX_MS + 1).on)              // अटका कार्ड अपने-आप हटे
        DkaiSearchLive.cancel()
    }

    @Test fun wiredOnlyToRealWebSearch() {
        val tools = src("GeminiLiveTools.kt")
        assertTrue(tools.contains("DkaiSearchLive.start(q)"))
        assertTrue(tools.contains("DkaiSearchLive.found(Grounding.sites(res.sources))"))
        // सामान्य सवाल (खोज नहीं) पर कार्ड नहीं: start, skip वाली लाइन के बाद
        assertTrue(tools.indexOf("NO_SEARCH_NEEDED") < tools.indexOf("DkaiSearchLive.start(q)"))
        assertTrue(src("GeminiLiveChecks.kt").contains("DkaiSearchLive.start(user)"))
        assertFalse(src("dkai/DkaiBroadcast.kt").contains("DkaiSearchLive"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("DkaiSearchCard(c)"))
        val card = src("dkai/DkaiSearchCard.kt")
        for (k in listOf("\"Processing\"", "Searching", "Researching: Live Research…", "Exploring live web sources", "visited")) assertTrue(k, card.contains(k))
    }

    @Test fun callScreenParts() {
        val v = src("dkai/DkaiVoice.kt")
        assertTrue(v.contains("DkaiCore.callState(st)"))
        assertTrue(v.contains("DkaiIcon.Kind.CALL_END"))
        assertTrue(v.contains("bars = speaking"))
        assertTrue(v.contains("call = callInt"))
        assertTrue(v.contains("bargeIn(\"बॉक्स पर टैप\")"))
        val views = src("dkai/DkaiViews.kt")
        assertTrue(views.contains("fun flowWaves("))
        assertTrue(views.contains("p.color = if (muted) DkaiTheme.RED"))
        assertTrue(src("dkai/DkaiTheme.kt").contains("CALL_END("))
    }
}
