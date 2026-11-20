package com.dk.assistant.voice.st3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026 (मालिक): कहानी की डबिंग - पुरुष आवाज़ें, हर किरदार अलग, हर लाइन अपने भाव में */
class St3StoryTest {
    @Test fun maleVoices_forStoryOnly_conversationStaysFemale() {
        assertEquals(listOf("M1", "M2", "M3", "M4", "M5"), St3Config.MALE.map { it.id })
        assertEquals(10, St3Config.CAST.map { it.id }.toSet().size)
        for (v in St3Config.MALE) {
            assertTrue(v.asset.urls[0], v.asset.urls[0] == St3Config.BASE + "voice_styles/${v.id}.json")
            assertEquals(64, v.asset.sha256.length)
            assertTrue(v.asset.bytes in 280_000L..300_000L)
            assertTrue(v.asset in com.dk.assistant.plus.models.ModelCatalog.ALL)
        }
        assertEquals("M3", St3Config.castVoice("M3").id)
        assertEquals("F2", St3Config.castVoice("F2").id)
        assertEquals("F1", St3Config.castVoice("X9").id)
        // बातचीत वाला नियम वही
        assertEquals("F1", St3Config.cleanVoice("M1"))
        assertEquals(emptyList<String>(), com.dk.assistant.plus.models.ModelCatalog.problems())
    }

    @Test fun cast_narratorSeparate_everyCharacterDifferent() {
        val sp = linkedMapOf("Narrator" to true, "रामू" to false, "सीता" to true, "शेरू" to false, "माँ" to true)
        val c = St3Story.assign(sp)
        assertEquals("F1", c["Narrator"])
        assertEquals(sp.keys, c.keys)
        assertEquals(c.size, c.values.toSet().size)                      // सब अलग
        assertTrue(c.getValue("रामू").startsWith("M") && c.getValue("शेरू").startsWith("M"))
        assertTrue(c.getValue("सीता").startsWith("F") && c.getValue("माँ").startsWith("F"))
        // अगला भाग: पुराने किरदार की वही आवाज़, नए को नई
        val c2 = St3Story.assign(linkedMapOf("Narrator" to true, "शेरू" to false, "राजा" to false), c)
        assertEquals(c["शेरू"], c2["शेरू"])
        assertNotEquals(c2["शेरू"], c2["राजा"])
        // पुरुष narrator
        assertEquals("M1", St3Story.assign(linkedMapOf("Narrator" to false, "a" to false))["Narrator"])
        // 7 पुरुष किरदार - 5 ही आवाज़ें, फिर भी सबको कोई आवाज़
        val many = St3Story.assign((1..7).associate { "p$it" to false })
        assertEquals(7, many.size); assertTrue(many.values.all { it.startsWith("M") })
    }

    @Test fun moods_mapToRealControls() {
        val shout = St3Story.mood("shouting")
        assertTrue(shout.gain > 1.2f && shout.speed > 1f)
        assertTrue(St3Story.mood("चिल्लाते हुए").gain > 1.2f)
        val wh = St3Story.mood("whispering")
        assertTrue(wh.gain < 0.7f && wh.speed < 1f)
        assertTrue(St3Story.mood("धीमी आवाज़ में").gain < 1f)
        assertTrue(St3Story.mood("scared").breathy && St3Story.mood("डरते हुए").breathy)
        assertTrue(St3Story.mood("panting").breathy && St3Story.mood("हाँफते हुए").before == "<breath>")
        assertEquals("<sigh>", St3Story.mood("रोते हुए").before)
        assertEquals("<laugh>", St3Story.mood("laughing").before)
        assertTrue(St3Story.mood("excited").speed > 1f)
        assertTrue(St3Story.mood("calm storytelling", narrator = true).pauseMs >= 450)
        assertEquals(1f, St3Story.mood("").speed, 0f)
    }

    @Test fun lineText_tagsAndPunctuation() {
        assertEquals("भागो यहाँ से!", St3Story.lineText("भागो यहाँ से।", St3Story.mood("shouting")))
        assertEquals("<laugh> अरे वाह, कमाल हो गया।", St3Story.lineText("अरे वाह, कमाल हो गया।", St3Story.mood("laughing")))
        val pant = St3Story.lineText("रुको, मैं आ रहा हूँ, बस दो मिनट।", St3Story.mood("panting"))
        assertTrue(pant, pant.startsWith("<breath>") && pant.count { it == '<' } >= 2)
        assertTrue(St3Text.tagsWhole(pant))
        assertEquals("", St3Story.lineText("  ", St3Story.mood("scared")))
    }

    @Test fun gain_softLimit_noClipping() {
        val w = floatArrayOf(0f, 0.5f, -0.5f, 0.9f, -1f)
        val loud = St3Story.applyGain(w, 1.45f)
        assertTrue(loud.all { kotlin.math.abs(it) <= 1f })
        assertTrue(loud[1] > 0.7f)
        assertEquals(0.25f, St3Story.applyGain(w, 0.5f)[1], 1e-6f)
        assertTrue(St3Story.applyGain(w, 1f) === w)
    }

    @Test fun storyUsesOfflineVoiceFirst() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val s = java.io.File(root, "com/dk/assistant/StoryVoice.kt").readText()
        assertTrue(s.contains("if (St3Store.coreReady(c))"))
        assertTrue(s.contains("St3Engine.renderLine("))
        assertTrue(s.contains("DkaiCastTts.render(keys, lines, cast"))           // Gemini वाला रास्ता बचा
    }
}
