package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक: "फ़ोटो / स्क्रीनशॉट डिलीट नहीं हो रहा") - जड़ से ठीक */
class DeleteFixTest {
    private fun go(t: String) = (MediaPick.parse(t) as? MediaPick.Out.Go)?.spec
    private val wake = listOf("डीके", "dk")

    @After fun clean() { MediaPick.forgetAsk() }

    /** "स्क्रीनशॉट मिटाओ / हटाओ" पर नया स्क्रीनशॉट ले लेते थे */
    @Test fun deleteWordsNeverTakeScreenshot() {
        for (p in listOf("स्क्रीनशॉट मिटाओ", "स्क्रीनशॉट हटा दो", "आखिरी स्क्रीनशॉट हटाओ", "आज के स्क्रीनशॉट मिटा दो", "remove screenshot", "स्क्रीनशॉट डिलिट करो")) {
            val t = QuickCommands.parse(p, wake)?.action?.optString("type")
            assertNotEquals(p, "screenshot_save", t)
            val f = LiveFast.pick(p, wake, "", fast = true)?.action?.optString("type")
            assertNotEquals(p, "screenshot_save", f)
        }
        // स्क्रीनशॉट लेना पहले जैसा
        assertEquals("screenshot_save", QuickCommands.parse("स्क्रीनशॉट लो", wake)?.action?.optString("type"))
        assertEquals("screenshot_save", QuickCommands.parse("screenshot", wake)?.action?.optString("type"))
    }

    @Test fun lastWordsUnderstood() {
        for (p in listOf("लास्ट स्क्रीनशॉट डिलीट करो", "आखरी फोटो डिलीट करो", "लेटेस्ट फोटो डिलीट करो", "न्यू स्क्रीनशॉट डिलीट करो")) {
            val s = go(p); assertTrue(p, s != null); assertEquals(p, 1, s!!.count); assertEquals(MediaPick.From.NEW, s.from); assertFalse(s.all)
        }
        assertEquals(0, go("delete today's screenshots")!!.day)
        assertEquals(1, go("delete yesterday's photos")!!.day)
    }

    /** "gallery" में "all" - पहले 500 तक मिटा देते */
    @Test fun galleryIsNotAll() {
        val s = go("delete my last screenshot from gallery")!!
        assertFalse(s.all); assertEquals(1, s.count)
        val p = go("delete the last photo from gallery")!!
        assertFalse(p.all); assertEquals(1, p.count)
        assertTrue(go("delete all screenshots")!!.all)
        assertTrue(go("सारे स्क्रीनशॉट डिलीट कर दो")!!.all)
    }

    /** DK ने पूछा "कौन-सी फ़ोटो?" - जवाब ("आखिरी वाली", "10", "आज की") अब नहीं खोता */
    @Test fun answerToAskIsKept() {
        val t0 = 1_000_000L
        assertTrue(MediaPick.quick("फोटो डिलीट करो", t0) is MediaPick.Out.Ask)
        val a = MediaPick.quick("आखिरी वाली", t0 + 5_000) as MediaPick.Out.Go
        assertEquals(1, a.spec.count); assertEquals("photo", a.spec.kind)
        // Go के बाद भूल गए
        assertNull(MediaPick.quick("10", t0 + 6_000))

        assertTrue(MediaPick.quick("स्क्रीनशॉट डिलीट करो", t0) is MediaPick.Out.Ask)
        val b = MediaPick.quick("10", t0 + 3_000) as MediaPick.Out.Go
        assertEquals(10, b.spec.count); assertTrue(b.spec.shot)

        assertTrue(MediaPick.quick("सारी फोटो डिलीट करो", t0) is MediaPick.Out.Ask)
        val c = MediaPick.quick("आज की", t0 + 3_000) as MediaPick.Out.Go
        assertEquals(0, c.spec.day); assertTrue(c.spec.all)
    }

    @Test fun unrelatedTalkNeverDeletes() {
        val t0 = 2_000_000L
        assertTrue(MediaPick.quick("फोटो डिलीट करो", t0) is MediaPick.Out.Ask)
        assertNull(MediaPick.quick("10 बजे का अलार्म लगाओ", t0 + 2_000))
        assertNull(MediaPick.quick("यूट्यूब खोलो", t0 + 2_000))
        assertNull(MediaPick.quick("नहीं रहने दो", t0 + 2_000))
        // बहुत देर बाद जवाब = नई बात
        assertNull(MediaPick.quick("10", t0 + MediaPick.ASK_FRESH_MS + 5_000))
    }

    @Test fun honestMessages() {
        assertTrue(MediaDelete.denied(opened = true, notified = false).startsWith("ERROR_DENIED:"))
        val b = MediaDelete.denied(opened = false, notified = true)
        assertTrue(b.startsWith("ERROR_POPUP_BLOCKED:")); assertTrue(b.contains("पॉप-अप"))
        assertTrue(LocalRouter.final(b))
        assertFalse(LiveFast.ok("delete_media", b))
        assertTrue(MediaDelete.trashLeft(0)); assertFalse(MediaDelete.trashLeft(1))
    }

    @Test fun wired() {
        val src = File("src/main/java/com/dk/assistant/MediaDelete.kt").readText()
        assertTrue(src.contains("QUERY_ARG_MATCH_TRASHED"))
        assertTrue(src.contains("isExternalStorageManager"))
        assertTrue(src.contains("MediaDelete.opened = true"))
        assertTrue(File("src/main/java/com/dk/assistant/GeminiLiveSetup.kt").readText().contains("delete gallery photos / screenshots"))
    }
}
