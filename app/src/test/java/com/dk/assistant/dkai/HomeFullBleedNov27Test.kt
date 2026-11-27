package com.dk.assistant.dkai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.dk.assistant.ui.ClayTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * 27 नवंबर 2026 (मालिक): होम पर चुना कैरेक्टर फुल-स्क्रीन फोटो -
 *  (1) तस्वीर पूरी स्क्रीन भरे, कोई खाली पट्टी नहीं (2) चेहरा कभी न कटे, ऊपर की पट्टी / नीचे की बार के बीच
 *  (3) focus स्क्रीन की 30-40% ऊँचाई के पास (4) फ़ोन और टैबलेट दोनों (5) नया कैरेक्टर = बस index.json में focus / face
 *  (6) ORB चुना = पहले वाला डिज़ाइन, कैरेक्टर = ORB / वेव / DNA छुपे (7) सिर्फ़ होम स्क्रीन.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class HomeFullBleedNov27Test {
    private val before = ClayTheme.current
    @After fun back() = ClayTheme.set(before)

    private fun src(p: String): String =
        listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    private fun root(): File = listOf(File("../avatars"), File("avatars")).first { it.exists() }

    private fun spec(v: Int) = View.MeasureSpec.makeMeasureSpec(v, View.MeasureSpec.EXACTLY)

    /** (चौड़ाई, ऊँचाई, density, टैबलेट) - फ़ोन छोटा / आम / बड़ा, टैबलेट portrait और landscape */
    private val screens = listOf(
        Triple(1080, 2400, 2.75f) to false, Triple(720, 1280, 2f) to false, Triple(1440, 3200, 3.5f) to false,
        Triple(1600, 2560, 2f) to true, Triple(2560, 1600, 2f) to true, Triple(1200, 1920, 2f) to true)

    private fun chars() = HomeAvatars.BUNDLED.filter { !it.orb }.map { HomeAvatars.hqLook(it) }

    // ------------------------------------------------------------------ चेहरा कभी न कटे, पूरी स्क्रीन भरी
    @Test fun everyBundledCharacter_fillsScreen_faceSafe_focusNear36_phoneAndTablet() {
        assertEquals(8, chars().size)
        for ((s, tab) in screens) {
            val (w, h, d) = s
            val top = d * (24 + 62 + 52); val bottom = d * (96 + 24)
            for (a in chars()) {
                val (_, focus, face) = HomeAvatars.frameOf(a, w > h)
                val p = CharacterFrame.place(w.toFloat(), h.toFloat(), 2048f, 2048f, focus, face, top, bottom,
                    if (tab) CharacterFrame.TABLET_FACE_K else 0f)
                val tag = "${a.id} ${w}x$h"
                assertTrue("$tag cover", p.covers)
                assertTrue("$tag face", p.faceFits)
                val f = p.faceOn(2048f, 2048f, face)
                assertTrue("$tag face top ${f[1]} < $top", f[1] >= top - 1f)
                assertTrue("$tag face bottom", f[3] <= h - bottom + 1f)
                assertTrue("$tag face sides", f[0] >= -1f && f[2] <= w + 1f)
                val fy = p.focusOn(2048f, 2048f, focus)[1] / h
                assertTrue("$tag focus $fy", fy in 0.27f..0.45f)
                // टैबलेट पर चेहरा छोटा न दिखे
                if (tab) assertTrue("$tag face small", (f[3] - f[1]) / h >= 0.24f)
            }
        }
    }

    /** cover में चेहरा न बैठे (बहुत बड़ा चेहरा / चौड़ी तस्वीर) = scale घटे, चेहरा फिर भी पूरा; कोई cut नहीं */
    @Test fun hugeFace_shrinksInsteadOfCutting() {
        val p = CharacterFrame.place(1080f, 2400f, 2048f, 1024f, listOf(0.5f, 0.5f), listOf(0.05f, 0.05f, 0.95f, 0.95f), 380f, 330f)
        assertTrue(p.faceFits)
        assertFalse(p.covers)                                    // किनारे का रंग भरता है (CLAMP) - खाली पट्टी नहीं
        val f = p.faceOn(2048f, 1024f, listOf(0.05f, 0.05f, 0.95f, 0.95f))
        assertTrue(f[0] >= -1f && f[2] <= 1081f && f[1] >= 379f && f[3] <= 2400f - 329f)
    }

    /** जगह सिर्फ़ स्क्रीन + पट्टियों पर टिकी - बोलना / बॉक्स बढ़ना इसमें आता ही नहीं (हर बार बिल्कुल वही) */
    @Test fun placement_isDeterministic() {
        val a = chars().first()
        val (_, fo, fa) = HomeAvatars.frameOf(a, false)
        val one = CharacterFrame.place(1080f, 2400f, 2048f, 2048f, fo, fa, 380f, 330f)
        repeat(5) { assertEquals(one, CharacterFrame.place(1080f, 2400f, 2048f, 2048f, fo, fa, 380f, 330f)) }
    }

    // ------------------------------------------------------------------ नया कैरेक्टर = index.json
    @Test fun parse_readsFocusFaceAndLand_badValuesIgnored() {
        val j = """{"avatars":[
            {"id":"dk_char_neon","name":"नियॉन","gender":"girl","file":"dk_char_neon.png","focus":[0.5,0.34],"face":[0.3,0.2,0.7,0.5],
             "land":{"file":"dk_char_neon_land.png","focus":[0.6,0.4],"face":[0.5,0.2,0.7,0.6]}},
            {"id":"dk_char_bad","file":"dk_char_bad.png","focus":[1.5,0.3],"face":[0.7,0.2,0.3,0.5],"land":{"file":"../x.png"}},
            {"id":"dk_char_plain","file":"dk_char_plain.png"}]}"""
        val l = HomeAvatars.parse(j)
        assertEquals(3, l.size)
        val n = l[0]
        assertEquals(listOf(0.5f, 0.34f), n.focus)
        assertEquals(listOf(0.3f, 0.2f, 0.7f, 0.5f), n.face)
        assertEquals("dk_char_neon_land.png", n.land)
        assertEquals(listOf(0.6f, 0.4f), n.landFocus)
        assertEquals("dk_char_neon_land.png", HomeAvatars.frameOf(n, true).first)
        assertEquals(listOf(0.6f, 0.4f), HomeAvatars.frameOf(n, true).second)
        assertEquals("dk_char_neon.png", HomeAvatars.frameOf(n, false).first)
        val bad = l[1]
        assertNull(bad.focus); assertNull(bad.face); assertNull(bad.land)
        assertEquals(CharacterFrame.DEFAULT_FOCUS, HomeAvatars.frameOf(bad, false).second)
        assertEquals(CharacterFrame.DEFAULT_FACE, HomeAvatars.frameOf(bad, true).third)
        // सिर्फ़ face दिया = focus उसी से; सिर्फ़ focus = face उसी के चारों ओर
        val ff = CharacterFrame.focusOf(null, listOf(0.2f, 0.2f, 0.6f, 0.7f))
        assertEquals(0.4f, ff[0], 1e-4f); assertEquals(0.4f, ff[1], 1e-4f)
        val fa = CharacterFrame.faceOf(listOf(0.5f, 0.4f), null)
        assertTrue(fa[0] < 0.5f && fa[2] > 0.5f && fa[1] < 0.4f && fa[3] > 0.4f)
        // बिना focus वाला नया कैरेक्टर भी फ़ोन पर पूरा भरे और डिफ़ॉल्ट चेहरा सुरक्षित
        val p = CharacterFrame.place(1080f, 2400f, 1536f, 2048f, CharacterFrame.DEFAULT_FOCUS, CharacterFrame.DEFAULT_FACE, 380f, 330f)
        assertTrue(p.covers && p.faceFits)
    }

    @Test fun bundledCharacters_haveFocusInsideFace_orbsUntouched() {
        for (a in HomeAvatars.BUNDLED) {
            if (a.orb) { assertNull(a.hqFocus); assertNull(a.hqFace); continue }
            val fo = CharacterFrame.okFocus(a.hqFocus); val fa = CharacterFrame.okFace(a.hqFace)
            assertNotNull(a.id, fo); assertNotNull(a.id, fa)
            assertTrue(a.id, fo!![0] in fa!![0]..fa[2] && fo[1] in fa[1]..fa[3])
            assertEquals(a.hqFocus, HomeAvatars.hqLook(a).focus)
            assertEquals(a.hqFace, HomeAvatars.hqLook(a).face)
        }
    }

    // ------------------------------------------------------------------ असली तस्वीर: पूरी स्क्रीन भरी, कोई खाली कोना नहीं
    @Test fun view_drawsEdgeToEdge_noEmptyBars_evenWhenShrunk() {
        val c = RuntimeEnvironment.getApplication()
        ClayTheme.set(ClayTheme.NAVY)
        val b = BitmapFactory.decodeFile(File(root(), "dk_art_hearts_hq.png").path, BitmapFactory.Options().apply { inSampleSize = 4 })
        val a = chars().first { it.id == "dk_art_hearts" }
        for ((w, h, face) in listOf(Triple(1080, 2400, a.face), Triple(2560, 1600, a.face), Triple(1080, 2400, listOf(0.02f, 0.02f, 0.98f, 0.98f)))) {
            val v = FullBleedCharacterView(c).apply { topSafe = { 380 }; bottomSafe = { 330 } }
            v.show(b, a.focus ?: CharacterFrame.DEFAULT_FOCUS, face ?: CharacterFrame.DEFAULT_FACE)
            v.measure(spec(w), spec(h)); v.layout(0, 0, w, h)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(out))
            assertNotNull(v.place)
            for ((x, y) in listOf(1 to 1, w - 2 to 1, 1 to h - 2, w - 2 to h - 2, w / 2 to 1, w / 2 to h - 2, 1 to h / 2, w - 2 to h / 2))
                assertEquals("${w}x$h ($x,$y)", 255, Color.alpha(out.getPixel(x, y)))
        }
    }

    // ------------------------------------------------------------------ होम स्क्रीन की वायरिंग
    @Test fun home_fullBleedWiring_orbKeptForOrbs_onlyHome() {
        val d = src("dkai/DkaiScreen.kt")
        assertTrue(d.contains("val full = av != null && !av.orb && bmp != null"))
        assertTrue(d.contains("fullBg?.show(landBmp ?: bmp, f.second, f.third)"))
        assertTrue(d.contains("val showAv = bmp != null && !full"))
        assertTrue(d.contains("centerOrb.visibility = if (!showAv && !classic && !showSpirit && !full)"))
        assertTrue(d.contains("val showSpirit = !showAv && !full && spirit != null"))
        assertTrue(d.contains("centerLogo.visibility = if (showAv || showSpirit || full)"))
        // सबसे नीचे की परत: content से पहले root में
        val at = d.substringAfter("fun attach(): DkaiScreen {")
        assertTrue(at.indexOf("root.addView(fullBg") in 0 until at.indexOf("root.addView(content"))
        assertTrue(at.indexOf("root.addView(topScrim") in 0 until at.indexOf("root.addView(content"))
        // सिर्फ़ होम टैब, पारदर्शी सिस्टम पट्टियाँ, काँच वाला बॉक्स और नीचे की बार
        val back = d.substringAfter("private fun renderBack()").substringBefore("\n    private fun fullTop")
        assertTrue(back.contains("tabNow == com.dk.assistant.ui.HomeTabs.Tab.HOME"))
        assertTrue(back.contains("HomeTabs.glass(tabsBar"))
        assertTrue(d.contains("w.statusBarColor = android.graphics.Color.TRANSPARENT"))
        assertTrue(d.contains("setDecorFitsSystemWindows(w, !full)"))
        assertTrue(d.contains("liveBox.background = if (full) glassCard("))
        assertTrue(src("ui/HomeTabs.kt").contains("var glass: android.graphics.drawable.Drawable? = null"))
        // फुल-स्क्रीन view: कोई blur / glow / परत नहीं, किनारे का रंग (CLAMP), हल्का crossfade
        val v = src("dkai/FullBleedCharacterView.kt")
        for (bad in listOf("BlurMaskFilter", "RenderEffect", "saveLayer", "RadialGradient", "sin(")) assertFalse(bad, v.contains(bad))
        assertTrue(v.contains("Shader.TileMode.CLAMP"))
        assertTrue(v.contains("reducedMotion()"))
        // Chat स्क्रीन पर नहीं
        assertTrue(d.contains("if (!chatPage) {\n            bgAvatar = HomeAvatarView(c)"))
    }
}
