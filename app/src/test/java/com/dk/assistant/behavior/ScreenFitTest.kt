package com.dk.assistant.behavior

import android.app.Activity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.dk.assistant.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * नवंबर 2026 (मालिक): DK कंप्यूटर + कोडिंग एजेंट 360 / 412 (फ़ोन), 800 (टैबलेट), 1280 (बड़ी स्क्रीन) चौड़ाई पर:
 * पूरी स्क्रीन भरी, कुछ कटे नहीं, आड़ा scroll नहीं, बटन छूने लायक (≥44dp), टैबलेट पर UI बड़ा.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ScreenFitTest {

    private val widths = listOf(360, 412, 800, 1280)

    private fun check(cls: Class<out Activity>, wDp: Int) {
        val hDp = if (wDp >= 800) 1280 else 800
        RuntimeEnvironment.setQualifiers("w${wDp}dp-h${hDp}dp")
        val a = Robolectric.buildActivity(cls).setup().visible().get()
        val baseD = a.applicationContext.resources.displayMetrics.density
        val d = a.resources.displayMetrics.density
        val tag = "${cls.simpleName} @${wDp}dp"
        // टैबलेट/बड़ी स्क्रीन पर पूरा UI बड़ा, फ़ोन पर वैसा ही
        assertEquals(tag, UiFitRules.scaleFor(wDp), d / baseD, 0.02f)

        val root = a.window.decorView
        val wPx = (wDp * baseD).toInt(); val hPx = (hDp * baseD).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, wPx, hPx)

        val loc = IntArray(2)
        fun left(v: View): Int { v.getLocationInWindow(loc); return loc[0] }
        val shown = Ui.all(a).filter { it.isShown && it.width > 0 }
        // कुछ भी दाएँ किनारे से बाहर नहीं (कटता नहीं)
        for (v in shown) assertTrue("$tag: ${v.javaClass.simpleName} '${(v as? TextView)?.text?.take(20)}' कट रहा है (${left(v) + v.width} > $wPx)", left(v) + v.width <= wPx + 1)
        // header में आड़ा scroll नहीं; बटन लपेटे जाते हैं
        val row = shown.filterIsInstance<WrapRow>().first()
        assertTrue(tag, shown.none { it is HorizontalScrollView && Ui.all(it).any { c -> c is WrapRow } })
        for (i in 0 until row.childCount) {
            val b = row.getChildAt(i)
            assertTrue("$tag: बटन ${(b as? TextView)?.text} ${b.height}px < 44dp", b.height >= (UiFitRules.MIN_TOUCH_DP * d).toInt() - 1)
            assertTrue("$tag: बटन ${(b as? TextView)?.text} दिख नहीं रहा", b.isShown && b.width > 0)
        }
        // header पूरी चौड़ाई (किनारे की थोड़ी जगह छोड़कर)
        assertTrue("$tag: header ${row.width}px", row.width >= wPx * 0.9)
        // लिखने का डिब्बा लगभग पूरी चौड़ाई, सबसे नीचे
        val comp = shown.filterIsInstance<DKComposer>().first()
        assertTrue("$tag: composer ${comp.width}px", comp.width >= wPx * 0.9)
        comp.getLocationInWindow(loc)
        assertTrue("$tag: composer नीचे नहीं (${loc[1] + comp.height} / $hPx)", loc[1] + comp.height >= hPx * 0.85)
    }

    @Test fun computerScreenFitsAllWidths() = widths.forEach { check(ComputerActivity::class.java, it) }
    @Test fun codingScreenFitsAllWidths() = widths.forEach { check(CodingActivity::class.java, it) }

    @Test fun scaleRules() {
        assertEquals(1f, UiFitRules.scaleFor(360), 0f)
        assertEquals(1f, UiFitRules.scaleFor(599), 0f)
        assertEquals(1.15f, UiFitRules.scaleFor(600), 0.001f)
        assertTrue(UiFitRules.scaleFor(800) in 1.15f..1.30f)
        assertEquals(1.30f, UiFitRules.scaleFor(1280), 0.001f)
        assertEquals(UiFitRules.Kind.TABLET, UiFitRules.kind(800))
        assertEquals(UiFitRules.Kind.LARGE, UiFitRules.kind(1280))
    }
}
