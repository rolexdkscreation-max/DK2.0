package com.dk.assistant

import android.view.View
import android.widget.LinearLayout
import com.dk.assistant.ui.Clay
import com.dk.assistant.ui.ClayCard
import com.dk.assistant.ui.ClayDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** 23 नवंबर 2026 (मालिक): सारे कार्ड एक जैसे - दबाने वाला नरम गोल रूप ही मूल रूप */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ClayCardNov23Test {
    private val root = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
    private fun src(p: String) = File(root, p).readText()
    private val c get() = RuntimeEnvironment.getApplication()

    @Test fun specValues() {
        assertTrue(ClayCard.RADIUS_DP in 28f..32f)
        assertEquals(0.97f, ClayCard.PRESS_SCALE, 0.001f)
        assertTrue(ClayDrawable.RELEASE_MS in 150L..200L)
        // बड़ा धुंधलापन, पर परछाईं डिब्बे के अंदर ख़त्म (किनारे पर कटी सख़्त रेखा नहीं)
        for (d in listOf(4f, 8f, 16f, 24f)) {
            val (off, blur) = ClayCard.shadow(d)
            assertTrue(off > 0f && blur >= off * 2f)
            assertTrue("d=$d", off + blur <= d)
        }
    }

    @Test fun sameRadiusPressedAndNormal() {
        val dp = c.resources.displayMetrics.density
        val d = Clay.card(c)
        assertTrue(d.card); assertEquals(ClayCard.RADIUS_DP * dp, d.radiusPx, 0.01f)
        assertEquals(ClayCard.DEPTH_DP * dp, d.depthPx, 0.01f)
        d.setBounds(0, 0, (300 * dp).toInt(), (90 * dp).toInt())
        val o1 = android.graphics.Outline(); d.getOutline(o1)
        d.state = intArrayOf(android.R.attr.state_pressed)
        val o2 = android.graphics.Outline(); d.getOutline(o2)
        assertEquals(o1.radius, o2.radius, 0.01f)
        assertEquals(ClayCard.RADIUS_DP * dp, o2.radius, 0.01f)
        assertTrue(Clay.card(c, selectable = true).selectable)
        assertFalse(Clay.raised(c).card)
    }

    @Test fun pressSinksAndComesBack() {
        val v = LinearLayout(c).apply { background = Clay.card(c); isClickable = true }
        v.layout(0, 0, 600, 200)
        v.isPressed = true
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(ClayCard.PRESS_SCALE, v.scaleX, 0.005f)
        assertEquals(ClayCard.PRESS_SCALE, v.scaleY, 0.005f)
        v.isPressed = false
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(1f, v.scaleX, 0.005f)
        // न दबाने योग्य (लाइव टेक्स्ट वाला डिब्बा) कार्ड नहीं हिलता
        val still = View(c).apply { background = Clay.card(c) }
        still.background.state = intArrayOf(android.R.attr.state_pressed)
        assertEquals(1f, still.scaleX, 0.001f)
    }

    @Test fun everyCardUsesTheSharedCard() {
        assertTrue(src("SettingsActivity.kt").contains("com.dk.assistant.ui.Clay.card(this@SettingsActivity)"))
        assertTrue(src("SettingsTheme.kt").contains("com.dk.assistant.ui.Clay.card(this@secTheme, selectable = true)"))
        assertTrue(src("ui/CollapsibleSection.kt").contains("Clay.card(ctx)"))     // Settings / मेमोरी के समूह
        assertTrue(src("ui/HomeMenu.kt").contains("Clay.card(a)"))
        assertTrue(src("dkai/ScanPanel.kt").contains("com.dk.assistant.ui.Clay.card(act)"))
        assertTrue(src("dkai/DkaiUi.kt").contains("com.dk.assistant.ui.Clay.card(c)"))
        assertEquals(3, Regex("com\\.dk\\.assistant\\.ui\\.Clay\\.card\\(c\\)").findAll(src("dkai/DkaiScreen.kt")).count())
        val pl = src("PlainLook.kt")
        assertTrue(pl.contains("Kind.CARD -> com.dk.assistant.ui.ClayDrawable(ClayStyle.RAISED, radiusPx = com.dk.assistant.ui.ClayCard.RADIUS_DP * dp"))
        assertTrue(pl.contains("if (k == Kind.CARD && !oval && r < FULL"))
        // कोई कार्ड अलग नाप से नहीं
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            assertFalse(f.name, Regex("Clay\\.raised\\(").containsMatchIn(f.readText()))
        }
        // कार्ड पर सख़्त किनारे की रेखा नहीं; रंग थीम से
        val dr = src("ui/ClayDrawables.kt")
        assertTrue(dr.contains("if (raised && !card && depth > 0.5f)"))
        val spec = dr.substringAfter("object ClayCard").substringBefore("\n}\n")
        assertFalse(Regex("0x[0-9A-Fa-f]{6,8}").containsMatchIn(spec))
    }
}
