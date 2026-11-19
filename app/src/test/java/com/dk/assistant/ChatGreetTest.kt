package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 19 नवंबर 2026: ऐप हटाकर खोला = नई चैट; Live जुड़ते ही एक ही बार नमस्ते; पुरानी चैट = उसी के हिसाब से; चैट Accessibility से अलग */
class ChatGreetTest {
    @After fun reset() { ChatGreet.disarm(); ChatGreet.reopenedChat = 0 }

    private fun src(rel: String): String {
        val f = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel")).first { it.exists() }
        return f.readText()
    }

    @Test fun freshOnlyWhenAppWasRemoved() {
        // recents से हटाकर launcher से खोला
        assertTrue(ChatGreet.freshOnLaunch(restored = false, mainAction = true, launcher = true, fromHistory = false, hasMsgs = true, busy = false))
        // system ने पुरानी हालत लौटाई / recents से वापस = वही चैट
        assertFalse(ChatGreet.freshOnLaunch(true, true, true, false, true, false))
        assertFalse(ChatGreet.freshOnLaunch(false, true, true, true, true, false))
        // notification / दूसरे रास्ते से खुला = वही चैट
        assertFalse(ChatGreet.freshOnLaunch(false, false, false, false, true, false))
        // चैट पहले से खाली / DK जवाब लिख रहा = कुछ नहीं बदलना
        assertFalse(ChatGreet.freshOnLaunch(false, true, true, false, false, false))
        assertFalse(ChatGreet.freshOnLaunch(false, true, true, false, true, true))
    }

    @Test fun greetOnlyOncePerChat() {
        ChatGreet.arm(5, 1_000L)
        assertTrue(ChatGreet.take(5, 2_000L))
        assertFalse(ChatGreet.take(5, 3_000L))      // दोबारा जुड़ा (8 मिनट वाला बदलाव) = फिर नमस्ते नहीं
    }

    @Test fun noGreetWhenStaleOrOtherChatOrUserSpoke() {
        ChatGreet.arm(5, 0L)
        assertFalse(ChatGreet.take(5, ChatGreet.ARM_MS + 1))
        ChatGreet.arm(5, 0L)
        assertFalse(ChatGreet.take(6, 10L))
        ChatGreet.arm(7, 0L)
        ChatGreet.disarm()                          // यूज़र बोला / लिखा (DKState.user / userNow यही बुलाते हैं)
        assertFalse(ChatGreet.take(7, 10L))
        val st = src("DKState.kt")
        assertTrue(st.substringAfter("fun user(t: String) {").take(200).contains("ChatGreet.disarm()"))
        assertTrue(st.substringAfter("fun userNow(t: String) {").take(200).contains("ChatGreet.disarm()"))
    }

    @Test fun newChatSaysOnlyNamaste() {
        val n = ChatGreet.newNote("बॉस")
        assertTrue(n.contains("\"नमस्ते बॉस\""))
        assertTrue(n.contains("nothing else"))
        assertTrue(n.contains("stay completely silent and wait"))
    }

    @Test fun addressFromPersonaThenNameThenBoss() {
        assertEquals("सर", ChatGreet.address("सर", "धीरज"))
        assertEquals("जी धीरज", ChatGreet.address("", "धीरज"))
        assertEquals("बॉस", ChatGreet.address("", ""))
    }

    @Test fun oldChatUsesItsOwnTalk() {
        val turns = listOf(LiveCarry.Turn("u", "कल मेरा इंटरव्यू है", 1L), LiveCarry.Turn("d", "बहुत अच्छा, तैयारी कैसी है?", 2L))
        val n = ChatGreet.oldNote("जी धीरज", turns)
        assertTrue(n.contains("कल मेरा इंटरव्यू है"))
        assertTrue(n.contains("at most two"))
        assertTrue(n.contains("Max 2 short Hindi sentences"))
        // सूची से खोली पुरानी चैट: बातें कितनी भी पुरानी हों, Live के नए सत्र में जाती हैं
        assertTrue(LiveCarry.note(turns, 10 * 3_600_000L).isEmpty())
        assertTrue(LiveCarry.reopened(turns).contains("EARLIER CONVERSATION IN THIS CHAT"))
        assertTrue(LiveCarry.reopened(emptyList()).isEmpty())
    }

    @Test fun chatSwitchMakesNewLiveSession() {
        assertTrue(ChatGreet.switchDue(3, 4, turnActive = false, playing = false, toolsIdle = true))
        assertFalse(ChatGreet.switchDue(3, 3, false, false, true))
        assertFalse(ChatGreet.switchDue(3, 4, true, false, true))     // DK बोल / सुन रहा = पहले पूरा हो
        assertFalse(ChatGreet.switchDue(0, 4, false, false, true))
    }

    @Test fun wiredIntoAppAndLive() {
        assertTrue(src("MainActivity.kt").contains("ChatFresh.onLaunch(this, savedInstanceState != null, intent)"))
        val scr = src("dkai/DkaiScreen.kt")
        assertTrue(scr.contains("ChatFresh.changed(c, Dkai.chatNo(c), reopened = false)"))
        assertTrue(scr.contains("ChatFresh.changed(c, n, reopened = true)"))
        assertTrue(src("GeminiLiveTurn.kt").contains("ChatFresh.note(svc)"))
        assertTrue(src("GeminiLive.kt").contains("ChatGreet.switchDue(connChat, nowChat"))
        assertTrue(src("DKAccessibilityService.kt").contains("ChatFresh.note(this@DKAccessibilityService) ?: com.dk.assistant.dkai.ChatFresh.greetFor"))
    }

    @Test fun textChatWorksWithoutAccessibility() {
        // Accessibility बंद (service नहीं) = चैट फिर भी सीधे AI से; चालू = पूरा दिमाग़ (फ़ोन के काम भी)
        for (f in listOf("dkai/DkaiScreen.kt", "ChatActivity.kt")) {
            val s = src(f)
            assertTrue(f, s.contains("if (svc != null) { svc.runCommand(t, speak ="))
            assertTrue(f, s.contains(".streamChat(\"USER SAYS: \$t\""))
        }
        val send = src("dkai/DkaiScreen.kt").substringAfter("private fun sendChat(t: String): Boolean {").substringBefore("private fun deepResearch")
        assertFalse(send.contains("host.ready()) return"))
        assertFalse(send.contains("accessibilityOn"))
    }
}
