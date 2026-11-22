package com.dk.assistant

import com.dk.assistant.livewall.LiveWallBuiltin
import com.dk.assistant.livewall.SkyAstro
import com.dk.assistant.livewall.SkyCatalog
import com.dk.assistant.livewall.SkyGfx
import com.dk.assistant.livewall.SkyPack
import com.dk.assistant.livewall.SkyScene
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 22 नवंबर 2026 (मालिक): DK लाइव के अंदर लाइव वॉलपेपर - सूरज/चाँद असली समय-जगह पर, आसमान भौतिकी से,
 * पैक इंटरनेट से (हमारी repo से, SHA-256 जाँच के साथ), होम + लॉक स्क्रीन.
 * संदर्भ-मान PyEphem (पुणे 18.63N 73.80E) से.
 */
class LiveWallpaperTest {
    private val lat = 18.63
    private val lon = 73.80
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun repo(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            if (File(d, "wallpapers/catalog.json").isFile) return d
            d = d.parentFile
        }
        error("wallpapers/catalog.json नहीं मिला")
    }

    // ------------------------------------------------------------ खगोल (असली सूरज-चाँद)
    @Test fun sunPositionMatchesEphemeris() {
        val noon = SkyAstro.sun(ms("2026-11-22T06:50:00Z"), lat, lon)   // 12:20 IST
        assertEquals(51.24, noon.alt, 0.15)
        assertEquals(179.68, noon.az, 0.3)
        val rise = SkyAstro.sun(ms("2026-11-22T01:30:00Z"), lat, lon)   // 07:00 IST
        assertEquals(2.64, rise.alt, 0.25)
        assertTrue("सुबह सूरज पूरब में", rise.az in 100.0..125.0)
        val june = SkyAstro.sun(ms("2026-06-21T07:00:00Z"), lat, lon)
        assertEquals(84.95, june.alt, 0.3)
    }

    @Test fun sunriseAndSunsetHappenAtRealTime() {
        // पुणे 22 नवंबर: सूर्योदय ~6:52, सूर्यास्त ~17:58 IST
        assertTrue(SkyAstro.sun(ms("2026-11-22T01:10:00Z"), lat, lon).alt < 0)   // 6:40
        assertTrue(SkyAstro.sun(ms("2026-11-22T01:35:00Z"), lat, lon).alt > 0)   // 7:05
        assertTrue(SkyAstro.sun(ms("2026-11-22T12:15:00Z"), lat, lon).alt > 0)   // 17:45
        assertTrue(SkyAstro.sun(ms("2026-11-22T12:40:00Z"), lat, lon).alt < 0)   // 18:10
        assertTrue(SkyAstro.sun(ms("2026-11-22T18:30:00Z"), lat, lon).alt < -60) // आधी रात
    }

    @Test fun moonPhaseIsReal() {
        val full = SkyAstro.moon(ms("2024-01-25T17:54:00Z"), lat, lon)   // पूर्णिमा
        assertTrue("पूर्णिमा ${full.illum}", full.illum > 0.99)
        val newM = SkyAstro.moon(ms("2024-01-11T11:57:00Z"), lat, lon)   // अमावस
        assertTrue("अमावस ${newM.illum}", newM.illum < 0.01)
        val wax = SkyAstro.moon(ms("2024-01-18T12:00:00Z"), lat, lon)
        assertTrue(wax.waxing)
        assertTrue(wax.illum in 0.4..0.75)
    }

    // ------------------------------------------------------------ दृश्य: सूरज कहाँ, रंग कैसा
    private fun scene(): SkyScene {
        val pack = SkyCatalog.parseScene(File(repo(), "wallpapers/sun_moon_lake/v1/scene.json").readText())!!
        return SkyScene(pack, emptyList(), null, 0, { intArrayOf(1000, 200) }).apply { lat = this@LiveWallpaperTest.lat; lon = this@LiveWallpaperTest.lon; resize(1080, 2400) }
    }

    @Test fun sunMovesEastToWestAndHighAtNoon() {
        val s = scene()
        s.update(ms("2026-11-22T02:30:00Z")); val x8 = s.state.sunX; val y8 = s.state.sunY     // 8:00
        s.update(ms("2026-11-22T06:50:00Z")); val x12 = s.state.sunX; val y12 = s.state.sunY   // 12:20
        s.update(ms("2026-11-22T11:30:00Z")); val x17 = s.state.sunX; val y17 = s.state.sunY   // 17:00
        assertTrue("सुबह बाएँ", x8 < 1080 * 0.4f)
        assertEquals("दोपहर बीच में", 540f, x12, 30f)
        assertTrue("शाम दाएँ", x17 > 1080 * 0.6f)
        assertTrue("दोपहर सबसे ऊपर", y12 < y8 && y12 < y17)
    }

    private fun lum(c: DoubleArray) = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

    @Test fun skyColorsAreRealistic() {
        val s = scene(); val c = DoubleArray(3)
        // दोपहर: ऊपर गहरा-चटक नीला, उजाला
        s.update(ms("2026-11-22T06:50:00Z")); s.skyColor(80.0, 0.0, c)
        assertTrue("दोपहर नीला ${c.toList()}", c[2] > c[0] * 1.3 && lum(c) > 0.12)
        // सूर्यास्त: सूरज की तरफ़ क्षितिज नारंगी
        s.update(ms("2026-11-22T12:25:00Z"))
        val dAz = (s.state.sunX - 540f) / (0.62f * 2400 / 90f)
        s.skyColor(1.0, dAz.toDouble(), c)
        assertTrue("सूर्यास्त नारंगी ${c.toList()}", c[0] > c[2] && c[0] > 0.3)
        // अमावस की आधी रात: एकदम अँधेरा (तारे दिखें)
        s.update(ms("2026-11-09T18:30:00Z")); s.skyColor(70.0, 0.0, c)
        assertTrue("रात अँधेरी ${c.toList()}", lum(c) < 0.02)
        assertTrue(s.state.night > 0.95)
        // चाँदनी रात: ठंडा नीला, पर दिन से बहुत कम
        s.update(ms("2026-11-22T18:30:00Z")); s.skyColor(70.0, 0.0, c)
        assertTrue("चाँदनी नीली ${c.toList()}", c[2] > c[0] && lum(c) < 0.12)
    }

    @Test fun frameDrawsSkyFirstNearLastAndNoSunAtNight() {
        val s = scene()
        val calls = ArrayList<String>()
        val g = object : SkyGfx {
            override fun layer(id: String, sx: Float, sy: Float, sw: Float, sh: Float, dx: Float, dy: Float, dw: Float, dh: Float, mul: Int, add: Int, alpha: Float, flipV: Boolean) { calls += id }
            override fun glow(x: Float, y: Float, rx: Float, ry: Float, color: Int, alpha: Float) { calls += "glow" }
            override fun dot(x: Float, y: Float, r: Float, color: Int, alpha: Float) { calls += "dot" }
            override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, alpha: Float) { calls += "rect" }
            override fun upload(id: String, px: IntArray, w: Int, h: Int) { calls += "up:$id" }
        }
        s.frame(g, ms("2026-11-22T06:50:00Z"), 0.0, 0.5f)
        val layers = calls.filter { !it.startsWith("up:") && it != "glow" && it != "dot" && it != "rect" }
        assertEquals(SkyScene.SKY, layers.first())
        assertEquals("near", layers.last())
        assertTrue(SkyScene.SUN in calls)
        calls.clear()
        s.frame(g, ms("2026-11-22T18:30:00Z"), 1.0, 0.5f)
        assertFalse("रात में सूरज नहीं", SkyScene.SUN in calls)
    }

    @Test fun starsCatalogIsRealAndColored() {
        val stars = SkyScene.parseStars(File(repo(), "wallpapers/sun_moon_lake/v1/stars.csv").readText())
        assertTrue(stars.size > 4000)
        val sirius = stars.minByOrNull { it.mag }!!
        assertEquals(101.29, sirius.ra, 0.3)    // व्याध (Sirius)
        assertEquals(-16.72, sirius.dec, 0.3)
        val blue = SkyScene.bvColor(-0.3); val red = SkyScene.bvColor(1.8)
        assertTrue((blue and 0xFF) > (red and 0xFF))
    }

    // ------------------------------------------------------------ कैटलॉग: इंटरनेट से, सुरक्षित
    @Test fun builtinCatalogMatchesRepoAndHashes() {
        val repoJson = File(repo(), "wallpapers/catalog.json").readText()
        assertEquals(JSONObject(repoJson).toString(), JSONObject(LiveWallBuiltin.CATALOG_JSON).toString())
        val items = SkyCatalog.parse(repoJson)
        assertEquals(1, items.size)
        val it0 = items[0]
        assertEquals(LiveWallBuiltin.FIRST_ID, it0.id)
        assertTrue(it0.supported)
        assertTrue(it0.files.any { it.name == "scene.json" })
        assertTrue(it0.totalBytes < 5L * 1024 * 1024)
        for (f in it0.files) {
            val local = File(repo(), "wallpapers/${it0.id}/v${it0.version}/${f.name}")
            assertTrue("${f.name} repo में होना चाहिए", local.isFile)
            assertEquals(f.name, f.bytes, local.length())
            val sha = MessageDigest.getInstance("SHA-256").digest(local.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(f.name, f.sha256, sha)
            assertTrue(f.url.endsWith("wallpapers/${it0.id}/v${it0.version}/${f.name}"))
        }
    }

    private fun item(mod: (JSONObject) -> Unit): SkyCatalog.Item? {
        val o = JSONObject(LiveWallBuiltin.CATALOG_JSON).getJSONArray("items").getJSONObject(0)
        mod(o)
        return SkyCatalog.parseItem(o)
    }

    @Test fun catalogRejectsUnsafeEntries() {
        assertNotNull(item { })
        assertNull("दूसरी साइट का URL", item { it.getJSONArray("files").getJSONObject(0).put("url", "https://evil.example.com/x.webp") })
        assertNull("ख़राब sha", item { it.getJSONArray("files").getJSONObject(0).put("sha256", "abc") })
        assertNull("बहुत बड़ी फ़ाइल", item { it.getJSONArray("files").getJSONObject(0).put("bytes", 50L * 1024 * 1024) })
        assertNull("अजीब नाम", item { it.getJSONArray("files").getJSONObject(0).put("name", "../x.webp") })
        assertNull("ख़राब id", item { it.put("id", "../../x") })
        assertNull("scene.json ज़रूरी", item { o ->
            val fa = o.getJSONArray("files")
            for (i in fa.length() - 1 downTo 0) if (fa.getJSONObject(i).getString("name") == "scene.json") fa.remove(i)
        })
        val future = item { it.put("minEngine", SkyCatalog.ENGINE + 1) }
        assertTrue(future == null || !future.supported)
        assertTrue(SkyCatalog.parse("not json").isEmpty())
    }

    @Test fun sceneConfigParses() {
        val p: SkyPack = SkyCatalog.parseScene(File(repo(), "wallpapers/sun_moon_lake/v1/scene.json").readText())!!
        assertTrue(p.horizon in 0.4f..0.8f)
        assertEquals(listOf("far", "mid", "near"), p.layers.map { it.id })
        assertEquals("near", p.layers.last().kind)
        assertNull(SkyCatalog.parseScene("{"))
    }

    // ------------------------------------------------------------ सेटिंग / manifest
    @Test fun settingsLiveWallpaperIsInsideDkLive() {
        val src = SetSrc.all()
        assertTrue(src.contains("\"livewall\" -> { title.text = \"लाइव वॉलपेपर\"; secLiveWall() }"))
        assertTrue(src.contains("if (cat == \"cat_live\") menuRow(\"livewall\""))
        assertEquals("लाइव वॉलपेपर", SettingsSearch.SECTIONS["livewall"])
        assertTrue(SettingsSearch.ENTRIES.any { it.section == "livewall" && it.card.contains("लगाएँ") })
        val lw = File(repo(), "app/src/main/java/com/dk/assistant/SettingsLiveWall.kt").readText()
        assertTrue("होम + लॉक दोनों", lw.contains("होम + लॉक"))
    }

    @Test fun manifestDeclaresWallpaperService() {
        val m = File(repo(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("com.dk.assistant.livewall.LiveWallpaperService"))
        assertTrue(m.contains("android.permission.BIND_WALLPAPER"))
        assertTrue(m.contains("android.service.wallpaper.WallpaperService"))
        assertTrue(m.contains("@xml/dk_livewall"))
        assertTrue(File(repo(), "app/src/main/res/xml/dk_livewall.xml").isFile)
        // पैक APK के अंदर नहीं (इंटरनेट से आता है)
        assertFalse(File(repo(), "app/src/main/assets/near.webp").exists())
    }
}
