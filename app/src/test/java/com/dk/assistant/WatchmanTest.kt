package com.dk.assistant

import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.PlusCommands
import com.dk.assistant.plus.tools.PlusTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 नवंबर 2026 (मालिक): "DK वॉचमैन - कौन आया, कौन गया, कितनी बार, क्या किया. जानकारी सिर्फ़ Boss को - पूछने पर DK ख़ुद
 * चेहरा स्कैन खोले, और वेरिफ़िकेशन और सख़्त."
 */
class WatchmanTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()
    private fun vec(seed: Int) = FloatArray(128) { i -> if (i == seed) 1f else 0f }

    @Test fun visits_countedByGap() {
        val b = Watchman.Book(); val t0 = 1_000_000L
        b.start(t0)
        // राहुल आया (कुछ सेकंड), चला गया, 5 मिनट बाद फिर आया
        for (i in 0..5) b.see("p:राहुल", "राहुल", FacePeople.FRIEND, "", t0 + i * 1_000L, 0.1f)
        assertEquals(1, b.visits.size)
        val (v, isNew) = b.see("p:राहुल", "राहुल", FacePeople.FRIEND, "", t0 + 5 * 60_000L, 0.4f)
        assertTrue(isNew); v.frames++
        assertEquals(2, b.visits.count { it.id == "p:राहुल" })
        // गैप से कम = वही आना
        assertFalse(b.see("p:राहुल", "राहुल", FacePeople.FRIEND, "", t0 + 5 * 60_000L + Watchman.GAP_MS - 1, 0.1f).second)
        val r = Watchman.report(b, 0L, t0 + 10 * 60_000L)
        assertTrue(r, r.contains("राहुल") && r.contains("2 बार") && r.contains("आपके दोस्त") && r.contains("फ़ोन के बिल्कुल पास"))
    }

    @Test fun unknown_sameFaceSameNumber() {
        val b = Watchman.Book()
        val a = b.unknownId(vec(1)); val again = b.unknownId(vec(1)); val other = b.unknownId(vec(2))
        assertEquals(a, again); assertTrue(a != other)
        assertEquals("u1", a); assertEquals("u2", other)
        assertNull(Watchman.matchUnknown(vec(3), b.unknown))
    }

    @Test fun report_touch_with_events_andOneGlimpseIgnored() {
        val b = Watchman.Book(); val t = 5_000_000L
        b.start(t)
        b.see("u1", "", "unknown", "", t + 1_000, 0.1f, listOf("u1", "p:मम्मी")); b.see("u1", "", "unknown", "", t + 2_000, 0.1f)
        b.see("p:मम्मी", "मम्मी", FacePeople.FAMILY, "मम्मी", t + 1_000, 0.1f); b.see("p:मम्मी", "मम्मी", FacePeople.FAMILY, "मम्मी", t + 2_000, 0.1f)
        b.see("p:ग़लत", "ग़लत", FacePeople.FRIEND, "", t + 3_000, 0.1f)   // एक झलक = गिनती नहीं
        b.touch(t + 2_500)
        val r = Watchman.report(b, 0L, t + 60_000)
        assertTrue(r, r.contains("अनजान व्यक्ति 1") && r.contains("आपकी मम्मी") && r.contains("फ़ोन 1 बार छुआ") && r.contains("साथ में"))
        assertFalse(r, r.contains("ग़लत"))
        assertTrue(r, r.contains("घटनाएँ") && r.contains("फ़ोन छुआ"))
        assertTrue(Watchman.report(Watchman.Book(), 0L, t).contains("कभी चालू नहीं"))
    }

    @Test fun book_savesAndLoads() {
        val b = Watchman.Book(); b.start(100L)
        b.see("p:राहुल", "राहुल", FacePeople.FRIEND, "", 200L, 0.5f, listOf("u1")); b.unknownId(vec(4)); b.note(300L, "x")
        val c = Watchman.Book.fromJson(b.toJson())
        assertEquals(1, c.visits.size); assertTrue(c.visits[0].near); assertEquals(setOf("u1"), c.visits[0].with)
        assertEquals("u1", c.unknownId(vec(4))); assertEquals("u2", c.unknownId(vec(5)))
        assertEquals(b.events.size, c.events.size)
        assertTrue("लॉग राज़ (encrypted)", SecretNames.isSecret(Watchman.KEY))
    }

    // ---------------------------------------------------------------- सख़्त Boss जाँच
    private fun f(faces: Int = 1, s: Float? = 0.7f, other: Float? = null, live: Boolean = true, front: Boolean = true,
                  ready: Boolean = true, n: Int = 10, sure: Boolean = true, fake: Boolean = false) = BossGate.Frame(faces, s, other, live, front, ready, n, sure, fake)

    @Test fun gate_passesOnlyAfterAllChecks() {
        val g = BossGate()
        assertEquals(BossGate.State.WAIT, g.feed(f()).state)
        assertEquals(BossGate.State.WAIT, g.feed(f()).state)
        assertEquals(BossGate.State.PASS, g.feed(f()).state)
        // बीच में कुछ भी चूका = गिनती शून्य
        val h = BossGate(); h.feed(f()); h.feed(f()); h.feed(f(faces = 2)); assertEquals(0, h.hits())
        for (bad in listOf(f(faces = 2), f(s = FaceMath.STRICT - 0.01f), f(other = 0.68f), f(live = false), f(front = false), f(n = BossGate.SPOOF_FRAMES - 1), f(sure = false))) {
            val x = BossGate(); repeat(10) { assertEquals(bad.toString(), BossGate.State.WAIT, x.feed(bad).state) }
        }
    }

    @Test fun gate_failsOnFakeOrNoModelOrStranger() {
        assertEquals(BossGate.State.FAIL, BossGate().feed(f(fake = true)).state)
        assertEquals(BossGate.State.FAIL, BossGate().feed(f(ready = false)).state)
        val g = BossGate(); repeat(BossGate.NOT_BOSS_FRAMES - 1) { assertEquals(BossGate.State.WAIT, g.feed(f(s = 0.1f)).state) }
        assertEquals(BossGate.State.FAIL, g.feed(f(s = 0.1f)).state)
        assertTrue(BossGate.SPOOF_FRAMES > FaceSpoof.SURE_FRAMES && BossGate.HITS > GuardRules.OWNER_HITS)
    }

    // ---------------------------------------------------------------- बोलकर / DK लाइव
    @Test fun voice_and_live() {
        assertEquals("start", PlusCommands.parse("वॉचमैन चालू करो")?.action?.optString("do"))
        assertEquals("report", PlusCommands.parse("मेरे पीछे कौन कौन आया था")?.action?.optString("do"))
        assertEquals("report", PlusCommands.parse("वॉचमैन रिपोर्ट बताओ")?.action?.optString("do"))
        assertEquals("stop", PlusCommands.parse("वॉचमैन बंद करो")?.action?.optString("do"))
        assertNull(PlusCommands.parse("आज मौसम कैसा है"))
        assertTrue(PlusCommands.WATCH in PlusCommands.TYPES)
        assertEquals("watchman", DkSettings.find("वॉचमैन चालू करो")?.id)
        assertEquals("face", DkSettings.find("चेहरा पहचान चालू करो")?.id)
        assertTrue(FeatureFlags.ready(FeatureFlags.FACE_WATCHMAN)); assertFalse(FeatureFlags.defaultOf(FeatureFlags.FACE_WATCHMAN))
        val t = PlusTools.byName("watchman"); assertNotNull(t); assertEquals(FeatureFlags.FACE_WATCHMAN, t!!.flag)
        assertTrue(DkSettings.RUN_HELP.contains("watchman"))
        val tools = src("plus/tools/NewTools.kt")
        assertTrue(tools.contains("BossCheckActivity.verify(c)") && tools.contains("DENIED") && tools.contains("Do NOT reveal"))
    }

    @Test fun screens_wired() {
        val w = src("WatchmanActivity.kt")
        assertTrue(w.contains("FLAG_KEEP_SCREEN_ON") && w.contains("BossCheckActivity.MODE_STOP") && w.contains("book.see("))
        val c = src("BossCheckActivity.kt")
        assertTrue(c.contains("FaceAI.realness(this, copy, det, force = true)") && c.contains("liveness.challenge") || c.contains("challenge(FaceLiveness.randomSide())"))
        assertTrue(c.contains("Watchman.reportFor("))
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(m.contains(".WatchmanActivity") && m.contains(".BossCheckActivity"))
        assertTrue(src("SettingsFace.kt").contains("watchmanCard()"))
    }
}
