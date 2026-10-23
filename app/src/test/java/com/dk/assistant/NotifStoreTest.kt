package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** v3.41: DK लाइव - notification याद रखना, बताना, सही वाले को reply */
class NotifStoreTest {
    private fun item(key: String, app: String, title: String, text: String, at: Long, reply: Boolean = true) =
        NotifStore.Item(key, "pkg.$app", app, title, text, at, reply)

    @Before fun clean() = NotifStore.clear()

    @Test fun newestFirst_updateReplacesOld() {
        NotifStore.add(item("a", "WhatsApp", "Rahul", "hi", 1000))
        NotifStore.add(item("b", "Messages", "Bank", "OTP 1234", 2000, reply = false))
        NotifStore.add(item("a", "WhatsApp", "Rahul", "कहाँ हो?", 3000))
        val all = NotifStore.all()
        assertEquals(2, all.size)
        assertEquals("कहाँ हो?", all.first().text)
    }

    @Test fun keepsOnlyMax() {
        repeat(NotifStore.MAX + 10) { NotifStore.add(item("k$it", "App", "T$it", "x", it.toLong())) }
        assertEquals(NotifStore.MAX, NotifStore.all().size)
        assertEquals("T${NotifStore.MAX + 9}", NotifStore.all().first().title)
    }

    @Test fun emptyIgnored() {
        NotifStore.add(item("e", "App", "", "", 1))
        assertTrue(NotifStore.all().isEmpty())
    }

    @Test fun replyTarget_byName_app_orLatest() {
        NotifStore.add(item("r", "WhatsApp", "Rahul Sharma", "hi", 1000))
        NotifStore.add(item("o", "Messages", "Bank", "OTP", 1500, reply = false))
        NotifStore.add(item("m", "Telegram", "Mummy", "खाना खाया?", 2000))
        assertEquals("r", NotifStore.findForReply("rahul")?.key)
        assertEquals("r", NotifStore.findForReply("WhatsApp")?.key)
        // "इसको" = सबसे नया जिसका reply हो सकता है
        assertEquals("m", NotifStore.findForReply("इसको")?.key)
        assertEquals("m", NotifStore.findForReply("")?.key)
        // Bank का reply नहीं हो सकता
        assertNull(NotifStore.findForReply("Bank"))
    }

    @Test fun filterAndDescribe() {
        NotifStore.add(item("r", "WhatsApp", "Rahul", "hi", 0))
        NotifStore.add(item("o", "Messages", "Bank", "OTP 1234", 60_000, reply = false))
        assertEquals(1, NotifStore.recent(app = "whatsapp").size)
        val d = NotifStore.describe(NotifStore.recent(), 120_000)
        assertTrue(d.startsWith("2 notification"))
        assertTrue(d.contains("[Messages] Bank: OTP 1234 (1 min ago)"))
        assertTrue(d.contains("[WhatsApp] Rahul: hi (2 min ago, can reply)"))
        assertEquals("No new notifications.", NotifStore.describe(emptyList(), 0))
    }

    @Test fun skipsOwnAndOngoing() {
        assertTrue(NotifStore.skip("com.dk.assistant", "com.dk.assistant", false, false))
        assertTrue(NotifStore.skip("com.spotify", "com.dk.assistant", true, false))
        assertTrue(NotifStore.skip("com.whatsapp", "com.dk.assistant", false, true))
        assertTrue(!NotifStore.skip("com.whatsapp", "com.dk.assistant", false, false))
    }
}
