package com.dk.assistant

import org.junit.Assert.*
import org.junit.Test

/** नवंबर 2026: merge पूछने से पहले पूरा ब्योरा (क्या कहा था / क्या बदला / कैसे काम करेगा / क्या नहीं हुआ) */
class MergeBriefTest {
    private val short = "✅ काम हो गया\nAPI: ठीक\nPR #281\nआगे: merge करूँ?"

    @Test fun fullBriefBeforeMergeQuestion() {
        val t = MergeBrief.text(short, "WhatsApp auto chat ठीक करो", listOf("WaAutoChat.kt" to "confirm अब सच में चालू करता है"),
            "अब आप हाँ बोलेंगे तभी auto chat चालू होगा", "", true, "https://github.com/u/r/pull/281", true)
        assertTrue(t.contains("📝 आपने क्या कहा था"))
        assertTrue(t.contains("WhatsApp auto chat ठीक करो"))
        assertTrue(t.contains("🔧 क्या-क्या किया (1 फ़ाइल)"))
        assertTrue(t.contains("WaAutoChat.kt — confirm"))
        assertTrue(t.contains("⚙️ यह कैसे काम करेगा"))
        assertTrue(t.contains("🚫 अभी क्या नहीं हुआ"))
        assertTrue(t.contains("🔗 PR: https://github.com/u/r/pull/281"))
        assertTrue(t.endsWith(MergeBrief.ASK))
        assertFalse("छोटी 'आगे:' लाइन दोबारा नहीं", t.contains("आगे: merge करूँ?"))
        assertTrue(t.indexOf("📝") < t.indexOf("🔧") && t.indexOf("🔧") < t.indexOf("⚙️") && t.indexOf("⚙️") < t.indexOf("❓"))
    }

    @Test fun usesToldWhenNoAiExplain() {
        val t = MergeBrief.text(short, "x", listOf("A.kt" to ""), "", "पक्का नतीजा: A बदला", true, "https://p", true)
        assertTrue(t.contains("पक्का नतीजा: A बदला"))
    }

    @Test fun noMergeQuestionWhenTestsFail() {
        val t = MergeBrief.text(short, "x", listOf("A.kt" to "y"), "z", "", false, "https://p", true)
        assertFalse(t.contains(MergeBrief.ASK))
        assertTrue(t.contains("पास नहीं हुई"))
    }

    @Test fun nothingChangedStaysShort() {
        assertEquals("✅ ठीक", MergeBrief.text("✅ ठीक", "x", emptyList(), "", "", null, "", true))
    }
}
