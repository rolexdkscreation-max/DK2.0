package com.dk.assistant.dkai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 26 नवंबर 2026 (मालिक): होलोग्राफ़िक 3D ORB - धुएँ / रेशम जैसी रोशनी से बने बिना चेहरे वाले महिला / पुरुष कैरेक्टर
 * ([CharacterOrbView] + assets/orb/orb_avatar.html), ORB की जगह और पुराने डॉट वाले "पुरुष / महिला चेहरा" की जगह.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class HoloOrbNov26Test {
    private fun file(rel: String): File = listOf(File("src/main/$rel"), File("app/src/main/$rel")).first { it.exists() }
    private fun src(rel: String) = file("java/com/dk/assistant/$rel").readText()
    private val html by lazy { file("assets/orb/orb_avatar.html").readText() }

    @Test fun jsBridgeIsSafe() {
        assertEquals("female", CharacterOrbView.norm("female"))
        assertEquals("male", CharacterOrbView.norm(" MALE "))
        assertEquals("female", CharacterOrbView.norm("');alert(1);//"))     // JS में कभी बाहर का text नहीं
        assertEquals("female", CharacterOrbView.norm(null))
        assertEquals("window.switchCharacter&&window.switchCharacter('male')", CharacterOrbView.switchJs("male"))
        assertEquals("window.setAudioLevel&&window.setAudioLevel(0.500)", CharacterOrbView.levelJs(0.5f))
        assertEquals("window.setAudioLevel&&window.setAudioLevel(1.000)", CharacterOrbView.levelJs(7f))
        assertEquals("window.setAudioLevel&&window.setAudioLevel(0.000)", CharacterOrbView.levelJs(Float.NaN))
        assertEquals(0f, CharacterOrbView.clampLevel(-2f), 0f)
        assertEquals("https://appassets.androidplatform.net/assets/orb/orb_avatar.html?c=male", CharacterOrbView.url("male"))
        assertEquals(280f, CharacterOrbView.DEFAULT_DP, 0f)
    }

    @Test fun amplitudeSentOnlyWhenChanged() {
        assertTrue(CharacterOrbView.changed(-1f, 0f))          // पहली बार हमेशा
        assertFalse(CharacterOrbView.changed(0.40f, 0.405f))   // ज़रा-सा फ़र्क = हर फ़्रेम JS नहीं
        assertTrue(CharacterOrbView.changed(0.40f, 0.42f))
        assertTrue(CharacterOrbView.changed(0.005f, 0f))        // चुप होते ही ज़रूर
        assertFalse(CharacterOrbView.changed(0f, 0f))
    }

    @Test fun choicesReplaceOldFaces() {
        val keys = DkaiOrbs.ORBS.map { it.first }
        assertTrue(DkaiOrbs.HOLO_FEMALE in keys && DkaiOrbs.HOLO_MALE in keys)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(DkaiOrbs.HOLO_MALE, DkaiOrbs.valid(DkaiOrbs.HOLO_MALE))
        assertEquals(3, DkaiOrbs.palette(DkaiOrbs.HOLO_FEMALE).size)
        assertEquals(0xFF8CE8F5.toInt(), DkaiOrbs.palette(DkaiOrbs.HOLO_MALE)[0])
        assertEquals("female", DkaiOrbs.holo(DkaiOrbs.HOLO_FEMALE, "earth"))
        assertEquals("male", DkaiOrbs.holo(DkaiOrbs.HOLO_MALE, null))
        // पुराना DK orb में पुराना पुरुष / महिला चेहरा = अब होलोग्राफ़िक कैरेक्टर
        assertEquals("male", DkaiOrbs.holo(DkaiOrbs.CLASSIC, "face_male"))
        assertEquals("female", DkaiOrbs.holo(DkaiOrbs.CLASSIC, "face_female"))
        assertNull(DkaiOrbs.holo(DkaiOrbs.CLASSIC, "earth"))
        assertNull(DkaiOrbs.holo(DkaiOrbs.FLUID, "face_male"))
        assertNull(DkaiOrbs.holo(null, null))
        // पहले जैसा: शुरू में Fluid
        val c = RuntimeEnvironment.getApplication()
        assertEquals(DkaiOrbs.FLUID, DkaiOrbs.get(c))
        assertNull(DkaiOrbs.holo(c))
        DkaiOrbs.set(c, DkaiOrbs.HOLO_MALE)
        assertEquals("male", DkaiOrbs.holo(c))
        DkaiOrbs.set(c, DkaiOrbs.FLUID)
    }

    @Test fun thumbnailDrawn() {
        for (female in listOf(true, false)) {
            val b = CharacterOrbView.thumb(128, female)
            assertEquals(128, b.width)
            val mid = b.getPixel(64, 47)                                   // चेहरे का बीच = चमकता
            assertTrue((mid ushr 24) > 0xC0 && (mid and 0xFF) > 0xA0)
            assertEquals(0, b.getPixel(0, 0) ushr 24)                     // कोना = पारदर्शी (गोल किनारा)
        }
    }

    @Test fun viewDefaultsTo280dpAndPassesTouches() {
        val c = RuntimeEnvironment.getApplication()
        val v = CharacterOrbView(c)
        val spec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        v.measure(spec, spec)
        assertEquals((280 * c.resources.displayMetrics.density).toInt(), v.measuredWidth)
        v.switchToMale(); assertEquals("male", v.character)
        v.switchToFemale(); assertEquals("female", v.character)
        v.updateVoiceAmplitude(0.7f)                                          // page तैयार न हो तब भी गिरे नहीं
        assertTrue(v.onInterceptTouchEvent(null))
        assertFalse(v.onTouchEvent(null))
        val s = src("dkai/CharacterOrbView.kt")
        assertTrue(s.contains("LAYER_TYPE_HARDWARE"))
        assertTrue(s.contains("WebViewAssetLoader.AssetsPathHandler"))
        assertTrue(s.contains("onRenderProcessGone"))
        assertTrue(s.contains("fun switchToFemale()") && s.contains("fun switchToMale()") && s.contains("fun updateVoiceAmplitude(amplitude: Float)"))
        assertFalse(s.contains("addJavascriptInterface"))                     // page से ऐप में कुछ नहीं आता
    }

    @Test fun sceneMatchesSpec() {
        // Three.js वही जो ऐप में पहले से है (APK नहीं बढ़ा); कोई network नहीं
        assertTrue(html.contains("\"three\":\"../forge/three.module.min.js\""))
        assertTrue(file("assets/forge/three.module.min.js").exists())
        assertTrue(html.contains("connect-src 'none'"))
        assertFalse(html.contains("http://") || Regex("https://(?!www\\.)").containsMatchIn(html))
        assertTrue(file("assets/orb/orb_avatar.html").length() < 64 * 1024)
        // रंग, काला पीछा, किनारा घुलता
        assertTrue(html.contains("#8CE8F5") && html.contains("#E0FAFF"))
        assertTrue(html.contains("setClearColor(0x000000, 1)") && html.contains("radial-gradient(circle closest-side"))
        // धुआँ: additive, 3D simplex + FBM, समय * 0.15
        assertTrue(html.contains("THREE.AdditiveBlending"))
        assertTrue(html.contains("float snoise(vec3 v)") && html.contains("float fbm(vec3 p)"))
        assertTrue(html.contains("uTime * 0.15"))
        // चिकनी रोशनी: lerp 0.05
        assertTrue(html.contains("const LERP = 0.05;"))
        assertTrue(html.contains("level = ease(level, target, dt, LERP)") && html.contains("glow = ease(glow,"))
        // Android की कॉल
        for (f in listOf("window.switchCharacter = function", "window.setAudioLevel = function", "window.setPaused = function")) assertTrue(f, html.contains(f))
        assertTrue(html.contains("float hairF(") && html.contains("float hairM(") && html.contains("float sdHead(") && html.contains("float sdBody("))
        // बिना चेहरे: आँख / नाक / मुँह कुछ नहीं
        assertFalse(Regex("\\b(eyes?|nose|mouth|lips)\\b").containsMatchIn(html.lowercase()))
    }

    @Test fun shownWhereOrbIs() {
        val home = src("dkai/DkaiScreen.kt")
        assertTrue(home.contains("val holo = DkaiOrbs.holo(c)"))
        assertTrue(home.contains("centerHolo = CharacterOrbView(c).apply { level = homeLevel }"))
        assertTrue(home.contains("centerOrb.visibility = if (!showAv && !classic && !showHolo)"))
        val live = src("dkai/DkaiVoice.kt")
        assertTrue(live.contains("holoOrb = CharacterOrbView(c).apply { level = this@DkaiVoice.level }"))
        assertTrue(live.contains("orbBox.addView(holoOrb"))
        val look = src("SettingsLook.kt")
        assertTrue(look.contains("listOf<Any?>(null, O.HOLO_FEMALE, O.HOLO_MALE)"))
        assertTrue(look.contains("CharacterOrbView.thumb("))
    }
}
