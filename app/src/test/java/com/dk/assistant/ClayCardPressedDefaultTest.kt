package com.dk.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import com.dk.assistant.ui.Clay
import com.dk.assistant.ui.ClayCard
import com.dk.assistant.ui.ClayDrawable
import com.dk.assistant.ui.ClayTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 23 नवंबर 2026 (मालिक: "जो रूप आज सिर्फ़ दबाने पर दिखता है, वही बिना छुए भी"):
 * साझा कार्ड का मूल रूप = धँसा (अंदर की परछाईं ऊपर-बाएँ गहरी, नीचे-दाएँ चमक), बाहर कोई परछाईं नहीं;
 * दबाने पर थोड़ा और गहरा, गोलाई वही. डार्क और लाइट दोनों थीम में.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ClayCardPressedDefaultTest {
    private val c get() = RuntimeEnvironment.getApplication()
    private val before = ClayTheme.current

    @After fun back() = ClayTheme.set(before)

    private fun draw(pressed: Boolean): Bitmap {
        val dp = c.resources.displayMetrics.density
        val w = (320 * dp).toInt(); val h = (90 * dp).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val d = Clay.card(c)
        d.setBounds(0, 0, w, h)
        d.state = if (pressed) intArrayOf(android.R.attr.state_pressed) else intArrayOf()
        d.jumpToCurrentState()
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun alpha(p: Int) = (p ushr 24) and 0xFF
    private fun lum(p: Int) = ((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114

    @Test fun restLooksLikeOldPressed() {
        for (th in listOf(ClayTheme.MIDNIGHT, ClayTheme.NAVY, ClayTheme.CLAY_DAY)) {
            ClayTheme.set(th)
            val rest = draw(false)
            val pressed = draw(true)
            val dp = c.resources.displayMetrics.density
            val m = (ClayCard.DEPTH_DP * dp).toInt()
            val w = rest.width; val h = rest.height
            // बाहर की परछाईं नहीं: आकार के बाहर वाली जगह (depth की पट्टी) पूरी पारदर्शी
            for (x in 0 until w step 7) {
                assertEquals("${th.id} ऊपर x=$x", 0, alpha(rest.getPixel(x, m / 3)))
                assertEquals("${th.id} नीचे x=$x", 0, alpha(rest.getPixel(x, h - 1 - m / 3)))
            }
            // धँसा: ऊपर-बाएँ किनारे के अंदर गहरा, नीचे-दाएँ किनारे के अंदर उजला
            val cy = h / 2
            val inL = rest.getPixel(m + (4 * dp).toInt(), cy); val inR = rest.getPixel(w - m - (4 * dp).toInt(), cy)
            val inT = rest.getPixel(w / 2, m + (3 * dp).toInt()); val inB = rest.getPixel(w / 2, h - m - (3 * dp).toInt())
            assertTrue("${th.id} बाएँ < दाएँ", lum(inL) < lum(inR))
            assertTrue("${th.id} ऊपर < नीचे", lum(inT) < lum(inB))
            // दबाने पर और गहरा (ऊपर-बाएँ अंदर का हिस्सा और अँधेरा), पर आकार / कोने वही
            assertTrue("${th.id} दबाने पर गहरा", lum(pressed.getPixel(w / 2, m + (6 * dp).toInt())) < lum(rest.getPixel(w / 2, m + (6 * dp).toInt())))
            for ((x, y) in listOf(m + 1 to m + 1, w - m - 2 to m + 1, m + 1 to h - m - 2, w - m - 2 to h - m - 2))
                assertEquals("${th.id} कोना", alpha(rest.getPixel(x, y)), alpha(pressed.getPixel(x, y)))
        }
    }

    @Test fun pressValues() {
        assertTrue(ClayCard.PRESS_SINK > 1f && ClayCard.PRESS_SINK <= 1.5f)
        assertEquals(0.97f, ClayCard.PRESS_SCALE, 0.001f)
        assertTrue(ClayDrawable.RELEASE_MS in 150L..200L)
        val root = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
        val dr = File(root, "ui/ClayDrawables.kt").readText()
        assertTrue(dr.contains("val out = if (raised && !card) depth else 0f"))
        assertTrue(dr.contains("val inn = if (card) 1f else if (raised) (1f - depth) else 1f"))
        // अवतार के ऊपर वाला काँच का कार्ड भी साझा गोलाई से
        assertTrue(File(root, "dkai/DkaiScreen.kt").readText().contains("cornerRadius = d(com.dk.assistant.ui.ClayCard.RADIUS_DP).toFloat()"))
    }
}
