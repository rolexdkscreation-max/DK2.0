package com.dk.assistant.voice.engine

import android.content.Context

/**
 * नवंबर 2026: कौन बोलेगा - दो रास्ते (Edge TTS हटाया, उसकी जगह Kokoro):
 *
 * - **Mode A (डिफ़ॉल्ट): Kokoro TTS** - Gemini Live सिर्फ़ जवाब लिखता है, आवाज़ फ़ोन में ही बनती है ([KokoroTtsEngine]).
 *   (Gemini TTS भी इसी तरह का "लिखो-फिर-बोलो" रास्ता है - पहले जैसा ही, Worker से आवाज़.)
 * - **Mode B: Direct Gemini Live Voice** ([LiveNativeEngine], "DK लाइव") - Gemini Live की अपनी PCM आवाज़ सीधे;
 *   तब Kokoro बेकार न बैठे -> उसकी याद (model) छोड़ दी जाती है.
 *
 * सब कुछ [VoiceEngines] में ही सेव होता है ("active", SharedPreferences) - ऐप बंद-चालू करने पर वही रहता है;
 * नया इंस्टॉल = Kokoro (Direct बंद). सेटिंग्स का "Direct Gemini Live Voice" स्विच = DK लाइव इंजन का स्विच.
 */
object VoiceModeController {
    enum class Mode { KOKORO, GEMINI_TTS, DIRECT_LIVE }

    fun modeOf(id: String): Mode = when (id) {
        VoiceEngines.LIVE_NATIVE -> Mode.DIRECT_LIVE
        VoiceEngines.GEMINI_TTS -> Mode.GEMINI_TTS
        else -> Mode.KOKORO
    }
    fun mode(c: Context): Mode = modeOf(VoiceEngines.activeId(c))
    fun directLive(c: Context): Boolean = mode(c) == Mode.DIRECT_LIVE

    /**
     * pure (JVM test): स्विच दबाया -> अब कौन. चालू वाला बंद किया = Kokoro पर लौटो; Kokoro ही बंद किया = Direct Live.
     * कोई भी चालू न रहे - ऐसा नहीं होता.
     */
    fun next(current: String, id: String, on: Boolean, available: Set<String>): String =
        VoiceEngines.toggle(current, id, on, default = VoiceEngines.KOKORO_TTS, available = available, second = VoiceEngines.LIVE_NATIVE)

    /** जो इंजन इस फ़ोन पर चुने जा सकते हैं (Kokoro सिर्फ़ तब, जब उसकी .so लोड हो) */
    fun available(): Set<String> = VoiceEngines.all.map { it.id }
        .filter { it != VoiceEngines.KOKORO_TTS || KokoroTtsEngine.nativeReady() }.toSet()

    /** सेटिंग्स का स्विच: बदलो, सेव करो, Kokoro को गर्म करो / छोड़ो. लौटाता है अब कौन चालू */
    fun switch(c: Context, id: String, on: Boolean): String {
        val cur = VoiceEngines.activeId(c)
        val next = next(cur, id, on, available())
        if (next != cur) VoiceEngines.setActive(c, next)
        apply(c, next)
        return next
    }

    /** "Direct Gemini Live Voice" चालू / बंद */
    fun setDirectLive(c: Context, on: Boolean): String = switch(c, VoiceEngines.LIVE_NATIVE, on)

    /** Kokoro चुना = पहले से लोड रखो (पहला जवाब जल्दी); नहीं = याद ख़ाली */
    fun apply(c: Context, id: String = VoiceEngines.activeId(c)) {
        if (id == VoiceEngines.KOKORO_TTS) KokoroTtsEngine.warm(c) else KokoroTtsEngine.releaseNow()
    }
}
