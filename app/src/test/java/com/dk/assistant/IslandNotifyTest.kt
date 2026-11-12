package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 13 नवंबर 2026 (मालिक): DK Island - सारे notification (टैप = वहीं खुले, स्लाइड = हटे, "सब साफ़ करें"), चार्जिंग animation,
 * रील गिनती island में, सोशल काम island में, नाम सिर्फ़ "DK Island" / "DK Privacy", 7 एजेंट हटा - सिर्फ़ सिंगल एजेंट.
 */
class IslandNotifyTest {
    private val now = 2_000_000_000L
    private fun n(key: String, at: Long, call: Boolean = false, ongoing: Boolean = false) =
        IslandCore.Notif(key, "com.whatsapp", "WhatsApp", "राहुल", "कहाँ हो?", at, ongoing, call)

    @Test fun notifOrder_callFirst_newestNext_noDuplicates_max() {
        val list = listOf(n("a", now - 5_000), n("b", now - 1_000), n("c", now - 9_000, call = true), n("a", now - 500))
        val r = IslandCore.notifOrder(list)
        assertEquals(listOf("c", "a", "b"), r.map { it.key })
        assertEquals(now - 500, r[1].at)                                   // वही key = नया वाला
        val many = (1..80).map { n("k$it", now - it * 1000L) }
        assertEquals(IslandCore.MAX_NOTIFS, IslandCore.notifOrder(many).size)
    }

    @Test fun swipe_and_clearAll() {
        assertTrue(IslandCore.swipeDismiss(400f, 1000))
        assertTrue(IslandCore.swipeDismiss(-360f, 1000))
        assertFalse(IslandCore.swipeDismiss(200f, 1000))
        assertFalse(IslandCore.swipeDismiss(0f, 0))
        val left = IslandCore.afterClearAll(listOf(n("x", now), n("call", now, call = true, ongoing = true)))
        assertEquals(listOf("call"), left.map { it.key })                 // चलती कॉल Android हटाने नहीं देता
    }

    @Test fun notifs_alone_showIsland_lowestPriority() {
        val s = IslandCore.Snap(notifs = listOf(n("a", now)))
        assertEquals(IslandCore.Kind.NOTIF, IslandCore.lead(s, now))
        assertTrue(IslandCore.wanted(true, false, s, now))
        assertFalse(IslandCore.wanted(true, true, s, now))
        assertEquals("WhatsApp: राहुल", IslandCore.compactText(s, now))
        assertEquals("🔔 1", IslandCore.compactRight(s, now))
        val withTask = s.copy(tasks = listOf(IslandCore.Task("pc:1", "pc", "PC", "काम", "चल रहा है", "", 0, now - 1000, 0, true)))
        assertEquals(IslandCore.Kind.TASK, IslandCore.lead(withTask, now))
        assertEquals(1, IslandCore.others(withTask, now))
    }

    @Test fun charging_popFirst_thenSmall_belowWork() {
        val c = IslandCore.Charge(72, true, false, now - 1_000, 45 * 60_000L)
        val s = IslandCore.Snap(charge = c, live = IslandCore.Live(Mode.LISTENING, false))
        assertEquals(IslandCore.Kind.CHARGING, IslandCore.lead(s, now))          // चार्जर अभी लगा = animation पहले
        val later = now + IslandCore.CHARGE_POP_MS + 1_000
        assertEquals(IslandCore.Kind.LIVE, IslandCore.lead(s, later))
        assertEquals(IslandCore.Kind.CHARGING, IslandCore.lead(IslandCore.Snap(charge = c), later))
        assertEquals("⚡ 72%", IslandCore.compactText(IslandCore.Snap(charge = c), later))
        assertEquals("45 मि", IslandCore.compactRight(IslandCore.Snap(charge = c), later))
        assertNull(IslandCore.lead(IslandCore.Snap(charge = c.copy(charging = false)), later))   // चार्जर नहीं = island नहीं
        assertEquals(IslandCore.Kind.CHARGING, IslandCore.lead(IslandCore.Snap(charge = c, notifs = listOf(n("a", now))), later))
    }

    @Test fun chargeTexts_realOnly() {
        assertEquals("पूरा होने में 1 घंटा 5 मिनट", IslandCore.chargeLeft(65 * 60_000L))
        assertEquals("पूरा होने में 45 मिनट", IslandCore.chargeLeft(45 * 60_000L))
        assertEquals("पूरा होने में 2 घंटा", IslandCore.chargeLeft(120 * 60_000L))
        assertEquals("पूरा चार्ज हो गया", IslandCore.chargeLeft(-1, full = true))
        assertEquals("समय निकाल रहा हूँ…", IslandCore.chargeLeft(-1))
        assertEquals("1 घं 5 मि", IslandCore.shortLeft(65 * 60_000L))
        assertEquals("⚡", IslandCore.shortLeft(-1))
        // अंदाज़ा: 10 मिनट में 5% = 1% / 2 मिनट; 80% पर 20% बाकी = 40 मिनट
        assertEquals(40 * 60_000L, IslandCore.estimateFullMs(listOf(now to 75, now + 600_000L to 80), 80))
        assertEquals(-1L, IslandCore.estimateFullMs(listOf(now to 75), 75))                          // नमूने कम = नकली समय नहीं
        assertEquals(-1L, IslandCore.estimateFullMs(listOf(now to 75, now + 30_000L to 76), 76))     // 1 मिनट से कम
        assertEquals(0L, IslandCore.estimateFullMs(emptyList(), 100))
        assertEquals(0xFFEF4444, IslandCore.batteryColor(10)); assertEquals(0xFF22C55E, IslandCore.batteryColor(80))
        assertEquals("USB", IslandCore.chargeSource(2))
    }

