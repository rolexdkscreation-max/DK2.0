package com.dk.assistant.autoreply

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 13 नवंबर 2026 (मालिक): "ऑटो रिप्लाई कभी-कभी डबल मैसेज करता है" - सामने से एक मैसेज = एक ही जवाब, वो भी समझदारी से */
class AutoReplyNoDoubleTest {
    private val C = AutoReplyCore
    private fun them(t: String, at: Long = 0L) = AutoReplyCore.In("Rahul", t, at, false)
    private fun me(t: String, at: Long = 0L) = AutoReplyCore.In("", t, at, true)

    @Test fun sameChat_evenWhenTitleShowsCount() {
        assertEquals(C.chatKey("com.whatsapp", "Rahul"), C.chatKey("com.whatsapp", "Rahul (2 messages)"))
        assertEquals(C.chatKey("com.whatsapp", "Rahul"), C.chatKey("com.whatsapp", "rahul  (3 नए संदेश)"))
        assertEquals(C.chatKey("com.whatsapp", "Rahul"), C.chatKey("com.whatsapp", "Rahul: 4 new messages"))
        assertNotEquals(C.chatKey("com.whatsapp", "Rahul"), C.chatKey("com.whatsapp", "Rohit"))
    }

    @Test fun dkOwnReply_neverTreatedAsTheirMessage() {
        // WhatsApp जवाब के बाद notification फिर डालता है - DK का जवाब "You" / मालिक के नाम से / बिना नाम
        val l = listOf(them("Kal aa rahe ho?", 1), AutoReplyCore.In("You", "Haan bhai aa raha hu", 2, false))
        assertTrue(C.unanswered(C.markMine(l, "", emptyList())).isEmpty())
        val l2 = listOf(them("Kal aa rahe ho?", 1), AutoReplyCore.In("Deepak", "Haan bhai aa raha hu", 2, false))
        assertTrue(C.unanswered(C.markMine(l2, "Deepak", emptyList())).isEmpty())
        val l3 = listOf(them("Kal aa rahe ho?", 1), AutoReplyCore.In("Rahul", "Haan bhai aa raha hu", 2, false))
        assertTrue(C.unanswered(C.markMine(l3, "", listOf("haan bhai  aa raha hu"))).isEmpty())
    }

    @Test fun oneMessage_oneReply_repostDoesNotReplyAgain() {
        val chat = listOf(them("Hello", 1), me("Haan bolo", 2), them("Kal free ho?", 3))
        val pend = C.unanswered(chat)
        assertEquals(listOf("Kal free ho?"), pend.map { it.text })
        assertNull(C.pendingSkip(pend, ""))
        val answered = C.fp(pend.last())
        // वही notification दोबारा (update / DK बंद-चालू) - उसी मैसेज पर दूसरा जवाब नहीं
        assertEquals("already answered", C.pendingSkip(C.unanswered(chat), answered))
        // नया मैसेज आया = फिर एक जवाब
        val more = chat + them("Bata na", 4)
        assertNull(C.pendingSkip(C.unanswered(more), answered))
        assertEquals(listOf("Bata na"), C.afterAnswered(C.unanswered(more), answered).map { it.text })
        // वही text पर नया समय = नया मैसेज
        assertNotEquals(C.fp(them("Hi", 10)), C.fp(them("Hi", 20)))
    }

    @Test fun manyQuickMessages_answeredTogether() {
        val pend = C.unanswered(listOf(me("Kal milte", 1), them("Bhai", 2), them("kitne baje?", 3), them("aur kahan?", 4)))
        assertEquals(3, pend.size)
        val p = C.promptAll("Rahul", listOf(me("Kal milte", 1)) + pend, pend)
        assertTrue(p.contains("ONE single reply") && p.contains("- kitne baje?") && p.contains("- aur kahan?"))
        assertTrue(p.contains("Do not repeat") && p.contains(AutoReplyCore.NO_REPLY))
        assertTrue(C.system("WhatsApp", emptyList()).contains("Send only ONE message per turn"))
    }

    @Test fun okThanksEmoji_noNeedlessReply() {
        for (t in listOf("ok", "Ok 👍", "thik hai", "Theek hai.", "thanks", "🙏", "👍👍", "ठीक है", "hmm", "ok bye")) assertTrue(t, C.isCloser(t))
        for (t in listOf("ok kal 5 baje aana", "kab aaoge?", "Hello", "kya kar rahe ho")) assertFalse(t, C.isCloser(t))
        val ans = C.fp(them("Kal free ho?", 3))
        assertEquals("just ok/thanks", C.pendingSkip(listOf(them("Kal free ho?", 3), them("ok 👍", 5)), ans))
        assertEquals("just ok/thanks", C.pendingSkip(listOf(them("thanks", 5)), ""))
        assertNull(C.clean("NO_REPLY"))
        assertNull(C.clean("no_reply"))
        assertEquals("Haan", C.clean("Haan"))
    }

    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun wiring_oneJobPerChat_savedBeforeSend() {
        val a = src("autoreply/AutoReply.kt")
        assertTrue(a.contains("lock(k).withLock"))                         // एक बातचीत में एक समय पर एक ही जवाब
        assertTrue(a.contains("AutoReplyCore.chatKey(pkg, title)"))
        assertTrue(a.contains("AutoReplyCore.markMine(snap.msgs, snap.self, sentList())"))
        assertTrue(a.contains("AutoReplyCore.promptAll("))
        assertTrue(a.indexOf("setAnswered(c, k, AutoReplyCore.fp(pend.last()))              // भेजने से पहले") < a.indexOf("DKNotifListener.reply(c, key, text)"))
        assertTrue(a.contains("getString(\"ans_\$k\""))                    // फ़ोन में सहेजा - DK बंद-चालू होने पर भी याद
    }
}
