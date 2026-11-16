package com.dk.assistant.wake

import com.dk.assistant.wake.WakeRules.Listen
import com.dk.assistant.wake.WakeRules.Sense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16 नवंबर 2026: नया वेक वर्ड "हे डीके / हेलो डीके" - फ़ोन के बिना जाँचने लायक सारे नियम */
class WakeRulesTest {
    @Test fun audioFormat_16kMonoSmallChunks() {
        assertEquals(16_000, WakeRules.RATE)
        assertEquals(80, WakeRules.STEP * 1000 / WakeRules.RATE)          // 80 ms टुकड़े
        assertEquals(2_000, WakeRules.RING_STEPS * 80)                    // RAM में सिर्फ़ 2 s
        assertEquals(8_000, WakeRules.SILENCE_DEFAULT_MS)
        assertTrue(WakeRules.SILENCE_DEFAULT_MS in WakeRules.SILENCE_CHOICES)
    }

    @Test fun twoWords() {
        assertEquals(listOf("hey_dk", "hello_dk"), WakeRules.Word.values().map { it.id })
        assertEquals("हे डीके", WakeRules.Word.HEY.hi)
        assertEquals("हेलो डीके", WakeRules.Word.HELLO.hi)
        assertEquals(WakeRules.Word.HELLO, WakeRules.Word.byId("hello_dk"))
    }

    @Test fun listenModes() {
        assertTrue(WakeRules.allowedNow(Listen.ALWAYS, screenOn = false, charging = false))
        assertTrue(WakeRules.allowedNow(Listen.SCREEN_ON, screenOn = true, charging = false))
        assertFalse(WakeRules.allowedNow(Listen.SCREEN_ON, screenOn = false, charging = true))
        assertTrue(WakeRules.allowedNow(Listen.CHARGING, screenOn = false, charging = true))
        assertFalse(WakeRules.allowedNow(Listen.CHARGING, screenOn = true, charging = false))
        assertFalse(WakeRules.allowedNow(Listen.BUTTON, screenOn = true, charging = true))
        assertEquals(Listen.ALWAYS, Listen.byId("कुछ भी"))
    }

    @Test fun sensitivity_threeSteps_warningOnHigh() {
        assertTrue(Sense.LOW.th > Sense.MID.th && Sense.MID.th > Sense.HIGH.th)
        assertEquals(listOf("कम", "बीच का", "ज़्यादा"), Sense.values().map { it.hi })
        assertTrue(Sense.HIGH.warn.contains("चेतावनी"))
        assertEquals(Sense.MID, Sense.byId(null))
    }

    @Test fun owner_threshold_slider_and_selectable() {
        assertEquals(WakeRules.OWNER_TH_MIN, WakeRules.ownerFromSlider(-5), 1e-6f)
        assertEquals(WakeRules.OWNER_TH_MAX, WakeRules.ownerFromSlider(999), 1e-6f)
        for (p in 0..60) assertTrue(kotlin.math.abs(WakeRules.ownerToSlider(WakeRules.ownerFromSlider(p)) - p) <= 1)
        assertEquals(WakeRules.OWNER_TH_DEFAULT, WakeRules.clampOwner(Float.NaN), 1e-6f)
        assertFalse(WakeRules.ownerOk(null, 0.4f))
        assertFalse(WakeRules.ownerOk(Float.NaN, 0.4f))
        assertFalse(WakeRules.ownerOk(0.39f, 0.4f))
        assertTrue(WakeRules.ownerOk(0.41f, 0.4f))
        // voiceprint नहीं = "सिर्फ़ मालिक" चुना ही नहीं जा सकता
        assertFalse(WakeRules.ownerSelectable(enrolled = false, modelReady = true))
        assertFalse(WakeRules.ownerSelectable(enrolled = true, modelReady = false))
        assertTrue(WakeRules.ownerSelectable(enrolled = true, modelReady = true))
        assertTrue(WakeRules.ENROLL_PHRASES.size in 3..5)
    }

    @Test fun decider_refractory() {
        val d = WakeRules.Decider()
        assertFalse(d.hit(0.2f, 0.5f, 1_000))
        assertTrue(d.hit(0.9f, 0.5f, 1_000))
        assertFalse("एक ही बात दो बार नहीं", d.hit(0.9f, 0.5f, 1_500))
        assertTrue(d.hit(0.9f, 0.5f, 1_000 + WakeRules.REFRACTORY_MS))
        assertFalse(d.hit(Float.NaN, 0.5f, 99_999))
    }

