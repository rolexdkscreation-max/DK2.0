package com.dk.assistant.voice

import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 15 नवंबर 2026 (हिस्सा 2): वॉइस गार्डियन के नियम + जुड़ाव */
class VoiceGuardianTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()

    @Test fun twoModelsNotInApk() {
        assertEquals(listOf(ModelCatalog.REDIMNET_LIGHT.id, ModelCatalog.REDIMNET_HEAVY.id), VgRules.MODELS.map { it.id })
        assertEquals(ModelCatalog.REDIMNET_LIGHT.id, VgRules.model("कुछ भी").id)
        assertEquals(ModelCatalog.REDIMNET_HEAVY.id, VgRules.model(ModelCatalog.REDIMNET_HEAVY.id).id)
        val assets = f("assets").walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertFalse(assets.any { it.endsWith(".onnx") && it.contains("redimnet", ignoreCase = true) })   // भारी मॉडल APK में नहीं
    }

    @Test fun sixPhrasesHindi() {
        assertEquals(6, VgRules.PHRASES.size)
        assertTrue(VgRules.PHRASES.all { p -> p.any { it in '\u0900'..'\u097F' } })
        assertEquals(VgRules.PHRASES.size, VgRules.PHRASES.toSet().size)
    }

    @Test fun thresholdSliderRoundTrip() {
        assertEquals(VgRules.TH_MIN, VgRules.thFromSlider(0), 1e-4f)
        assertEquals(VgRules.TH_MAX, VgRules.thFromSlider(100), 1e-4f)
        assertEquals(VgRules.TH_MAX, VgRules.clampTh(5f), 1e-4f)
        assertEquals(VgRules.TH_DEFAULT, VgRules.clampTh(Float.NaN), 1e-4f)
        val p = VgRules.sliderFromTh(VgRules.TH_DEFAULT)
        assertEquals(VgRules.TH_DEFAULT, VgRules.thFromSlider(p), 0.01f)
        assertTrue(VgRules.testLine(0.7f, 0.5f).contains("✓"))
        assertTrue(VgRules.testLine(0.2f, 0.5f).contains("✗"))
        assertTrue(VgRules.testLine(null, 0.5f).contains("फिर से"))
    }

    @Test fun awayWarnThenLock() {
        val a = VgRules.AwayState()
        assertEquals(VgRules.Away.WARN, a.onStranger(1_000))
        assertEquals(VgRules.Away.LOCK, a.onStranger(5_000))
        assertEquals(VgRules.Away.WARN, a.onStranger(6_000))       // लॉक के बाद गिनती फिर से
        assertEquals(VgRules.Away.WARN, a.onStranger(6_000 + VgRules.AWAY_WINDOW_MS + 1))  // पुरानी चेतावनी पुरानी
        a.reset()
        assertEquals(VgRules.Away.WARN, a.onStranger(99_999_999))
    }

    @Test fun flagsDefaultOffAndShown() {
        for (k in listOf(FeatureFlags.VOICE_GUARDIAN, FeatureFlags.VG_OWNER_ONLY, FeatureFlags.VG_AWAY)) {
            assertTrue(k in FeatureFlags.READY)
            assertFalse(FeatureFlags.byKey(k)!!.default)
        }
    }

    @Test fun wiredWithoutOldOwnerSwitch() {
        val vg = src("voice/VoiceGuardian.kt")
        assertTrue(vg.contains("\"dk_voice_guardian\"") && vg.contains("GLOBAL_ACTION_LOCK_SCREEN"))
        val live = src("GeminiLiveOwner.kt")
        assertTrue(live.contains("VoiceGuardian.gateOn(svc)") && live.contains("VoiceGuardian.onStranger(svc)"))
        assertTrue(src("DKAccessibilityService.kt").contains("VoiceGuardian.gateOn(this)"))
        val card = src("SettingsVoiceGuardian.kt")
        assertTrue(card.contains("card(\"वॉइस गार्डियन\"") && card.contains("मॉडल डाउनलोड करो") && card.contains("ProgressBar"))
        assertFalse(card.contains("owner_voice_on") || card.contains("सिर्फ़ मेरी आवाज़ सुनो"))
        assertTrue(src("SettingsActivity.kt").contains("voiceGuardianCard()"))
        assertTrue(src("SettingsSearch.kt").contains("\"वॉइस गार्डियन\""))
    }
}
