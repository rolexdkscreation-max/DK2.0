package com.dk.assistant

import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.talk.TalkPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 21 नवंबर 2026 (मालिक): Whisper पूरी तरह हटा - बोली लिखना फ़ोन पर Sherpa-ONNX (streaming, बिना नेट / key),
 * Live का caption भी उसी एक मॉडल से, और caption न चले तो साफ़ चेतावनी.
 */
class SherpaSttTest {
    private fun root(): File = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.isDirectory }
    private fun src(p: String) = File(root(), p).readText()
    private fun app(p: String): File = listOf(File(p), File("app/$p")).first { it.exists() }

    @Test fun onlyTwoEngines_oldWhisperPrefBecomesSherpa() {
        assertEquals(TalkPrefs.STT_SHERPA, TalkPrefs.norm("whisper"))
        assertEquals(TalkPrefs.STT_SHERPA, TalkPrefs.norm(""))
        assertEquals(TalkPrefs.STT_SHERPA, TalkPrefs.norm("sherpa"))
        assertEquals(TalkPrefs.STT_ANDROID, TalkPrefs.norm("android"))
    }

    @Test fun oldWhisperFileButton_showsItsSize() {
        assertEquals("ggml_small_q5_1.bin", ModelCatalog.OLD_WHISPER_FILE)
        assertEquals("181.3", String.format(java.util.Locale.US, "%.1f", ModelCatalog.OLD_WHISPER_BYTES / 1_048_576.0))
        assertTrue(src("SettingsTalk.kt").contains("पुरानी Whisper फ़ाइल हटाएँ"))
        assertTrue(src("SettingsTalk.kt").contains("ModelManager.deleteOldWhisper(this)"))
        // पुरानी फ़ाइल अब डाउनलोड-सूची में नहीं
        assertFalse(ModelCatalog.ALL.any { it.fileName == ModelCatalog.OLD_WHISPER_FILE })
    }

    @Test fun captionText_onlyNewWords() {
        assertEquals("मौसम कैसा है", CaptionText.after("मौसम कैसा है", ""))
        assertEquals("कैसा है", CaptionText.after("मौसम कैसा है", "मौसम"))
        // मॉडल ने पिछला आख़िरी शब्द सुधारा - शब्द बीच से नहीं कटता
        assertEquals("मौसम कैसा है", CaptionText.after("मौसम कैसा है", "मौसा"))
        assertEquals("आज कैसा", CaptionText.after("hello आज कैसा", "hello वो"))
    }

    @Test fun captionWarn_neverSilent() {
        assertEquals("लिखावट अभी बंद है, पर DK सुन रहा है", CaptionWarn.TEXT)
        assertFalse(CaptionWarn.show(live = false, has = false, usable = false, ready = false, stale = true, sinceStartMs = 99_000))
        assertTrue(CaptionWarn.show(true, has = false, usable = false, ready = false, stale = false, sinceStartMs = 0))
        assertTrue(CaptionWarn.show(true, true, usable = false, ready = true, stale = false, sinceStartMs = 0))
        assertTrue(CaptionWarn.show(true, true, true, true, stale = true, sinceStartMs = 0))
        assertFalse(CaptionWarn.show(true, true, true, ready = false, stale = false, sinceStartMs = 1_000))   // अभी खुल रहा
        assertTrue(CaptionWarn.show(true, true, true, ready = false, stale = false, sinceStartMs = CaptionWarn.GRACE_MS + 1))
        assertFalse(CaptionWarn.show(true, true, true, true, false, 0))
        assertEquals("", CaptionWarn.line(true, true, true, true, false, 0))
    }

    @Test fun whisperFullyRemoved() {
        for (f in listOf("LiveWhisper.kt", "stt/WhisperStt.kt", "stt/WhisperNative.kt", "BackendDictation.kt")) assertFalse(f, File(root(), f).exists())
        assertFalse(File(root(), "../../../../cpp").exists())
        val g = app("build.gradle.kts").readText()
        assertFalse(g.contains("externalNativeBuild")); assertFalse(g.contains("cmake"))
        // 21 नवंबर 2026: Sherpa-ONNX का AAR भी हटा (ऐप का MB) - पहचान अब ऐप के अपने onnxruntime से (IndicConformer)
        assertFalse(g.contains("k2fsa")); assertFalse(File(app("build.gradle.kts").parentFile, "libs/k2fsa-onnx-stt-2.0.2-arm64.aar").exists())
        assertFalse(app("proguard-rules.pro").readText().contains("com.k2fsa"))
        assertFalse(src("Transcriber.kt").contains("okhttp")); assertFalse(src("Transcriber.kt").contains("groq"))
    }

    @Test fun liveCaption_sameModel_keyOnlyBackup_andWarn() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("SherpaCaption("))
        assertTrue(g.indexOf("SherpaStt.ready") < g.indexOf("LiveCaption(LiveKeys.order(listOf(capKey)"))
        assertTrue(g.contains("DKState.setCaptionWarn(captionWarnNow())"))
        // Ear भी वही मॉडल
        assertTrue(src("DKAccessibilityHear.kt").contains("SherpaCaption(this"))
        // mic की आवाज़: caption को भेजना कभी रुकता नहीं (queue, पुराना टुकड़ा छोड़ो)
        val c = src("SherpaCaption.kt")
        assertTrue(c.contains("LinkedBlockingQueue")); assertTrue(c.contains("dk-caption-stt"))
        assertTrue(src("VoiceActivity.kt").contains("DKState.captionWarn"))
        assertTrue(src("dkai/DkaiVoice.kt").contains("DKState.captionWarn"))
    }

    @Test fun typingMic_sherpaFirst_thenPhone() {
        val d = src("SherpaDictation.kt")
        assertTrue(d.contains("TalkPrefs.STT_SHERPA && SherpaStt.ready(c)"))
        assertTrue(d.contains("else phone(onText, onLevel, onEnd)"))
        assertTrue(d.contains("SherpaStt.Stream.open(ctx)"))
    }
}