    @Test fun reel_insideIsland_freshOnly() {
        val r = IslandCore.Reel("Instagram रील: 12", false, now)
        val s = IslandCore.Snap(reel = r)
        assertEquals(IslandCore.Kind.REEL, IslandCore.lead(s, now))
        assertEquals("Instagram रील: 12", IslandCore.compactText(s, now))
        assertNull(IslandCore.lead(s, now + IslandCore.REEL_SHOW_MS + 500))
        assertEquals(IslandCore.Kind.CALL, IslandCore.lead(s.copy(call = IslandCore.Call("माँ", true, now)), now))
    }

    @Test fun social_task_inIsland() {
        val run = IslandCore.fromSocial("s-1", "Instagram", "POST_REEL", "RUNNING", "रील चुन रहा हूँ", 2, 6, now - 5_000, 0)
        assertTrue(run.active); assertEquals("social:s-1", run.id); assertEquals(TaskStatus.State.RUNNING.hi, run.state)
        assertTrue(run.last.contains("(2/6)"))
        val wait = IslandCore.fromSocial("s-1", "Instagram", "POST_REEL", "WAITING_CONFIRMATION", "पोस्ट करूँ?", 5, 6, now - 5_000, 0)
        assertTrue(wait.active); assertEquals(TaskStatus.State.WAITING.hi, wait.state)
        val done = IslandCore.fromSocial("s-1", "Instagram", "POST_REEL", "COMPLETED", "पोस्ट हो गई", 6, 6, now - 5_000, now)
        assertFalse(done.active); assertTrue(done.ok); assertEquals(now, done.ended)
        assertEquals(IslandCore.Kind.TASK, IslandCore.lead(IslandCore.Snap(tasks = listOf(run)), now))
    }

    private fun src(p: String) = listOf(File(p), File("app/$p")).first { it.exists() }.readText()
    private fun main(f: String) = src("src/main/java/com/dk/assistant/$f")

    @Test fun wiring_feed_tapOpens_swipeDismiss_clearAll() {
        val l = main("DKNotifListener.kt")
        assertTrue(l.contains("IslandFeed.posted(this, it)") && l.contains("IslandFeed.removed(it)"))
        val f = main("IslandFeed.kt")
        assertTrue(f.contains("cancelNotification(key)") && f.contains("cancelAllNotifications()"))
        assertTrue(f.contains("MODE_BACKGROUND_ACTIVITY_START_ALLOWED"))
        val i = main("DKIsland.kt")
        assertTrue(i.contains("IslandFeed.open(s, n.key)") && i.contains("IslandFeed.dismiss(n.key)") && i.contains("IslandFeed.clearAll()"))
        assertTrue(i.contains("showInCallScreen(false)"))                     // कॉल पर टैप = चलती कॉल
        assertTrue(i.contains("ACTION_BATTERY_CHANGED") && i.contains("computeChargeTimeRemaining()"))
        assertTrue(i.contains("\"DK Island\""))
        assertTrue(main("ReelWatch.kt").contains("DKIsland.reel(text, warn)"))
        assertTrue(main("agent/android/SocialAgent.kt").contains("DKIsland.poke()"))
    }

    @Test fun names_noIphone_dkPrivacy() {
        for (f in listOf("DKIsland.kt", "IslandCore.kt", "SettingsIsland.kt", "SettingsSearch.kt", "DKAccessibilityService.kt"))
            assertFalse(f, main(f).contains("iPhone") || main(f).contains("Dynamic Island"))
        assertTrue(SettingsSearch.ENTRIES.any { it.card == "DK Island" })
        assertTrue(SettingsSearch.ENTRIES.any { it.card == "DK Privacy (बगल से न दिखे)" })
        assertFalse(SettingsSearch.ENTRIES.any { it.card.contains("प्राइवेसी स्क्रीन") })
    }

    @Test fun coder_singleAgentOnly_seesMedia() {
        val p = main("Prefs.kt")
        assertTrue(p.contains("fun coderSeven(c: Context): Boolean = true") && p.contains("fun coderSolo(c: Context): Boolean = true"))
        val set = main("SettingsCoder.kt")
        assertFalse(set.contains("swSeven") || set.contains("swSolo") || set.contains("swTeam"))
        assertTrue(set.contains("सिंगल एजेंट"))
        val t = main("SevenTeam.kt")
        assertTrue(t.contains("(solo && a == Agent.CODER) || a == Agent.EXPLAINER"))
        assertFalse(main("CodingActivity.kt").contains("AgentIntro.coderCapabilities(seven, github)"))
    }
}
