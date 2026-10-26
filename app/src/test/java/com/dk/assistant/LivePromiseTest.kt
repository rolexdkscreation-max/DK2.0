package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.75: Live "सर्च करके बताता हूँ" बोलकर रुक जाए तो फ़ोन खुद सर्च / काम करवाए */
class LivePromiseTest {
    @Test fun searchPromiseOnQuestion() {
        assertEquals(LivePromise.Kind.SEARCH, LivePromise.kind("एक सेकंड, सर्च करके बताता हूँ।", "आज नासिक का मौसम कैसा है"))
        assertEquals(LivePromise.Kind.SEARCH, LivePromise.kind("1 मिनट, देख के बताता हूँ", "पेट्रोल का रेट क्या है"))
        assertEquals(LivePromise.Kind.SEARCH, LivePromise.kind("Let me check that for you.", "who won the match"))
    }

    @Test fun actionPromiseOnPhoneTask() {
        assertEquals(LivePromise.Kind.ACTION, LivePromise.kind("हाँ, कर रहा हूँ", "क्रोम पे कोई वेबसाइट सर्च करो"))
        assertEquals(LivePromise.Kind.ACTION, LivePromise.kind("ठीक है, खोल रहा हूँ", "chrome kholo aur amazon search karo"))
    }

    @Test fun realAnswerIsNotPromise() {
        assertEquals(LivePromise.Kind.NONE, LivePromise.kind("आज नासिक में धूप रहेगी, तापमान 31 डिग्री।", "मौसम कैसा है"))
        assertEquals(LivePromise.Kind.NONE, LivePromise.kind("", "कुछ भी"))
        val long = "एक सेकंड, " + "भारत की राजधानी नई दिल्ली है और यह बहुत बड़ा शहर है। ".repeat(5)
        assertEquals(LivePromise.Kind.NONE, LivePromise.kind(long, "भारत की राजधानी"))
    }

    @Test fun hintsShowWhatDkIsDoing() {
        assertTrue(LivePromise.hintFor("web_search", "नासिक मौसम").startsWith("इंटरनेट पर खोज रहा है"))
        assertTrue(LivePromise.hintFor("phone_task", "क्रोम खोलो").contains("फ़ोन पर काम"))
        assertEquals("", LivePromise.hintFor("remember_fact"))
    }

    @Test fun notesAreHonest() {
        val n = LivePromise.searchNote("मौसम", "धूप है")
        assertTrue(n.contains("धूप है")); assertTrue(n.contains("Do NOT say you will search again"))
        assertTrue(LivePromise.actionNote("क्रोम खोलो").contains("phone_task"))
        assertFalse(LivePromise.searchFailNote("x").contains("RESULT"))
    }
}
