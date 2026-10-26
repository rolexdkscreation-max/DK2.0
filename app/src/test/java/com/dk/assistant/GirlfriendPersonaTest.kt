package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v3.77: DK = यूज़र की गर्लफ्रेंड (हल्का, सभ्य रोमांस), यूज़र हमेशा लड़का, DK हमेशा स्त्रीलिंग - DK Voice + DK Live में */
class GirlfriendPersonaTest {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun dkAlwaysFemale() {
        assertTrue(GirlfriendPersona.female("female"))
        assertTrue(GirlfriendPersona.female("male"))
    }

    @Test fun blockHasOwnerTextAndKeyRules() {
        val b = GirlfriendPersona.block("नवीन")
        assertTrue(b.contains(GirlfriendPersona.OWNER_TEXT))
        assertTrue(b.contains("user is ALWAYS male"))
        assertTrue(b.contains("You are ALWAYS female"))
        assertTrue(b.contains("मैडम"))
        assertTrue(b.contains("\"नवीन\""))
        assertTrue(b.contains("ONLY ONE question"))
        assertTrue(b.contains("OBEY HIM"))
        assertTrue(b.contains("never anything obscene"))
        assertTrue(b.contains("honesty rules"))
    }

    @Test fun noNameNoEmptyQuotes() {
        assertFalse(GirlfriendPersona.block("").contains("\"\""))
    }

    @Test fun ownerTextComplete() {
        val t = GirlfriendPersona.OWNER_TEXT
        listOf("【संबोधन का नियम】", "【रोमांटिक और परवाह वाला स्वभाव】", "【बोलने का अंदाज़】", "【बातचीत आगे बढ़ाने का तरीका】",
            "【पिछली बातें याद रखना】", "【गर्मजोशी की सीमा】", "【उदाहरण-संवाद】", "【हमेशा याद रखो】", "【यूज़र की बात मानना (सबसे ऊँची प्राथमिकता)】",
            "हो गया सर।").forEach { assertTrue(it, t.contains(it)) }
    }

    @Test fun appliedInAllVoicePrompts() {
        val bm = src("BrainManager.kt")
        assertEquals(3, Regex("GirlfriendPersona\\.block\\(").findAll(bm).count())
        assertFalse(bm.contains("Prefs.voiceGender(ctx) != \"male\""))
    }
}
