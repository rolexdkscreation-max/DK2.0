package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlTextTest {
    @Test fun progress_showsPercentAndMb() {
        assertEquals("0% (0.0/27.0 MB)", DlText.progress(0, 28_311_552L))
        assertEquals("50% (13.5/27.0 MB)", DlText.progress(50, 28_311_552L))
        assertEquals("100% (27.0/27.0 MB)", DlText.progress(100, 28_311_552L))
    }

    @Test fun progress_clampsAndHandlesUnknownSize() {
        assertEquals("100% (1.0/1.0 MB)", DlText.progress(140, 1_048_576L))
        assertEquals("0% (0.0/1.0 MB)", DlText.progress(-3, 1_048_576L))
        assertEquals("42%", DlText.progress(42, 0L))
    }

    @Test fun existingDownloads_useMbProgress() {
        // 16 नवंबर 2026: पुराना Hey DK सिखाओ (SettingsActivity) हटा - वही MB-progress अब आवाज़-कमांड सिखाने में (SettingsVoiceCmds)
        val vc = listOf(java.io.File("src/main/java/com/dk/assistant/SettingsVoiceCmds.kt"), java.io.File("app/src/main/java/com/dk/assistant/SettingsVoiceCmds.kt")).first { it.exists() }.readText()
        val s = SetSrc.all() + "\n" + vc
        assertTrue(s.contains("DlText.progress(pct, OV.MODEL_BYTES)"))
    }
}
