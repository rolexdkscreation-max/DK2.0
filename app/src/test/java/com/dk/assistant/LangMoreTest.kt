package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 15 नवंबर 2026 (मालिक): हिंदी, भोजपुरी, English, मराठी + Auto (बाकी हर भाषा Auto खुद पहचाने) */
class LangMoreTest {
    @Test fun bhojpuriMarathi_choices_keepOldOrder() {
        assertEquals(4, Lang.CHOICES.size)                      // पुरानी सूची वैसी ही
        assertEquals(listOf(Lang.BHOJPURI, Lang.MARATHI), Lang.MORE.map { it.first })
        assertEquals(Lang.BHOJPURI, Lang.norm("bho")); assertEquals(Lang.MARATHI, Lang.norm("mr"))
        assertEquals("मराठी", Lang.short(Lang.MARATHI)); assertEquals("भोजपुरी", Lang.short(Lang.BHOJPURI))
        assertTrue(Lang.rule(Lang.MARATHI).contains("Marathi")); assertTrue(Lang.rule(Lang.BHOJPURI).contains("Bhojpuri"))
        assertTrue(Lang.rule(Lang.MARATHI).contains("JSON keys"))
    }

    @Test fun auto_anyLanguage() {
        val r = Lang.rule(Lang.AUTO)
        assertTrue(r.contains("SAME language the user used") && r.contains("Marathi") && r.contains("Bhojpuri"))
        assertEquals("मराठी हिंदी", Lang.pick("मराठी हिंदी", "en", Lang.MARATHI))
    }
}
