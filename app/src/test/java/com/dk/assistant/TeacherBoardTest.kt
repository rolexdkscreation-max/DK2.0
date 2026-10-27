package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TeacherBoardTest {
    @Before fun clean() { TeacherBoard.reset(); TeacherBoard.open = false }

    @Test fun studyQuestionsAreDetected() {
        assertTrue(TeacherBoard.isStudy("सूरज क्यों उगता है"))
        assertTrue(TeacherBoard.isStudy("25 का 18 प्रतिशत निकालो"))
        assertTrue(TeacherBoard.isStudy("टीचर मोड"))
        assertTrue(TeacherBoard.isStudy("मुझे प्रकाश संश्लेषण समझाओ"))
        assertTrue(TeacherBoard.isStudy("पढ़ाओ"))
        assertTrue(TeacherBoard.isStudy("न्यूटन का दूसरा नियम क्या है"))
        assertTrue(TeacherBoard.isStudy("12 x 15 कितना होगा"))
    }

    @Test fun nonStudyIsNotDetected() {
        assertFalse(TeacherBoard.isStudy("तुम क्यों नाराज़ हो"))
        assertFalse(TeacherBoard.isStudy("आज का मौसम कैसा है"))
        assertFalse(TeacherBoard.isStudy("अलार्म लगाओ 7 बजे"))
        assertFalse(TeacherBoard.isStudy("वॉल्यूम 50 प्रतिशत करो"))
        assertFalse(TeacherBoard.isStudy("कैसी हो"))
        assertFalse(TeacherBoard.isStudy("आज की ताज़ा ख़बर क्या है"))
        assertFalse(TeacherBoard.isStudy("टीचर मोड बंद करो"))
    }

    @Test fun exitWords() {
        assertTrue(TeacherBoard.isExit("बंद करो"))
        assertTrue(TeacherBoard.isExit("वापस जाओ"))
        assertTrue(TeacherBoard.isExit("टीचर मोड बंद करो"))
        assertFalse(TeacherBoard.isExit("टॉर्च बंद करो"))
        assertFalse(TeacherBoard.isExit("सूरज क्यों उगता है"))
    }

    @Test fun opensItselfAndWritesWhatDkSays() {
        // Live: आधी बात "…" में आती है, पूरी बात जवाब के बाद
        assertEquals(TeacherBoard.Action.OPEN, TeacherBoard.onUser("सूरज क्यों उगता…"))
        TeacherBoard.open = true
        TeacherBoard.onReply("सूरज असल में उगता नहीं है।", final = false)
        TeacherBoard.onReply("सूरज असल में उगता नहीं है। पृथ्वी अपनी धुरी पर घूमती है।", final = true)
        assertEquals(TeacherBoard.Action.NONE, TeacherBoard.onUser("सूरज क्यों उगता है"))
        val s = TeacherBoard.sections()
        assertEquals(1, s.size)
        assertEquals("सूरज क्यों उगता है", s[0].question)
        assertTrue(s[0].answer.contains("धुरी"))
        // बीच में काम - पन्ने पर नहीं लिखा जाता, पन्ना खुला रहता है
        assertEquals(TeacherBoard.Action.NONE, TeacherBoard.onUser("अलार्म लगा दो 6 बजे का"))
        TeacherBoard.onReply("हो गया।", final = true)
        assertEquals(1, TeacherBoard.sections().size)
        assertEquals(TeacherBoard.Action.CLOSE, TeacherBoard.onUser("वापस जाओ"))
    }

    @Test fun closedBoardOpensForMath() {
        TeacherBoard.open = false
        assertEquals(TeacherBoard.Action.OPEN, TeacherBoard.onUser("25 का 18 प्रतिशत निकालो"))
    }

    @Test fun mathIsWrittenStepByStep() {
        val l = TeacherBoard.format("25 का 18 प्रतिशत निकालते हैं। चरण 1: प्रतिशत का मतलब है सौ में से। चरण 2: 18 बटा 100 गुणा 25। चरण 3: 450 बटा 100 = 4.5। उत्तर: 4.5")
        val steps = l.filter { it.kind == TeacherBoard.Kind.STEP && it.text.startsWith("चरण") }
        assertEquals(3, steps.size)
        assertTrue(steps[2].text.contains("4.5"))
        assertEquals(TeacherBoard.Kind.ANSWER, l.last().kind)
        assertEquals("उत्तर: 4.5", l.last().text)
    }

    @Test fun otherSubjectsHeadingAndPoints() {
        val l = TeacherBoard.format("सूरज का उगना। सूरज असल में नहीं उगता। पृथ्वी पश्चिम से पूरब घूमती है। इसलिए सुबह सूरज पूरब में दिखता है।")
        assertEquals(TeacherBoard.Kind.HEADING, l[0].kind)
        assertEquals(3, l.count { it.kind == TeacherBoard.Kind.POINT })
    }

    @Test fun decimalsAreNotSplit() {
        assertEquals(listOf("मान 4.5 है।", "ठीक?"), TeacherBoard.sentences("मान 4.5 है। ठीक?"))
    }
}

