package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.33: हिंदी में बोला नाम = फ़ोन में अंग्रेज़ी में लिखा नाम */
class NameMatchTest {
    @Test fun transliteration() {
        assertEquals("raahul", NameMatch.latin("राहुल"))
        assertEquals("sharmaa", NameMatch.latin("शर्मा"))
        assertEquals("amit", NameMatch.latin("अमित"))
        assertEquals(NameMatch.key("Rahul"), NameMatch.key("राहुल"))
        assertEquals(NameMatch.key("Naveen"), NameMatch.key("नवीन"))
        assertEquals(NameMatch.key("Shubham"), NameMatch.key("शुभम"))
        assertEquals(NameMatch.skeleton("Snapchat"), NameMatch.skeleton("स्नैपचैट"))
        assertEquals(NameMatch.key("Priya"), NameMatch.key("प्रिया"))
    }

    @Test fun contactPick() {
        val book = listOf("Rahul Sharma", "Rahul Verma", "Amit Kumar", "Mummy", "Papa Ji")
        // दो राहुल = पूछना पड़ेगा (दोनों लौटते हैं)
        assertEquals(2, NameMatch.best("राहुल", book, { it }).size)
        assertEquals(listOf("Rahul Sharma"), NameMatch.best("राहुल शर्मा", book, { it }))
        assertEquals(listOf("Rahul Verma"), NameMatch.best("rahul verma", book, { it }))
        assertEquals(listOf("Amit Kumar"), NameMatch.best("अमित", book, { it }))
        assertEquals(listOf("Mummy"), NameMatch.best("मम्मी", book, { it }))
        assertEquals(listOf("Papa Ji"), NameMatch.best("पापा जी", book, { it }))
        assertTrue("सिर्फ़ व्यंजन मिलें (सुमित/Smita) = पक्का नहीं (<75, कॉल से पहले पूछना)", NameMatch.score("सुमित", "Smita") < 75)
        assertTrue("अनजान नाम = कोई नहीं", NameMatch.best("सुरेश", book, { it }).isEmpty())
        // एक ठीक-ठीक "Rahul" हो तो वही (बाकी "Rahul X" नहीं)
        assertEquals(listOf("Rahul"), NameMatch.best("राहुल", book + "Rahul", { it }))
    }

    @Test fun appNames() {
        val apps = listOf("WhatsApp", "Snapchat", "YouTube", "YouTube Music", "Google Photos")
        assertEquals(listOf("WhatsApp"), NameMatch.best("whats app", apps, { it }, min = 80))
        assertEquals(listOf("Snapchat"), NameMatch.best("स्नैपचैट", apps, { it }, min = 60))
        assertEquals(listOf("YouTube"), NameMatch.best("youtube", apps, { it }, min = 80))
    }
}
