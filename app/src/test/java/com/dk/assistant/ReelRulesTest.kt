package com.dk.assistant

import com.dk.assistant.ReelRules.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026: रील गिनती + ऊपर की पट्टी + लिमिट (ReelRules / ReelWatch / SettingsReels) */
class ReelRulesTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    /** रील प्लेयर: दाईं तरफ़ ऊपर-नीचे लाइक/कमेंट/शेयर + नीचे नाम / caption / गाना */
    private fun reel(author: String, caption: String, likes: String = "1.2K") = listOf(
        Node("", "", "Like", 0.92f, 0.55f), Node("", likes, "", 0.92f, 0.60f),
        Node("", "", "Comment", 0.92f, 0.67f), Node("", "", "Share", 0.92f, 0.78f),
        Node("", author, "", 0.20f, 0.84f), Node("", caption, "", 0.35f, 0.89f),
        Node("", "Follow", "", 0.45f, 0.84f), Node("", "Original audio", "", 0.30f, 0.94f),
    )

    @Test fun apps_byPackage() {
        assertEquals(ReelRules.App.INSTAGRAM, ReelRules.appOf("com.instagram.android"))
        assertEquals(ReelRules.App.YOUTUBE, ReelRules.appOf("com.google.android.youtube"))
        assertEquals(ReelRules.App.FACEBOOK, ReelRules.appOf("com.facebook.katana"))
        assertEquals(ReelRules.App.FACEBOOK, ReelRules.appOf("com.facebook.lite"))
        assertNull(ReelRules.appOf("com.whatsapp")); assertNull(ReelRules.appOf(null))
    }

    @Test fun reelScreen_detected_feedNot() {
        assertTrue(ReelRules.reelMode(ReelRules.App.INSTAGRAM, reel("ram_k", "aaj ka video")))
        // फ़ीड: लाइक/कमेंट/शेयर एक आड़ी लाइन में (बाएँ से दाएँ) = रील नहीं
        val feed = listOf(Node("", "", "Like", 0.1f, 0.7f), Node("", "", "Comment", 0.3f, 0.7f), Node("", "", "Share", 0.5f, 0.7f), Node("", "Reels", "", 0.5f, 0.97f))
        assertFalse(ReelRules.reelMode(ReelRules.App.FACEBOOK, feed))
        // YouTube शॉर्ट्स का view id
        assertTrue(ReelRules.reelMode(ReelRules.App.YOUTUBE, listOf(Node("com.google.android.youtube:id/reel_player_page_container", "", "", 0.5f, 0.5f))))
        assertTrue(ReelRules.reelMode(ReelRules.App.INSTAGRAM, listOf(Node("com.instagram.android:id/clips_viewer_view_pager", "", "", 0.5f, 0.5f))))
        assertFalse(ReelRules.reelMode(ReelRules.App.YOUTUBE, listOf(Node("com.google.android.youtube:id/watch_player", "", "", 0.5f, 0.3f))))
    }

    @Test fun signature_ignoresCountersAndButtons() {
        val a = ReelRules.signature(reel("ram_k", "aaj ka video", likes = "1.2K"))
        val b = ReelRules.signature(reel("ram_k", "aaj ka video", likes = "1.3K"))
        assertEquals(a, b)                       // लाइक बढ़े = वही रील
        assertTrue("ram_k" in a && "aaj ka video" in a)
        assertFalse(a.any { it == "follow" || it == "1.2k" })
    }

    @Test fun counts_eachNewReel_once_afterStable() {
        val c = ReelRules.Counter(stableMs = 700)
        val r1 = ReelRules.signature(reel("ram_k", "pehla"))
        val r2 = ReelRules.signature(reel("sita_m", "doosra"))
        var n = 0; var t = 0L
        fun feed(s: Set<String>, ms: Long) { var e = 0L; while (e <= ms) { if (c.feed(true, s, t)) n++; t += 100; e += 100 } }
        feed(r1, 1_500); assertEquals(1, n)
        feed(r1, 3_000); assertEquals(1, n)      // वही रील चलती रही = +1 नहीं
        feed(r2, 300); assertEquals(1, n)        // बीच में से निकल गई (0.3 सेकंड) = नहीं गिनी
        feed(r2, 1_000); assertEquals(2, n)
        feed(r1, 1_000); assertEquals(3, n)      // पीछे स्क्रॉल, वही पुरानी रील फिर = +1 (मालिक की माँग)
    }

    @Test fun commentsSheet_shortLeave_noDoubleCount_longLeave_counts() {
        val c = ReelRules.Counter(stableMs = 700, backGraceMs = 4_000)
        val r1 = ReelRules.signature(reel("ram_k", "pehla"))
        assertFalse(c.feed(true, r1, 0)); assertTrue(c.feed(true, r1, 800))
        assertFalse(c.feed(false, emptySet(), 1_000))          // कमेंट खोले (रील स्क्रीन नहीं)
        assertFalse(c.feed(true, r1, 3_000)); assertFalse(c.feed(true, r1, 4_000))   // लौटे, वही रील = +1 नहीं
        assertFalse(c.feed(false, emptySet(), 5_000))          // ऐप से बाहर बहुत देर
        assertFalse(c.feed(true, r1, 20_000)); assertTrue(c.feed(true, r1, 20_800))  // बाद में फिर देखी = नई बार
    }

    @Test fun emptySignature_neverCounts() {
        val c = ReelRules.Counter()
        assertFalse(c.feed(true, emptySet(), 0)); assertFalse(c.feed(true, emptySet(), 5_000))
    }

    @Test fun limit_watchAll_thenStopOnNext() {
        assertEquals(1 to false, ReelRules.onNewReel(0, 0))
        assertEquals(30 to false, ReelRules.onNewReel(29, 30))     // 30वीं पूरी देखो
        assertEquals(30 to true, ReelRules.onNewReel(30, 30))      // 31वीं = रोक, गिनती 30 ही
        assertEquals(501 to false, ReelRules.onNewReel(500, 0))    // 0 = कोई लिमिट नहीं
    }

    @Test fun pill_text() {
        assertEquals("इंस्टाग्राम रील • आज 12 / 30", ReelRules.pill(ReelRules.App.INSTAGRAM, 12, 30))
        assertEquals("YouTube शॉर्ट्स रील • आज 4", ReelRules.pill(ReelRules.App.YOUTUBE, 4, 0))
        assertTrue(ReelRules.pill(ReelRules.App.FACEBOOK, 5, 5).contains("लिमिट पूरी"))
        assertTrue(ReelRules.blockedPill(ReelRules.App.FACEBOOK).contains("DK सेटिंग"))
        assertEquals(ReelRules.Action.CLOSE, ReelRules.Action.of(null))
        assertEquals(ReelRules.Action.LOCK, ReelRules.Action.of("lock"))
    }

    @Test fun wiring_overlayNotNotification_dkDrivingExempt_blockUntilUnblock() {
        val w = src("ReelWatch.kt")
        assertTrue(w.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse(w.contains("NotificationCompat") || w.contains("NotificationManager"))
        assertTrue(w.contains("InsightsAgent.busy") && w.contains("SocialAgent.busy()") && w.contains("svc.agent.isBusy"))
        assertTrue(w.contains("GLOBAL_ACTION_LOCK_SCREEN") && w.contains("GLOBAL_ACTION_HOME"))
        assertTrue(w.contains("if (ReelStore.blocked(c, app))"))
        assertTrue(w.contains("fun unblock(c: Context, a: ReelRules.App)"))
        val s = src("DKAccessibilityService.kt")
        assertTrue(s.contains("reelWatch = ReelWatch(this).also { it.start() }"))
        assertTrue(s.contains("reelWatch?.release()"))
        val set = src("SettingsReels.kt")
        assertTrue(set.contains("ReelStore.unblock(this, a)") && set.contains("ReelStore.setLimit("))
        assertTrue(src("SettingsActivity.kt").contains("\"reels\" -> { title.text = \"रील गिनती और लिमिट\""))
    }
}
