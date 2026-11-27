package com.dk.assistant

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
import org.robolectric.annotation.Config
import java.io.File

/** 27 नवंबर 2026 (मालिक): पट्टी / चिप / छोटा बटन / सेक्शन डिब्बा - सब एक ही साझा दबा रूप ([ClayCard.pressedCard]) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PressedCardNov27Test {
    private val root = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
    private fun src(p: String) = File(root, p).readText()
    private val c get() = RuntimeEnvironment.getApplication()

    @Test fun sharedTokenKeepsShapeAndSelection() {
        val dp = c.resources.displayMetrics.density
        val d = ClayCard.pressedCard(20f * dp, 4.5f * dp, selectable = true)
        assertTrue(d.sunk); assertFalse(d.card); assertTrue(d.selectable)
        assertEquals(ClayDrawable.Style.RAISED, d.style)
        assertEquals(20f * dp, d.radiusPx, 0.01f); assertEquals(4.5f * dp, d.depthPx, 0.01f)
    }

    @Test fun factoriesUsePressedLook() {
        assertTrue(Clay.raised(c).sunk)
        assertTrue(Clay.chip(c).sunk)
        assertTrue(Clay.chip(c, 20f, 4f, selectable = true).selectable)
        // नीला (accent), गोल बटन, साझा कार्ड - वैसे ही
        assertFalse(Clay.accent(c).sunk)
        assertFalse(Clay.circle(c).sunk)
        assertFalse(Clay.card(c).sunk); assertTrue(Clay.card(c).card)
    }

    @Test fun pressAndSelectStillWork() {
        val d = ClayCard.pressedCard(40f, 8f, selectable = true)
        val v = LinearLayout(c).apply { background = d; isClickable = true }
        v.layout(0, 0, 600, 160)
        v.isPressed = true; v.isPressed = false
        v.isSelected = true
        assertEquals(1f, v.scaleX, 0.001f)   // दबी पट्टी पर कार्ड वाला scale नहीं - लेआउट वही
        val bmp = android.graphics.Bitmap.createBitmap(600, 160, android.graphics.Bitmap.Config.ARGB_8888)
        d.draw(android.graphics.Canvas(bmp))
    }

    @Test fun everyRowUsesToken() {
        assertTrue(src("dkai/DkaiUi.kt").contains("com.dk.assistant.ui.ClayCard.pressedCard(DkaiTheme.dpf(c, 20f), DkaiTheme.dpf(c, 4.5f)"))
        assertTrue(src("ui/Clay.kt").contains("r.background = ClayCard.pressedCard(dp(c, 20f), dp(c, 5f), selectable = true)"))
        val pl = src("PlainLook.kt")
        assertTrue(pl.contains("Kind.ROW -> p(20f, 4f, fill = chip)"))
        assertTrue(pl.contains("Kind.PILL -> p(FULL, 4f, fill = chip)"))
        assertTrue(pl.contains("if (style == ClayStyle.RAISED && !oval) return com.dk.assistant.ui.ClayCard.pressedCard(r, depth, fill = fill)"))
        // बाहर की परछाईं / चमक / किनारा: साझा कार्ड और दबे रूप दोनों पर नहीं
        val cd = src("ui/ClayDrawables.kt")
        assertTrue(cd.contains("private val flat = card || sunk"))
        assertTrue(cd.contains("val card = flat"))
        assertTrue(cd.contains("val out = if (raised && !card) depth else 0f"))
    }
}
