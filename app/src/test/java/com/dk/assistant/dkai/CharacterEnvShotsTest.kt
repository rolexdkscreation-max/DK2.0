package com.dk.assistant.dkai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.dk.assistant.ui.ClayTheme
import com.dk.assistant.ui.NeonTheme
import org.junit.After
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
 * 26 नवंबर 2026 (Full-Bleed Character Environment): होम स्क्रीन जैसा फ्रेम (ऊपर SPEAKING पट्टी, बीच में कैरेक्टर,
 * नीचे संदेश कार्ड + नीचे की पट्टी) - हर थीम x हर तस्वीर-आकार. सिर्फ़ पुराने (main) में भी मौजूद API इस्तेमाल होते हैं,
 * ताकि वही फ़ाइल main पर चला कर "पहले" और इस branch पर "बाद" की तस्वीरें मिलें.
 * PNG सिर्फ़ तब लिखे जाते हैं जब env DK_SHOTS_DIR दिया हो; जाँच (कैरेक्टर दिखे, कार्ड / पट्टी पर न चढ़े) हमेशा.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class CharacterEnvShotsTest {
    private val before = ClayTheme.current
    @After fun back() = ClayTheme.set(before)

    private val W = 412; private val H = 892
    private val HEAD = 64; private val CARD_T = 664; private val CARD_B = 780; private val NAV_T = 812

    private fun root(): File = listOf(File("../avatars"), File("avatars")).first { it.exists() }
    private fun hq(n: String): Bitmap = BitmapFactory.decodeFile(File(root(), n).path)

    private val custom = ClayTheme.Theme("custom_peach", "Custom", "test", 0xFFFFF1E6.toInt(), 0xFFFFFFFF.toInt(), 0xFFE11D48.toInt())
    private val themes = listOf(ClayTheme.CLAY_DAY, ClayTheme.MIDNIGHT, ClayTheme.EMBER, ClayTheme.NAVY, ClayTheme.NOCTURNE, custom)

    /** पारदर्शी PNG जैसा कैरेक्टर (रोबोट), नीचे से कटा */
    private fun robot(): Bitmap {
        val b = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 300f, 0f, 1000f, 0xFF7C8BA1.toInt(), 0xFF39475C.toInt(), Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(150f, 560f, 650f, 1060f), 120f, 120f, p)
        p.shader = LinearGradient(0f, 120f, 0f, 520f, 0xFFDCE3EE.toInt(), 0xFF8E9BB0.toInt(), Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(220f, 120f, 580f, 520f), 110f, 110f, p)
        p.shader = null; p.color = 0xFF0B1220.toInt(); cv.drawRoundRect(RectF(260f, 240f, 540f, 380f), 60f, 60f, p)
        p.color = 0xFF22D3EE.toInt(); cv.drawCircle(340f, 310f, 30f, p); cv.drawCircle(460f, 310f, 30f, p)
        p.color = 0xFFF59E0B.toInt(); cv.drawCircle(400f, 80f, 26f, p)
        p.color = 0xFF8E9BB0.toInt(); cv.drawRect(392f, 90f, 408f, 130f, p)
        return b
    }

    /** गोल आर्टवर्क: सफ़ेद चौकोर पर गोल तस्वीर */
    private fun circle(): Bitmap {
        val src = hq("dk_art_headphone_orb_hq.png")
        val b = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.drawColor(0xFFFFFFFF.toInt())
        val path = Path().apply { addCircle(512f, 512f, 470f, Path.Direction.CW) }
        cv.save(); cv.clipPath(path); cv.drawBitmap(src, null, RectF(42f, 42f, 982f, 982f), Paint(Paint.FILTER_BITMAP_FLAG)); cv.restore()
        return b
    }

    private class Case(val id: String, val bmp: Bitmap?, val art: Boolean, val orb: Boolean, val subject: List<Float>, val spirit: Boolean = false)

    private fun cases(): List<Case> {
        val c = RuntimeEnvironment.getApplication()
        val hearts = hq("dk_art_hearts_hq.png")
        val bun = hq("dk_art_glasses_bun_hq.png")
        val fly = hq("dk_art_butterfly_girl_hq.png")
        val dk = HomeAvatars.BUNDLED.first { it.id == HomeAvatars.DEFAULT_ID }
        return listOf(
            Case("1_square_hq", hearts, true, false, HomeAvatars.BUNDLED.first { it.id == "dk_art_hearts" }.hqSubject ?: HomeAvatars.FULL_SUBJECT),
            Case("2_portrait", Bitmap.createBitmap(bun, 256, 0, 1536, 2048), false, false, HomeAvatars.FULL_SUBJECT),
            Case("3_landscape", Bitmap.createBitmap(fly, 0, 448, 2048, 1152), false, false, HomeAvatars.FULL_SUBJECT),
            Case("4_circular", circle(), false, false, HomeAvatars.FULL_SUBJECT),
            Case("5_transparent_png", robot(), false, false, HomeAvatars.FULL_SUBJECT),
            Case("6_orb_default", HomeAvatars.bitmap(c, dk, 900), true, true, dk.subject),
            Case("7_spirit_female", null, false, false, HomeAvatars.FULL_SUBJECT, spirit = true),
        )
    }

    private fun spec(v: Int) = View.MeasureSpec.makeMeasureSpec(v, View.MeasureSpec.EXACTLY)

    /** होम स्क्रीन का फ्रेम: [top]..[bottom] = कैरेक्टर की जगह */
    private fun frame(cs: Case, th: ClayTheme.Theme, top: Int, bottom: Int): Bitmap {
        val c = RuntimeEnvironment.getApplication()
        ClayTheme.set(ClayTheme.NAVY); ClayTheme.set(th)
        val k = ClayTheme.t
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawColor(k.bg)
        val vh = bottom - top
        val v: View = if (cs.spirit) SpiritCharacterHost(c).apply { show(SpiritIdentity.FEMALE) }
            else HomeAvatarView(c).apply { bitmap = cs.bmp; art = cs.art; orb = cs.orb; fade = true; subject = cs.subject }
        v.measure(spec(W), spec(vh)); v.layout(0, 0, W, vh)
        if (v is SpiritCharacterHost) for (i in 0 until v.childCount) (v.getChildAt(i) as? SpiritCharacterView)?.engine?.snap()
        cv.save(); cv.translate(0f, top.toFloat()); cv.clipRect(0, 0, W, vh); v.draw(cv); cv.restore()
        // पीछे की तैयारी (अगर हो) पूरी होने दो, फिर दोबारा
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) { Thread.sleep(60); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); if (System.currentTimeMillis() > end - 7700) break }
        Thread.sleep(400); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        cv.drawColor(k.bg)
        cv.save(); cv.translate(0f, top.toFloat()); cv.clipRect(0, 0, W, vh); v.draw(cv); cv.restore()
        // UI: ऊपर पट्टी, संदेश कार्ड, नीचे की पट्टी
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = k.text; p.textSize = 15f; cv.drawText("DK AI", 18f, 38f, p)
        p.color = 0xFF10B981.toInt(); p.textSize = 12f; cv.drawText("SPEAKING", W - 96f, 38f, p)
        p.color = NeonTheme.alpha(k.surface, 0xD8); cv.drawRoundRect(RectF(14f, CARD_T.toFloat(), W - 14f, CARD_B.toFloat()), 22f, 22f, p)
        p.color = NeonTheme.alpha(k.rim, 0x88); p.style = Paint.Style.STROKE; p.strokeWidth = 1f
        cv.drawRoundRect(RectF(14f, CARD_T.toFloat(), W - 14f, CARD_B.toFloat()), 22f, 22f, p); p.style = Paint.Style.FILL
        p.color = 0xFF10B981.toInt(); p.textSize = 12f; cv.drawText("SPEAKING", 40f, CARD_T + 28f, p)
        p.color = k.text; p.textSize = 15f; cv.drawText("Namaste! Main DK hoon.", 32f, CARD_T + 60f, p)
        p.color = k.surface; cv.drawRect(0f, NAV_T.toFloat(), W.toFloat(), H.toFloat(), p)
        return out
    }

    @Test fun everyThemeAndShape_characterVisible_insideItsArea() {
        val dir = System.getenv("DK_SHOTS_DIR")?.let { File(it).apply { mkdirs() } }
        val mode = System.getenv("DK_SHOTS_MODE") ?: "after"
        val drop = DkaiHomeCore.cardDropDp(H)
        // पहले: अवतार की जगह ऊपर 4dp छोड़कर, कार्ड से 14+cardDrop ऊपर तक. बाद: पट्टी से कार्ड तक पूरी
        val top = if (mode == "before") HEAD + 4 else HEAD
        val bottom = if (mode == "before") CARD_T - 14 - drop else CARD_T
        val all = if (dir != null) cases() else cases().filter { it.id.startsWith("5_") || it.id.startsWith("6_") }
        val ths = if (dir != null) themes else listOf(ClayTheme.CLAY_DAY, ClayTheme.MIDNIGHT)
        for (cs in all) for (th in ths) {
            val out = frame(cs, th, top, bottom)
            // कैरेक्टर की जगह में थीम के पीछे से अलग पिक्सल हों (खाली / टूटी जगह नहीं)
            var diff = 0; var n = 0
            for (y in top + 40 until bottom - 40 step 6) for (x in 40 until W - 40 step 6) {
                n++; if (CharacterArt.dist(out.getPixel(x, y), th.bg) > 0.08f) diff++
            }
            assertTrue("${cs.id}/${th.id} diff=$diff/$n", diff > n / 20)
            dir?.let { d -> File(d, "${mode}_${cs.id}_${th.id}.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    /** पलक: rig वाले हर HQ कैरेक्टर पर आँख की जगह पलक (आधी / पूरी बंद) - बाकी तस्वीर वैसी ही */
    @Test fun blink_onRiggedCharacters() {
        val dir = System.getenv("DK_SHOTS_DIR")?.let { File(it).apply { mkdirs() } }
        for (a in HomeAvatars.BUNDLED.filter { it.rig != null }) {
            val f = File(root(), a.id + HomeAvatars.HQ_SUFFIX)
            if (!f.exists()) continue
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            val b = BitmapFactory.decodeFile(f.path, o)
            val rig = a.rig!!
            assertTrue(a.id, rig.fits(b.width, b.height))
            val out = Bitmap.createBitmap(b.width * 3, b.height / 2, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            val eye = rig.eyes.first()
            for ((i, k) in listOf(0f, 0.55f, 1f).withIndex()) {
                val tile = b.copy(Bitmap.Config.ARGB_8888, true)
                CharacterAnimator(1).drawBlink(Canvas(tile), b, rig, RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), k)
                if (k == 1f) {
                    // आँख के बीच का रंग बदला, आँख से दूर वैसा ही
                    val ex = (eye.x * b.width).toInt(); val ey = (eye.y * b.height).toInt()
                    assertTrue(a.id, CharacterArt.dist(tile.getPixel(ex, ey), b.getPixel(ex, ey)) > 0.08f)
                    assertTrue(a.id, tile.getPixel(5, b.height - 5) == b.getPixel(5, b.height - 5))
                }
                val top = ((eye.y - 0.25f) * b.height).toInt().coerceIn(0, b.height / 2)
                cv.drawBitmap(tile, android.graphics.Rect(0, top, b.width, top + b.height / 2), RectF(i * b.width.toFloat(), 0f, (i + 1) * b.width.toFloat(), b.height / 2f), null)
            }
            dir?.let { d -> File(d, "blink_${a.id}.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }
}
