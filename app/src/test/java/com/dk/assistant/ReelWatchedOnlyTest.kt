package com.dk.assistant

import com.dk.assistant.ReelRules.App
import com.dk.assistant.ReelRules.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): "फ़ीड स्क्रॉल पर गिनती नहीं - YouTube / फेसबुक / इंस्टाग्राम. टैप करके रील / वीडियो देखना शुरू करें
 * और वह चले, तभी गिनो. YouTube पर शॉर्ट्स के आगे शॉर्ट्स, वीडियो के आगे वीडियो - अलग गिनती."
 */
class ReelWatchedOnlyTest {
    private fun src(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }.readText()

    /** पूरी स्क्रीन का रील प्लेयर, दाईं तरफ़ ऊपर-नीचे बटन, नीचे नाम / caption - असली नाप के साथ */
    private fun player(id: String, author: String) = listOf(
        Node(id, "", "", 0.5f, 0.5f, 1f, 0.92f),
        Node("", "", "Like", 0.92f, 0.55f, 0.1f, 0.05f), Node("", "", "Comment", 0.92f, 0.67f, 0.1f, 0.05f), Node("", "", "Share", 0.92f, 0.78f, 0.1f, 0.05f),
        Node("", author, "", 0.2f, 0.85f, 0.3f, 0.03f), Node("", "caption of $author", "", 0.35f, 0.9f, 0.6f, 0.03f),
    )

    @Test fun feedScroll_neverReelMode_allApps() {
        // इंस्टाग्राम फ़ीड: पोस्ट के अंदर चलती छोटी वीडियो (clips id पर छोटी), बटन आड़ी लाइन में
        val ig = listOf(Node("com.instagram.android:id/clips_video_container", "", "", 0.5f, 0.45f, 1f, 0.5f),
            Node("", "", "Like", 0.08f, 0.73f, 0.08f, 0.04f), Node("", "", "Comment", 0.18f, 0.73f, 0.08f, 0.04f), Node("", "", "Share", 0.28f, 0.73f, 0.08f, 0.04f))
        assertFalse(ReelRules.reelMode(App.INSTAGRAM, ig))
        // फेसबुक फ़ीड: अपने-आप चलती वीडियो (video_player, आधी स्क्रीन से कम)
        assertFalse(ReelRules.reelMode(App.FACEBOOK, listOf(Node("com.facebook.katana:id/video_player", "", "", 0.5f, 0.4f, 1f, 0.32f))))
        // YouTube होम: शॉर्ट्स की कतार (छोटे खाने) - शॉर्ट्स नहीं
        val shelf = (0..2).map { Node("com.google.android.youtube:id/reel_item_thumbnail", "", "", 0.2f + it * 0.3f, 0.6f, 0.3f, 0.3f) }
        assertFalse(ReelRules.reelMode(App.YOUTUBE, shelf))
        assertFalse(ReelRules.watchMode(App.YOUTUBE, shelf))
    }

    @Test fun tappedFullScreen_isReelMode_allApps() {
        assertTrue(ReelRules.reelMode(App.INSTAGRAM, player("com.instagram.android:id/clips_viewer_view_pager", "ram_k")))
        assertTrue(ReelRules.reelMode(App.FACEBOOK, player("com.facebook.katana:id/reels_viewer", "sita")))
        assertTrue(ReelRules.reelMode(App.YOUTUBE, player("com.google.android.youtube:id/reel_player_page_container", "kisan")))
        // id न मिले, पर दाईं तरफ़ ऊपर-नीचे बटन = रील प्लेयर
        assertTrue(ReelRules.reelMode(App.FACEBOOK, player("", "sita")))
    }

