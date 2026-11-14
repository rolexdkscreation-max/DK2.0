package com.dk.assistant.behavior

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.dk.assistant.MainActivity
import com.dk.assistant.R
import com.dk.assistant.dkai.DkaiCore
import com.dk.assistant.dkai.DkaiMode
import com.dk.assistant.dkai.DkaiScreen
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 14 नवंबर 2026 (मालिक): "Pipeline या Broadcast चुनता हूँ तो आवाज़ वाला (माइक) बटन साइड होकर छिप जाता है.
 * कोई भी ऑप्शन चुनें - हिडन नहीं, हर बटन अपनी जगह पर". असली नाप-जोख (असली font, NATIVE graphics) के साथ
 * आम फ़ोन की चौड़ाई (360dp) पर हर मोड चुनकर देखते हैं: माइक / भेजें / + / मोड पूरे स्क्रीन में और एक ही जगह.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
class DkaiMicStaysTest {
    private fun byDesc(v: View, d: String): View? {
        if (v.contentDescription?.toString() == d) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) byDesc(v.getChildAt(i), d)?.let { return it }
        return null
    }

    private data class Box(val left: Int, val right: Int, val width: Int, val shown: Boolean)

    private fun box(v: View): Box {
        val loc = IntArray(2); v.getLocationInWindow(loc)
        return Box(loc[0], loc[0] + v.width, v.width, v.isShown)
    }

    @Test fun micNeverMovesOrHides() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        val s = a.findViewById<View>(R.id.dkai_root).tag as DkaiScreen
        val screenW = a.resources.displayMetrics.widthPixels
        val seen = HashMap<String, Box>()
        for (m in DkaiMode.values()) {
            s.pickMode(m)
            shadowOf(Looper.getMainLooper()).idle()
            a.window.decorView.let { d -> d.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(a.resources.displayMetrics.heightPixels, View.MeasureSpec.EXACTLY)); d.layout(0, 0, d.measuredWidth, d.measuredHeight) }
            for (desc in listOf("माइक - आवाज़ से बात", "भेजें", "फोटो / फ़ाइल जोड़ें", "मोड चुनें")) {
                val v = byDesc(s.root, desc)
                assertNotNull("$desc बटन नहीं मिला", v)
                val b = box(v!!)
                assertTrue("${m.label}: '$desc' छिप गया", b.shown)
                assertTrue("${m.label}: '$desc' की चौड़ाई 0 (धकेला गया)", b.width > 0)
                assertTrue("${m.label}: '$desc' स्क्रीन से बाहर (right ${b.right} > $screenW)", b.right <= screenW && b.left >= 0)
                val first = seen.getOrPut(desc) { b }
                assertEquals("${m.label}: '$desc' अपनी जगह से हिल गया", first, b)
            }
            assertTrue("मोड गोली पर '${m.label}' नहीं", (byDesc(s.root, "मोड चुनें") as android.widget.TextView).text.startsWith(m.label))
        }
        // माइक का पूरा गोल बटन दिखे (48dp)
        assertEquals((48 * a.resources.displayMetrics.density).toInt().toFloat(), seen["माइक - आवाज़ से बात"]!!.width.toFloat(), 2f)
    }

    @Test fun rowFitsCommonPhones() {
        for (w in listOf(320f, 360f, 393f, 412f)) assertTrue("$w dp फ़ोन पर पक्के बटन नहीं समाते", DkaiCore.inputRowFits(w))
    }
}
