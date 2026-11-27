package com.dk.assistant

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.ui.CollapsibleSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 27 नवंबर 2026 (मालिक): हर सेटिंग स्क्रीन के हिस्से शुरू में बंद (साझा CollapsibleSection), "Themes" भी कार्ड,
 * डार्क / लाइट = Profile जैसा एक टॉगल, PIN पैड DK के Clay UI जैसा (स्कैन नहीं).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsCollapseNov27Test {
    private val c: Context get() = ApplicationProvider.getApplicationContext()
    private val root = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
    private fun src(n: String) = File(root, n).readText()

    @Test fun noSectionStartsOpen() {
        val open = root.walkTopDown().filter { it.extension == "kt" && it.name != "CollapsibleSection.kt" }
            .filter { it.readText().contains("startOpen = true") }.map { it.name }.toList()
        assertTrue("अब भी खुले: $open", open.isEmpty())
        assertFalse(src("SettingsBrain.kt").contains("startOpen = one"))
        // सर्च से आए तो वही हिस्सा खुले (पहले जैसा)
        assertTrue(src("SettingsWidgets.kt").contains("startOpen = startOpen || focus == title"))
        assertTrue(src("backup/BackupActivity.kt").contains("CollapsibleSection.add(this, container, UiLang.s(title), body = body)"))
    }

    @Test fun sharedSectionClosedThenToggles() {
        val parent = LinearLayout(c)
        val s = CollapsibleSection.add(c, parent, "Themes") { it.addView(View(c)) }
        assertFalse(s.isOpen); assertEquals(View.GONE, s.body.visibility)
        s.toggle(); assertTrue(s.isOpen); assertEquals(View.VISIBLE, s.body.visibility)
        s.toggle(); assertFalse(s.isOpen); assertEquals(View.GONE, s.body.visibility)
        val s2 = CollapsibleSection.add(c, parent, "दूसरा") {}
        s.toggle(); s2.toggle(); assertTrue(s.isOpen && s2.isOpen)    // एक से ज़्यादा भी खुल सकते हैं
        // तीर: बंद = नीचे, खुला = ऊपर
        assertEquals(90f, CollapsibleSection.arrowAngle(false), 0f)
        assertEquals(-90f, CollapsibleSection.arrowAngle(true), 0f)
    }

    @Test fun themesIsACardAndLightIsAToggle() {
        assertEquals("Themes", THEMES_TITLE)
        val th = src("SettingsTheme.kt")
        assertTrue(th.contains("card(THEMES_TITLE, null) { container ->"))
        assertFalse(th.contains("text = \"THEMES\""))
        val look = src("SettingsLook.kt")
        assertFalse(look.contains("radios(box, SettingsThemeMode.ITEMS"))
        assertTrue(look.contains("box.addView(lightModeSwitch {"))
        assertTrue(src("SettingsActivity.kt").contains("txt.addView(lightModeSwitch {"))
        assertEquals("लाइट मोड (बंद = डार्क)", SettingsThemeMode.SWITCH)
        // दिखावट में अब भी पहले Themes, फिर बाकी
        assertTrue(src("SettingsActivity.kt").contains("\"ui\" -> { title.text = \"दिखावट\"; secTheme(); secUi() }"))
    }

    @Test fun pinPadIsClayAndStill() {
        val pad = NeonPinPad(c, autoLen = 4)
        var got = ""
        pad.onDone = { got = it }
        pad.measure(View.MeasureSpec.makeMeasureSpec(840, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        pad.layout(0, 0, pad.measuredWidth, pad.measuredHeight)
        assertTrue(pad.measuredHeight > pad.paddingTop + pad.paddingBottom)
        pad.draw(Canvas(Bitmap.createBitmap(pad.measuredWidth, pad.measuredHeight, Bitmap.Config.ARGB_8888)))
        assertTrue(pad.background != null)
        assertEquals("", got)
        val v = src("AppLockViews.kt")
        assertFalse(v.contains("postInvalidateOnAnimation()") && v.indexOf("postInvalidateOnAnimation()") < v.indexOf("class NeonPattern"))
    }
}
