package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 22 नवंबर 2026 (मालिक): सायरन बजते किसी ने फ़ोन खोला = 1 सेकंड में सच में फिर लॉक (लॉक स्क्रीन दिखे, सायरन वाली स्क्रीन ढके नहीं);
 * टच मोड में दो टॉगल - छूने / उठाने पर सायरन, और हमेशा वाला "गलत PIN = सायरन + लोकेशन".
 */
class TouchRelockWrongPinTest {
    private fun src(n: String): String {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant/$n"), File(d, "app/src/main/java/com/dk/assistant/$n")).firstOrNull { it.exists() }?.let { return it.readText() }
            d = d.parentFile
        }
        error("$n नहीं मिला")
    }

    @Test fun relockFastAndScreenStaysDark() {
        assertTrue(GuardRules.RELOCK_MS in 800L..1_500L)
        // DK ने फिर लॉक किया + स्क्रीन बंद = चेतावनी के लिए स्क्रीन खुद मत जलाओ
        assertFalse(GuardRules.wakeForAlarm(relockDark = true, interactive = false))
        assertTrue(GuardRules.wakeForAlarm(relockDark = true, interactive = true))
        assertTrue(GuardRules.wakeForAlarm(relockDark = false, interactive = false))
        assertTrue(GuardRules.LOCK_TRIES >= 2 && GuardRules.LOCK_POLLS * GuardRules.LOCK_POLL_MS <= 1_000L)
    }

    @Test fun relockIsVerifiedNotFireAndForget() {
        val g = src("Guard.kt")
        val at = g.indexOf("relockJob = scope.launch {")
        assertTrue(at > 0)
        val body = g.substring(at, g.indexOf("Smart Lock", at))
        assertTrue(body.contains("relockDark = true") && body.contains("lockSure(app)"))
        // पुराना कारण: लॉक के 0.5 सेकंड बाद खुद चेतावनी स्क्रीन जलाना
        assertFalse(g.contains("delay(500); GuardAlarmActivity.show(app)"))
        assertTrue(g.contains("if (lockedNow(app)) return true"))
        assertTrue(g.contains("Intent.ACTION_SCREEN_ON -> relockDark = false"))
        assertTrue(src("GuardAlarmActivity.kt").contains("if (!GuardRules.wakeForAlarm(Guard.relockDark, pm?.isInteractive != false)) return"))
    }

    @Test fun motionToggle() {
        assertTrue(GuardRules.bouncerTrips(motionOn = true, alarming = false))
        assertFalse(GuardRules.bouncerTrips(motionOn = false, alarming = false))
        assertFalse(GuardRules.bouncerTrips(motionOn = true, alarming = true))
        val g = src("Guard.kt")
        assertTrue(g.contains("fun motionOn(c: Context) = Prefs.bool(c, P_MOTION, true)"))      // बाय डिफ़ॉल्ट पहले जैसा
        assertTrue(g.contains("if (moveOn) try {"))
        assertTrue(g.contains("idleWake = moveOn && GuardRules.idleWake(wakeSensor)"))
        assertTrue(g.contains("GuardRules.isBouncer(texts) && GuardRules.bouncerTrips(motionOn(app), alarming)"))
    }

    @Test fun wrongPinAlwaysToggle() {
        assertTrue(GuardRules.sentinelOn(on = true, secure = true))
        assertFalse(GuardRules.sentinelOn(on = true, secure = false))
        assertFalse(GuardRules.sentinelOn(on = false, secure = true))
        val g = src("Guard.kt")
        assertTrue(g.contains("fun wrongAlwaysOn(c: Context) = Prefs.bool(c, P_WRONG_ALWAYS, false)"))  // बाय डिफ़ॉल्ट बंद
        assertTrue(g.contains("if (!armed && !alarming) {\n            if (sentry != null && !arming) sentinelEvent(svc, e)\n            return\n        }"))
        // गलत PIN → बिना गिनती पहरा + तुरंत अलार्म (अलार्म = सायरन + फ़ोटो + लोकेशन SMS)
        val arm = g.substring(g.indexOf("private fun armNow("))
        assertTrue(arm.indexOf("startWatch(svc, 0L)") in 1 until arm.indexOf("trigger(app, why)"))
        assertTrue(g.substring(g.indexOf("private fun sentinelEvent(")).let { it.contains("GuardRules.isWrongPin(texts)") && it.contains("if (!locked(app)) return") })
        assertTrue(src("DKAccessibilityService.kt").contains("Guard.sentinel(this)"))
        val s = src("SettingsGuard.kt")
        assertTrue(s.contains("Guard.P_MOTION") && s.contains("Guard.P_WRONG_ALWAYS") && s.contains("Guard.sentinel(this)"))
        assertEquals(1, Regex("card\\(\"सायरन कब बजे\"").findAll(s).count())
    }
}