    @Test fun youtubeVideo_onlyOpenedWatchPage_notHomeInlineOrMiniPlayer() {
        val title = Node("", "Kheti ka naya tarika", "", 0.4f, 0.36f, 0.8f, 0.04f)
        val sub = Node("", "Subscribe", "", 0.8f, 0.42f, 0.2f, 0.04f)
        val page = listOf(Node("com.google.android.youtube:id/watch_player", "", "", 0.5f, 0.15f, 1f, 0.28f), title, sub)
        assertTrue(ReelRules.watchMode(App.YOUTUBE, page))                                       // टैप करके खुला वीडियो
        val home = listOf(Node("com.google.android.youtube:id/player_view", "", "", 0.5f, 0.3f, 1f, 0.28f), title)
        assertFalse(ReelRules.watchMode(App.YOUTUBE, home))                                      // होम फ़ीड में अपने-आप चलती वीडियो
        val mini = listOf(Node("com.google.android.youtube:id/watch_player", "", "", 0.2f, 0.9f, 0.3f, 0.07f), title, sub)
        assertFalse(ReelRules.watchMode(App.YOUTUBE, mini))                                      // नीचे छोटा mini player, ऊपर फ़ीड स्क्रॉल
        assertTrue(ReelRules.watchMode(App.YOUTUBE, listOf(Node("com.google.android.youtube:id/watch_player", "", "", 0.5f, 0.5f, 1f, 1f))))   // पूरी स्क्रीन (आड़ा)
    }

    @Test fun fastScrollThroughReels_notCounted_watched_counted() {
        val c = ReelRules.Counter(stableMs = ReelRules.WATCH_MS)
        var n = 0; var t = 0L
        fun show(who: String, ms: Long) { val s = ReelRules.signature(player("", who)); var e = 0L; while (e <= ms) { if (c.feed(true, s, t)) n++; t += 450; e += 450 } }
        show("a1", 900); show("a2", 900); show("a3", 450)          // तेज़ी से निकल गईं = देखी नहीं
        assertEquals(0, n)
        show("a4", 3_000); assertEquals(1, n)                         // रुककर देखी = +1
        show("a4", 5_000); assertEquals(1, n)                         // वही चलती रही = +1 नहीं
        assertEquals(1_500L, ReelRules.WATCH_MS)
    }

    @Test fun youtubePill_shortsAndVideosSeparate() {
        assertEquals("YouTube शॉर्ट्स • आज 7 • वीडियो 2", ReelRules.ytPill(false, 7, 2, 0))
        assertEquals("YouTube वीडियो • आज 2 • शॉर्ट्स 7", ReelRules.ytPill(true, 7, 2, 0))
        assertEquals("YouTube वीडियो • आज 1", ReelRules.ytPill(true, 0, 1, 0))
        assertEquals("YouTube शॉर्ट्स • आज 8 • वीडियो 2 • कुल 10 / 10 • लिमिट पूरी", ReelRules.ytPill(false, 8, 2, 10))
    }

    @Test fun week_youtubeShortsAndVideos_separateRows_totalTogether() {
        var w = ReelWeek.roll(null, "2026-11-02")
        repeat(5) { w = ReelWeek.addReel(w, "2026-11-02", "yt", "short $it") }
        repeat(2) { w = ReelWeek.addReel(w, "2026-11-02", ReelWeek.YT_VIDEO, "video $it") }
        val d = ReelWeek.day(w, "2026-11-02")!!
        assertEquals(7, ReelWeek.total(d))
        val t = ReelWeek.table(w, "2026-11-02", 0)
        assertTrue(t, t.contains("आज (11-02): 7 रील / वीडियो") && t.contains("YouTube शॉर्ट्स 5, YouTube वीडियो 2"))
        assertEquals(7, ReelWeek.total(ReelWeek.decode(ReelWeek.encode(w))!!.days[0]))
        assertTrue(ReelWeek.liveText(w, "2026-11-02", 0, true).contains("scrolling feeds is never counted"))
    }

    @Test fun wiring_sizesRead_videoCountedSeparately() {
        val w = src("java/com/dk/assistant/ReelWatch.kt")
        assertTrue(w.contains("r.width() / w, r.height() / h"))
        assertTrue(w.contains("ReelDaily.addReel(c, app, sig.maxByOrNull { it.length }, video = isVideo)"))
        assertTrue(w.contains("ReelRules.ytPill(isVideo,"))
        assertTrue(w.contains("e.putInt(\"reel_count_ytv\", 0)"))
        assertTrue(src("java/com/dk/assistant/SettingsReels.kt").contains("YouTube वीडियो: \$v"))
    }
}
