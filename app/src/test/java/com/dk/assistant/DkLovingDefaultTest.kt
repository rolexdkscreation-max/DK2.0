package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 19 नवंबर 2026 (मालिक): डिफ़ॉल्ट DK = केयरिंग, प्यार वाली, इज़्ज़त करने वाली, नाम से बुलाने वाली - सभ्य, कोई अश्लील बात नहीं */
class DkLovingDefaultTest {

    // 25 नवंबर 2026 (मालिक ने डिफ़ॉल्ट फिर बदला: "बाय डिफ़ॉल्ट दोस्ताना वाला"): 19 नवंबर का अपने-आप वाला गर्लफ्रेंड एक बार दोस्ताना पर
    @Test fun oldDefaultMovesOnce_choiceKept() {
        assertEquals("friendly", DkPersona.migrated(null))
        assertEquals("friendly", DkPersona.migrated(""))
        assertEquals("friendly", DkPersona.migrated("girlfriend"))
        assertEquals("friendly", DkPersona.migrated("friendly"))
        assertEquals("dk_persona_default_v3", DkPersona.DEFAULT_V)   // नया निशान = पुराने फ़ोनों पर भी एक बार चले
        assertEquals("pro", DkPersona.migrated("pro"))
        assertEquals("natkhat", DkPersona.migrated("natkhat"))
        assertEquals("caring", DkPersona.migrated("caring"))
    }

    // 24 नवंबर 2026 (मालिक ने नियम बदला): गर्लफ्रेंड / रोमांटिक में नाम नहीं - सिर्फ़ जान / बाबू / डार्लिंग. पहले: "MOSTLY by his name"
    @Test fun callsHimByName_mostly() {
        val g = DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "धीरज")
        assertTrue(g.contains("call him ONLY \"बाबू\" / \"जान\" / \"डार्लिंग\""))
        assertTrue(g.contains("NEVER his name in this mode (not \"धीरज\""))
        assertFalse(g.contains("use it only if he asks for his name"))
        assertTrue(g.contains("अरे बाबू, क्या हुआ?"))
        // नाम न हो तो पुराना तरीका
        assertTrue(DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "").contains("\"हाँ बाबू\""))
    }

    @Test fun lovingRules_thankYouRespectSafety() {
        val g = DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "धीरज")
        assertTrue(g.contains("LOVING DEFAULT"))
        assertTrue(g.contains("THANK YOU"))
        assertTrue(g.contains("अरे बाबू, आपसे थैंक यू?"))   // 24 नवंबर 2026: नाम की जगह बाबू
        assertTrue(g.contains("RESPECT"))
        assertTrue(g.contains("NO sexual, explicit or vulgar"))
        assertTrue(g.contains("under 18"))
        assertTrue(g.contains("no jealousy, no guilt"))
        assertTrue(g.contains("never stop him from leaving"))
        // बाकी स्वभाव में यह नहीं
        for (p in listOf(DkPersona.P.FRIENDLY, DkPersona.P.NATKHAT, DkPersona.P.PRO, DkPersona.P.CARING)) {
            assertFalse(p.name, DkPersona.finalFor(p, "धीरज").contains("LOVING DEFAULT"))
        }
    }

    @Test fun minorSignStillDetected() {
        assertEquals(RomanticPersona.Hit.MINOR, RomanticPersona.detect("मैं 15 साल का हूँ"))
        assertEquals(RomanticPersona.Hit.STOP, RomanticPersona.detect("रुको"))
    }
}
