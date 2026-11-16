package com.dk.assistant

import java.util.Locale

/**
 * 15 नवंबर 2026 (मालिक: "डाउनलोड हो तो साफ़ दिखे"): मॉडल डाउनलोड की प्रगति % के साथ MB में भी - जैसे "55% (15.5/28.3 MB)".
 * Pure (JVM test: DlTextTest). [com.dk.assistant.plus.models.ModelManager] और [FaceAI] के डाउनलोड यही दिखाते हैं.
 */
object DlText {
    fun mb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes.coerceAtLeast(0L) / 1_048_576.0)

    /** pct (0..100) + कुल साइज़ -> "55% (15.5/28.3 MB)" */
    fun progress(pct: Int, totalBytes: Long): String {
        val p = pct.coerceIn(0, 100)
        if (totalBytes <= 0L) return "$p%"
        val got = totalBytes * p / 100
        return "$p% (${mb(got)}/${mb(totalBytes)} MB)"
    }
}
