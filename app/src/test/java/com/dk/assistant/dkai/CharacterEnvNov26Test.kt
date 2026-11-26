package com.dk.assistant.dkai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.dk.assistant.ui.ClayTheme
import com.dk.assistant.ui.NeonTheme
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
 * 26 नवंबर 2026: Dynamic Full-Bleed Character Environment + Universal Character Animation Engine.
 * तस्वीर का प्रकार अपने आप, अनुपात कभी नहीं बदलता, थीम से रंग, सादा पीछा पारदर्शी, साँस / पलक / रोशनी एक ही नियम से.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class CharacterEnvNov26Test {
    private val before = ClayTheme.current
    @After fun back() = ClayTheme.set(before)

    private fun src(p: String): String = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    private fun solid(w: Int, h: Int, bg: Int, fig: Int? = 0xFFE0A080.toInt(), circle: Boolean = false): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (circle) { p.color = 0xFF3355AA.toInt(); cv.drawCircle(w / 2f, h / 2f, minOf(w, h) * 0.46f, p) }
        if (fig != null) { p.color = fig; cv.drawOval(RectF(w * 0.3f, h * 0.2f, w * 0.7f, h * 1.1f), p) }
        return b
    }

    private fun px(b: Bitmap): IntArray { val a = IntArray(b.width * b.height); b.getPixels(a, 0, b.width, 0, 0, b.width, b.height); return a }

    @Test fun classify_detectsAssetType_withoutAssuming() {
        // पारदर्शी PNG
        val t = solid(48, 60, 0x00000000)
        assertEquals(CharacterArt.Kind.TRANSPARENT, CharacterArt.classify(px(t), 48, 60).kind)
        // काले पीछे वाली रोशनी
        val e = solid(48, 48, 0xFF000000.toInt(), 0xFF8CE8F5.toInt())
        assertEquals(CharacterArt.Kind.EMISSIVE, CharacterArt.classify(px(e), 48, 48).kind)
        // सादा सफ़ेद पीछा + कैरेक्टर = keyed
        val k = solid(48, 48, 0xFFFFFFFF.toInt())
        assertEquals(CharacterArt.Kind.KEYED, CharacterArt.classify(px(k), 48, 48).kind)
        // सिर्फ़ सफ़ेद (अंदर कुछ नहीं) = पूरी आर्टवर्क (जैसी है वैसी)
        val w = solid(48, 48, 0xFFFFFFFF.toInt(), null)
        assertEquals(CharacterArt.Kind.FULL_ART, CharacterArt.classify(px(w), 48, 48).kind)
        // गोल आर्टवर्क (सफ़ेद पर गोला)
        val c = solid(48, 48, 0xFFFFFFFF.toInt(), null, circle = true)
        assertTrue(CharacterArt.classify(px(c), 48, 48).circular)
        // किनारे तक चित्र (शोर) = FULL_ART
        val n = IntArray(48 * 48) { i -> (0xFF shl 24) or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        assertEquals(CharacterArt.Kind.FULL_ART, CharacterArt.classify(n, 48, 48).kind)
        // आकार
        assertEquals(CharacterArt.Shape.PORTRAIT, CharacterArt.shapeOf(412, 450 * 2))
        assertEquals(CharacterArt.Shape.LANDSCAPE, CharacterArt.shapeOf(1600, 900))
        assertEquals(CharacterArt.Shape.SQUARE, CharacterArt.shapeOf(2048, 2048))
    }

    @Test fun place_neverStretches_subjectInside() {
        val out = RectF()
        val sizes = listOf(1080f to 1500f, 412f to 560f, 800f to 1280f, 1600f to 900f, 360f to 360f)
        val imgs = listOf(2048f to 2048f, 900f to 1600f, 1600f to 900f, 412f to 450f, 95f to 66f)
        for ((w, h) in sizes) for ((bw, bh) in imgs) for (kind in CharacterArt.Kind.values()) for (circ in listOf(false, true)) {
            val p = CharacterArt.Profile(kind, CharacterArt.shapeOf(bw.toInt(), bh.toInt()), circ)
            val sub = HomeAvatars.HQ_CHAR
            CharacterArt.place(w, h, bw, bh, sub, p, 1f, false, out)
            assertEquals("aspect $w x $h / $bw x $bh $kind", bw / bh, out.width() / out.height(), 0.002f)
            // न-कटने वाला हिस्सा (चेहरा / शरीर) view के अंदर
            val sl = out.left + out.width() * sub[0]; val sr = out.left + out.width() * sub[2]
            val st = out.top + out.height() * sub[1]
            assertTrue("subject x $w $h $bw $bh $kind", sl >= -1f && sr <= w + 1f)
            assertTrue("subject top $w $h $bw $bh $kind", st >= -1f)
        }
    }

    @Test fun landscape_fullArt_controlledCrop_centerKept() {
        val out = RectF()
        val p = CharacterArt.Profile(CharacterArt.Kind.FULL_ART, CharacterArt.Shape.LANDSCAPE, false)
        CharacterArt.place(412f, 600f, 1600f, 900f, HomeAvatars.FULL_SUBJECT, p, 1f, false, out)
        assertEquals(1600f / 900f, out.width() / out.height(), 0.002f)
        // बीच का 70% हिस्सा view के अंदर, तस्वीर पहले (सिर्फ़ fit) से बड़ी
        assertTrue(out.left + out.width() * 0.15f >= -1f && out.left + out.width() * 0.85f <= 413f)
        assertTrue(out.width() > 412f * 1.2f)
        assertTrue(out.top >= 0f && out.bottom <= 600f)
    }

    @Test fun feather_widerOnShortSide() {
        val sq = CharacterArt.featherOf(1000, 1000)
        assertEquals(CharacterArt.FEATHER, sq[0], 1e-4f); assertEquals(CharacterArt.FEATHER, sq[1], 1e-4f)
        val wide = CharacterArt.featherOf(1600, 900)
        assertEquals(CharacterArt.FEATHER, wide[0], 1e-4f); assertTrue(wide[1] > 0.2f && wide[1] <= 0.24f)
        val tall = CharacterArt.featherOf(600, 1000)
        assertTrue(tall[0] > 0.19f && tall[0] <= 0.24f); assertEquals(CharacterArt.FEATHER, tall[1], 1e-4f)
    }

    @Test fun palette_followsEveryTheme_andCustom() {
        val custom = ClayTheme.Theme("custom_x", "Custom", "", 0xFFFFF1E6.toInt(), 0xFFFFFFFF.toInt(), 0xFFE11D48.toInt())
        for (th in ClayTheme.ALL + custom) {
            ClayTheme.set(ClayTheme.NAVY); ClayTheme.set(th)
            val k = ClayTheme.t
            val pal = CharacterArt.palette(k.bg, k.accent, k.light)
            assertEquals(3, pal.size)
            // माहौल थीम के पीछे के करीब (कोई काली / सफ़ेद पट्टी नहीं)
            assertTrue(th.id, CharacterArt.dist(pal[0], k.bg) < 0.5f)
            // रंग accent से निकले - दो थीम अलग = रोशनी अलग
            assertTrue(th.id, pal.all { (it ushr 24) == 0xFF })
        }
        val a = CharacterArt.palette(ClayTheme.NOCTURNE.bg, ClayTheme.NOCTURNE.accent, false)
        val b = CharacterArt.palette(ClayTheme.EMBER.bg, ClayTheme.EMBER.accent, false)
        assertTrue(CharacterArt.dist(a[1], b[1]) > 0.1f)
    }

    @Test fun prepare_keysPlainBackground_keepsSizeRatio_cached() {
        val b = solid(300, 400, 0xFFFFFFFF.toInt())
        val p = CharacterArt.prepare(b, HomeAvatars.FULL_SUBJECT)
        assertNotNull(p)
        assertEquals(CharacterArt.Kind.KEYED, p!!.profile.kind)
        assertEquals(300f / 400f, p.art.width.toFloat() / p.art.height, 0.01f)
        assertTrue((p.art.getPixel(10, 10) ushr 24) < 40)                 // सफ़ेद पीछा पारदर्शी
        assertTrue((p.art.getPixel(150, 300) ushr 24) > 200)              // कैरेक्टर ठोस
        assertTrue(CharacterArt.prepare(b, HomeAvatars.FULL_SUBJECT) === p)  // cache
        // बड़ी तस्वीर memory में छोटी (अनुपात वही)
        val big = solid(2048, 2048, 0xFF0B1A3A.toInt(), 0xFF22D3EE.toInt())
        val q = CharacterArt.prepare(big, HomeAvatars.FULL_SUBJECT)!!
        assertTrue(maxOf(q.art.width, q.art.height) <= CharacterArt.MAX_SIDE)
        assertEquals(q.art.width, q.art.height)
        val bd = q.backdrop(ClayTheme.CLAY_DAY.bg, ClayTheme.CLAY_DAY.accent, true)
        assertNotNull(bd)
        assertTrue(bd === q.backdrop(ClayTheme.CLAY_DAY.bg, ClayTheme.CLAY_DAY.accent, true))
    }

    @Test fun prepareAsync_notifiesEveryWaitingView() {
        val b = solid(320, 420, 0xFFFFFFFF.toInt(), 0xFF7755CC.toInt())
        var a = 0; var c = 0
        CharacterArt.prepareAsync(b, HomeAvatars.FULL_SUBJECT) { a++ }
        CharacterArt.prepareAsync(b, HomeAvatars.FULL_SUBJECT) { c++ }       // दूसरा view, वही तस्वीर, साथ में
        val end = System.currentTimeMillis() + 10000
        while ((a == 0 || c == 0) && System.currentTimeMillis() < end) {
            Thread.sleep(30); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertEquals(1, a); assertEquals(1, c)
        assertNotNull(CharacterArt.peek(b, HomeAvatars.FULL_SUBJECT))
    }

    @Test fun view_keepsPreparedArt_afterCacheEviction() {
        val c = RuntimeEnvironment.getApplication()
        ClayTheme.set(ClayTheme.NAVY); ClayTheme.set(ClayTheme.CLAY_DAY)
        val bmp = solid(600, 600, 0xFF0B1A3A.toInt(), 0xFFF0C8B0.toInt())
        CharacterArt.prepare(bmp, HomeAvatars.FULL_SUBJECT)
        val v = HomeAvatarView(c).apply { bitmap = bmp; fade = true }
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 400, 600)
        val out = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(ClayTheme.t.bg); v.draw(this) }      // view तैयार तस्वीर पकड़ लेता है
        // दूसरी तस्वीरें छोटे cache से इसे बाहर कर दें
        for (i in 0 until 4) CharacterArt.prepare(solid(300 + i * 10, 300, 0xFF102040.toInt(), 0xFF8866AA.toInt()), HomeAvatars.FULL_SUBJECT)
        assertNull(CharacterArt.peek(bmp, HomeAvatars.FULL_SUBJECT))
        Canvas(out).apply { drawColor(ClayTheme.t.bg); v.draw(this) }
        // फिर भी नरम किनारे - ऊपर से नीचे कहीं भी अचानक (सख़्त) उजाला -> अँधेरा कूद नहीं
        for (x in listOf(60, 200, 340)) {
            var jump = 0.0
            for (y in 1 until 600) jump = maxOf(jump, NeonTheme.luminance(out.getPixel(x, y - 1)) - NeonTheme.luminance(out.getPixel(x, y)))
            assertTrue("x=$x jump=$jump", jump < 0.12)
        }
    }

    @Test fun motion_breathUniform_blinkTimed_pulseBounded() {
        // साँस: 1..1.012 (एक ही पैमाना)
        for (i in 0..200) {
            val s = CharacterMotion.breathScale(i * 0.05f)
            assertTrue(s >= 1f && s <= 1f + CharacterMotion.BREATH_SCALE + 1e-4f)
        }
        // पलक: 60 सेकंड में 10-28 बार, हर बार छोटी (बंद हिस्सा कुल समय का बहुत कम)
        var closedFrames = 0; var blinks = 0; var was = false
        val step = 1f / 120f
        var t = 0f
        while (t < 60f) {
            val c = CharacterMotion.blink(t, 42)
            assertTrue(c in 0f..1f)
            if (c > 0.5f) closedFrames++
            val now = c > 0.5f
            if (now && !was) blinks++
            was = now; t += step
        }
        assertTrue("blinks=$blinks", blinks in 10..28)
        assertTrue(closedFrames * step < 60f * 0.05f)
        // घंटों बाद भी पलक चलती रहे
        var later = 0; t = 36000f
        while (t < 36060f) { if (CharacterMotion.blink(t, 42) > 0.5f) later++; t += step }
        assertTrue(later > 0)
        // अलग view = अलग ताल
        var same = 0; t = 2f
        while (t < 60f) { if ((CharacterMotion.blink(t, 1) > 0.5f) == (CharacterMotion.blink(t, 2) > 0.5f)) same++; t += step }
        assertTrue(same < ((58f / step).toInt()))
        assertEquals(0f, CharacterMotion.blink(0f, 3), 0f)
        for (sp in listOf(true, false)) for (lv in listOf(0f, 0.5f, 1f)) for (i in 0..50) assertTrue(CharacterMotion.pulse(i * 0.1f, sp, lv) in 0f..1f)
    }

    @Test fun rig_onlyOnMatchingImage_parseSafe() {
        val r = CharacterRig.square(0.4f, 0.35f, 0.07f, 0.04f, 0.55f, 0.36f, 0.07f, 0.04f)
        assertEquals(2, r.eyes.size)
        assertTrue(r.fits(2048, 2048))
        assertFalse(r.fits(95, 66))                // छोटी अलग कटी तस्वीर पर नहीं
        assertFalse(CharacterRig(1f).fits(2048, 2048))   // आँखें नहीं = पलक नहीं
        val o = org.json.JSONObject("""{"aspect":0.75,"eyes":[[0.4,0.3,0.06,0.03],[0.6,0.3,0.06,0.03]]}""")
        val p = CharacterRig.parse(o)!!
        assertTrue(p.fits(750, 1000))
        assertNull(CharacterRig.parse(null))
        assertNull(CharacterRig.parse(org.json.JSONObject("""{"aspect":-1}""")))
        // index.json वाले नए कैरेक्टर को भी rig मिलता है
        val list = HomeAvatars.parse("""{"avatars":[{"id":"robo_x","file":"robo_x.png","rig":{"aspect":1,"eyes":[[0.5,0.4,0.1,0.05]]}}]}""")
        assertEquals(1, list.single().rig!!.eyes.size)
        assertNull(HomeAvatars.parse("""{"avatars":[{"id":"robo_y","file":"robo_y.png"}]}""").single().rig)
        // खुली आँखों वाले बंडल कैरेक्टर पर rig
        assertTrue(HomeAvatars.BUNDLED.count { it.rig != null } >= 5)
        assertTrue(HomeAvatars.BUNDLED.filter { it.orb }.all { it.rig == null })
    }

    @Test fun blink_drawsLidOnlyOverEye() {
        val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val cv0 = Canvas(b); cv0.drawColor(0xFFF0C8B0.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202040.toInt() }
        cv0.drawOval(RectF(86f, 92f, 114f, 108f), p)            // आँख
        val rig = CharacterRig.square(0.5f, 0.5f, 0.14f, 0.08f)
        val out = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawBitmap(b, 0f, 0f, null)
        CharacterAnimator(1).drawBlink(cv, b, rig, RectF(0f, 0f, 200f, 200f), 1f)
        // आँख की जगह अब त्वचा जैसी, बाकी तस्वीर वैसी ही
        assertTrue(CharacterArt.dist(out.getPixel(100, 97), 0xFFF0C8B0.toInt()) < 0.25f)
        assertEquals(b.getPixel(20, 20), out.getPixel(20, 20))
        assertEquals(b.getPixel(100, 160), out.getPixel(100, 160))
        // rig न लगे = कुछ नहीं बदले
        val out2 = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val cv2 = Canvas(out2); cv2.drawBitmap(b, 0f, 0f, null)
        CharacterAnimator(1).drawBlink(cv2, b, CharacterRig(0.5f, rig.eyes), RectF(0f, 0f, 200f, 200f), 1f)
        assertEquals(b.getPixel(100, 100), out2.getPixel(100, 100))
    }

    @Test fun lightTheme_noDarkBox_aroundCharacter() {
        val c = RuntimeEnvironment.getApplication()
        ClayTheme.set(ClayTheme.NAVY); ClayTheme.set(ClayTheme.CLAY_DAY)
        val bmp = solid(600, 600, 0xFF0B1A3A.toInt(), 0xFFF0C8B0.toInt())   // गहरे पीछे वाली आर्ट
        CharacterArt.prepare(bmp, HomeAvatars.FULL_SUBJECT)
        val v = HomeAvatarView(c).apply { bitmap = bmp; fade = true }
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 400, 600)
        val out = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(ClayTheme.t.bg); v.draw(this) }
        // ऊपर / नीचे / किनारे थीम के हल्के रंग में (कोई अँधेरा आयत नहीं)
        for ((x, y) in listOf(5 to 5, 395 to 5, 5 to 595, 395 to 595, 200 to 3, 200 to 597)) {
            assertTrue("$x,$y", NeonTheme.luminance(out.getPixel(x, y)) > 0.5)
        }
        assertTrue(NeonTheme.luminance(out.getPixel(200, 420)) > 0.2)   // कैरेक्टर दिखे
    }

    @Test fun wiring_sharedEngine_noHeavyOps() {
        val env = src("dkai/CharacterEnv.kt")
        val mot = src("dkai/CharacterMotion.kt")
        val av = src("dkai/HomeAvatarView.kt")
        val sp = src("dkai/SpiritEngine.kt")
        for (s in listOf(env, mot)) { assertFalse(s.contains("BlurMaskFilter")); assertFalse(s.contains("RenderEffect")); assertFalse(s.contains("saveLayer")) }
        assertTrue(av.contains("CharacterArt.prepareAsync"))
        assertTrue(av.contains("motion.drawBlink"))
        assertTrue(av.contains("CharacterMotion.breathScale"))
        assertTrue(sp.contains("CharacterMotion.pulse"))
        assertTrue(src("dkai/SpiritCharacterView.kt").contains("SpiritArt.lit"))
        assertFalse(sp.contains("drawStage"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("centerAvatar.rig = av?.rig"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("bottomMargin = -d(14f + cardDrop)"))
    }
}
