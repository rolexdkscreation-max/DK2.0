package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 13 नवंबर 2026 (मालिक): "2 कॉल चल रही हों तो अलग-अलग, वैसा ही मैनेज हो; island अटकना नहीं चाहिए;
 * छोटा-बड़ा और ऊपर से कितनी दूर का option."
 */
class IslandBubbleTest {
    private val now = 1_000_000_000L
    private fun call(who: String, ringing: Boolean, since: Long, key: String, app: String = "", pkg: String = "") =
        IslandCore.Call(who, ringing, since, app, pkg, key)

    @Test fun twoCalls_secondInOwnBubble_ringingFirst() {
        val sim = call("माँ", false, now - 60_000, "sim")
        val wa = call("राहुल", true, now - 2_000, "0|com.whatsapp|1", "WhatsApp", "com.whatsapp")
        val calls = IslandCore.mergeCalls(sim, listOf(wa))
        assertEquals(listOf("राहुल", "माँ"), calls.map { it.who })                // बजती कॉल पहले
        val s = IslandCore.Snap(call = calls.first(), calls = calls)
        val items = IslandCore.items(s, now)
        assertEquals(listOf(IslandCore.Item(IslandCore.Kind.CALL, 0), IslandCore.Item(IslandCore.Kind.CALL, 1)), items)
        assertEquals(IslandCore.Item(IslandCore.Kind.CALL, 1), IslandCore.bubble(s, now))
        assertEquals("माँ", IslandCore.callText(calls[1]))
        assertEquals("1:00", IslandCore.callRight(calls[1], now))
        // एक ही कॉल दो बार नहीं
        assertEquals(1, IslandCore.mergeCalls(null, listOf(wa, wa)).size)
    }

    @Test fun oneThing_noBubble_twoThings_bubble_plusCountNotDoubled() {
        val media = IslandCore.Media("गाना", "गायक", "Spotify", "com.spotify.music", true)
        assertNull(IslandCore.bubble(IslandCore.Snap(media = media), now))
        val c = call("माँ", false, now - 5_000, "sim")
        val s = IslandCore.Snap(call = c, media = media)
        assertEquals(IslandCore.Item(IslandCore.Kind.MEDIA), IslandCore.bubble(s, now))
        assertTrue(IslandCore.compactRight(s, now).endsWith("+1"))              // पुराना नियम वही
        assertFalse(IslandCore.compactRightWith(s, now, bubbled = true).contains("+"))  // बुलबुले वाली चीज़ दोबारा न गिने
        assertEquals("0:05", IslandCore.compactRightWith(s, now, bubbled = true))
    }

    @Test fun itemsOrder_matchesLead() {
        val media = IslandCore.Media("गाना", "", "YouTube", "com.google.android.youtube", true)
        val t = IslandCore.Task("pc:1", "pc", "PC", "काम", "चल रहा है", "", 0, now - 5_000, 0, true, false)
        val s = IslandCore.Snap(live = IslandCore.Live(Mode.LISTENING, false), tasks = listOf(t), media = media)
        assertEquals(IslandCore.lead(s, now), IslandCore.items(s, now).first().kind)
        assertEquals(listOf(IslandCore.Kind.LIVE, IslandCore.Kind.TASK, IslandCore.Kind.MEDIA), IslandCore.items(s, now).map { it.kind })
        assertTrue(IslandCore.items(IslandCore.Snap(), now).isEmpty())
    }

    @Test fun tap_opensApp_holdExpands() {
        listOf(IslandCore.Kind.CALL, IslandCore.Kind.MEDIA, IslandCore.Kind.TASK, IslandCore.Kind.NOTIF).forEach { assertTrue(it.name, IslandCore.tapOpens(it)) }
        assertFalse(IslandCore.tapOpens(IslandCore.Kind.CHARGING))
        assertFalse(IslandCore.tapOpens(IslandCore.Kind.REEL))
    }

