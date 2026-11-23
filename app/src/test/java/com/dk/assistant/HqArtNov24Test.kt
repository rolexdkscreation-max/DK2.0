package com.dk.assistant

import com.dk.assistant.dkai.HomeAvatars
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
 * 24 नवंबर 2026 (मालिक: "ORB और कैरेक्टर धुंधले - हाई क्वालिटी बनाओ, इंटरनेट से डाउनलोड हो, APK में न रहे").
 * असली वजह: drawable-nodpi वाले आर्टवर्क कोलाज से कटे 90-190px के टुकड़े थे. अब हर एक की 2048px HQ तस्वीर
 * repo के `avatars/` में (APK के बाहर) - फ़ोन पर डाउनलोड होकर वही दिखती है.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class HqArtNov24Test {
    private fun repo(p: String): File = listOf(File("../$p"), File(p)).first { it.exists() }

    /** PNG IHDR से चौड़ाई / ऊँचाई */
    private fun pngSize(f: File): Pair<Int, Int> {
        val b = f.inputStream().use { it.readNBytes(24) }
        fun i(o: Int) = ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)
        return i(16) to i(20)
    }

    @After fun clean() {
        val c = RuntimeEnvironment.getApplication()
        HomeAvatars.dir(c).listFiles()?.forEach { it.delete() }
    }

    @Test fun everyArtworkHasHqFileOnline_notInApk() {
        for (a in HomeAvatars.BUNDLED) {
            val n = HomeAvatars.hqName(a)
            assertEquals(a.id + "_hq.png", n)
            assertTrue(HomeAvatars.safeFile(n))
            val f = repo("avatars/$n")
            assertTrue(n, f.isFile)
            assertTrue(n, HomeAvatars.isPng(f.inputStream().use { it.readNBytes(8) }))
            val (w, h) = pngSize(f)
            assertTrue("$n $w x $h", minOf(w, h) >= 2000)                                   // पहले 90-190px
            assertTrue(n, f.length() in 1_000_000L..HomeAvatars.HQ_MAX_BYTES.toLong())
            assertNotNull(a.hqSubject); assertEquals(4, a.hqSubject!!.size)
        }
        // HQ APK में नहीं (res में कोई _hq नहीं)
        val res = repo("app/src/main/res")
        assertTrue(res.walk().none { it.name.contains("_hq") })
        assertTrue(HomeAvatars.HQ_MAX_BYTES > HomeAvatars.MAX_BYTES)
    }

    @Test fun hqLookUsesItsOwnFraming() {
        val bf = HomeAvatars.BUNDLED.first { it.id == "orb_art_butterfly" }
        val l = HomeAvatars.hqLook(bf)
        assertEquals(bf.hqSubject, l.subject)
        assertEquals(0.56f, l.badgeY)
        assertEquals(HomeAvatars.BADGE_Y, bf.badgeY)                                  // पुरानी छोटी तस्वीर = पुरानी जगह
        val net = HomeAvatars.Avatar("boy1", "लड़का", "boy", "boy1.png")
        assertNull(HomeAvatars.hqName(net))
        assertEquals(net, HomeAvatars.hqLook(net))
    }

    @Test fun downloadedHqIsShownSharp_smallUntilThen() {
        val c = RuntimeEnvironment.getApplication()
        val a = HomeAvatars.BUNDLED.first { it.id == HomeAvatars.DEFAULT_ID }
        assertFalse(HomeAvatars.hqReady(c, a))
        assertEquals(a.subject, HomeAvatars.byId(c, a.id)!!.subject)                      // तब तक पुरानी
        assertNotNull(HomeAvatars.bitmap(c, a, 800))                                     // छोटी तस्वीर - कभी खाली नहीं
        // डाउनलोड हुई (यहाँ: repo वाली फ़ाइल फ़ोन के फ़ोल्डर में)
        repo("avatars/" + HomeAvatars.hqName(a)).copyTo(HomeAvatars.hqFile(c, a)!!, overwrite = true)
        assertTrue(HomeAvatars.hqReady(c, a))
        val shown = HomeAvatars.byId(c, a.id)!!
        assertEquals(a.hqSubject, shown.subject)
        val big = HomeAvatars.bitmap(c, shown, 2400)!!
        assertEquals(2048, big.width)                                                     // असली पिक्सल, खींचा नहीं
        val small = HomeAvatars.bitmap(c, shown, 900)!!
        assertTrue(small.width in 900..2048)                                              // ज़रूरत भर (memory बचे)
        val th = HomeAvatars.thumb(c, shown)!!
        assertTrue(th.width in HomeAvatars.THUMB_PX..(HomeAvatars.THUMB_PX * 2))
    }

    @Test fun brokenHqIsDroppedAndOldShown() {
        val c = RuntimeEnvironment.getApplication()
        val a = HomeAvatars.BUNDLED.first { it.id == "dk_art_cat_ears" }
        HomeAvatars.hqFile(c, a)!!.writeBytes(ByteArray(500) { 7 })
        assertNotNull(HomeAvatars.bitmap(c, a, 800))                                     // पुरानी छोटी
        assertFalse(HomeAvatars.hqReady(c, a))                                            // टूटी हटी - फिर डाउनलोड होगी
    }

    @Test fun wiredOnHomeAndSettings() {
        val home = repo("app/src/main/java/com/dk/assistant/dkai/DkaiScreen.kt").readText()
        assertTrue(home.contains("HomeAvatars.fetchHq(c, av)"))
        assertTrue(home.contains("centerAvatar.badgeY = av?.badgeY"))
        val set = repo("app/src/main/java/com/dk/assistant/SettingsLook.kt").readText()
        assertTrue(set.contains("H.fetchHq(this, a)"))
        assertTrue(set.contains("H.withHq(this, it)"))
    }
}
