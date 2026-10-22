package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ऐप की स्क्रीनों की भाषा (English शुरुआती / हिंदी): शब्दकोश और अनुवाद के नियम */
class UiTextTest {

    private fun tsvFile(): File = listOf(File("src/main/assets/ui_en.tsv"), File("app/src/main/assets/ui_en.tsv")).first { it.exists() }
    private val real by lazy { UiText(UiText.parse(tsvFile().readText())) }

    @Test fun exactAndTrimmed() {
        val d = UiText(mapOf("सेव करें" to "Save", "रोकें" to "Stop"))
        assertEquals("Save", d.tr("सेव करें"))
        assertEquals("  Stop ", d.tr("  रोकें "))
        assertEquals("Hello DK", d.tr("Hello DK"))           // English वैसा ही
        assertEquals("", d.tr(""))
    }

    @Test fun partsInsideDynamicText_wholeWordsOnly_longestFirst() {
        val d = UiText(mapOf("डाउनलोड हो रहा है • DK" to "Downloading • DK", "डाउनलोड" to "Download", "का" to "of", "काम कर रहा है" to "Working"))
        assertEquals("Software update Downloading • DK 3.36", d.tr("Software update डाउनलोड हो रहा है • DK 3.36"))
        // छोटा शब्द "का" बीच में नहीं बदलता, और "काम" के अंदर से कभी नहीं
        assertEquals("⏳ Working…", d.tr("⏳ काम कर रहा है…"))
        assertEquals("राम का घर", d.tr("राम का घर"))
        assertEquals("of", d.tr("का"))                         // पूरा text हो तो हाँ
    }

    @Test fun multiLine() {
        val d = UiText(mapOf("पहली लाइन" to "First line", "दूसरी लाइन" to "Second line"))
        assertEquals("First line\nSecond line", d.tr("पहली लाइन\nदूसरी लाइन"))
    }

    @Test fun parse_skipsCommentsAndBadLines() {
        val m = UiText.parse("# टिप्पणी\nहाँ\tYes\nबिना टैब\n\nनहीं\tNo\n")
        assertEquals(mapOf("हाँ" to "Yes", "नहीं" to "No"), m)
    }

    @Test fun realDictionary_isEnglishOnly_andCoversMainScreens() {
        val m = UiText.parse(tsvFile().readText())
        assertTrue("शब्दकोश बहुत छोटा: ${m.size}", m.size > 600)
        for ((k, v) in m) assertFalse("English में हिंदी बची: $k -> $v", v.any { UiText.dev(it) })
        // होम स्क्रीन, Settings और भाषा वाला हिस्सा
        for (t in listOf("चैट", "आवाज़", "मेमोरी", "सेटिंग्स", "सेव करें", "ओर्ब पर टैप करके बोलिए", "ऐप की भाषा (स्क्रीन)", "DK किस भाषा में जवाब दे",
                "AI दिमाग", "कोडिंग एजेंट", "भेजें", "रोकें", "हाँ", "नहीं"))
            assertFalse("अनुवाद नहीं: $t -> ${real.tr(t)}", real.tr(t).any { UiText.dev(it) })
    }

    @Test fun everyLayoutText_hasEnglish() {
        val dir = listOf(File("src/main/res/layout"), File("app/src/main/res/layout")).first { it.exists() }
        val re = Regex("""android:(?:text|hint|contentDescription)="([^"]*)"""")
        dir.listFiles()!!.filter { it.name.endsWith(".xml") }.forEach { f ->
            re.findAll(f.readText()).map { it.groupValues[1] }.filter { s -> s.any { UiText.dev(it) } }.forEach { s ->
                assertFalse("${f.name}: \"$s\" का English नहीं", real.tr(s).any { UiText.dev(it) })
            }
        }
    }
}
