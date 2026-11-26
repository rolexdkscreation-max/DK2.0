package com.dk.assistant.dkai

import com.dk.assistant.Mode
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
 * 26 नवंबर 2026 (मालिक की तस्वीर): ethereal AI महिला / पुरुष कैरेक्टर - असली आर्टवर्क हूबहू, SCREEN blend,
 * एक साझा इंजन, आवाज़ की हालत एक साफ़ पुल से. होलोग्राफ़िक 3D ORB पूरी तरह हटा.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class SpiritCharNov26Test {
    private fun file(rel: String): File = listOf(File("src/main/$rel"), File("app/src/main/$rel")).first { it.exists() }
    private fun src(rel: String) = file("java/com/dk/assistant/$rel").readText()
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun bridgeMapsVoiceStates() {
        var m = Mode.IDLE
        val b = SpiritVoiceBridge(mode = { m }, amp = { 0.7f })
        assertEquals(SpiritState.IDLE, b.tick(1000))
        m = Mode.LISTENING; assertEquals(SpiritState.LISTENING, b.tick(1100))
        m = Mode.THINKING; assertEquals(SpiritState.THINKING, b.tick(1200))
        m = Mode.SPEAKING; assertEquals(SpiritState.SPEAKING, b.tick(1300)); assertEquals(0.7f, b.amplitude, 0.001f)
        m = Mode.WORKING; assertEquals(SpiritState.ACTIVE, b.tick(1400)); assertEquals(-1f, b.amplitude, 0.001f)
        // काम खत्म = थोड़ी देर ACTIVE, फिर अपने-आप IDLE
        m = Mode.IDLE; assertEquals(SpiritState.ACTIVE, b.tick(1500))
        assertEquals(SpiritState.IDLE, b.tick(1500 + SpiritVoiceBridge.ACTIVE_HOLD_MS + 10))
    }

    @Test fun speakingWithoutAmplitudeIsSoftNotFake() {
        val b = SpiritVoiceBridge(mode = { Mode.SPEAKING }, amp = { null })
        assertEquals(SpiritState.SPEAKING, b.tick(5))
        assertEquals(-1f, b.amplitude, 0.001f)
        val boom = SpiritVoiceBridge(mode = { throw IllegalStateException("x") }, amp = { null })
        assertEquals(SpiritState.IDLE, boom.tick(5))   // आवाज़ सिस्टम की गड़बड़ी से कैरेक्टर नहीं गिरता
    }

    @Test fun auraIsGentleAndOrdered() {
        val idle = SpiritVoiceBridge.aura(SpiritState.IDLE)
        assertTrue(SpiritVoiceBridge.aura(SpiritState.LISTENING) > idle)
        assertTrue(SpiritVoiceBridge.aura(SpiritState.THINKING) > idle)
        assertTrue(SpiritVoiceBridge.aura(SpiritState.ACTIVE) > SpiritVoiceBridge.aura(SpiritState.LISTENING))
        for (s in SpiritState.values()) assertTrue(SpiritVoiceBridge.aura(s) in 0.2f..0.7f)
    }

    @Test fun orbKeysAndOldFacesMapToSpirit() {
        assertEquals(SpiritIdentity.FEMALE, DkaiOrbs.spirit(DkaiOrbs.SPIRIT_FEMALE, null))
        assertEquals(SpiritIdentity.MALE, DkaiOrbs.spirit(DkaiOrbs.SPIRIT_MALE, null))
        assertEquals(SpiritIdentity.FEMALE, DkaiOrbs.spirit(DkaiOrbs.CLASSIC, "face_female"))
        assertEquals(SpiritIdentity.MALE, DkaiOrbs.spirit(DkaiOrbs.CLASSIC, "face_male"))
        assertNull(DkaiOrbs.spirit(DkaiOrbs.CLASSIC, "sphere"))
        assertNull(DkaiOrbs.spirit(DkaiOrbs.FLUID, null))
        DkaiOrbs.set(ctx, DkaiOrbs.SPIRIT_MALE)
        assertEquals(DkaiOrbs.SPIRIT_MALE, DkaiOrbs.get(ctx))   // dk_prefs में सेव
        assertEquals(3, DkaiOrbs.palette(DkaiOrbs.SPIRIT_FEMALE).size)
    }

    @Test fun artworkIsBundledExactlyAndSmall() {
        for (n in listOf("spirit_female", "spirit_male")) {
            val f = file("res/drawable-nodpi/$n.webp")
            assertTrue(f.length() in 50_000L until 1_000_000L)
        }
        val fb = SpiritArt.get(ctx, SpiritIdentity.FEMALE_ID.res)
        val mb = SpiritArt.get(ctx, SpiritIdentity.MALE_ID.res)
        assertNotNull(fb); assertNotNull(mb)
        assertEquals(412, fb!!.width); assertEquals(450, fb.height)     // बिना scale, असली साइज़
        assertEquals(388, mb!!.width); assertEquals(450, mb.height)
        assertTrue(fb === SpiritArt.get(ctx, SpiritIdentity.FEMALE_ID.res))   // cache
    }

    @Test fun viewsDrawAndHostShowsOneAtATime() {
        val host = SpiritCharacterHost(ctx)
        host.show(SpiritIdentity.FEMALE)
        host.measure(android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 720, 1200)
        val f = host.view(SpiritIdentity.FEMALE)
        assertTrue(f is FemaleCharacterScreen)
        val bmp = android.graphics.Bitmap.createBitmap(720, 1200, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        cv.drawColor(0xFF0B0F1A.toInt())
        repeat(30) { f.draw(cv) }
        // fit: पूरा कैरेक्टर, अनुपात वही, कभी न कटे
        val d = f.engine.dst
        assertTrue(d.left >= 0f && d.top >= 0f && d.right <= 720.5f && d.bottom <= 1200.5f)
        assertEquals(412f / 450f, d.width() / d.height(), 0.01f)
        host.show(SpiritIdentity.MALE)
        val m = host.view(SpiritIdentity.MALE)
        assertTrue(m is MaleCharacterScreen)
        assertEquals(SpiritIdentity.MALE, host.current)
        // नया तब तक नहीं दिखता जब तक पुराना पूरी तरह ओझल न हो
        assertFalse(f.visibility == android.view.View.VISIBLE && m.visibility == android.view.View.VISIBLE)
        assertFalse(f.hasOverlappingRendering())
    }

    @Test fun reducedModePrefSaved() {
        SpiritFx.setReduced(ctx, true); assertTrue(SpiritFx.reducedPref(ctx)); assertTrue(SpiritFx.reduced(ctx))
        SpiritFx.setReduced(ctx, false); assertFalse(SpiritFx.reducedPref(ctx))
    }

    @Test fun engineUsesScreenBlendMeshAndNoHolo() {
        val e = src("dkai/SpiritEngine.kt")
        assertTrue(e.contains("PorterDuff.Mode.SCREEN"))
        assertTrue(e.contains("drawBitmapMesh"))
        assertTrue(e.contains("inScaled = false"))
        assertFalse(e.contains("BlurMaskFilter"))
        val v = src("dkai/SpiritCharacterView.kt")
        assertTrue(v.contains("onVisibilityAggregated"))
        assertTrue(v.contains("onDetachedFromWindow"))
        // होलो ORB पूरी तरह हटा
        assertFalse(File("src/main/assets/orb/orb_avatar.html").exists() || File("app/src/main/assets/orb/orb_avatar.html").exists())
        for (f in listOf("dkai/DkaiScreen.kt", "dkai/DkaiVoice.kt", "dkai/DkaiOrbs.kt", "SettingsLook.kt")) assertFalse(src(f).contains("CharacterOrbView"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("SpiritCharacterHost"))
        assertTrue(src("dkai/DkaiVoice.kt").contains("SpiritCharacterHost"))
    }
}
