package com.dk.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import com.dk.assistant.dkai.DkaiChatFiles
import com.dk.assistant.dkai.DkaiHomeCore
import com.dk.assistant.ui.ClayCard
import com.dk.assistant.ui.ClayDrawable
import com.dk.assistant.ui.ClayTheme
import com.dk.assistant.ui.HomeMenu
import com.dk.assistant.ui.HomeTabs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 23 नवंबर 2026 (मालिक): ☰ मेन्यू और नीचे की पट्टी पर वही साझा कार्ड स्टाइल, मेन्यू के नीचे "DK AI / By Dhiraj Kumar Singh",
 * माइक = कॉल-कट (एक बटन, दो हालत), होम पर छोटी पट्टी नहीं, नया hint, लाइव कार्ड सिर्फ़ लाइव में, लाइव में फ़ाइल,
 * स्कैन बिना दूसरी विंडो + ऊपर दो बटन.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class MenuTabsLiveNov23Test {
    private val root = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
    private fun src(p: String) = File(root, p).readText()
    private val c get() = RuntimeEnvironment.getApplication()
    private val before = ClayTheme.current

    @After fun back() = ClayTheme.set(before)

    @Test fun menuItems_useSharedCard_noHardcodedRadius() {
        val m = src("ui/HomeMenu.kt")
        assertTrue(m.contains("background = Clay.card(a)"))
        assertFalse("आइटम पर पुराना उभरा chip नहीं", m.contains("Clay.chip(a, 20f, 4f)"))
        assertEquals(4f, HomeMenu.ROW_GAP_DP, 0.001f)
    }

    @Test fun menuSignature_twoLines_versionOnlyInAbout() {
        val m = src("ui/HomeMenu.kt")
        assertEquals("DK AI", HomeMenu.BRAND)
        assertEquals("By Dhiraj Kumar Singh", HomeMenu.BYLINE)
        assertFalse("मेन्यू में version नंबर नहीं", m.contains("Updater.displayVersion"))
        assertTrue(m.contains("FutureViews.goldShader(this, goldDeeper(k.light))"))
        assertTrue(m.contains("Typeface.ITALIC"))
        assertTrue(m.contains("\"sans-serif-medium\""))
        // version "ऐप के बारे में" में ही
        assertTrue(src("SettingsActivity.kt").contains("\"about\" -> { title.text = \"ऐप के बारे में\"; secAbout() }"))
        // डार्क = Settings वाला हूबहू सुनहरा, लाइट = थोड़ा गहरा
        assertEquals(0f, HomeMenu.goldDeeper(false), 0.0001f)
        assertTrue(HomeMenu.goldDeeper(true) in 0.15f..0.5f)
        // नीचे वाली पंक्ति ~70% opacity, लाइट पर गहरी
        for (l in listOf(false, true)) assertEquals(0xB3, (HomeMenu.bylineColor(l) ushr 24) and 0xFF)
        fun lum(p: Int) = ((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114
        assertTrue(lum(HomeMenu.bylineColor(true)) < lum(HomeMenu.bylineColor(false)))
        // Settings का शीर्षक पहले जैसा (deeper के बिना)
        assertTrue(src("SettingsActivity.kt").contains("FutureViews.goldShader(title)"))
    }

    @Test fun tabsBar_sharedCard_softBlob_softMic() {
        val t = src("ui/HomeTabs.kt")
        assertTrue(t.contains("d(ClayCard.RADIUS_DP).toFloat(), d(ClayCard.DEPTH_DP).toFloat()"))
        assertTrue(t.contains("ClayDrawable(ClayDrawable.Style.RAISED, radiusPx = radius, depthPx = depth, card = true)"))
        assertTrue(t.contains("ClayDrawable(ClayDrawable.Style.ACCENT, radiusPx = radius, depthPx = blobDepth, soft = true)"))
        assertFalse("हार्डकोड 28dp / 20dp नहीं", t.contains("d(28f)") || t.contains("radiusPx = d(20f)"))
        assertTrue(t.contains("setEnterFadeDuration(ClayDrawable.PRESS_MS.toInt()); setExitFadeDuration(ClayDrawable.RELEASE_MS.toInt())"))
        assertTrue(t.contains("ClayDrawable(ClayDrawable.Style.ACCENT, oval = true, depthPx = depth, soft = true)"))
        // Speaking वाली छोटी पट्टी भी साझा कार्ड
        assertTrue(src("dkai/DkaiVoice.kt").contains("mini.background = com.dk.assistant.ui.Clay.card(c)"))
    }

    private fun draw(d: ClayDrawable, wDp: Int, hDp: Int): Bitmap {
        val dp = c.resources.displayMetrics.density
        val bmp = Bitmap.createBitmap((wDp * dp).toInt(), (hDp * dp).toInt(), Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, bmp.width, bmp.height); d.draw(Canvas(bmp))
        return bmp
    }

    @Test fun softShadow_lighterThanOld_noRim() {
        val dp = c.resources.displayMetrics.density
        for (th in listOf(ClayTheme.MIDNIGHT, ClayTheme.NAVY, ClayTheme.CLAY_DAY)) {
            ClayTheme.set(th)
            val old = draw(ClayDrawable(ClayDrawable.Style.ACCENT, oval = true, depthPx = 5 * dp), 66, 66)
            val soft = draw(ClayDrawable(ClayDrawable.Style.ACCENT, oval = true, depthPx = 5 * dp, soft = true), 66, 66)
            // नीचे-दाएँ, आकार के ठीक बाहर: नरम छाया पुरानी से हल्की (कम गाढ़ी)
            val m = (5 * dp).toInt()
            var sumOld = 0; var sumSoft = 0
            for (x in old.width / 2 until old.width) for (y in old.height - m until old.height) {
                sumOld += old.getPixel(x, y) ushr 24; sumSoft += soft.getPixel(x, y) ushr 24
            }
            assertTrue("${th.id} नरम छाया हल्की", sumSoft <= sumOld)
            // बीच का रंग वही (accent भराव)
            assertEquals(old.getPixel(old.width / 2, old.height * 3 / 4), soft.getPixel(soft.width / 2, soft.height * 3 / 4))
        }
        val dr = src("ui/ClayDrawables.kt")
        assertTrue(dr.contains("if (!soft) {   // नरम"))
        assertTrue(dr.contains("val (off0, bl) = ClayCard.shadow(d)"))
        assertTrue(ClayCard.shadow(10f).let { it.first + it.second <= 10f })
    }

    @Test fun micIsCallEnd_whenLive() {
        val on = HomeTabs.micLook(true); val off = HomeTabs.micLook(false)
        assertTrue(on.endCall); assertFalse(off.endCall)
        assertEquals("कॉल कट", on.label); assertEquals("माइक", off.label)
        val s = src("dkai/DkaiScreen.kt")
        assertTrue(s.contains("if (t == com.dk.assistant.ui.HomeTabs.Tab.MIC && DKState.live) { voice.endCall();"))
        assertTrue(s.contains("com.dk.assistant.ui.HomeTabs.live(tabsBar, DKState.live)"))
        assertTrue(src("ui/HomeTabs.kt").contains("DkaiIcon.Kind.CALL_END"))
        // छोटी पट्टी के तीन बटन लाइव में छिपे
        assertTrue(src("dkai/DkaiVoice.kt").contains("for (b in miniBtns) DkaiTheme.gone(b, DKState.live)"))
    }

    @Test fun miniOnlyOnChat_hint_liveCard() {
        assertFalse(DkaiHomeCore.miniShown(chatPage = false, chatTab = false))
        assertTrue(DkaiHomeCore.miniShown(chatPage = false, chatTab = true))
        assertTrue(DkaiHomeCore.miniShown(chatPage = true, chatTab = false))
        assertEquals("DK AI से कुछ भी पूछो", DkaiHomeCore.HINT)
        assertTrue(DkaiHomeCore.liveCardShown(true)); assertFalse(DkaiHomeCore.liveCardShown(false))
        val s = src("dkai/DkaiScreen.kt")
        assertFalse(s.contains("\"Ask anything…\""))
        assertFalse("लाइव कार्ड में डिफ़ॉल्ट लाइन नहीं", s.contains("बीच में या नीचे माइक पर टैप करके बात कीजिए"))
        assertTrue(s.contains("DkaiTheme.gone(liveBox, !show)"))
    }

    @Test fun filesDuringLive_goToLive() {
        assertTrue(DkaiHomeCore.attachToLive(live = true, canTalk = true, files = 1))
        assertFalse(DkaiHomeCore.attachToLive(live = false, canTalk = true, files = 1))
        assertFalse(DkaiHomeCore.attachToLive(live = true, canTalk = false, files = 1))
        assertFalse(DkaiHomeCore.attachToLive(live = true, canTalk = true, files = 0))
        val photo = DkaiChatFiles.liveNote("इसमें क्या लिखा है?", listOf("a.jpg"), emptyList(), 1)
        assertTrue(photo.contains("look_photo")); assertTrue(photo.contains("इसमें क्या लिखा है?")); assertTrue(photo.contains("a.jpg"))
        val long = "क".repeat(DkaiChatFiles.LIVE_TEXT + 500)
        val txt = DkaiChatFiles.liveNote("", listOf("n.txt"), listOf("n.txt" to long), 0)
        assertFalse(txt.contains("look_photo"))
        assertTrue(txt.contains("--- n.txt ---"))
        assertTrue(txt.count { it == 'क' } == DkaiChatFiles.LIVE_TEXT)
        val s = src("dkai/DkaiScreen.kt")
        assertTrue(s.contains("if (liveFiles(pics, t)) return true"))
        assertTrue(s.contains("g.pushNote(DkaiChatFiles.liveNote(t, r.names, r.texts, r.images))"))
    }

    @Test fun scan_noSecondWindow_twoTopButtons() {
        val m = src("MainActivity.kt")
        assertTrue(m.contains("\"कैमरे से कॉपी, स्क्रीन या चीज़ पढ़वाएँ\") { openScanTab() }"))
        assertTrue(m.contains("if (!d.showTab(com.dk.assistant.ui.HomeTabs.Tab.SCAN))"))
        val sp = src("dkai/ScanPanel.kt")
        assertTrue(sp.contains("R.drawable.ic_screen_share") && sp.contains("R.drawable.ic_flip"))
        assertTrue(sp.contains("Gravity.TOP or Gravity.START") && sp.contains("Gravity.TOP or Gravity.END"))
        assertTrue(sp.contains("LiveVision.toggleScreen(act)") && sp.contains("LiveVision.flipCamera(act)"))
    }
}
