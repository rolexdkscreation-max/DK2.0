package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReplyTest {
    @Test fun answers() {
        assertEquals(CallReply.ANSWER, CallReply.parse("हाँ उठाओ"))
        assertEquals(CallReply.ANSWER, CallReply.parse("कॉल उठा लो"))
        assertEquals(CallReply.ANSWER, CallReply.parse("हाँ"))
        assertEquals(CallReply.ANSWER, CallReply.parse("pick up the call"))
    }

    @Test fun answerOnSpeaker() {
        assertEquals(CallReply.ANSWER_SPEAKER, CallReply.parse("उठाओ और स्पीकर पर डालो"))
        assertEquals(CallReply.ANSWER_SPEAKER, CallReply.parse("स्पीकर पर डाल दो"))
        assertEquals(CallReply.ANSWER_SPEAKER, CallReply.parse("answer on speaker"))
    }

    @Test fun rejects() {
        assertEquals(CallReply.REJECT, CallReply.parse("नहीं, काट दो"))
        assertEquals(CallReply.REJECT, CallReply.parse("कॉल नहीं उठाना है, काट दो"))
        assertEquals(CallReply.REJECT, CallReply.parse("मत उठाओ"))
        assertEquals(CallReply.REJECT, CallReply.parse("नहीं"))
        assertEquals(CallReply.REJECT, CallReply.parse("cut the call"))
    }

    @Test fun ownQuestionEchoIsIgnored() {
        assertNull(CallReply.parse("राहुल का कॉल आ रहा है, उठाऊँ या काट दूँ?"))
    }

    @Test fun otherTalkIsNotACallReply() {
        assertNull(CallReply.parse("आज मौसम कैसा है"))
        assertEquals(CallReply.WHO, CallReply.parse("किसका कॉल है"))
    }

    @Test fun noteAsksUserAndNamesTool() {
        val n = CallReply.liveNote("राहुल")
        assertTrue(n.contains("राहुल")); assertTrue(n.contains("phone_call")); assertTrue(n.contains("NEVER"))
        assertTrue(CallReply.liveNote("").contains("unknown"))
    }

    @Test fun results() {
        assertTrue(CallReply.resultNote(CallReply.ANSWER, "ERROR_CALL_PERM").contains("permission"))
        assertTrue(CallReply.resultNote(CallReply.REJECT, "NO_CALL").contains("no call"))
        assertTrue(CallReply.resultNote(CallReply.ANSWER, "call utha li").startsWith("Done"))
    }
}
