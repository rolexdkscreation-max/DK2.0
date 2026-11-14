package com.dk.assistant.behavior

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.dk.assistant.MainActivity
import com.dk.assistant.R
import com.dk.assistant.dkai.DkaiCore
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

    /**
     * 14 नवंबर 2026 (मालिक: Perplexity जैसा डिब्बा): 🔍 / 🖥 बदलो, लंबा लिखो - + / 🔍 / 🖥 / 🎤 / 〰 कोई नहीं छिपता, न हिलता.
     */
    @Test fun micNeverMovesOrHides() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        val s = a.findViewById<View>(R.id.dkai_root).tag as DkaiScreen
        val screenW = a.resources.displayMetrics.widthPixels
        val seen = HashMap<String, Box>()
        for (computer in listOf(false, true, false)) {
            s.setComputer(computer)
            shadowOf(Looper.getMainLooper()).idle()
            a.window.decorView.let { d -> d.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(a.resources.displayMetrics.heightPixels, View.MeasureSpec.EXACTLY)); d.layout(0, 0, d.measuredWidth, d.measuredHeight) }
            for (desc in listOf("आवाज़ से बात (DK लाइव)", "बोलकर लिखें", "फोटो / फ़ाइल जोड़ें (5 तक)", "खोज / चैट", "DK Computer (टैप = चालू)", "AI मॉडल चुनें")) {
                val v = byDesc(s.root, desc)
                assertNotNull("$desc बटन नहीं मिला", v)
                val b = box(v!!)
                assertTrue("computer=$computer: '$desc' छिप गया", b.shown)
                assertTrue("computer=$computer: '$desc' की चौड़ाई 0 (धकेला गया)", b.width > 0)
                assertTrue("computer=$computer: '$desc' स्क्रीन से बाहर (right ${b.right} > $screenW)", b.right <= screenW && b.left >= 0)
                val first = seen.getOrPut(desc) { b }
                assertEquals("computer=$computer: '$desc' अपनी जगह से हिल गया", first, b)
            }
            assertEquals(computer, s.computerMode)
        }
        // आवाज़ वाला पूरा गोल बटन दिखे (44dp)
        assertEquals((44 * a.resources.displayMetrics.density).toInt().toFloat(), seen["आवाज़ से बात (DK लाइव)"]!!.width.toFloat(), 2f)
        // कुछ लिखा = वही बटन "भेजें" बने (जगह वही)
        s.input.setText("नमस्ते")
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(byDesc(s.root, "भेजें"))
    }

    @Test fun rowFitsCommonPhones() {
        for (w in listOf(320f, 360f, 393f, 412f)) assertTrue("$w dp फ़ोन पर पक्के बटन नहीं समाते", DkaiCore.inputRowFits(w))
    }
}
