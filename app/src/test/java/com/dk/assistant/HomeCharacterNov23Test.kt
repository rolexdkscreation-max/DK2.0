package com.dk.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import com.dk.assistant.dkai.FluidOrbView
import com.dk.assistant.dkai.HomeAvatarView
import com.dk.assistant.dkai.HomeAvatars
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
 * 23 नवंबर 2026 (मालिक का कोलाज): होम कैरेक्टर - कोलाज से कटे आर्टवर्क ऐप के अंदर (drawable-nodpi, असली पिक्सल),
 * डिफ़ॉल्ट = DK वेव ऑर्ब, पहले वाला अवतार हटा, कैरेक्टर कभी न कटे, किनारे फीके, वेव / साँस / धड़कन, अभिवादन ऊपर की पट्टी में,
 * Settings में "होम कैरेक्टर" प्रीव्यू के साथ.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class HomeCharacterNov23Test {
    private val before = ClayTheme.current
    @After fun back() = ClayTheme.set(before)

    private fun file(p: String): File = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(p: String): String = file("java/com/dk/assistant/$p").readText()

    @Test fun allArtworksBundled_nodpi_lossless() {
        assertEquals(11, HomeAvatars.BUNDLED.size)
        assertEquals(8, HomeAvatars.BUNDLED.count { !it.orb })
        assertEquals(3, HomeAvatars.BUNDLED.count { it.orb })
        for (a in HomeAvatars.BUNDLED) {
            val f = file("res/drawable-nodpi/${a.res}.png")
            assertTrue(a.id, f.length() > 1000)
            val head = f.readBytes().copyOf(8)
            assertTrue(a.id, HomeAvatars.isPng(head))            // PNG = बिना क्वालिटी घटाए
            assertEquals(4, a.subject.size)
        }
        // तितली ऑर्ब पर बीच में "DK"
        assertEquals("DK", HomeAvatars.BUNDLED.first { it.id == "orb_art_butterfly" }.badge)
    }

    @Test fun defaultIsDkWaveOrb_oldAvatarRemoved() {
        assertEquals("orb_art_dk_wave", HomeAvatars.DEFAULT_ID)
        assertEquals("orb_art_dk_wave", HomeAvatars.shownId(null))                    // कुछ न चुना = DK वेव ऑर्ब
        assertEquals("orb_art_dk_wave", HomeAvatars.shownId("avatar:dk_girl"))       // हटाया गया = डिफ़ॉल्ट
        assertNull(HomeAvatars.shownId(HomeAvatars.ORB))                              // ORB चुना = ORB ही
        assertEquals("dk_art_sleepy", HomeAvatars.shownId("avatar:dk_art_sleepy"))
        assertEquals(HomeAvatars.prefFor("orb_art_dk_wave"), HomeAvatars.choiceOf(null))
        assertEquals(HomeAvatars.ORB, HomeAvatars.choiceOf(HomeAvatars.ORB))
        assertFalse(HomeAvatars.merge(emptyList()).any { it.id == "dk_girl" })
        val idx = listOf(File("avatars/index.json"), File("../avatars/index.json")).first { it.exists() }.readText()
        assertFalse(idx.contains("dk_girl"))
    }

    @Test fun choiceRemembered_afterRestart() {
        val c = RuntimeEnvironment.getApplication()
        HomeAvatars.chooseAvatar(c, "dk_art_cat_ears")
        // नया पढ़ना (ऐप दोबारा खुलने जैसा) - SharedPreferences से
        assertEquals("dk_art_cat_ears", HomeAvatars.chosenId(c))
        HomeAvatars.chooseOrb(c)
        assertNull(HomeAvatars.chosenId(c))
    }

    @Test fun neverCutsCharacter_fitAtLeast() {
        // चौड़ी तस्वीर, लंबी स्क्रीन: subject (बीच 60%) पूरा अंदर
        val s = HomeAvatarView.artScale(400f, 600f, 100f, 70f, listOf(0.2f, 0f, 0.8f, 1f))
        assertTrue(100f * 0.6f * s <= 400.01f)
        assertTrue(70f * s <= 600.01f)
        assertTrue(s >= minOf(400f / 100f, 600f / 70f) - 0.001f)                       // कम से कम Fit
        // पूरा subject = Fit से ज़्यादा नहीं
        assertEquals(4f, HomeAvatarView.artScale(400f, 600f, 100f, 70f, HomeAvatars.FULL_SUBJECT), 0.001f)
        // टैबलेट: चौड़ाई सीमित
        assertTrue(HomeAvatarView.tabletScale(1000f, 700f, 100f, 70f, 20f) * 100f <= 1000f * HomeAvatarView.TABLET_W + 0.01f)
    }

    @Test fun animation_states() {
        assertEquals(0, HomeAvatarView.waveMode(-1))
        assertEquals(1, HomeAvatarView.waveMode(FluidOrbView.LISTEN))
        assertEquals(2, HomeAvatarView.waveMode(FluidOrbView.SPEAK))
        // बोलते समय वेव तेज़ और चमकदार; सुनते समय उल्टी दिशा (अलग ढंग)
        assertTrue(kotlin.math.abs(HomeAvatarView.waveSpeed(2)) > kotlin.math.abs(HomeAvatarView.waveSpeed(0)))
        assertTrue(HomeAvatarView.waveSpeed(1) < 0f && HomeAvatarView.waveSpeed(0) > 0f)
        assertTrue(HomeAvatarView.waveAlpha(2, false) > HomeAvatarView.waveAlpha(0, false))
        assertTrue(HomeAvatarView.waveAmp(2, 1f) > HomeAvatarView.waveAmp(2, 0f))
        // लाइट मोड में वेव फीकी नहीं
        assertTrue(HomeAvatarView.waveAlpha(0, true) >= HomeAvatarView.waveAlpha(0, false))
        // ऑर्ब आवाज़ के साथ धड़के, पर 8% से ज़्यादा नहीं; चुप = नहीं
        assertEquals(1f, HomeAvatarView.orbPulse(false, 1f), 0f)
        assertEquals(1.08f, HomeAvatarView.orbPulse(true, 1f), 0.0001f)
        // वेव बीच (कैरेक्टर) पर नहीं, दोनों तरफ़
        assertEquals(0f, HomeAvatarView.waveEnvelope(0f, 0.22f), 0f)
        assertTrue(HomeAvatarView.waveEnvelope(0.6f, 0.22f) > 0.9f)
        // बड़ा करने के कदम: 2x-2x, आख़िरी ठीक target
        assertEquals(listOf(190, 380, 760, 1440), HomeAvatars.upSteps(95, 1440))
        assertTrue(HomeAvatars.upSteps(2000, 1440).isEmpty())
    }

    @Test fun greetingInTopStrip_settingsGrid() {
        val d = src("dkai/DkaiScreen.kt")
        val head = d.substringAfter("private fun buildHeader").substringBefore("\n    private fun ")
        assertTrue(head.contains("greetSlot = LinearLayout(c)"))
        assertTrue(d.contains("slot.addView(greetTxt"))
        assertTrue(d.contains("centerAvatar.art = showAv && av?.res != null"))
        val s = src("SettingsLook.kt")
        assertTrue(s.contains("card(\"होम कैरेक्टर\""))
        assertTrue(s.contains("homeCharacterGrid(box)"))
        assertTrue(s.contains("H.thumb(this@homeCharacterGrid, it)"))
        assertTrue(src("dkai/HomeAvatars.kt").contains("inScaled = false"))             // nodpi: असली पिक्सल
    }

    @Test fun drawsInDarkAndLight_characterVisible() {
        val c = RuntimeEnvironment.getApplication()
        for (th in listOf(ClayTheme.MIDNIGHT, ClayTheme.CLAY_DAY)) {
            ClayTheme.set(ClayTheme.NAVY); ClayTheme.set(th)
            val a = HomeAvatars.BUNDLED.first { it.id == HomeAvatars.DEFAULT_ID }
            val bmp = HomeAvatars.bitmap(c, a, 800)
            assertNotNull(bmp)
            assertTrue(maxOf(bmp!!.width, bmp.height) >= 700)                        // फ़िल्टर के साथ बड़ा
            val v = HomeAvatarView(c).apply { bitmap = bmp; art = true; orb = true; subject = a.subject }
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 400, 600)
            val out = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            Canvas(out).apply { drawColor(th.bg); v.draw(this) }
            // बीच (ऑर्ब) साफ़ दिखे, किनारा थीम का पीछा
            val mid = out.getPixel(200, 300)
            assertTrue(th.id, com.dk.assistant.ui.NeonTheme.luminance(mid) > 0.05)
            assertEquals(th.id, th.bg, out.getPixel(0, 0))
        }
    }
}
