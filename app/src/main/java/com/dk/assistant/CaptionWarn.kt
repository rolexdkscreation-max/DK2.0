package com.dk.assistant

/**
 * 21 नवंबर 2026 (मालिक: "Live में caption न चले - मॉडल उतरा न हो या चला न हो - तो स्क्रीन पर छोटी और साफ़ चेतावनी,
 * caption चुपचाप बंद न हो"). सिर्फ़ नियम (Android import नहीं -> JVM test [CaptionWarnTest]).
 */
object CaptionWarn {
    const val TEXT = "लिखावट अभी बंद है, पर DK सुन रहा है"
    /** मॉडल खुलने / जुड़ने का समय - इतनी देर तक "तैयार नहीं" पर चेतावनी नहीं */
    const val GRACE_MS = 5_000L

    /**
     * [live] = Live जुड़ा और चल रहा; [has] = कोई लिखने वाला है; [usable] = वह बंद / ख़राब नहीं; [ready] = खुल / जुड़ गया;
     * [stale] = लंबी बातों पर भी एक शब्द नहीं लिखा ([CaptionHealth]); [sinceStartMs] = लिखने वाला कब से शुरू.
     */
    fun show(live: Boolean, has: Boolean, usable: Boolean, ready: Boolean, stale: Boolean, sinceStartMs: Long): Boolean = when {
        !live -> false
        !has || !usable || stale -> true
        else -> !ready && sinceStartMs > GRACE_MS
    }

    fun line(live: Boolean, has: Boolean, usable: Boolean, ready: Boolean, stale: Boolean, sinceStartMs: Long): String =
        if (show(live, has, usable, ready, stale, sinceStartMs)) TEXT else ""
}
