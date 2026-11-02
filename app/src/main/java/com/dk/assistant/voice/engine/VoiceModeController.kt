package com.dk.assistant.voice.engine

import android.content.Context

/**
 * नवंबर 2026 (मालिक: "Kokoro पूरी तरह हटाओ; डिफ़ॉल्ट Direct Gemini Live; बैकअप Gemini TTS, फिर फ़ोन की आवाज़"):
 *
 * - **Mode A (डिफ़ॉल्ट): Direct Gemini Live Voice** ([LiveNativeEngine], "DK लाइव") - Gemini Live की अपनी PCM आवाज़ सीधे.
 * - **Mode B (बैकअप): Gemini TTS** - Gemini Live सिर्फ़ जवाब लिखता है, आवाज़ Gemini TTS से ("लिखो-फिर-बोलो").
 *   Gemini TTS भी न चले = फ़ोन की अपनी (Google / Android) आवाज़ ([com.dk.assistant.PhoneVoice]) - चुप नहीं.
 *
 * सब कुछ [VoiceEngines] में ही सेव होता है ("active", SharedPreferences) - ऐप बंद-चालू करने पर वही रहता है;
 * नया इंस्टॉल = Direct Gemini Live. सेटिंग्स का "Direct Gemini Live Voice" स्विच = DK लाइव इंजन का स्विच.
 * Kokoro पूरी तरह हटाया (वापस नहीं लाना).
 */
object VoiceModeController {
    enum class Mode { GEMINI_TTS, DIRECT_LIVE }

    fun modeOf(id: String): Mode = when (id) {
        VoiceEngines.GEMINI_TTS -> Mode.GEMINI_TTS
        else -> Mode.DIRECT_LIVE
    }
    fun mode(c: Context): Mode = modeOf(VoiceEngines.activeId(c))
    fun directLive(c: Context): Boolean = mode(c) == Mode.DIRECT_LIVE

    /**
     * pure (JVM test): स्विच दबाया -> अब कौन. चालू वाला बंद किया = Direct Live पर लौटो; Direct Live ही बंद किया = Gemini TTS.
     * कोई भी चालू न रहे - ऐसा नहीं होता.
     */
    fun next(current: String, id: String, on: Boolean, available: Set<String>): String =
        VoiceEngines.toggle(current, id, on, default = VoiceEngines.LIVE_NATIVE, available = available, second = VoiceEngines.GEMINI_TTS)

    /** जो इंजन इस फ़ोन पर चुने जा सकते हैं */
    fun available(): Set<String> = VoiceEngines.all.map { it.id }.toSet()

    /** सेटिंग्स का स्विच: बदलो, सेव करो. लौटाता है अब कौन चालू */
    fun switch(c: Context, id: String, on: Boolean): String {
        val cur = VoiceEngines.activeId(c)
        val next = next(cur, id, on, available())
        if (next != cur) VoiceEngines.setActive(c, next)
        return next
    }

    /** "Direct Gemini Live Voice" चालू / बंद */
    fun setDirectLive(c: Context, on: Boolean): String = switch(c, VoiceEngines.LIVE_NATIVE, on)
}
