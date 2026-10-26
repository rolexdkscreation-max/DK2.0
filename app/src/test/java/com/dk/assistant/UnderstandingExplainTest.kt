package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** समझ ऐसे समझाए जैसे कोई साथी: आपने क्या माँगा (हर बात), अभी क्या हो रहा है, क्या बदलेगा, क्या साफ़ नहीं */
class UnderstandingExplainTest {

    private val json = """{"understood":"आप चाहते हैं कि रिपोर्ट छोटी हो और आगे करो पर काम वहीं से चले",
        "wants":["रिपोर्ट में API बदलने की बातें न दिखें","सिर्फ़ वजह और हल दिखे","आगे करो पर वहीं से शुरू हो"],
        "now":"अभी हर API बदलाव रिपोर्ट में लिखा जाता है, और आगे करो पर टीम शुरू से चलती है",
        "changes":["छोटी रिपोर्ट"],"steps":["रिपोर्ट बदलना"],"spec":"s","acceptance":["4 लाइन"],"not_touch":["merge बटन"],
        "area":["app/src/main/java/com/dk/assistant/SevenTeam.kt - report"],"questions":[]}"""

    private fun u() = SevenPlus.Understanding.parse(Seven.json(json), Seven.Kind.FEATURE, "b", emptyList())

    @Test fun parse_wantsAndNow() {
        val u = u()
        assertEquals(3, u.wants.size)
        assertTrue(u.now.contains("शुरू से"))
    }

    @Test fun hindi_explainsLikeAFriend_noTechnicalGuesswork() {
        val h = u().hindi()
        assertTrue(h, h.contains("आप ये चाहते हैं:\n1. रिपोर्ट में API बदलने की बातें न दिखें\n2. सिर्फ़ वजह और हल दिखे\n3. आगे करो पर वहीं से शुरू हो"))
        assertTrue(h, h.contains("🔍 अभी क्या हो रहा है:") && h.contains("टीम शुरू से चलती है"))
        assertTrue(h, h.contains("🛡 इन्हें नहीं छेड़ा जाएगा") && h.contains("\"हाँ\""))
        assertFalse(h, h.contains("SevenTeam.kt"))                 // फ़ाइलों के path यूज़र को नहीं
        assertFalse(h, h.contains("काम का प्रकार"))
    }

    @Test fun history_keepsWantsAndNow() {
        val back = CoderHistory.Logic.u(CoderHistory.Logic.uJson(u()))
        assertEquals(u().wants, back.wants)
        assertEquals(u().now, back.now)
    }

    @Test fun prompt_asksForEveryPoint_andPlainWords() {
        val p = Seven.Prompts.PROMPT
        assertTrue(p.contains("\"wants\"") && p.contains("\"now\""))
        assertTrue(p.contains("Do not drop, merge"))
    }
}
