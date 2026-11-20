package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 20 नवंबर 2026 (मालिक): प्रोफ़ाइल में नंबर, गाने, Instagram / Facebook / YouTube, और जानकारी - DK को पता, कहें तो खोले */
class OwnerProfileTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun handles_fromAnyPaste() {
        assertEquals("rahul_01", OwnerProfile.handle("@rahul_01"))
        assertEquals("rahul_01", OwnerProfile.handle(" rahul_01 "))
        assertEquals("rahul_01", OwnerProfile.handle("https://www.instagram.com/rahul_01/?igsh=abc"))
        assertEquals("rahul_01", OwnerProfile.handle("instagram.com/rahul_01"))
        assertEquals("https://www.instagram.com/rahul_01/", OwnerProfile.instagramUrl("https://instagram.com/rahul_01?hl=en"))
        assertEquals("https://www.facebook.com/rahul.k", OwnerProfile.facebookUrl("rahul.k"))
        assertEquals("https://facebook.com/rahul.k", OwnerProfile.facebookUrl("facebook.com/rahul.k"))
        assertEquals("https://m.facebook.com/profile.php?id=1", OwnerProfile.facebookUrl("https://m.facebook.com/profile.php?id=1"))
        assertEquals("https://www.youtube.com/@dkcreation", OwnerProfile.youtubeUrl("@dkcreation"))
        assertEquals("", OwnerProfile.instagramUrl("  "))
    }

    @Test fun songs_linkOrName() {
        val s = OwnerProfile.songsOf("तुम ही हो\n\n https://youtu.be/abc \nतुम ही हो")
        assertEquals(listOf("तुम ही हो", "https://youtu.be/abc"), s)
        assertEquals("https://youtu.be/abc", OwnerProfile.songUrl("https://youtu.be/abc"))
        assertTrue(OwnerProfile.songUrl("तुम ही हो").startsWith("https://www.youtube.com/results?search_query="))
        assertTrue(OwnerProfile.songUrl("tum hi ho").endsWith("tum+hi+ho"))
    }

    @Test fun block_emptyIsNothing_filledHasAll() {
        assertEquals("", OwnerProfile.block(OwnerProfile.Data()))
        val b = OwnerProfile.block(OwnerProfile.Data(phone = "9876543210", songs = listOf("तुम ही हो"), instagram = "@rahul_01",
            facebook = "rahul.k", youtube = "@dkcreation", extra = "नासिक में रहता हूँ\nजन्मदिन 5 मार्च"))
        for (k in listOf("9876543210", "@rahul_01", "https://www.instagram.com/rahul_01/", "https://www.facebook.com/rahul.k",
            "https://www.youtube.com/@dkcreation", "तुम ही हो", "नासिक में रहता हूँ • जन्मदिन 5 मार्च", "phone_task", "यह लिंक खोलो"))
            assertTrue(k, b.contains(k))
        // किसी और को नंबर नहीं - सिर्फ़ मालिक के कहने पर
        assertTrue(b.contains("Never send, read out or share his number"))
        val many = OwnerProfile.block(OwnerProfile.Data(songs = (1..30).map { "गाना $it" }, extra = "x".repeat(5_000)))
        assertFalse(many.contains("गाना 11"))
        assertTrue(many.length < 3_000)
    }

    @Test fun wiredIntoPromptsAndSettings() {
        val bm = src("BrainManager.kt")
        assertEquals(5, Regex(Regex.escape(".append(OwnerProfile.block(ctx))")).findAll(bm).count())   // चैट ×2, Live पूरा, Live छोटा, आवाज़
        val st = src("SettingsSystem.kt")
        for (k in listOf("OwnerProfile.PHONE", "OwnerProfile.SONGS", "OwnerProfile.INSTAGRAM", "OwnerProfile.FACEBOOK", "OwnerProfile.YOUTUBE", "OwnerProfile.EXTRA"))
            assertTrue(k, st.contains("profileFields[$k] = field("))
        assertTrue(src("SettingsActivity.kt").contains("profileFields.forEach { (k, e) -> putString(k, e.text.toString().trim()) }"))
        assertTrue(SettingsSearch.ENTRIES.any { it.section == "profile" && it.card == "आपकी और जानकारी" })
    }
}
