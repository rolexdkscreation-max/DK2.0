package com.dk.assistant

/**
 * v3.30: DK के जवाब की भाषा - Hinglish (शुरुआती) / हिंदी / English. Settings → भाषा.
 * हर AI बुलावे के system prompt के आख़िर में एक ही नियम (AiClients, CodingAgent, Gemini Live) -
 * DK लाइव, DK चैट, बिना-Live आवाज़, DK कंप्यूटर, कोडिंग एजेंट: सब एक साथ.
 * ऐप की स्क्रीनें (बटन/Settings) हिंदी ही रहती हैं. कहानी लेखक (हिंदी कहानियाँ) पर लागू नहीं.
 * Android import नहीं -> JVM test.
 */
object Lang {
    const val HINGLISH = "hinglish"
    const val HINDI = "hi"
    const val ENGLISH = "en"
    const val KEY = "reply_lang"

    /** Settings में क्रम + नाम */
    val ALL = listOf(
        HINGLISH to "Hinglish — हिंदी + English मिलाकर (शुरुआती)",
        HINDI to "हिंदी — शुद्ध, आसान हिंदी",
        ENGLISH to "English — सिर्फ़ अंग्रेज़ी",
    )

    /** अभी चुनी भाषा (DKApp/Settings से भरी जाती है) - बाय डिफ़ॉल्ट English */
    @Volatile var current: String = ENGLISH

    fun norm(v: String?): String = if (v == HINDI || v == ENGLISH || v == HINGLISH) v else ENGLISH

    fun short(code: String = current) = when (norm(code)) { HINDI -> "हिंदी"; ENGLISH -> "English"; else -> "Hinglish" }

    private const val MARK = "LANGUAGE (final rule"

    fun rule(code: String = current): String {
        val how = when (norm(code)) {
            HINDI -> "reply in pure, simple Hindi written in Devanagari. Use English words only for names, app names or technical terms that have no common Hindi word."
            ENGLISH -> "reply in clear, simple English (Indian English is fine), even when the user speaks Hindi or Hinglish."
            else -> "reply in Hinglish - natural everyday Hindi mixed with common English words, the way people in India really talk " +
                "(e.g. \"ठीक है, मैंने alarm set कर दिया\"). Write Hindi words in Devanagari and English words in English letters."
        }
        return "$MARK - it overrides every other language instruction above): $how " +
            "This is only about the natural-language text meant for the user. If the instructions above require an exact format, " +
            "fixed words, codes, tags, file names, commands or JSON keys, keep those exactly as required. " +
            "If the user clearly asks for a different language for a reply, use that for that reply."
    }

    /** system prompt + भाषा का नियम (एक ही बार) */
    fun sys(system: String, code: String = current): String =
        if (system.contains(MARK)) system else system.trimEnd() + "\n\n" + rule(code)

    /** DK की तय (बिना AI) बोली जाने वाली छोटी लाइनें */
    fun pick(hindi: String, english: String, code: String = current): String = if (norm(code) == ENGLISH) english else hindi

    /** फ़ोन के सीधे कामों (QuickCommands) की तय लाइनें - English चुनी हो तो English में */
    private val EN = mapOf(
        "ऐप बंद कर दिया" to "App closed",
        "कॉल उठा कर स्पीकर पर डाल रहा हूँ" to "Answering the call on speaker",
        "कॉल उठा रहा हूँ" to "Answering the call",
        "कॉल काट दिया" to "Call ended",
        "टॉर्च चालू" to "Torch on",
        "टॉर्च बंद" to "Torch off",
        "ठीक है, अब मैं आपकी स्क्रीन नहीं देखूँगा" to "Okay, I won't look at your screen now",
        "ठीक है, अब मैं स्क्रीन देखकर काम करूँगा" to "Okay, I'll look at the screen while working",
        "फ़ोन साइलेंट पर" to "Phone on silent",
        "ये रहा मेरा चेहरा" to "Here's my face",
        "रिंगटोन तेज़ कर दी" to "Ringtone volume up",
        "रिंगटोन धीमी कर दी" to "Ringtone volume down",
        "वाईफाई पैनल खोल दिया, यहाँ से चालू-बंद करें" to "Opened the Wi-Fi panel, turn it on or off here",
        "वीडियो चला दिया" to "Playing the video",
        "सारे ऐप बंद कर दिए" to "Closed all apps",
        "स्क्रीनशॉट ले लिया" to "Screenshot taken",
        "स्पीकर चालू" to "Speaker on",
        "स्पीकर बंद" to "Speaker off",
    )

    fun say(hindi: String, code: String = current): String = if (norm(code) == ENGLISH) EN[hindi.trim()] ?: hindi else hindi
}
