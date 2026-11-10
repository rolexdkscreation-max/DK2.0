package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): कोडिंग स्क्रीन - मौजूदा काम हेडर में, बड़ा कार्ड नहीं; नया डिफ़ॉल्ट वॉलपेपर */
class CoderHeaderTaskTest {
    private fun src() = File("src/main/java/com/dk/assistant/CodingActivity.kt").readText()

    @Test fun taskShownInsideSameHeader() {
        val s = src()
        assertTrue(s.contains("FutureViews.glassTopBar(top)"))
        assertTrue(s.contains("private fun headMode(working: Boolean)"))
        assertTrue(s.contains("headMode(true)") && s.contains("headMode(false)"))
        // हेडर का आकार न बदले: सिर्फ़ alpha/INVISIBLE, GONE नहीं
        val hm = s.substringAfter("private fun headMode(").substringBefore("\n    }\n")
        assertFalse(hm.contains("View.GONE"))
        assertTrue(s.contains("isSingleLine = true") && s.contains("TruncateAt.END"))
    }

    @Test fun bigTaskCardRemovedStatusStripBelowButtons() {
        val s = src()
        assertFalse(s.contains("busyBar"))
        assertFalse(s.contains("text = \"मौजूदा काम\""))
        val top = s.indexOf("col.addView(top)")
        val strip = s.indexOf("col.addView(agentWork")
        val chat = s.indexOf("col.addView(scroll")
        assertTrue(top in 0 until strip && strip < chat)
        assertTrue(s.contains("agentWork.visibility = View.GONE"))
    }

    @Test fun newDefaultWallpaperNotOldBlue() {
        val f = File("src/main/res/drawable-nodpi/dk_theme_bg.webp")
        assertTrue(f.isFile && f.length() in 40_000..200_000)
    }
}