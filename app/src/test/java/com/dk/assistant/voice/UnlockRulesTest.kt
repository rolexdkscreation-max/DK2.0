package com.dk.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 16 नवंबर 2026 (ऑडिट G1): आवाज़ से अनलॉक - PIN सिर्फ़ मालिक की पक्की आवाज़ पर, random शब्द नहीं */
class UnlockRulesTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun thresholdIsStricterThanNormal() {
        assertEquals(0.60f, UnlockRules.threshold(0.30f), 1e-6f)          // ढीली सीमा पर भी कम से कम 0.60
        assertEquals(0.70f, UnlockRules.threshold(0.60f), 1e-6f)          // आम से 0.10 ऊपर
        assertEquals(UnlockRules.TH_CAP, UnlockRules.threshold(0.80f), 1e-6f)
        assertEquals(0.70f, UnlockRules.threshold(Float.NaN), 1e-6f)          // गड़बड़ सीमा = और कड़ी
    }

    @Test fun judge() {
        val th = 0.65f
        assertEquals(UnlockRules.Verdict.OK, UnlockRules.judge(0.70f, th, false, 1500))
        assertEquals(UnlockRules.Verdict.LOW, UnlockRules.judge(0.64f, th, false, 1500))
        assertEquals(UnlockRules.Verdict.REPLAY, UnlockRules.judge(0.99f, th, true, 1500))
        assertEquals(UnlockRules.Verdict.SHORT, UnlockRules.judge(0.90f, th, false, 300))
        assertEquals(UnlockRules.Verdict.NOT_READY, UnlockRules.judge(null, th, false, 1500))
        assertFalse(UnlockRules.Verdict.values().filter { it != UnlockRules.Verdict.OK }.any { it.ok })
    }

    @Test fun replayCatchesNearIdenticalOnly() {
        val a = VoicePrint.normalize(FloatArray(VoicePrint.DIM) { (it % 7).toFloat() + 1f })
        val same = a.copyOf()
        val other = VoicePrint.normalize(FloatArray(VoicePrint.DIM) { if (it % 2 == 0) 1f else -1f })
        assertTrue(UnlockRules.isReplay(same, listOf(other, a)))
        assertFalse(UnlockRules.isReplay(other, listOf(a)))
        assertFalse(UnlockRules.isReplay(a, emptyList()))
    }

    @Test fun strikesPauseAfterThreeWrongVoices() {
        val s = UnlockRules.Strikes()
        s.fail(0); s.fail(1_000)
        assertFalse(s.paused(2_000))
        s.fail(2_000)
        assertTrue(s.paused(3_000))
        assertFalse(s.paused(2_000 + UnlockRules.PAUSE_MS + 1))
        val t = UnlockRules.Strikes()
        t.fail(0); t.fail(1); t.ok(); t.fail(2)
        assertFalse(t.paused(3))                                            // सही आवाज़ = गिनती साफ़
        val u = UnlockRules.Strikes()
        u.fail(0); u.fail(1); u.fail(UnlockRules.REJECT_WINDOW_MS + 10)     // पुरानी गलती गिनती से बाहर
        assertFalse(u.paused(UnlockRules.REJECT_WINDOW_MS + 20))
    }

    @Test fun utterMustBeRecent() {
        assertTrue(UnlockRules.utterFits(utterAt = 95_000, askedAt = 100_000))
        assertTrue(UnlockRules.utterFits(utterAt = 101_000, askedAt = 100_000))
        assertFalse(UnlockRules.utterFits(utterAt = 80_000, askedAt = 100_000))
    }

    @Test fun pinOnlyAfterGuard() {
        val s = src("DKAccessibilityScreen.kt")
        assertTrue(s.contains("canPin -> com.dk.assistant.voice.UnlockGuard.allow(this) ?: PhoneUnlocker.enter(this, km, asked)"))
        // फ़ोन के अपने सुनने वाले रास्ते ही "अनलॉक कहा" दर्ज करते हैं - AI का अपना unlock नहीं
        assertTrue(src("GeminiLiveTurnEnd.kt").contains("UnlockGuard.intent()"))
        assertTrue(src("DKAccessibilityService.kt").contains("UnlockGuard.intent()"))
        assertFalse(src("AgentRunner.kt").contains("UnlockGuard.intent("))
        // हर सुनने वाला रास्ता बात की आवाज़ देता है (सिर्फ़ मेमोरी में)
        for (f in listOf("DKAccessibilityService.kt", "DKAccessibilityOffline.kt", "GeminiLiveMic.kt", "wake/WakeWord.kt")) assertTrue(f, src(f).contains("UnlockGuard.heard("))
        // आवाज़-कमांड वाला अनलॉक भी उसी पहरेदार से
        assertTrue(src("voice/VoiceCmds.kt").contains("UnlockGuard.checkCommand(c, pcm)"))
        // log में PIN नहीं
        assertFalse(src("voice/UnlockGuard.kt").contains("PhoneLockStore.secret"))
    }
}
