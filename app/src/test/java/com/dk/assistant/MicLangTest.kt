package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mic error 12/13 (SpeechRecognizer: भाषा नहीं मिली) - हल्के मोड में अटकना नहीं, तुरंत अगली भाषा/online */
class MicLangTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name")).first { it.exists() }.readText()

    private val HiOff = MicLang.Plan("hi-IN", true)
    private val HiOn = MicLang.Plan("hi-IN", false)
    private val EnOn = MicLang.Plan("en-IN", false)

    @Test fun codes_matchAndroid() {
        assertTrue(MicLang.isLangError(13)); assertTrue(MicLang.isLangError(12))
        assertFalse(MicLang.isLangError(7)); assertFalse(MicLang.isLangError(9)); assertFalse(MicLang.isLangError(0))
        assertEquals(DictationRules.ERR_LANG_UNAVAILABLE, MicLang.ERR_LANG_UNAVAILABLE)
        assertEquals(DictationRules.ERR_LANG_NOT_SUPPORTED, MicLang.ERR_LANG_NOT_SUPPORTED)
    }

    @Test fun plans_offlineFirst_thenOnline_thenEnglish_thenDevice() {
        assertEquals(listOf(HiOff, HiOn, EnOn), MicLang.plans(true, "en-IN"))
        assertEquals(listOf(HiOn, EnOn), MicLang.plans(false, "hi-IN"))
        assertEquals(listOf(HiOff, HiOn, EnOn, MicLang.Plan("mr-IN", false)), MicLang.plans(true, "mr-IN"))
        assertEquals(listOf(HiOn, EnOn), MicLang.plans(false, "und"))
    }

    @Test fun error13_offline_isSkipped_andRemembered() {
        val m = MicLang.Memory(); val all = MicLang.plans(true, "en-IN")
        assertEquals(HiOff, m.order(all, 0).first())
        m.bad(HiOff, now = 0)                                   // offline पैक नहीं = 13
        assertEquals(listOf(HiOn, EnOn), m.order(all, 1_000))   // अगली बार सीधे online, offline दोबारा नहीं
        m.good(HiOn)
        assertEquals(HiOn, m.order(all, 2_000).first())
        // 10 मिनट बाद offline फिर आज़माओ (पैक डाउनलोड हो गया हो)
        assertTrue(HiOff in m.order(all, MicLang.BAD_FOR_MS + 1))
        assertEquals(HiOn, m.order(all, MicLang.BAD_FOR_MS + 1).first())
    }

    @Test fun allBad_retriesFullList_notEmpty() {
        val m = MicLang.Memory(); val all = MicLang.plans(true, "en-IN")
        all.forEach { m.bad(it, 0) }
        assertEquals(all, m.order(all, 1))
    }

    @Test fun hinglish_extraLanguage() {
        assertEquals("en-IN", MicLang.extraLang("hi-IN"))
        assertEquals("hi-IN", MicLang.extraLang("en-IN"))
        assertEquals(null, MicLang.extraLang("mr-IN"))
    }

    @Test fun source_listenerUsesPlan_noDeadIntent_noFixedHindiOffline() {
        val v = src("VoiceListener.kt")
        assertTrue(v.contains("EXTRA_LANGUAGE, plan.lang"))
        assertTrue(v.contains("if (plan.offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)"))
        assertFalse(v.contains("if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE"))
        val s = com.dk.assistant.AccSrc.all()
        val hl = s.substring(s.indexOf("private suspend fun hearBackup()"), s.indexOf("fun appOpened()"))
        assertFalse(hl.contains("recognizerIntent"))           // बना पर कभी न भेजा गया Intent हटा
        assertFalse(hl.contains("delay(3000)"))                // 5 × 3s चुप्पी नहीं
        assertTrue(hl.contains("listener.langMissing"))
    }

    @Test fun source_liveMic_echoCancelSourceFirst() {
        val g = LiveSrc.all()
        val om = g.substring(g.indexOf("internal fun GeminiLive.openMic()"))
        val vc = om.indexOf("AudioSource.VOICE_COMMUNICATION"); val def = om.indexOf("AudioSource.DEFAULT")
        assertTrue(vc in 0 until def)
    }
}
