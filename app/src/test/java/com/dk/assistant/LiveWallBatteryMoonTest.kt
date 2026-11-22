package com.dk.assistant

import com.dk.assistant.livewall.LiveWallPower
import com.dk.assistant.livewall.SkyAstro
import com.dk.assistant.livewall.SkyCatalog
import com.dk.assistant.livewall.SkyGfx
import com.dk.assistant.livewall.SkyLight
import com.dk.assistant.livewall.SkyScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * 22 नवंबर 2026 (मालिक): "वॉलपेपर लगा है तो बैटरी खत्म नहीं होनी चाहिए" +
 * "5 बजे सूरज और चाँद दोनों दिख रहे थे - सूरज के समय सिर्फ़ सूरज, सूरज डूबे तब चाँद".
 */
class LiveWallBatteryMoonTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun repo(): File {
        var d: File? = File("").absoluteFile
        while (d != null) { if (File(d, "wallpapers/catalog.json").isFile) return d; d = d.parentFile }
        error("repo नहीं मिला")
    }

    // ------------------------------------------------------------ बैटरी
    @Test fun saveMode_animatesOnlyBriefly_thenStill() {
        val m = LiveWallPower.MODE_SAVE
        assertEquals(LiveWallPower.ANIM_MS, LiveWallPower.nextDelay(m, 0, false, 80, false))
        assertEquals(LiveWallPower.ANIM_MS, LiveWallPower.nextDelay(m, LiveWallPower.BURST_MS - 1, false, 80, false))
        assertEquals(LiveWallPower.STILL_MS, LiveWallPower.nextDelay(m, LiveWallPower.BURST_MS, false, 80, false))
        assertEquals(LiveWallPower.STILL_MS, LiveWallPower.nextDelay(m, 10 * 60_000L, false, 80, false))
        assertTrue("रुकी तस्वीर कम से कम 20 सेकंड", LiveWallPower.STILL_MS >= 20_000L)
        assertTrue("चलना सिर्फ़ कुछ सेकंड", LiveWallPower.BURST_MS <= 10_000L)
    }

    @Test fun powerSaverOrLowBattery_alwaysStill_evenSmooth() {
        for (m in listOf(LiveWallPower.MODE_SAVE, LiveWallPower.MODE_SMOOTH)) {
            assertEquals(LiveWallPower.STILL_MS, LiveWallPower.nextDelay(m, 0, true, 90, false))
            assertEquals(LiveWallPower.STILL_MS, LiveWallPower.nextDelay(m, 0, false, 15, false))
        }
        assertEquals(LiveWallPower.ANIM_MS, LiveWallPower.nextDelay(LiveWallPower.MODE_SAVE, 0, false, 15, true))   // चार्जर लगा
        assertEquals(LiveWallPower.SMOOTH_MS, LiveWallPower.nextDelay(LiveWallPower.MODE_SMOOTH, 60_000, false, 80, false))
    }

    @Test fun service_usesPowerRules_andDefaultIsSave() {
        val svc = File(repo(), "app/src/main/java/com/dk/assistant/livewall/LiveWallpaperService.kt").readText()
        assertTrue(svc.contains("LiveWallPower.nextDelay("))
        assertFalse("पुराना हमेशा 24fps नहीं", svc.contains("if (ps) 83L else 41L"))
        val store = File(repo(), "app/src/main/java/com/dk/assistant/livewall/LiveWallStore.kt").readText()
        assertTrue(store.contains("getString(K_ANIM, LiveWallPower.MODE_SAVE)"))
    }

    // ------------------------------------------------------------ दिन में सिर्फ़ सूरज, रात में चाँद
    @Test fun moonVisibility_followsSun() {
        assertEquals(0.0, SkyLight.moonVisible(30.0), 1e-9)
        assertEquals(0.0, SkyLight.moonVisible(0.0), 1e-9)
        assertEquals(1.0, SkyLight.moonVisible(-6.0), 1e-9)
        assertEquals(1.0, SkyLight.moonVisible(-40.0), 1e-9)
        val mid = SkyLight.moonVisible(-3.0)
        assertTrue(mid > 0.2 && mid < 0.8)
    }

    private class Rec : SkyGfx {
        val alpha = HashMap<String, Float>()
        override fun layer(id: String, sx: Float, sy: Float, sw: Float, sh: Float, dx: Float, dy: Float, dw: Float, dh: Float, mul: Int, add: Int, alpha: Float, flipV: Boolean) {
            if (!flipV) this.alpha[id] = maxOf(this.alpha[id] ?: 0f, alpha)
        }
        override fun glow(x: Float, y: Float, rx: Float, ry: Float, color: Int, alpha: Float) = Unit
        override fun dot(x: Float, y: Float, r: Float, color: Int, alpha: Float) = Unit
        override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, alpha: Float) = Unit
        override fun upload(id: String, px: IntArray, w: Int, h: Int) = Unit
    }

    private fun scene(): SkyScene {
        val pack = SkyCatalog.parseScene(File(repo(), "wallpapers/sun_moon_lake/v1/scene.json").readText())!!
        return SkyScene(pack, emptyList(), null, 0, { intArrayOf(1000, 200) }).apply { lat = 18.63; lon = 73.80; resize(1080, 2400) }
    }

    @Test fun at5pm_moonIsUpButOnlySunShown() {
        val t = ms("2026-11-22T11:30:00Z")   // 17:00 IST - असल में चाँद पूरब में ऊपर है
        assertTrue("चाँद आसमान में है", SkyAstro.moon(t, 18.63, 73.80).pos.alt > 10)
        val s = scene(); val g = Rec()
        s.frame(g, t, 0.0, 0.5f)
        assertTrue(SkyScene.SUN in g.alpha)
        assertFalse("दिन में चाँद नहीं", (g.alpha[SkyScene.MOON] ?: 0f) > 0f)
        assertEquals(0.0, s.state.moonVis, 1e-9)
    }

    @Test fun afterSunset_moonComes_sunGone() {
        val t = ms("2026-11-22T14:00:00Z")   // 19:30 IST
        val s = scene(); val g = Rec()
        s.frame(g, t, 0.0, 0.5f)
        assertFalse("रात में सूरज नहीं", SkyScene.SUN in g.alpha)
        assertTrue("चाँद पूरा", (g.alpha[SkyScene.MOON] ?: 0f) > 0.95f)
    }

    @Test fun beforeSunrise_moonFadesAway() {
        // 23 नवंबर 06:50 IST (सूर्योदय से ठीक पहले) - चाँद (अगर ऊपर हो) लगभग छिप चुका
        val s = scene(); s.update(ms("2026-11-23T01:20:00Z"))
        assertTrue(s.state.moonVis < 0.3)
    }
}
