package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 22 नवंबर 2026 (मालिक): ऐप लॉक - सही PIN पर तुरंत (OK नहीं), पुरानी आवाज़-रिकॉर्डिंग पूरी बंद,
 * और फ़ेस जाँच तेज़ पर फ़ोटो / वीडियो से नहीं ([QuickFaceGate]).
 */
class AppLockFastTest {
    private fun src(n: String): String {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant/$n"), File(d, "app/src/main/java/com/dk/assistant/$n")).firstOrNull { it.exists() }?.let { return it.readText() }
            d = d.parentFile
        }
        error("$n नहीं मिला")
    }

    // ---------------------------------------------------------------- PIN अपने-आप
    @Test fun pinAutoLenOnlyForValidSavedLength() {
        assertEquals(6, AppLockRules.autoLen(6, hasPin = true))
        assertEquals(4, AppLockRules.autoLen(4, hasPin = true))
        assertEquals(0, AppLockRules.autoLen(0, hasPin = true))      // पुराना PIN - लंबाई अभी पता नहीं = OK से
        assertEquals(0, AppLockRules.autoLen(3, hasPin = true))
        assertEquals(0, AppLockRules.autoLen(13, hasPin = true))
        assertEquals(0, AppLockRules.autoLen(6, hasPin = false))
    }

    @Test fun lockScreenPinPadChecksItselfWithoutOk() {
        val act = src("AppLockActivity.kt")
        assertTrue(act.contains("NeonPinPad(this, autoLen = auto)"))
        assertTrue(act.contains("AppLockStore.pinLen(this)"))
        // सही = तुरंत ACCESS GRANTED; गलत = वही गिनती वाली जाँच (checkPin)
        assertTrue(act.contains("if (AppLockStore.checkPin(this, p)) { pad.grant(); ok(\"PIN\"); return }"))
        assertTrue(act.contains("pad.reject()"))
        val store = src("AppLockStore.kt")
        assertTrue(store.contains("putLong(P_PIN_LEN, pin.length.toLong())"))
        // सिर्फ़ लंबाई - PIN अब भी सिर्फ़ hash
        assertTrue(store.contains("AppLockRules.seal(pin, salt())"))
        assertTrue(src("GuardAlarmActivity.kt").contains("NeonPinPad(this, autoLen = auto)"))
        assertTrue(src("SettingsAppLock.kt").contains("autoLen = AppLockStore.pinLen(this)"))
    }

    @Test fun padIsFuturisticHud() {
        val v = src("AppLockViews.kt")
        assertTrue(v.contains("private fun hexPath(") && v.contains("fun reject()") && v.contains("fun grant()"))
        assertTrue(v.contains("\"ACCESS GRANTED\"") && v.contains("\"ACCESS DENIED\""))
        assertTrue(v.contains("if (autoLen > 0) \"CLR\" else \"OK\""))
    }

    @Test fun oldVoiceRecordingsFullyOff() {
        val v = src("voice/VoiceCmds.kt")
        assertTrue(v.contains("const val ENABLED = false"))
        assertTrue(v.contains("fun on(c: Context, cmd: Cmd) = ENABLED && "))
    }

    // ---------------------------------------------------------------- तेज़ लाइव फ़ेस
    private var t = 0L
    private fun fr(score: Float? = 0.8f, real: Float? = 0.95f, yaw: Float? = 0f, faces: Int = 1, other: Float? = null, cx: Float = 100f) =
        QuickFaceGate.Frame(t.also { t += 80 }, faces, cx, 100f, 80f, yaw, score, other, real)

    private fun run(g: QuickFaceGate, n: Int, f: () -> QuickFaceGate.Frame): QuickFaceGate.Verdict {
        var v = g.feed(f()); repeat(n - 1) { if (v.state == QuickFaceGate.State.WAIT && !v.flash) v = g.feed(f()) }; return v
    }

    /** रंगीन रोशनी वाली जाँच: [live] = असली चेहरा (चेहरे पर रंग पड़ता है), नहीं तो स्क्रीन / वीडियो (चेहरा और आस-पास बराबर) */
    private fun flash(g: QuickFaceGate, live: Boolean): QuickFaceGate.Verdict {
        val fl = g.flash ?: error("रंगीन जाँच शुरू नहीं हुई")
        fl.start(t)
        val rnd = java.util.Random(7)
        var v = g.feed(fr(score = null, real = null))
        while (v.state == QuickFaceGate.State.WAIT) {
            val c = fl.color(t - 90)
            val n = { (rnd.nextGaussian() * 0.003).toFloat() }
            if (live) fl.sample(t, 0.15f + 0.05f * c + n(), 0.10f + 0.01f * c + n())
            else fl.sample(t, 0.15f + 0.02f * c + n(), 0.10f + 0.02f * c + n())
            v = g.feed(fr(score = null, real = null))
        }
        return v
    }

    @Test fun liveOwnerPassesFastWithoutTurning() {
        val g = QuickFaceGate(side = 1)
        val v = run(g, QuickFaceGate.FAST_FRAMES) { fr() }
        // अब सीधा PASS नहीं - आख़िरी ताला रंगीन रोशनी
        assertEquals(QuickFaceGate.State.WAIT, v.state)
        assertTrue(v.flash)
        assertFalse(v.turn)
        assertEquals(QuickFaceGate.State.PASS, flash(g, live = true).state)
    }

    @Test fun recordedVideoThatPassesAiStillFailsFlash() {
        // मालिक का सिर घुमाता वीडियो जिसे AI ने "असली" मान लिया - फिर भी रंगीन रोशनी का जवाब नहीं
        val g = QuickFaceGate(side = 1)
        run(g, QuickFaceGate.FAST_FRAMES) { fr() }
        val v = flash(g, live = false)
        assertEquals(QuickFaceGate.State.FAIL, v.state)
        assertFalse(g.lastFlash!!.live)
    }

    @Test fun faceLeavingDuringFlashFails() {
        val g = QuickFaceGate(side = 1)
        run(g, QuickFaceGate.FAST_FRAMES) { fr() }
        g.flash!!.start(t)
        g.feed(fr(score = null, real = null))
        assertEquals(QuickFaceGate.State.FAIL, g.feed(fr(score = null, real = null, cx = 900f)).state)
    }

    @Test fun printedPhotoOrScreenFails() {
        val g = QuickFaceGate(side = 1)
        val v = run(g, 10) { fr(real = 0.1f) }
        assertEquals(QuickFaceGate.State.FAIL, v.state)
    }

    @Test fun unsureAiNeverPassesWithoutRandomTurn() {
        val g = QuickFaceGate(side = 1)
        // AI 0.7 = पक्का नहीं (तेज़ रास्ता नहीं) - सीधा चेहरा कितनी भी देर, खुलना नहीं
        val v = run(g, 60) { fr(real = 0.7f) }
        assertEquals(QuickFaceGate.State.WAIT, v.state)
        assertTrue(v.turn)
        assertFalse(g.flashing)
        // गलत तरफ़ घुमाया (पहले से बना वीडियो) = अब भी नहीं
        repeat(5) { g.feed(fr(real = 0.7f, yaw = -0.15f, score = null)) }
        assertFalse(run(g, 10) { fr(real = 0.7f) }.flash)
        assertFalse(g.flashing)
        // माँगी तरफ़ हल्का घुमाया, फिर सीधे = रंगीन जाँच, फिर खुला
        g.feed(fr(real = 0.7f, yaw = 0.12f, score = null))
        val w = run(g, 5) { fr(real = 0.7f) }
        assertTrue(w.flash)
        assertEquals(QuickFaceGate.State.PASS, flash(g, live = true).state)
    }

    @Test fun touchModeGateNeverAsksTurnAndNeedsFlash() {
        val g = QuickFaceGate(side = 1, allowTurn = false)
        // AI पुरानी सख़्त शर्त पर पक्का (0.7) - सिर घुमाने को कभी नहीं, सीधे रंगीन जाँच
        val v = run(g, 40) { fr(real = 0.7f) }
        assertFalse(v.turn)
        assertTrue(v.flash)
        assertEquals(QuickFaceGate.State.PASS, flash(g, live = true).state)
        // वीडियो (AI ने असली माना) = रंगीन जाँच पर फ़ेल
        val h = QuickFaceGate(side = 1, allowTurn = false)
        run(h, 40) { fr(real = 0.9f) }
        assertEquals(QuickFaceGate.State.FAIL, flash(h, live = false).state)
        // AI पक्का नहीं (0.6) = कितनी भी देर, न घुमाना न खुलना
        val k = QuickFaceGate(side = 1, allowTurn = false)
        val w = run(k, 80) { fr(real = 0.6f) }
        assertEquals(QuickFaceGate.State.WAIT, w.state); assertFalse(w.turn); assertFalse(k.flashing)
    }

    @Test fun flatPhotoYawNeverCountsAsTurn() {
        val g = QuickFaceGate(side = 1)
        val v = run(g, 80) { fr(real = 0.7f, yaw = 0.02f) }
        assertEquals(QuickFaceGate.State.WAIT, v.state)
    }

    @Test fun strangerOrWeakMatchNeverPasses() {
        assertEquals(QuickFaceGate.State.FAIL, run(QuickFaceGate(), 10) { fr(score = 0.2f) }.state)
        assertEquals(QuickFaceGate.State.WAIT, run(QuickFaceGate(), 20) { fr(score = FaceMath.STRICT - 0.01f) }.state)
        assertEquals(QuickFaceGate.State.WAIT, run(QuickFaceGate(), 20) { fr(score = 0.8f, other = 0.78f) }.state)
    }

    @Test fun twoFacesOrSwappedFaceRestarts() {
        assertEquals(QuickFaceGate.State.WAIT, run(QuickFaceGate(), 20) { fr(faces = 2) }.state)
        val g = QuickFaceGate()
        var x = 0f
        // हर फ़्रेम चेहरा दूसरी जगह कूदे (फ़ोटो बदलना) = कभी भी पूरी गिनती नहीं
        val v = run(g, 20) { x += 500f; fr(cx = x) }
        assertEquals(QuickFaceGate.State.WAIT, v.state)
    }

    @Test fun noSpoofModelNeverOpens() {
        val v = QuickFaceGate().feed(QuickFaceGate.Frame(0L, 1, score = 0.9f, real = 0.99f, spoofReady = false))
        assertEquals(QuickFaceGate.State.FAIL, v.state)
    }

    @Test fun appLockUsesQuickGateAndOpensImmediately() {
        val act = src("AppLockActivity.kt")
        assertTrue(act.contains("QuickFaceGate.State.PASS -> runOnUiThread { ok(\"DK फ़ेस स्कैन\") }"))
        assertTrue(act.contains("FaceAI.realness(this, copy, det, force = true)"))
        assertFalse(act.contains("liveness.challenge("))
        assertTrue(act.contains("FaceCam(this, pv, gapMs = FlashLive.CAM_GAP_MS) { b -> flashScr.sample(b); onFrame(b) }"))
        assertTrue(act.contains("flashScr.begin(it)"))
    }

    @Test fun touchModeNeedsFlashAndForcedAntiSpoof() {
        val a = src("GuardAlarmActivity.kt")
        assertTrue(a.contains("val spoofOn = FaceAI.ready(this) && FaceAI.hasSpoof(this)"))
        assertTrue(a.contains("FaceAI.realness(this, b, det, force = true)"))
        assertTrue(a.contains("if (v.flash) runOnUiThread { g.flash?.let { flashScr.begin(it) } }"))
        // disarm सिर्फ़ गेट के PASS पर - और गेट PASS सिर्फ़ रंगीन जाँच ([QuickFaceGate.flashStep]) से
        val disarmAt = a.indexOf("Guard.disarm(this, \"मालिक का चेहरा पहचाना")
        assertTrue(disarmAt > a.indexOf("QuickFaceGate.State.PASS -> {"))
        val q = src("QuickFaceGate.kt")
        assertEquals(1, Regex("State\\.PASS,").findAll(q).count())
        assertTrue(q.indexOf("State.PASS,") > q.indexOf("private fun flashStep("))
        assertTrue(src("FaceCam.kt").contains("now - last < gapMs"))
    }
}
