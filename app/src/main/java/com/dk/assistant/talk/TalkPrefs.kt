package com.dk.assistant.talk

import android.content.Context
import com.dk.assistant.Prefs

/**
 * 18 नवंबर 2026 (मालिक: "सुनने का काम अलग हो और सोचने-बोलने का काम अलग"): नए "अलग-अलग" तरीके की settings.
 * पुरानी कोई setting नहीं बदलती - default = Gemini Live (पहले जैसा). नई keys ही जोड़ी हैं.
 */
object TalkPrefs {
    const val MODE = "talk_mode"                 // "live" (Gemini Live, पहले जैसा) / "split" (अलग-अलग)
    const val STT = "stt_engine"                 // "whisper" / "android"
    const val SILENCE = "talk_silence_ms"        // रुकने के बाद कितनी चुप्पी = बात पूरी
    const val FULL = "whisper_full_accuracy"     // Whisper पूरी 30 s खिड़की (धीमा, थोड़ा ज़्यादा सटीक)
    const val BARGE = "talk_barge"               // DK बोलते समय बीच में बोलें = DK रुके

    const val MODE_LIVE = "live"
    const val MODE_SPLIT = "split"
    const val STT_WHISPER = "whisper"
    const val STT_ANDROID = "android"

    const val SILENCE_DEF = 750L
    const val SILENCE_MIN = 400L
    const val SILENCE_MAX = 1500L

    fun split(c: Context): Boolean = Prefs.str(c, MODE, MODE_LIVE) == MODE_SPLIT
    fun stt(c: Context): String = Prefs.str(c, STT, STT_WHISPER).let { if (it == STT_ANDROID) STT_ANDROID else STT_WHISPER }
    fun silenceMs(c: Context): Long = clampSilence(Prefs.long(c, SILENCE, SILENCE_DEF))
    fun fullAccuracy(c: Context): Boolean = Prefs.bool(c, FULL, false)
    fun barge(c: Context): Boolean = Prefs.bool(c, BARGE, true)

    fun setMode(c: Context, split: Boolean) = Prefs.edit(c) { putString(MODE, if (split) MODE_SPLIT else MODE_LIVE) }
    fun setStt(c: Context, id: String) = Prefs.edit(c) { putString(STT, if (id == STT_ANDROID) STT_ANDROID else STT_WHISPER) }
    fun setSilence(c: Context, ms: Long) = Prefs.edit(c) { putLong(SILENCE, clampSilence(ms)) }
    fun setFull(c: Context, on: Boolean) = Prefs.edit(c) { putBoolean(FULL, on) }
    fun setBarge(c: Context, on: Boolean) = Prefs.edit(c) { putBoolean(BARGE, on) }

    /** pure (JUnit) - 50 ms के कदम */
    fun clampSilence(ms: Long): Long = (ms.coerceIn(SILENCE_MIN, SILENCE_MAX) / 50) * 50
}
