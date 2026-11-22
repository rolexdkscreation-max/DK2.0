package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 21 नवंबर 2026 (मालिक): DK ऐप लॉक + टच मोड की बैटरी / फिर से लॉक - pure नियम और जोड़ */
class AppLockRulesTest {
    private val own = "com.dk.assistant"

    @Test fun pin_rules() {
        assertTrue(AppLockRules.pinOk("4826"))
        assertFalse(AppLockRules.pinOk("123"))
        assertFalse(AppLockRules.pinOk("12a4"))
        assertFalse(AppLockRules.pinOk("1234567890123"))
        assertTrue(AppLockRules.weakPin("1111"))
        assertTrue(AppLockRules.weakPin("1234"))
        assertTrue(AppLockRules.weakPin("9876"))
        assertFalse(AppLockRules.weakPin("4826"))
    }

    @Test fun pattern_rules() {
        assertTrue(AppLockRules.patternOk(listOf(0, 1, 2, 5)))
        assertFalse(AppLockRules.patternOk(listOf(0, 1, 2)))
        assertFalse(AppLockRules.patternOk(listOf(0, 1, 1, 2)))
        assertFalse(AppLockRules.patternOk(listOf(0, 1, 2, 9)))
        assertEquals("P:0-1-2-5", AppLockRules.patternKey(listOf(0, 1, 2, 5)))
    }

    @Test fun seal_neverStoresPin_andChecks() {
        val salt = ByteArray(16) { it.toByte() }
        val h = AppLockRules.seal("4826", salt, 2_000)
        assertFalse(h.contains("4826"))
        assertTrue(h.startsWith("v1:2000:"))
        assertTrue(AppLockRules.check("4826", h))
        assertFalse(AppLockRules.check("4827", h))
        assertFalse(AppLockRules.check("", h))
        assertFalse(AppLockRules.check("4826", ""))
        assertFalse(AppLockRules.check("4826", "garbage"))
        // नया salt = नया hash (एक जैसा PIN भी अलग दिखे)
        assertNotEquals(h, AppLockRules.seal("4826", ByteArray(16) { 7 }, 2_000))
    }

    @Test fun wait_growsAfterFreeTries() {
        for (f in 0 until AppLockRules.FREE_TRIES) assertEquals(0L, AppLockRules.waitMs(f))
        assertEquals(30_000L, AppLockRules.waitMs(AppLockRules.FREE_TRIES))
        assertEquals(60_000L, AppLockRules.waitMs(AppLockRules.FREE_TRIES + 1))
        assertEquals(AppLockRules.MAX_WAIT_MS, AppLockRules.waitMs(50))
        assertEquals("30 सेकंड", AppLockRules.waitLabel(30_000L))
    }

    @Test fun front_ignoresSystemKeyboardAndDkHelpers() {
        assertEquals("com.whatsapp", AppLockRules.front("com.whatsapp", "com.whatsapp.Main", own))
        assertNull(AppLockRules.front("com.android.systemui", "x", own))
        assertNull(AppLockRules.front("com.google.android.inputmethod.latin", "x", own))
        assertNull(AppLockRules.front("android", "x", own))
        assertEquals(own, AppLockRules.front(own, "com.dk.assistant.MainActivity", own))
        assertEquals(own, AppLockRules.front(own, "com.dk.assistant.studio.StudioActivity", own))
        // ताले की अपनी स्क्रीन / टच मोड अलार्म / dialog = गिनती में नहीं (वरना ताला खुद पर ताला)
        assertNull(AppLockRules.front(own, "com.dk.assistant.AppLockActivity", own))
        assertNull(AppLockRules.front(own, "com.dk.assistant.GuardAlarmActivity", own))
        assertNull(AppLockRules.front(own, "android.app.AlertDialog", own))
        assertNull(AppLockRules.front(null, null, own))
    }

    @Test fun session_locksOnReturnAfterGrace_andScreenOff() {
        val s = AppLockRules.Session(10_000L)
        assertTrue(s.onFront("com.whatsapp", true, 0))          // पहली बार = ताला
        s.unlocked("com.whatsapp")
        assertFalse(s.onFront("com.whatsapp", true, 100))       // अंदर ही
        assertFalse(s.onFront("com.launcher", false, 1_000))     // होम पर गए
        assertFalse(s.onFront("com.whatsapp", true, 5_000))      // 10 s के अंदर लौटे
        assertFalse(s.onFront("com.launcher", false, 6_000))
        assertTrue(s.onFront("com.whatsapp", true, 30_000))      // देर से लौटे = फिर ताला
        s.unlocked("com.whatsapp")
        s.screenOff()
        assertTrue(s.onFront("com.whatsapp", true, 30_500))      // स्क्रीन बंद हुई थी
        assertFalse(s.onFront("com.android.chrome", false, 31_000))
    }

