package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 22 नवंबर 2026 (मालिक): हिंदी/इंग्लिश सेट = सिर्फ़ हिंदी-इंग्लिश; दूसरी भाषा दो बार -> DK पूछे -> हाँ पर ही बदले */
class LangLockTest {
    @Test fun lockOnlyForHindiEnglishModes() {
        assertTrue(LangLock.locked(Lang.HINGLISH)); assertTrue(LangLock.locked(Lang.HINDI)); assertTrue(LangLock.locked(Lang.ENGLISH))
        assertFalse(LangLock.locked(Lang.AUTO)); assertFalse(LangLock.locked(Lang.MARATHI)); assertFalse(LangLock.locked(Lang.BHOJPURI))
    }

    @Test fun promptHasLockRule() {
        for (c in listOf(Lang.HINGLISH, Lang.HINDI, Lang.ENGLISH)) {
            val r = Lang.rule(c)
            assertTrue(r.contains("LANGUAGE LOCK")); assertTrue(r.contains("two turns")); assertTrue(r.contains("क्या आप <भाषा> में बात करना चाहते हैं?"))
            assertTrue(r.contains("only after a clear yes"))
        }
        assertFalse(Lang.rule(Lang.AUTO).contains("LANGUAGE LOCK"))
        assertFalse(Lang.rule(Lang.MARATHI).contains("LANGUAGE LOCK"))
    }

    @Test fun detectsForeign() {
        assertEquals("urdu", LangLock.foreign("آپ کیسے ہیں"))
        assertEquals("bengali", LangLock.foreign("আমি ভালো আছি"))
        assertEquals("tamil", LangLock.foreign("வணக்கம் நண்பா"))
        assertEquals("marathi", LangLock.foreign("ठीक आहे, मी अलार्म लावला आहे"))
        assertEquals("bhojpuri", LangLock.foreign("हम कर देत बानी, रउआ का चाहीं"))
        assertNull(LangLock.foreign("ठीक है सर, मैंने alarm set कर दिया"))
        assertNull(LangLock.foreign("Okay sir, I opened YouTube"))
        assertNull(LangLock.foreign("वह अभी आता है"))
        assertNull(LangLock.foreign(""))
    }

    @Test fun detectsAsk() {
        assertEquals("marathi", LangLock.asked("मराठी में बात करो"))
        assertEquals("tamil", LangLock.asked("please speak in tamil"))
        assertEquals("bhojpuri", LangLock.asked("bhojpuri me bolo"))
        assertNull(LangLock.asked("तमिलनाडु के बारे में बताओ"))
        assertNull(LangLock.asked("tamil song youtube pe chalao"))
        assertTrue(LangLock.backHome("हिंदी में बोलो"))
        assertTrue(LangLock.yes("हाँ")); assertTrue(LangLock.yes("haan ji")); assertFalse(LangLock.yes("नहीं"))
    }

    @Test fun oneForeignTurnNeverSwitches() {
        val st = LangLock.State()
        // यूज़र एक बार मराठी लहजे में बोला, DK मराठी में चला गया = वापस हिंदी का note
        val n = LangLock.turn(st, Lang.HINGLISH, "मला अलार्म लाव", "ठीक आहे, मी अलार्म लावला आहे")
        assertNotNull(n); assertTrue(n.orEmpty().contains("by mistake")); assertTrue(n.orEmpty().contains("Hindi/Hinglish"))
        assertNull(st.approved)
    }

    @Test fun hindiReplyNoNote() {
        val st = LangLock.State()
        assertNull(LangLock.turn(st, Lang.HINGLISH, "alarm laga do", "ठीक है सर, alarm set कर दिया"))
        assertNull(LangLock.turn(st, Lang.HINGLISH, "मला अलार्म लाव", "ठीक है सर, alarm लगा दिया"))
    }

    @Test fun twiceThenAskThenYes() {
        val st = LangLock.State()
        assertNull(LangLock.turn(st, Lang.HINGLISH, "मराठी में बात करो", "ठीक है सर, बताइए"))
        val n = LangLock.turn(st, Lang.HINGLISH, "मराठी में बोलो ना", "जी सर")
        assertNotNull(n); assertTrue(n.orEmpty().contains("क्या आप मराठी में बात करना चाहते हैं?"))
        assertEquals("marathi", st.askedFor)
        // यूज़र हाँ बोला -> अब मराठी चलेगी (कोई note नहीं)
        assertNull(LangLock.turn(st, Lang.HINGLISH, "हाँ", "ठीक आहे, आता मी मराठीत बोलतो"))
        assertEquals("marathi", st.approved)
        // हिंदी में वापस
        LangLock.turn(st, Lang.HINGLISH, "हिंदी में बोलो", "ठीक है सर")
        assertNull(st.approved)
    }

    @Test fun twiceThenNoStaysHindi() {
        val st = LangLock.State()
        LangLock.turn(st, Lang.HINDI, "speak in tamil", "ठीक है")
        LangLock.turn(st, Lang.HINDI, "speak in tamil please", "क्या आप तमिल में बात करना चाहते हैं?")
        assertEquals("tamil", st.askedFor)
        LangLock.turn(st, Lang.HINDI, "नहीं", "ठीक है, हिंदी में ही बात करते हैं")
        assertNull(st.approved)
        val n = LangLock.turn(st, Lang.HINDI, "kya haal hai", "வணக்கம் நண்பா")
        assertNotNull(n)
    }

    @Test fun autoModeUntouched() {
        val st = LangLock.State()
        assertNull(LangLock.turn(st, Lang.AUTO, "मला अलार्म लाव", "ठीक आहे, मी अलार्म लावला आहे"))
    }

    @Test fun wiredInLiveTurn() {
        val src = java.io.File("src/main/java/com/dk/assistant/GeminiLiveTurn.kt").let { if (it.exists()) it else java.io.File("app/" + it.path) }.readText()
        assertTrue(src.contains("LangLock.turn(langLock, Lang.current, u, r)"))
    }
}
