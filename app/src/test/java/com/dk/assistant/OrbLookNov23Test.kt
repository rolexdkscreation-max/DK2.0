package com.dk.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.dk.assistant.dkai.FluidOrbView
import com.dk.assistant.dkai.HomeAvatarView
import com.dk.assistant.dkai.OrbLook
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 23 नवंबर 2026 (मालिक): Settings › "ORB का साइज़ और रंग" - ORB / कैरेक्टर छोटा-बड़ा (60..140%), ORB के अपने रंग
 * (1 से 5, मिलाकर), सेव रहे, कैरेक्टर की तस्वीर कभी न रंगे.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class OrbLookNov23Test {
    private val c get() = RuntimeEnvironment.getApplication()
    @After fun clean() { OrbLook.clearColors(c); OrbLook.setSize(c, OrbLook.DEF_SIZE) }

    private fun src(p: String): String = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun colors_parseEncode_max5() {
        val five = listOf(0xFFEF4444, 0xFFFACC15, 0xFF22C55E, 0xFF3B82F6, 0xFFA855F7).map { it.toInt() }
        assertEquals(five, OrbLook.parse(OrbLook.encode(five)))
        assertEquals(5, OrbLook.parse(OrbLook.encode(five + five)).size)                 // 5 से ज़्यादा नहीं
        assertEquals(listOf(0xFF112233.toInt()), OrbLook.parse("bad, #112233 ,#12"))         // ग़लत टुकड़े छोड़े
        assertTrue(OrbLook.parse(null).isEmpty() && OrbLook.parse("").isEmpty())
    }

    @Test fun choice_remembered() {
        OrbLook.setColors(c, listOf(Color.RED, Color.GREEN))
        OrbLook.setSize(c, 130)
        assertEquals(listOf(Color.RED, Color.GREEN), OrbLook.colors(c))
        assertEquals(130, OrbLook.size(c))
        OrbLook.setSize(c, 999); assertEquals(OrbLook.MAX_SIZE, OrbLook.size(c))
        OrbLook.setSize(c, 1); assertEquals(OrbLook.MIN_SIZE, OrbLook.size(c))
        OrbLook.clearColors(c)
        assertTrue(OrbLook.colors(c).isEmpty())
        assertEquals(1f, OrbLook.factor(OrbLook.DEF_SIZE), 0f)
    }

    @Test fun palette_single_two_many() {
        assertNull(OrbLook.palette3(emptyList()))                                            // कोई नहीं = orb के अपने रंग
        val one = OrbLook.palette3(listOf(Color.RED))!!
        assertEquals(3, one.size); assertEquals(Color.RED, one[1])
        val two = OrbLook.palette3(listOf(Color.RED, Color.BLUE))!!
        assertEquals(Color.RED, two[0]); assertEquals(Color.BLUE, two[2])
        val five = listOf(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, Color.MAGENTA)
        assertArrayEquals(five.toIntArray(), OrbLook.bodyColors(five))                       // 4-5 रंग सारे दिखें
        assertEquals(Color.RED, OrbLook.at(five, 0f)); assertEquals(Color.MAGENTA, OrbLook.at(five, 1f))
        assertEquals(Color.GREEN, OrbLook.at(five, 0.5f))
    }

    @Test fun recolor_keepsLightAndAlpha() {
        val red = listOf(Color.RED)
        assertEquals(0xFF000000.toInt(), OrbLook.recolorPixel(0xFF000000.toInt(), red, 0.5f))   // अँधेरा = अँधेरा
        val hi = OrbLook.recolorPixel(0xFFFFFFFF.toInt(), red, 0.5f)
        assertTrue(Color.green(hi) > 180 && Color.red(hi) > 240)                             // तेज़ चमक सफ़ेद की ओर
        val mid = OrbLook.recolorPixel(0xFF2050A0.toInt(), red, 0.5f)
        assertTrue(Color.red(mid) > Color.blue(mid) && Color.red(mid) > Color.green(mid))    // नीला -> चुना लाल
        assertEquals(0xFF0A1430.toInt(), OrbLook.recolorPixel(0xFF0A1430.toInt(), red, 0.5f))   // पीछे का गहरा नीला वैसा ही (रंगीन डिब्बा नहीं)
        // कई रंग: ऑर्ब के घेरे पर बारी-बारी (ऊपर = पहला रंग, घूमकर वापस पहला)
        assertEquals(0f, OrbLook.angle01(50f, 0f, 50f, 50f), 0.001f)
        assertEquals(0.25f, OrbLook.angle01(100f, 50f, 50f, 50f), 0.001f)
        assertEquals(0.5f, OrbLook.angle01(50f, 100f, 50f, 50f), 0.001f)
        val two = listOf(Color.RED, Color.BLUE)
        val top = OrbLook.recolorPixel(0xFF6080C0.toInt(), two, 0f); val bottom = OrbLook.recolorPixel(0xFF6080C0.toInt(), two, 0.5f)
        assertTrue(Color.red(top) > Color.blue(top) && Color.blue(bottom) > Color.red(bottom))
        assertEquals(0x80, OrbLook.recolorPixel(0x802050A0.toInt(), red, 0.5f) ushr 24)      // पारदर्शिता वही
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2050A0.toInt()) }
        assertTrue(OrbLook.recolor(b, emptyList()) === b)                                    // रंग नहीं = वही
        val r = OrbLook.recolor(b, red)
        assertTrue(r !== b && b.getPixel(0, 0) == 0xFF2050A0.toInt())                       // असली नहीं बदला
        assertTrue(Color.red(r.getPixel(2, 2)) > Color.blue(r.getPixel(2, 2)))
    }

    @Test fun liveOrb_usesMyColors() {
        OrbLook.setColors(c, listOf(Color.RED))
        val v = FluidOrbView(c)
        v.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 200, 200)
        val out = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(out))
        var red = 0; var blue = 0
        for (x in 40 until 160 step 8) for (y in 40 until 160 step 8) { val p = out.getPixel(x, y); red += Color.red(p); blue += Color.blue(p) }
        assertTrue("red=$red blue=$blue", red > blue)
    }

    @Test fun artSize_smallerAndBigger() {
        fun spread(k: Float): Int {
            val bmp = Bitmap.createBitmap(100, 70, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFFFFFF.toInt()) }
            val v = HomeAvatarView(c).apply { bitmap = bmp; art = true; sizeK = k }
            v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 400, 600)
            val out = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(out))
            return (0 until 600).count { com.dk.assistant.ui.NeonTheme.luminance(out.getPixel(200, it)) > 0.6 }
        }
        val small = spread(0.6f); val normal = spread(1f); val big = spread(1.4f)
        assertTrue("$small < $normal < $big", small < normal && normal < big)
    }

    @Test fun wiredIntoHomeAndSettings() {
        val d = src("dkai/DkaiScreen.kt")
        assertTrue(d.contains("val look = OrbLook.sig(c)"))
        assertTrue(d.contains("centerAvatar.sizeK = k"))
        assertTrue(d.contains("av?.orb == true && mine.isNotEmpty()"))                       // सिर्फ़ ऑर्ब आर्टवर्क रंगे
        assertTrue(src("dkai/DkaiViews.kt").contains("OrbLook.palette3(mine)?.let { pal = it }"))
        val s = src("SettingsLook.kt")
        assertTrue(s.contains("card(\"ORB का साइज़ और रंग\""))
        assertTrue(s.contains("button(\"अपना रंग बनाएँ\")"))
        assertTrue(SettingsSearch.ENTRIES.any { it.card == "ORB का साइज़ और रंग" })
    }
}