    @Test fun session_immediateAndScreenOffOnly() {
        val now = AppLockRules.Session(0L)
        now.onFront("a", true, 0); now.unlocked("a"); now.onFront("b", false, 10)
        assertTrue(now.onFront("a", true, 20))
        val off = AppLockRules.Session(AppLockRules.ON_SCREEN_OFF)
        off.onFront("a", true, 0); off.unlocked("a"); off.onFront("b", false, 10)
        assertFalse(off.onFront("a", true, 99_999_999))
    }

    @Test fun guard_battery_noIdleWakeLock() {
        assertFalse(GuardRules.idleWake(true))
        assertTrue(GuardRules.idleWake(false))
        assertFalse(GuardRules.needWake(alarming = false, tracking = false, idleWake = false))
        assertTrue(GuardRules.needWake(alarming = true, tracking = false, idleWake = false))
        assertTrue(GuardRules.needWake(alarming = false, tracking = true, idleWake = false))
    }

    @Test fun guard_wrongPin_texts() {
        assertTrue(GuardRules.isWrongPin(listOf("Wrong PIN")))
        assertTrue(GuardRules.isWrongPin(listOf("Incorrect pattern. Try again")))
        assertTrue(GuardRules.isWrongPin(listOf("गलत पिन")))
        assertTrue(GuardRules.isWrongPin(listOf("Try again in 30 seconds")))
        assertFalse(GuardRules.isWrongPin(listOf("Enter PIN")))
        assertFalse(GuardRules.isWrongPin(listOf("Wi-Fi", "Bluetooth")))
    }

    @Test fun guard_relockOnOpen_onlyWithAnotherWayOut() {
        assertTrue(GuardRules.relockOnOpen(alarming = true, on = true, faceReady = false, hasCode = false, hasDkPin = true))
        assertTrue(GuardRules.relockOnOpen(alarming = true, on = true, faceReady = true, hasCode = false, hasDkPin = false))
        assertFalse(GuardRules.relockOnOpen(alarming = true, on = true, faceReady = false, hasCode = false, hasDkPin = false))   // मालिक फँसे नहीं
        assertFalse(GuardRules.relockOnOpen(alarming = false, on = true, faceReady = true, hasCode = true, hasDkPin = true))
        assertFalse(GuardRules.relockOnOpen(alarming = true, on = false, faceReady = true, hasCode = true, hasDkPin = true))
    }

    private fun src(n: String): String {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant/$n"), File(d, "app/src/main/java/com/dk/assistant/$n")).firstOrNull { it.exists() }?.let { return it.readText() }
            d = d.parentFile
        }
        error("$n नहीं मिला")
    }

    @Test fun wiring() {
        val g = src("Guard.kt")
        assertTrue(g.contains("getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)"))
        assertTrue(g.contains("SensorManager.SENSOR_DELAY_NORMAL, GuardRules.SENSOR_BATCH_US"))
        assertFalse(g.contains("SensorManager.SENSOR_DELAY_UI"))
        assertTrue(g.contains("GuardRules.needWake("))
        assertTrue(g.contains("GuardRules.relockOnOpen("))
        assertTrue(g.contains("GuardRules.isWrongPin(texts)"))
        assertTrue(g.contains("fun pinDisarm("))
        val svc = src("DKAccessibilityService.kt")
        assertTrue(svc.contains("AppLock.onEvent(this, event)"))
        assertTrue(svc.contains("AppLock.start(this)"))
        val alarm = src("GuardAlarmActivity.kt")
        assertTrue(alarm.contains("DK सीक्रेट PIN डालें") && alarm.contains("Guard.pinDisarm("))
        val act = src("AppLockActivity.kt")
        assertTrue(act.contains("BiometricPrompt") && act.contains("QuickFaceGate()") && act.contains("FLAG_SECURE"))
        assertTrue(act.contains("NeonPinPad") && act.contains("NeonPattern"))
        assertTrue(src("SettingsActivity.kt").contains("\"applock\" -> { title.text = \"ऐप लॉक\"; secAppLock() }"))
        // PIN कभी सीधा सेव नहीं
        assertTrue(src("AppLockStore.kt").contains("AppLockRules.seal("))
    }
}