    @Test fun autoCollapse_evenDuringCall_notWhileRinging_orTouching() {
        val ongoing = IslandCore.Snap(call = call("माँ", false, now - 9_000, "sim"))
        val ring = IslandCore.Snap(call = call("माँ", true, now, "sim"))
        val idle = IslandCore.AUTO_COLLAPSE_MS + 1
        assertTrue(IslandCore.autoCollapse(true, idle, false, ongoing))         // पहले यहाँ कभी छोटा नहीं होता था = अटका
        assertFalse(IslandCore.autoCollapse(true, idle, false, ring))
        assertFalse(IslandCore.autoCollapse(true, idle, true, ongoing))
        assertFalse(IslandCore.autoCollapse(true, 1_000, false, ongoing))
        assertFalse(IslandCore.autoCollapse(false, idle, false, ongoing))
    }

    @Test fun freshNotif_onlyNew_notFirstLook_notOngoing() {
        val a = IslandCore.Notif("a", "com.whatsapp", "WhatsApp", "राहुल", "हाय", now - 1000, false, false)
        val b = a.copy(key = "b", at = now)
        val d = a.copy(key = "d", at = now + 5, ongoing = true)
        assertNull(IslandCore.freshNotif(emptySet(), listOf(a, b), first = true))
        assertEquals("b", IslandCore.freshNotif(setOf("a"), listOf(a, b, d), first = false)?.key)
        assertNull(IslandCore.freshNotif(setOf("a", "b"), listOf(a, b, d), first = false))
    }

    @Test fun sizeAndPlace_clamped() {
        assertEquals(IslandCore.SIZE_MIN, IslandCore.size(10)); assertEquals(IslandCore.SIZE_MAX, IslandCore.size(500))
        assertEquals(0, IslandCore.top(-5)); assertEquals(IslandCore.TOP_MAX, IslandCore.top(999))
        assertEquals(IslandCore.SIDE_MIN, IslandCore.side(-999))
        assertEquals("छोटा", IslandCore.sizeLabel(80)); assertEquals("मध्यम", IslandCore.sizeLabel(100)); assertEquals("बड़ा", IslandCore.sizeLabel(130))
        assertEquals(100 + 75 + 6 + 17, IslandCore.bubbleX(100, 150, 6, 34))
    }

    @Test fun background_hidesOnlyFrontCall() {
        val sim = call("माँ", false, now - 9_000, "sim")
        val wa = call("राहुल", false, now - 3_000, "w", "WhatsApp", "com.whatsapp")
        val s = IslandCore.Snap(call = wa, calls = listOf(wa, sim))
        val b = IslandCore.background(s, "com.whatsapp")
        assertEquals(listOf(sim), b.calls); assertEquals(sim, b.call)
    }

    private fun src(f: String) = listOf(File("src/main/java/com/dk/assistant/$f"), File("app/src/main/java/com/dk/assistant/$f")).first { it.exists() }.readText()

    @Test fun wired() {
        val i = src("DKIsland.kt")
        assertTrue(i.contains("IslandCore.mergeCalls(sim, apps)") && i.contains("AppCall.all(now)"))
        assertTrue(i.contains("AppCall.answerKey(s, c.key)") && i.contains("AppCall.endKey(s, c.key)"))
        assertTrue(i.contains("renderBubble(s, sn, now)") && i.contains("IslandCore.autoCollapse(expanded"))
        assertTrue(i.contains("holding()") && i.contains("IslandCore.TOUCH_HOLD_MS"))
        assertTrue(i.contains("topDp(s)") && i.contains("sizePct(s)"))
        assertFalse(i.contains("AUTO_COLLAPSE_MS && sn.call == null"))
        val a = src("AppCall.kt")
        assertTrue(a.contains("fun all(") && a.contains("fun prune(") && a.contains("fun answerKey(") && a.contains("fun endKey("))
        assertTrue(src("DKNotifListener.kt").contains("AppCall.prune("))
        val look = src("SettingsIslandLook.kt")
        assertTrue(look.contains("DKIsland.setSizePct(") && look.contains("DKIsland.setTopDp(") && look.contains("DKIsland.preview()"))
        assertTrue(src("SettingsIsland.kt").contains("islandLook(box)"))
    }
}
