package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/** v4.x: नए futuristic डॉट ORB (हर एक का अपना एनीमेशन), 5 नए टाइपिंग एनीमेशन, लड़की अवतार पूरी तरह हटा */
class FutureOrbsTypingTest {
    private val NEW = FutureOrbs.STYLES.map { it.first }

    private fun src(name: String): File =
        listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name")).first { it.exists() }
    private fun res(path: String): File = File((if (File("src/main").exists()) "src/main/" else "app/src/main/") + path)

    @Test fun sixNewOrbs_listedInSettings_oldOnesKept() {
        assertEquals(6, NEW.size)
        val keys = Shapes.STYLES.map { it.first }
        for (k in NEW) { assertTrue(k in keys); assertEquals(k, Shapes.valid(k)); assertTrue(FutureOrbs.code(k) > 0) }
        assertEquals(keys.size, keys.toSet().size)
        for (k in listOf(Shapes.NEON, "earth", "galaxy", "atom", "torus", "face_male", "face_female", "geodesic", "knot", "dyson"))
            assertTrue("$k should stay", k in keys)
        assertEquals(0, FutureOrbs.code("earth"))
    }

    @Test fun everyNewOrb_madeOfDots_inBounds_atAnyTime() {
        val out = FloatArray(4)
        for (style in NEW) for (n in listOf(1400, 320, 60)) {
            val c = Shapes.make(style, n)
            assertEquals("$style n=$n", n, c.count)
            var bright = 0
            val code = FutureOrbs.code(style)
            for (i in 0 until c.count) {
                if (c.kind[i].toInt() == 1) bright++
                for (t in listOf(0f, 0.7f, 3.3f, 17.9f, 250f)) for (amp in listOf(0f, 1f)) {
                    FutureOrbs.move(code, i, c.x[i], c.y[i], c.z[i], c.anc[i], t, amp, out)
                    val d = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
                    assertTrue("$style dot $i t=$t finite", d.isFinite() && out[3].isFinite())
                    assertTrue("$style dot $i t=$t too far: $d", d <= 1.45f)
                    assertTrue("$style dot $i brightness ${out[3]}", out[3] in 0f..2f)
                }
            }
            assertTrue("$style has glowing dots", bright > 0)
            assertTrue("$style has normal dots", bright < c.count)
        }
    }

    @Test fun everyNewOrb_reallyMoves_andEachDiffers() {
        val out = FloatArray(4)
        val moved = HashMap<String, Float>()
        for (style in NEW) {
            val c = Shapes.make(style, 1400); val code = FutureOrbs.code(style)
            var change = 0f
            for (i in 0 until c.count) {
                FutureOrbs.move(code, i, c.x[i], c.y[i], c.z[i], c.anc[i], 1f, 0.5f, out)
                val ax = out[0]; val ay = out[1]; val az = out[2]; val ab = out[3]
                FutureOrbs.move(code, i, c.x[i], c.y[i], c.z[i], c.anc[i], 2.5f, 0.5f, out)
                change += kotlin.math.abs(out[0] - ax) + kotlin.math.abs(out[1] - ay) + kotlin.math.abs(out[2] - az) + kotlin.math.abs(out[3] - ab)
            }
            assertTrue("$style should animate", change / c.count > 0.01f)
            moved[style] = change
        }
        // आकार अलग-अलग
        for (a in NEW) for (b in NEW) if (a < b) {
            val ca = Shapes.make(a, 400); val cb = Shapes.make(b, 400)
            var same = 0
            for (i in 0 until 400) if (ca.x[i] == cb.x[i] && ca.y[i] == cb.y[i]) same++
            assertTrue("$a vs $b", same < 40)
        }
    }

    @Test fun orbView_runsNewOrbAnimation_andMiniShowsNewOrb() {
        val s = src("OrbView.kt").readText()
        assertTrue(s.contains("FutureOrbs.move(fxCode"))
        assertTrue(s.contains("fxCode = FutureOrbs.code(style)"))
        assertTrue(s.contains("if (mini) return if (FutureOrbs.has(styleCache)) styleCache else \"earth\""))
    }

    @Test fun typing_fiveNewDotStyles_plusOldBars() {
        val keys = TypingStyles.STYLES.map { it.first }
        assertEquals(6, keys.size); assertEquals(TypingStyles.BARS, keys.first())
        assertEquals(TypingStyles.BARS, TypingStyles.valid(null)); assertEquals(TypingStyles.BARS, TypingStyles.valid("xyz"))
        val out = FloatArray(TypingStyles.MAX * 4)
        assertEquals(0, TypingStyles.dots(TypingStyles.BARS, 1.0, 1f, out))
        for (k in keys.drop(1)) {
            assertEquals(k, TypingStyles.valid(k))
            val a = TypingStyles.dots(k, 1.0, 1f, out).also { assertTrue("$k dots", it in 3..TypingStyles.MAX) }
            val first = out.copyOf(a * 4)
            TypingStyles.dots(k, 4.0, 1f, out)
            var diff = 0f
            for (i in 0 until a) {
                val x = out[i * 4]; val y = out[i * 4 + 1]; val r = out[i * 4 + 2]; val al = out[i * 4 + 3]
                assertTrue("$k inside box", x - r >= -0.5f && x + r <= 0.5f && y - r >= -0.5f && y + r <= 0.5f)
                assertTrue("$k alpha", al in 0f..1f)
                for (j in 0 until 4) diff += kotlin.math.abs(out[i * 4 + j] - first[i * 4 + j])
            }
            assertTrue("$k should animate", diff > 0.01f)
        }
        val w = src("DKWorkingIndicator.kt").readText()
        assertTrue(w.contains("TypingStyles.dots(typing"))
        val st = src("SettingsActivity.kt").readText()
        assertTrue(st.contains("card(\"टाइपिंग एनीमेशन\""))
        assertTrue(st.contains("putString(\"typing_style\", v)"))
    }

    @Test fun girlAvatar_fullyRemoved_dotFacesKept() {
        assertFalse(res("res/drawable-nodpi/avatar_body.png").exists())
        val dir = res("res/drawable-nodpi")
        assertTrue(!dir.exists() || dir.listFiles().orEmpty().none { it.name.startsWith("face_") || it.name.startsWith("avatar") })
        assertTrue(res("res/layout").exists())
        assertFalse(listOf(File("src/main/java/com/dk/assistant/avatar/AvatarView.kt"), File("app/src/main/java/com/dk/assistant/avatar/AvatarView.kt")).any { it.exists() })
        assertFalse(listOf(File("src/main/java/com/dk/assistant/avatar/AvatarAssets.kt"), File("app/src/main/java/com/dk/assistant/avatar/AvatarAssets.kt")).any { it.exists() })
        for (f in listOf("MainActivity.kt", "VoiceActivity.kt", "SettingsActivity.kt", "Prefs.kt")) {
            val s = src(f).readText()
            assertFalse("$f still has avatar", s.contains("AvatarView") || s.contains("AvatarAssets") || s.contains("avatarKind") || s.contains("avatar_kind"))
        }
        val layout = res("res/layout/activity_main.xml").readText()
        assertFalse(layout.contains("AvatarView"))
        // डॉट वाले चेहरे रहते हैं
        val keys = Shapes.STYLES.map { it.first }
        assertTrue("face_male" in keys && "face_female" in keys)
        assertEquals(3200, Shapes.make("face_female", 3200).count)
    }
}