class WorkChatModeTest {
    @Test fun modes() {
        assertEquals(WorkChatMode.Mode.WORK, WorkChatMode.classify("अलार्म लगाओ"))
        assertEquals(WorkChatMode.Mode.WORK, WorkChatMode.classify("राहुल को मैसेज भेजो"))
        assertEquals(WorkChatMode.Mode.WORK, WorkChatMode.classify("रुको"))
        assertEquals(WorkChatMode.Mode.CHAT, WorkChatMode.classify("आज तो बहुत थक गया यार"))
        assertEquals(WorkChatMode.Mode.CHAT, WorkChatMode.classify("तुम कैसी हो"))
        assertEquals(WorkChatMode.Mode.MIXED, WorkChatMode.classify("यार आज मूड ख़राब है, एक अलार्म लगा दो"))
    }
    @Test fun promptKeepsPersonaAndHasRules() {
        val b = WorkChatMode.block(live = true)
        assertTrue(b.contains("do NOT change your personality"))
        assertTrue(b.contains("phone_task ONCE"))
        assertTrue(b.contains("masculine"))
        assertFalse(WorkChatMode.block(live = false).contains("phone_task ONCE"))
    }
}

class SearchGuardTest {
    @Test fun liveUsesItsOwnKeyFirstThenChatKey() {
        assertEquals(listOf("LIVE", "CHAT"), SearchGuard.keys(true, "CHAT", "LIVE"))
        assertEquals(listOf("CHAT", "LIVE"), SearchGuard.keys(false, "CHAT", "LIVE"))
        // पहले की गड़बड़: चैट की Gemini key खाली = Live की खोज बिना key
        assertEquals(listOf("LIVE"), SearchGuard.keys(true, "", "LIVE"))
        assertEquals(listOf("K"), SearchGuard.keys(true, "K", "K"))
        assertTrue(SearchGuard.keys(true, " ", "").isEmpty())
    }
    @Test fun classify() {
        assertEquals(SearchGuard.Fail.BAD_KEY, SearchGuard.classify(400, "API key not valid. Please pass a valid API key."))
        assertEquals(SearchGuard.Fail.NO_KEY, SearchGuard.classify(403, "Method doesn't allow unregistered callers (callers without established identity). Please use API Key"))
        assertEquals(SearchGuard.Fail.QUOTA, SearchGuard.classify(429, "Resource has been exhausted (e.g. check quota)."))
        assertEquals(SearchGuard.Fail.NETWORK, SearchGuard.classify(0, "Unable to resolve host generativelanguage.googleapis.com"))
        assertEquals(SearchGuard.Fail.TIMEOUT, SearchGuard.classify(0, "timeout"))
        assertTrue(SearchGuard.tryNextKey(SearchGuard.Fail.QUOTA))
        assertFalse(SearchGuard.tryNextKey(SearchGuard.Fail.NETWORK))
    }
    @Test fun failMessageNeverSaysTool() {
        val r = SearchGuard.failResult("आज की ताज़ा ख़बर", SearchGuard.Fail.QUOTA)
        assertTrue(r.contains(SearchGuard.USER_LINE))
        assertTrue(r.contains("NEVER mention a tool"))
        assertFalse(SearchGuard.USER_LINE.contains("टूल"))
        assertEquals("", SearchGuard.sourcesLine("", listOf("a" to "b")))
    }
}
