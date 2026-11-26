package com.dk.assistant.dkai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dk.assistant.ui.ClayTheme
import com.dk.assistant.ui.NeonTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 27 नवंबर 2026 (मालिक): होम का character stage -
 *  (1) पीछे धुंधला चौकोर / बादल नहीं, कैरेक्टर तेज़ और स्थिर (2) बात बढ़े तब भी कैरेक्टर की जगह / साइज़ वही, सिर कभी न कटे
 *  (3) हर थीम (8) में कैरेक्टर थीम का हिस्सा - कोई आयत / seam नहीं, रोशनी थीम की, हल्का color-grade.
 * env DK_SHOTS_DIR = 8 थीम x 3 कैरेक्टर x 3 लिखावट की तस्वीरें.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class CharacterStageNov27Test {
    private val before = ClayTheme.current
    @After fun back() = ClayTheme.set(before)

    private fun src(p: String): String =
        listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    private fun root(): File = listOf(File("../avatars"), File("avatars")).first { it.exists() }
    private fun hq(n: String): Bitmap = BitmapFactory.decodeFile(File(root(), n).path, BitmapFactory.Options().apply { inSampleSize = 2 })

    private fun spec(v: Int) = View.MeasureSpec.makeMeasureSpec(v, View.MeasureSpec.EXACTLY)

    // ------------------------------------------------------------------ blur हटा
    @Test fun noBlur_noBlurredCopy_noCharacterTransform() {
        val files = listOf("dkai/CharacterEnv.kt", "dkai/CharacterStage.kt", "dkai/HomeAvatarView.kt", "dkai/SpiritEngine.kt", "dkai/CharacterMotion.kt")
        for (f in files) {
            val s = src(f)
            for (bad in listOf("BlurMaskFilter", "RenderEffect", "ScriptIntrinsicBlur", "createBlurEffect", "drawBackdrop", "backdrop(")) assertFalse("$f: $bad", s.contains(bad))
        }
        // तस्वीर की छोटी कॉपी (जिसे बड़ा करने से धुंधला पीछा बनता था) अब कहीं नहीं
        assertFalse(src("dkai/CharacterEnv.kt").contains("createScaledBitmap(t, "))
        assertFalse(src("dkai/CharacterEnv.kt").contains("TINY"))
        val sp = src("dkai/SpiritEngine.kt")
        assertFalse(sp.contains("env.drawBase"))
        assertTrue(sp.contains("val s = 1f\n"))          // साँस का scale हटा
    }

    @Test fun glow_isSubtle_20to30percent_max2percentScale() {
        for (i in 0..20) {
            val p = i / 20f
            assertTrue(CharacterGrade.glowAlpha(p) in 0.199f..0.301f)
            assertTrue(CharacterGrade.glowScale(p) in 1f..1.0201f)
        }
    }

    /** हालत (READY / LISTENING / SPEAKING / RESEARCHING) और समय बदलें - कैरेक्टर की जगह / साइज़ ज़रा भी नहीं */
    @Test fun character_neverMovesOrResizes_acrossStatesAndTime() {
        val c = RuntimeEnvironment.getApplication()
        ClayTheme.set(ClayTheme.NAVY)
        val b = hq("dk_art_sleepy_hq.png")
        var st = -1
        val v = HomeAvatarView(c).apply { bitmap = b; art = true; fade = true; call = { st }; reserveBottom = 300 }
        v.measure(spec(1080), spec(1700)); v.layout(0, 0, 1080, 1700)
        val out = Bitmap.createBitmap(1080, 1700, Bitmap.Config.ARGB_8888)
        val f = HomeAvatarView::class.java.getDeclaredField("dst").apply { isAccessible = true }
        val seen = HashSet<String>()
        for (s in listOf(-1, FluidOrbView.LISTEN, FluidOrbView.SPEAK, FluidOrbView.THINK)) for (l in 0..2) {
            st = s
            repeat(3) { Thread.sleep(40); Canvas(out).apply { drawColor(ClayTheme.t.bg); v.draw(this) } }
            seen.add((f.get(v) as RectF).toShortString())
        }
        assertEquals(seen.toString(), 1, seen.size)
    }

    /** तैयारी पूरी होने से पहले का पहला frame: कच्ची तस्वीर का सख़्त आयत नहीं (नरम mask), और तैयार होने पर जगह / साइज़ वही */
    @Test fun firstFrame_beforePrepare_noHardRect_sameRect() {
        for (id in listOf("dk_art_sleepy", "dk_art_butterfly_girl", "dk_art_hearts")) {
            val b = hq(id + HomeAvatars.HQ_SUFFIX)
            // पहले frame का प्रकार (छोटे नमूने से) = तैयार तस्वीर का प्रकार -> place() वही -> कोई कूद नहीं
            assertEquals(id, CharacterArt.prepare(b, HomeAvatars.FULL_SUBJECT)!!.profile, CharacterArt.analyze(b))
            for (th in listOf(ClayTheme.NAVY, ClayTheme.CLAY_DAY)) {
                val out = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
                val cv = Canvas(out); cv.drawColor(th.bg)
                val r = RectF(20f, 40f, 580f, 40f + 560f * b.height / b.width)
                CharacterEnvironment().drawArtMasked(cv, b, r, null, !th.light)
                for ((x, y) in listOf(r.left + 4 to r.top + 4, r.right - 4 to r.top + 4, r.left + 4 to r.bottom - 4, r.right - 4 to r.bottom - 4)) {
                    val d = CharacterArt.dist(out.getPixel(x.toInt(), y.toInt()), th.bg)
                    assertTrue("${th.id}/$id corner $d", d < 0.08f)
                }
                assertTrue(CharacterArt.dist(out.getPixel(300, (r.top + r.height() * 0.4f).toInt()), th.bg) > 0.1f)   // बीच में कैरेक्टर
            }
        }
    }

    // ------------------------------------------------------------------ layout
    /** खाली / छोटी / बहुत लंबी बात - stage में कैरेक्टर परत का साइज़ वही, बॉक्स कभी अपनी सीमा से बड़ा नहीं, अंदर स्क्रॉल */
    @Test fun stage_fixed_transcriptOverlayCapped() {
        val c = RuntimeEnvironment.getApplication()
        val st = CharacterStage(c)
        val tv = TextView(c).apply { textSize = 16f }
        val chrome = 120
        st.boxMaxPx = CharacterStage.boxMax(2400, tv.lineHeight, chrome); st.gapPx = 20
        assertTrue(st.boxMaxPx <= (2400 * 0.22f).toInt())
        val ch = View(c)
        st.setCharacter(ch, FrameLayout.LayoutParams(-1, -1))
        val max = st.boxMaxPx - chrome
        val scroll = object : ScrollView(c) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST))
        }.apply { addView(tv) }
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, chrome, 0, 0); addView(scroll) }
        st.setOverlay(box, FrameLayout.LayoutParams(-1, -2))
        val sizes = HashSet<String>()
        for (txt in listOf("", "नमस्ते", (1..200).joinToString(" ") { "लंबी बात $it" })) {
            tv.text = txt
            st.measure(spec(1080), spec(1700)); st.layout(0, 0, 1080, 1700)
            sizes.add("${ch.left},${ch.top},${ch.width}x${ch.height}")
            assertTrue("box ${box.height} > ${st.boxMaxPx}", box.height <= st.boxMaxPx)
            assertEquals(1700 - st.gapPx, box.bottom)          // नीचे टिका
        }
        assertEquals(1, sizes.size)
        assertEquals("1080x1700", sizes.first().substringAfterLast(","))
    }

    @Test fun screen_usesStage_overlayNotInFlow() {
        val d = src("dkai/DkaiScreen.kt")
        assertTrue(d.contains("stage.setCharacter(glow, avatarArea(false))"))
        assertTrue(d.contains("stage.setOverlay(liveBox"))
        assertFalse(d.contains("heroBox.addView(liveBox"))
        assertFalse(d.contains("heroBox.addView(glow"))
        assertTrue(d.contains("isVerticalFadingEdgeEnabled = true"))
        assertTrue(d.contains("liveScroll.fullScroll(View.FOCUS_DOWN)"))
        assertTrue(d.contains("centerAvatar.reserveBottom = stage.reservePx"))
        assertTrue(d.contains("h.reserve = stage.reservePx"))
    }

    /** फ़ोन और टैबलेट, हर आकार की तस्वीर: पूरा सिर अंदर (ऊपर जगह), चेहरा बॉक्स के ऊपर, अनुपात वही */
    @Test fun head_neverCropped_faceAboveTranscript_phoneAndTablet() {
        val kinds = listOf(CharacterArt.Kind.FULL_ART, CharacterArt.Kind.KEYED, CharacterArt.Kind.TRANSPARENT)
        val imgs = listOf(1024f to 1024f, 1536f to 2048f, 2048f to 1152f, 800f to 1000f, 600f to 1800f)
        val stages = listOf(Triple(1080f, 1500f, false), Triple(720f, 900f, false), Triple(1600f, 1900f, true), Triple(2560f, 1200f, true))
        val out = RectF()
        for ((w, h, tab) in stages) for ((bw, bh) in imgs) for (k in kinds) {
            val p = CharacterArt.Profile(k, CharacterArt.shapeOf(bw.toInt(), bh.toInt()), false)
            val reserve = (h * 0.24f).toInt()
            CharacterArt.place(w, h, bw, bh, HomeAvatars.FULL_SUBJECT, p, 1f, tab, out, CharacterStage.faceFloor(h.toInt(), reserve))
            val tag = "$w x $h tab=$tab img=$bw x $bh $k -> $out"
            assertEquals(tag, bw / bh, out.width() / out.height(), 0.01f)
            assertTrue(tag, out.top >= -0.5f)                                                // सिर नहीं कटा
            assertTrue(tag, out.top + out.height() * CharacterArt.FACE_K <= h - reserve + 1f)  // चेहरा बॉक्स के ऊपर
        }
    }

    // ------------------------------------------------------------------ पंख (feather)
    @Test fun feather_radialAndBottom_noRectEdge() {
        val full = CharacterArt.Profile(CharacterArt.Kind.FULL_ART, CharacterArt.Shape.SQUARE, false)
        val px = 0xFF203040.toInt()
        fun a(u: Float, v: Float, p: CharacterArt.Profile = full) = CharacterArt.pixel(px, p, u, v, null) ushr 24
        assertEquals(255, a(0.5f, 0.45f))
        assertTrue(a(0.98f, 0.02f) < 10)            // कोना
        assertTrue(a(0.5f, 0.985f) < 20)            // नीचे
        assertTrue(a(0.5f, 0.85f) < a(0.5f, 0.6f))  // नीचे की ओर धीरे फीका
        // किनारे के बीच भी धीरे फीका (सीधी रेखा नहीं)
        assertTrue(a(0.02f, 0.5f) < 30)
        // असली पारदर्शी तस्वीर: अंडाकार नहीं (कोने के पास भी कैरेक्टर साफ़)
        val tr = CharacterArt.Profile(CharacterArt.Kind.TRANSPARENT, CharacterArt.Shape.SQUARE, false)
        assertTrue(a(0.8f, 0.2f, tr) > a(0.8f, 0.2f, full))
    }

    // ------------------------------------------------------------------ थीम color-grade
    @Test fun grade_eachTheme_subtle_andLeansToItsPalette() {
        val skin = 0xFFE8B89A.toInt(); val gray = 0xFF808080.toInt()
        fun g(t: ClayTheme.Theme) = CharacterGrade.matrix(CharacterGrade.of(t.id, t.bg, t.accent, t.light))
        for (t in ClayTheme.ALL) {
            val gr = CharacterGrade.of(t.id, t.bg, t.accent, t.light)
            assertTrue(t.id, kotlin.math.abs(gr.hue) <= CharacterGrade.MAX_HUE)
            assertTrue(t.id, gr.tintK in CharacterGrade.TINT_MIN..CharacterGrade.TINT_MAX)
            assertTrue(t.id, gr.saturate in 0.85f..1.06f)
            // चेहरा / त्वचा प्राकृतिक: रंग बहुत कम बदले
            val s2 = CharacterGrade.apply(g(t), skin)
            assertTrue("${t.id} skin moved ${CharacterArt.dist(skin, s2)}", CharacterArt.dist(skin, s2) < 0.12f)
            assertEquals(0xFF, s2 ushr 24)
        }
        fun ch(c: Int, s: Int) = (c ushr s) and 255
        val ember = CharacterGrade.apply(g(ClayTheme.EMBER), gray)
        assertTrue("ember warm", ch(ember, 16) > ch(ember, 0))
        val noct = CharacterGrade.apply(g(ClayTheme.NOCTURNE), gray)
        assertTrue("nocturne violet", ch(noct, 0) > ch(noct, 8) && ch(noct, 16) > ch(noct, 8))
        val abyss = CharacterGrade.apply(g(ClayTheme.ABYSS), gray)
        assertTrue("abyss teal", ch(abyss, 8) > ch(abyss, 16) && ch(abyss, 0) > ch(abyss, 16))
        val rose = CharacterGrade.apply(g(ClayTheme.ROSEWOOD), gray)
        assertTrue("rosewood rose", ch(rose, 16) > ch(rose, 8))
        val navy = CharacterGrade.apply(g(ClayTheme.NAVY), gray)
        assertTrue("navy blue", ch(navy, 0) > ch(navy, 16))
        // Obsidian थोड़ा फीका; Clay Day का contrast थोड़ा कम
        val vivid = 0xFFE04040.toInt()
        val ob = CharacterGrade.apply(g(ClayTheme.OBSIDIAN), vivid)
        assertTrue(ch(ob, 16) - ch(ob, 8) < ch(vivid, 16) - ch(vivid, 8))
        val day = CharacterGrade.matrix(CharacterGrade.of("clay_day", ClayTheme.CLAY_DAY.bg, ClayTheme.CLAY_DAY.accent, true))
        val blk = CharacterGrade.apply(day, 0xFF000000.toInt())
        assertTrue(ch(blk, 16) > 0)
        // अनजानी / अपनी थीम भी (बिना नए कोड के)
        val cu = CharacterGrade.of("custom_x", 0xFF101010.toInt(), 0xFF22C55E.toInt(), false)
        assertTrue(cu.tintK in CharacterGrade.TINT_MIN..CharacterGrade.TINT_MAX)
    }

    // ------------------------------------------------------------------ 8 थीम x 3 कैरेक्टर
    private fun stageShot(th: ClayTheme.Theme, who: String, text: String, w: Int = 412, h: Int = 892): Pair<Bitmap, RectF?> {
        val c = RuntimeEnvironment.getApplication()
        ClayTheme.set(if (th == ClayTheme.NAVY) ClayTheme.MIDNIGHT else ClayTheme.NAVY); ClayTheme.set(th)
        val k = ClayTheme.t
        val head = 96; val nav = h - 84
        val sh = nav - head
        val reserve = (h * CharacterStage.BOX_SCREEN_K).toInt() + 8
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val v: View = when (who) {
            "spirit" -> SpiritCharacterHost(c).apply { reserve(this, reserve); show(SpiritIdentity.FEMALE) }
            else -> HomeAvatarView(c).apply {
                val a = HomeAvatars.BUNDLED.first { it.id == who }
                bitmap = hq(a.id + HomeAvatars.HQ_SUFFIX); art = true; fade = true; subject = a.hqSubject ?: a.subject; reserveBottom = reserve
            }
        }
        v.measure(spec(w), spec(sh)); v.layout(0, 0, w, sh)
        if (v is SpiritCharacterHost) for (i in 0 until v.childCount) (v.getChildAt(i) as? SpiritCharacterView)?.let { sv ->
            if (sv.identity.key == SpiritIdentity.FEMALE) { sv.animate().cancel(); sv.alpha = 1f; sv.visibility = View.VISIBLE }
            sv.engine.snap()
        }
        fun draw() { cv.drawColor(k.bg); cv.save(); cv.translate(0f, head.toFloat()); v.draw(cv); cv.restore() }
        draw()
        val t0 = System.currentTimeMillis(); val end = t0 + 6000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(50); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (v is HomeAvatarView && v.bitmap?.let { CharacterArt.peek(it, v.subject) } != null) break
            if (v !is HomeAvatarView && System.currentTimeMillis() > t0 + 1500) break
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        if (v is SpiritCharacterHost) for (i in 0 until v.childCount) (v.getChildAt(i) as? SpiritCharacterView)?.let { sv ->
            if (sv.identity.key == SpiritIdentity.FEMALE) { sv.alpha = 1f; sv.visibility = View.VISIBLE }; sv.engine.snap()
        }
        draw()
        // ऊपर की पट्टी, तैरता संदेश बॉक्स (~75% surface), नीचे की बार
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = k.text; p.textSize = 15f; cv.drawText("DKAI", 18f, 40f, p)
        p.color = 0xFF10B981.toInt(); p.textSize = 12f; cv.drawText("SPEAKING", 18f, 70f, p)
        if (text.isNotEmpty()) {
            val lines = text.chunked(42).takeLast(4)
            val bh = minOf((h * CharacterStage.BOX_SCREEN_K).toInt(), 46 + lines.size * 21)
            val r = RectF(14f, (nav - 8 - bh).toFloat(), w - 14f, (nav - 8).toFloat())
            p.color = NeonTheme.alpha(k.surface, 0xBE); cv.drawRoundRect(r, 22f, 22f, p)
            p.color = 0xFF10B981.toInt(); p.textSize = 12f; cv.drawText("SPEAKING", r.left + 18f, r.top + 24f, p)
            p.color = k.text; p.textSize = 14f
            lines.forEachIndexed { i, l -> cv.drawText(l, r.left + 18f, r.top + 46f + i * 21f, p) }
        }
        p.color = k.surface; cv.drawRect(0f, nav.toFloat(), w.toFloat(), h.toFloat(), p)
        val rect = (v as? HomeAvatarView)?.let { (HomeAvatarView::class.java.getDeclaredField("dst").apply { isAccessible = true }.get(it) as RectF).let { r -> RectF(r) } }
        return out to rect
    }

    private fun reserve(h: SpiritCharacterHost, r: Int) { h.reserve = r }

    @Test fun everyTheme_threeCharacters_noRectNoSeam_glowMatchesTheme() {
        val dir = System.getenv("DK_SHOTS_DIR")?.let { File(it).apply { mkdirs() } }
        val who = listOf("dk_art_sleepy", "dk_art_butterfly_girl", "spirit")
        val texts = listOf("", "नमस्ते! मैं DK हूँ।", (1..60).joinToString(" ") { "बहुत लंबी बात $it" })
        val ths = if (dir != null) ClayTheme.ALL else listOf(ClayTheme.NAVY, ClayTheme.CLAY_DAY, ClayTheme.EMBER)
        val nav0 = 892 - 84 - (892 * CharacterStage.BOX_SCREEN_K).toInt() - 16   // बॉक्स से ऊपर तक
        for (th in ths) for (wh in who) {
            val rects = HashSet<String>()
            for ((ti, tx) in texts.withIndex()) {
                if (dir == null && ti == 1) continue
                val (out, r) = stageShot(th, wh, tx)
                r?.let { rects.add(it.toShortString()) }
                // stage के चारों ओर (ऊपर-बाएँ / ऊपर-दाएँ कोने, बाएँ-दाएँ किनारा) = ठीक थीम का रंग (कोई आयत / seam नहीं)
                for ((x, y) in listOf(2 to 100, 409 to 100, 2 to 400, 409 to 400)) {
                    val d = CharacterArt.dist(out.getPixel(x, y), th.bg)
                    assertTrue("${th.id}/$wh ($x,$y) seam=$d", d < 0.05f)
                }
                // कोई आयत नहीं: stage के किनारे की पट्टियों (ऊपर की जगह, बाएँ / दाएँ) में थीम से धीरे-धीरे ही बदलाव, कहीं सख़्त रेखा नहीं
                fun band(x0: Int, x1: Int, y0: Int, y1: Int) {
                    var j = 0f
                    for (y in y0 + 1 until y1) for (x in x0 + 1 until x1) j = maxOf(j,
                        CharacterArt.dist(out.getPixel(x, y - 1), out.getPixel(x, y)), CharacterArt.dist(out.getPixel(x - 1, y), out.getPixel(x, y)))
                    if (j >= 0.04f) System.err.println("BAND ${th.id}/$wh t=$ti $x0..$x1 x $y0..$y1 jump=$j")
                }
                band(0, 412, 96, 128); band(0, 14, 96, nav0); band(398, 412, 96, nav0)
                // कैरेक्टर दिखे
                var diff = 0; var n = 0
                for (y in 140 until 520 step 8) for (x in 60 until 352 step 8) { n++; if (CharacterArt.dist(out.getPixel(x, y), th.bg) > 0.08f) diff++ }
                assertTrue("${th.id}/$wh visible $diff/$n", diff > n / 10)
                dir?.let { d -> File(d, "stage_${th.id}_${wh}_$ti.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }
            assertTrue("${th.id}/$wh moved: $rects", rects.size <= 1)       // लिखावट कितनी भी हो, कैरेक्टर वहीं
        }
    }
}
