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
    /** DK AI ब्रीफ़ Part 03: यूज़र जिस भाषा में बोले, हर बार उसी में जवाब (LanguageDetector) */
    const val AUTO = "auto"
    const val KEY = "reply_lang"

    /** Settings में क्रम + नाम */
    val ALL = listOf(
        HINGLISH to "Hinglish — हिंदी + English मिलाकर (शुरुआती)",
        HINDI to "हिंदी — शुद्ध, आसान हिंदी",
        ENGLISH to "English — सिर्फ़ अंग्रेज़ी",
    )

    /** Settings में दिखने वाली सारी पसंद: ऊपर की तीन + Auto (आख़िर में, ताकि पुराने क्रम/ID न बदलें) */
    val CHOICES = ALL + (AUTO to "Auto — आप जिस भाषा में बोलें, उसी में जवाब")

    /** अभी चुनी भाषा (DKApp/Settings से भरी जाती है) */
    @Volatile var current: String = HINGLISH

    /** Auto में: यूज़र की पिछली बात किस भाषा में थी (शुरू में Hinglish) */
    @Volatile var detected: String = HINGLISH

    fun norm(v: String?): String = if (v == HINDI || v == ENGLISH || v == HINGLISH || v == AUTO) v else HINGLISH

    /** Auto हो तो यूज़र की पहचानी भाषा, नहीं तो चुनी हुई */
    fun effective(code: String = current): String = norm(code).let { if (it == AUTO) norm(detected).takeIf { d -> d != AUTO } ?: HINGLISH else it }

    /** यूज़र की नई बात आई - Auto के लिए उसकी भाषा याद रखो (खाली/पता नहीं = पिछली ही) */
    fun noteUser(text: String?) {
        LanguageDetector.toLangCode(LanguageDetector.detect(text))?.let { detected = it }
    }

    fun short(code: String = current) = when (norm(code)) { HINDI -> "हिंदी"; ENGLISH -> "English"; AUTO -> "Auto"; else -> "Hinglish" }

    private const val MARK = "LANGUAGE (final rule"

    fun rule(code: String = current): String {
        val how = when (norm(code)) {
            AUTO -> "reply in the SAME language the user used in their latest message, turn by turn: Devanagari Hindi -> pure, simple Hindi " +
                "in Devanagari; Hindi written in English letters (Hinglish) -> natural Hinglish (Hindi words in Devanagari, English words " +
                "in English letters); English -> clear, simple English. If the user switches language, switch with them. " +
                "Never translate or repeat back what the user said."
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
    fun pick(hindi: String, english: String, code: String = current): String = if (effective(code) == ENGLISH) english else hindi

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

    fun say(hindi: String, code: String = current): String = if (effective(code) == ENGLISH) EN[hindi.trim()] ?: hindi else hindi
}
