package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 13 नवंबर 2026 (मालिक): island में ही मैसेज का जवाब (DK Privacy वाले नहीं), कॉल में वहीं म्यूट / स्पीकर,
 * और "एक ही दिखे" - island चालू है तो Android वाला popup नहीं (डबल नहीं).
 */
class IslandReplyQuietTest {
    private fun src(f: String) = listOf(File("src/main/java/com/dk/assistant/$f"), File("app/src/main/java/com/dk/assistant/$f")).first { it.exists() }.readText()
    private val Q = IslandCore.Quiet::class.java.enumConstants!!

    private fun q(
        pref: Boolean = true, up: Boolean = true, notifs: Boolean = true, usable: Boolean = true, fg: String = "com.android.chrome",
        pkg: String = "com.whatsapp", system: Boolean = false, ongoing: Boolean = false, call: Boolean = false, ringing: Boolean = false,
        media: Boolean = false, cat: String? = "msg", inIsland: Boolean = true,
    ) = IslandCore.quiet(pref, up, notifs, usable, fg, pkg, "com.dk.assistant", system, ongoing, call, ringing, media, cat, inIsland)

    @Test fun message_inIsland_androidPopupRemoved() {
        assertEquals(3, Q.size)
        assertEquals(IslandCore.Quiet.CANCEL, q())
        assertEquals(IslandCore.Quiet.CANCEL, q(fg = "com.sec.android.app.launcher"))        // होम स्क्रीन पर भी
        assertEquals(IslandCore.Quiet.CANCEL, q(pkg = "com.instagram.android", cat = null))  // कोई भी ऐप
    }

    @Test fun keepsAndroid_whenIslandCantShowIt() {
        assertEquals(IslandCore.Quiet.NONE, q(pref = false))          // स्विच बंद
        assertEquals(IslandCore.Quiet.NONE, q(up = false))            // island बंद / DK ऐप सामने
        assertEquals(IslandCore.Quiet.NONE, q(usable = false))        // फ़ोन lock / स्क्रीन बंद
        assertEquals(IslandCore.Quiet.NONE, q(notifs = false))        // island में notification बंद
        assertEquals(IslandCore.Quiet.NONE, q(inIsland = false))      // island ने लिया ही नहीं (DK Privacy / summary)
        assertEquals(IslandCore.Quiet.NONE, q(fg = "com.whatsapp"))   // वही ऐप सामने = island में नहीं दिखेगा
        assertEquals(IslandCore.Quiet.NONE, q(pkg = "com.dk.assistant"))
        assertEquals(IslandCore.Quiet.NONE, q(system = true))
        assertEquals(IslandCore.Quiet.NONE, q(media = true))
        assertEquals(IslandCore.Quiet.NONE, q(ongoing = true))
        for (c in listOf("alarm", "progress", "navigation", "transport", "sys")) assertEquals(c, IslandCore.Quiet.NONE, q(cat = c))
    }

    @Test fun ringingCall_snoozed_ongoingCall_notTouched() {
        assertEquals(IslandCore.Quiet.SNOOZE, q(call = true, ringing = true, ongoing = true, cat = "call"))
        assertEquals(IslandCore.Quiet.SNOOZE, q(pkg = "com.google.android.dialer", call = true, ringing = true, cat = "call", inIsland = false))
        assertEquals(IslandCore.Quiet.NONE, q(call = true, ringing = false, ongoing = true, cat = "call"))
        assertEquals(IslandCore.Quiet.NONE, q(call = true, ringing = true, fg = "com.whatsapp"))            // उसी ऐप की कॉल स्क्रीन सामने
        assertEquals(IslandCore.Quiet.NONE, q(pkg = "com.google.android.dialer", call = true, ringing = true, fg = "com.android.incallui"))
        assertEquals(IslandCore.Quiet.NONE, q(call = true, ringing = true, usable = false))                 // lock = Android की कॉल स्क्रीन
        assertTrue(IslandCore.CALL_SNOOZE_MS in 30_000L..120_000L)
    }

    @Test fun replyButton_onlyReplyable_notCalls() {
        val n = IslandCore.Notif("k", "com.whatsapp", "WhatsApp", "राहुल", "कहाँ हो?", 1L)
        assertFalse(n.canReply); assertFalse(IslandCore.replyButton(n))            // पुराना constructor = बिना जवाब
        assertTrue(IslandCore.replyButton(n.copy(canReply = true)))
        assertFalse(IslandCore.replyButton(n.copy(canReply = true, call = true)))
        assertEquals("ठीक है", IslandCore.replyText("  ठीक है \n"))
        assertEquals("", IslandCore.replyText("   "))
        assertEquals(1000, IslandCore.replyText("a".repeat(5000)).length)
        assertEquals("✓ राहुल को भेज दिया", IslandCore.replyResult(null, "राहुल"))
        assertEquals("✓ जवाब को भेज दिया", IslandCore.replyResult(null, ""))
        assertEquals("reply नहीं गया", IslandCore.replyResult("reply नहीं गया", "राहुल"))
    }

    @Test fun callButtons_labels() {
        assertEquals("🔇 म्यूट", IslandCore.muteLabel(false)); assertEquals("🎙 माइक चालू", IslandCore.muteLabel(true))
        assertEquals("🔊 स्पीकर", IslandCore.speakerLabel(false)); assertEquals("🔈 स्पीकर बंद", IslandCore.speakerLabel(true))
        assertEquals(0, AppCall.pickMute(listOf("Mute", "Hang up")))
        assertEquals(1, AppCall.pickSpeaker(listOf("Mute", "Speaker")))
        assertEquals(-1, AppCall.pickMute(listOf("Answer", "Decline")))
    }

    @Test fun wired() {
        val i = src("DKIsland.kt")
        assertTrue(i.contains("startReply(s, n)") && i.contains("DKNotifListener.reply(s, n.key, t)"))
        assertTrue(i.contains("FLAG_NOT_FOCUSABLE.inv()"))                              // लिखते समय कीबोर्ड मिले
        assertTrue(i.contains("replyTo == null && IslandCore.autoCollapse(expanded"))   // लिखते समय अपने-आप छोटा नहीं
        assertTrue(i.contains("toggleMute(s, c)") && i.contains("toggleSpeaker(s, c)") && i.contains("s.calls.muteApi(on)") && i.contains("s.calls.speakerApi(on)"))
        assertTrue(i.contains("IslandQuiet.poll()") && i.contains("IslandQuiet.appOpened(p)"))
        val l = src("DKNotifListener.kt")
        assertTrue(l.contains("IslandQuiet.onPosted(this, it, inIsland)") && l.contains("IslandQuiet.keep(sbn.key, sbn.packageName, reason)"))
        assertTrue(l.contains("IslandFeed.posted(this, it)") && l.contains("IslandFeed.removed(it)"))
        val qf = src("IslandQuiet.kt")
        assertTrue(qf.contains("l.cancelNotification(sbn.key)") && qf.contains("l.snoozeNotification(sbn.key, IslandCore.CALL_SNOOZE_MS)"))
        assertTrue(qf.contains("isKeyguardLocked"))
        assertTrue(src("IslandFeed.kt").contains("allowFreeFormInput"))
        assertTrue(src("SettingsIsland.kt").contains("IslandQuiet.setOn(this, v)"))
    }
}
