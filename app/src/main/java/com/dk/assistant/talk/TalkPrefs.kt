package com.dk.assistant.talk

import android.content.Context
import com.dk.assistant.Prefs

/**
 * 18 नवंबर 2026 (मालिक: "सुनने का काम अलग हो और सोचने-बोलने का काम अलग"): नए "अलग-अलग" तरीके की settings.
 * पुरानी कोई setting नहीं बदलती - default = Gemini Live (पहले जैसा). नई keys ही जोड़ी हैं.
 */
object TalkPrefs {
    const val MODE = "talk_mode"                 // "live" (Gemini Live, पहले जैसा) / "split" (अलग-अलग)
    const val STT = "stt_engine"                 // "sherpa" / "android" (पुराना "whisper" = sherpa)
    const val SILENCE = "talk_silence_ms"        // रुकने के बाद कितनी चुप्पी = बात पूरी
    const val BARGE = "talk_barge"               // DK बोलते समय बीच में बोलें = DK रुके

    const val MODE_LIVE = "live"
    const val MODE_SPLIT = "split"
    /** 21 नवंबर 2026: Whisper हटा - फ़ोन पर Sherpa-ONNX ([com.dk.assistant.stt.SherpaStt]) */
    const val STT_SHERPA = "sherpa"
    const val STT_ANDROID = "android"

    const val SILENCE_DEF = 750L
    const val SILENCE_MIN = 400L
    const val SILENCE_MAX = 1500L

    fun split(c: Context): Boolean = Prefs.str(c, MODE, MODE_LIVE) == MODE_SPLIT

    /**
     * 20 नवंबर 2026 (मालिक - वॉइस का नियम): अलग-अलग तरीका (सुनना -> सोचना -> TTS) चाहिए? Settings में "अलग-अलग" चुना,
     * या चिप में ऐसा मॉडल चुना जो खुद आवाज़ में नहीं बोलता (supportsVoice = false) - तब वही मॉडल जवाब लिखे और मौजूदा TTS बोले.
     */
    fun wanted(c: Context): Boolean = split(c) || com.dk.assistant.dkai.SelectedModel.textVoice(c)
    fun stt(c: Context): String = norm(Prefs.str(c, STT, STT_SHERPA))
    fun silenceMs(c: Context): Long = clampSilence(Prefs.long(c, SILENCE, SILENCE_DEF))
    fun barge(c: Context): Boolean = Prefs.bool(c, BARGE, true)

    fun setMode(c: Context, split: Boolean) = Prefs.edit(c) { putString(MODE, if (split) MODE_SPLIT else MODE_LIVE) }
    fun setStt(c: Context, id: String) = Prefs.edit(c) { putString(STT, norm(id)) }
    fun setSilence(c: Context, ms: Long) = Prefs.edit(c) { putLong(SILENCE, clampSilence(ms)) }
    fun setBarge(c: Context, on: Boolean) = Prefs.edit(c) { putBoolean(BARGE, on) }

    /** pure (JUnit): सिर्फ़ दो इंजन - Android, बाकी सब (पुराना "whisper" भी) = Sherpa-ONNX */
    fun norm(id: String): String = if (id == STT_ANDROID) STT_ANDROID else STT_SHERPA

    /** pure (JUnit) - 50 ms के कदम */
    fun clampSilence(ms: Long): Long = (ms.coerceIn(SILENCE_MIN, SILENCE_MAX) / 50) * 50
}