    @Test fun decider_needsConsecutiveSteps() {
        val d = WakeRules.Decider()
        assertFalse(d.hit(0.9f, 0.5f, 1_000, need = 3))
        assertFalse(d.hit(0.9f, 0.5f, 1_080, need = 3))
        assertFalse("बीच में टूटा = फिर से गिनो", d.hit(0.1f, 0.5f, 1_160, need = 3))
        assertFalse(d.hit(0.9f, 0.5f, 1_240, need = 3))
        assertFalse(d.hit(0.9f, 0.5f, 1_320, need = 3))
        assertTrue(d.hit(0.9f, 0.5f, 1_400, need = 3))
        assertTrue(WakeRules.Sense.LOW.need >= WakeRules.Sense.MID.need && WakeRules.Sense.MID.need >= WakeRules.Sense.HIGH.need)
    }

    @Test fun gate_opensOnSpeech_closesAfterQuiet() {
        val g = WakeRules.Gate()
        repeat(10) { assertFalse(g.feed(50.0)) }
        assertFalse("चुप्पी में मॉडल नहीं चलता (बैटरी)", g.open)
        assertTrue("अभी खुला", g.feed(3_000.0))
        assertTrue(g.open)
        assertFalse("खुले पर दोबारा 'अभी खुला' नहीं", g.feed(3_000.0))
        g.feed(50.0); assertTrue("थोड़ी चुप्पी में खुला रहे", g.open)
        repeat(20) { g.feed(50.0) }
        assertFalse(g.open)
    }

    @Test fun agc_boostsQuietSpeech_neverClips() {
        val a = WakeRules.Agc()
        val x = ShortArray(1280) { (if (it % 2 == 0) 300 else -300).toShort() }
        repeat(20) { a.apply(x.copyOf(), speech = true) }
        val y = x.copyOf(); a.apply(y, speech = true)
        assertTrue(WakeRules.rms(y) > WakeRules.rms(x))
        val loud = ShortArray(1280) { Short.MAX_VALUE }
        a.apply(loud, speech = true)
        assertTrue(loud.all { it <= Short.MAX_VALUE && it >= Short.MIN_VALUE })
    }

    @Test fun latency() {
        assertEquals(240L, WakeRules.latencyMs(10_240, 10_000))
        assertEquals(0L, WakeRules.latencyMs(10_000, 0))
        assertTrue(WakeRules.latencyText(240, 120).contains("1 सेकंड से कम"))
        assertTrue(WakeRules.latencyText(1_400, null).contains("⚠"))
    }

    @Test fun lockScreen_privateNeedsUnlock_emergencyAllowed() {
        for (t in listOf("notifications", "look_photo", "chat_reply", "notification_reply")) assertTrue(t, WakeRules.lockedPrivate(t, null))
        assertTrue(WakeRules.lockedPrivate("whatsapp_task", "send_message"))
        assertFalse(WakeRules.lockedPrivate("whatsapp_task", "voice_call"))
        assertFalse(WakeRules.lockedPrivate("whatsapp_task", "send_location"))
        for (t in listOf("torch", "set_alarm", "call_contact", "emergency_sos")) assertFalse(t, WakeRules.lockedPrivate(t, null))
        assertTrue(WakeRules.LOCKED_PRIVATE_NOTE.contains("अनलॉक"))
    }

    @Test fun honestLimits_and_hotword() {
        assertTrue(WakeRules.LIMITS.contains("100%"))
        assertTrue(WakeRules.LIMITS.contains("रिकॉर्डिंग") && WakeRules.LIMITS.contains("पासवर्ड"))
        assertTrue(WakeRules.LIMITS.contains("हरा माइक"))
        assertEquals("enrolled", WakeRules.hotwordState(2))
        assertEquals("unsupported", WakeRules.hotwordState(-1))
        assertTrue(WakeRules.hotwordText("unsupported").contains("DK का अपना मॉडल"))
        assertTrue(WakeRules.A14_NOTE.contains("Android 14"))
    }
}
