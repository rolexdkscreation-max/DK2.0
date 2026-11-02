package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** मालिक: "हिंदी या हिंग्लिश में बोले, तो उच्चारण टूटे बिना" - रोमन हिंदी -> देवनागरी, अंग्रेज़ी वाक्य वैसे ही */
class HinglishTextTest {
    private fun t(i: String) = HinglishText.toSpeakable(i)

    @Test fun romanHindiBecomesDevanagari_englishWordsStay() {
        assertEquals("हाँ बिल्कुल, मैं आपका reminder set कर देता हूँ.", t("Haan bilkul, main aapka reminder set kar deta hoon."))
        assertEquals("मुझे तो पता है की आप busy हो.", t("Mujhe to pata hai ki aap busy ho."))
        assertEquals("Thank you, आपका दिन अच्छा हो!", t("Thank you, aapka din accha ho!"))
        assertEquals("WhatsApp खोलो", t("WhatsApp kholo"))
    }

    @Test fun englishSentencesUnchanged() {
        for (s in listOf("This is the main point. Please call me at 5.", "Ok, done.", "I am going to the market.",
            "Send it to Rahul.", "Are you sure?", "Main road is closed.")) assertEquals(s, t(s))
    }

    @Test fun devanagariUnchanged() {
        val s = "ठीक है, मैंने WhatsApp पर message भेज दिया।"
        assertEquals(s, t(s))
        assertEquals("", t(""))
    }
}
