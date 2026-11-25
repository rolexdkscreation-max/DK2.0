package com.dk.assistant

import com.dk.assistant.ContentPlan.Type
import org.junit.Assert.*
import org.junit.Test

/** 25 नवंबर 2026 (मालिक): अपनी लंबाई (मिनट), बोलने का भाव / तरीका, म्यूज़िक सुझाव */
class ContentExtrasTest {
    @Test fun customMinutes() {
        assertEquals(180, ContentPlan.parseMinutes("3"))
        assertEquals(90, ContentPlan.parseMinutes("1.5"))
        assertEquals(150, ContentPlan.parseMinutes("2:30"))
        assertEquals(120, ContentPlan.parseMinutes("2 मिनट"))
        assertEquals(300, ContentPlan.parseMinutes("5 min"))
        assertNull(ContentPlan.parseMinutes("abc"))
        assertNull(ContentPlan.parseMinutes("0"))
        assertNull(ContentPlan.parseMinutes("45"))      // 45 मिनट - बहुत लंबा
        assertNull(ContentPlan.parseMinutes("1:75"))
        assertTrue(ContentPlan.request(Type.SCRIPT, "भारत", 180, emptyList()).contains("180-second"))
    }

    @Test fun extras_onlyForNonStoryNonCaption() {
        assertTrue(ContentPlan.request(Type.FACTS, "समुद्र", 40, emptyList()).contains(ContentPlan.DELIVERY_MARK))
        assertTrue(ContentPlan.request(Type.SCRIPT, "समुद्र", 60, emptyList()).contains(ContentPlan.MUSIC_MARK))
        assertTrue(ContentPlan.request(Type.FIX, "मेरी स्क्रिप्ट", 0, emptyList()).contains(ContentPlan.DELIVERY_MARK))
        assertFalse(ContentPlan.request(Type.CAPTION, "रील", 0, emptyList()).contains(ContentPlan.DELIVERY_MARK))
        assertEquals("कहानी", ContentPlan.request(Type.STORY, "कहानी", 0, emptyList()))
        assertFalse(ContentPlan.captionFor("स्क्रिप्ट").contains(ContentPlan.MUSIC_MARK))
        assertTrue(ContentPlan.modify("पुरानी", "छोटा करो").contains(ContentPlan.MUSIC_MARK))
    }

    @Test fun split_scriptDeliveryMusic() {
        val raw = "क्या आप जानते हैं?\nसमुद्र बहुत गहरा है।\nफॉलो करें\n\n**###DK_DELIVERY###**\n- हुक: हैरानी से, धीमे\n- बीच: जोश में\n\n###DK_MUSIC###\n1. Suspense dark piano | रहस्य | हुक के साथ सही\n- Ocean ambient | शांत | समुद्र का माहौल\n"
        val p = ContentPlan.split(raw)
        assertEquals("क्या आप जानते हैं?\nसमुद्र बहुत गहरा है।\nफॉलो करें", p.script)
        assertEquals("हुक: हैरानी से, धीमे\nबीच: जोश में", p.delivery)
        assertEquals(2, p.music.size)
        assertEquals("Suspense dark piano", p.music[0].name); assertEquals("रहस्य", p.music[0].mood)
        assertTrue(p.music[1].url.startsWith("https://www.youtube.com/results?search_query=Ocean+ambient"))
        // निशान नहीं = सब स्क्रिप्ट (पुराना जवाब वैसा ही)
        val q = ContentPlan.split("सिर्फ़ स्क्रिप्ट")
        assertEquals("सिर्फ़ स्क्रिप्ट", q.script); assertTrue(q.music.isEmpty()); assertEquals("", q.delivery)
    }

    @Test fun cleanKeep_keepsBlocks_timeFromScriptOnly() {
        val k = ContentPlan.cleanKeep("**Hook:** क्या आप जानते हैं?\n###DK_DELIVERY###\nजोश में\n###DK_MUSIC###\nEpic drums | जोश | तेज़")
        val p = ContentPlan.split(k)
        assertEquals("क्या आप जानते हैं?", p.script)
        assertEquals("जोश में", p.delivery)
        assertEquals("Epic drums", p.music.single().name)
        assertEquals(ContentPlan.cleanKeep("सिर्फ़ स्क्रिप्ट"), ContentPlan.clean("सिर्फ़ स्क्रिप्ट"))
    }
}
