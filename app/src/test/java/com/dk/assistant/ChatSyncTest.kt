package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSyncTest {
    private fun m(i: Int, role: String = "u") = DKState.Msg(role, "msg $i", time = 1000L + i)

    @Test fun empty_and_first() {
        assertEquals(0, ChatSync.firstNew(emptyList<DKState.Msg>(), null))
        val a = listOf(m(1), m(2))
        assertEquals(0, ChatSync.firstNew(a, null))
        assertEquals(2, ChatSync.firstNew(a, a[1]))   // कुछ नया नहीं
        assertEquals(1, ChatSync.firstNew(a, a[0]))
    }

    /** असली बग: 200 पूरे -> नया आते ही पुराना हटता है, गिनती 200 ही -> आपका message नहीं दिखता था */
    @Test fun fullList_newMessageStillShown() {
        val list = java.util.concurrent.CopyOnWriteArrayList((1..200).map { m(it) })
        val last = list.last()
        val countBefore = list.size
        list.add(m(201)); while (list.size > 200) list.removeAt(0)     // DKState.trim() जैसा
        assertEquals(countBefore, list.size)                              // गिनती वही (पुराना तरीका यहीं अटकता था)
        val from = ChatSync.firstNew(list, last)
        assertEquals(199, from)
        assertEquals("msg 201", list[from].text)
        // आपका message + DK का जवाब दोनों
        list.add(m(202, "d")); list.removeAt(0)
        assertEquals(listOf("msg 201", "msg 202"), list.subList(ChatSync.firstNew(list, last), list.size).map { it.text })
    }

    @Test fun sameTextTwice_bothShown() {
        val a = DKState.Msg("u", "हाँ", 5L); val b = DKState.Msg("u", "हाँ", 5L)   // बराबर दिखें, पर अलग message
        assertEquals(2, ChatSync.firstNew(listOf(a, b), b))
        assertEquals(1, ChatSync.firstNew(listOf(a, b), a))
    }

    @Test fun cleared_meansFullRedraw() {
        val old = m(1)
        assertEquals(-1, ChatSync.firstNew(emptyList(), old))
        assertEquals(-1, ChatSync.firstNew(listOf(m(5)), old))
    }
}
