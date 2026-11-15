package com.dk.assistant

/**
 * 15 नवंबर 2026 (मालिक: "चैट पूरी तरह अच्छे से चेक करो, जो गड़बड़ी है सब ठीक करो"): चैट के छोटे नियम एक जगह (JVM test).
 * Android import नहीं.
 */
object ChatFlow {
    /** चैट स्क्रीन: DK अभी सच में काम कर रहा है (AI / खोज / फ़ोन का काम) तो इतनी देर रुको; कुछ नहीं चल रहा तो कम */
    const val WAIT_IDLE_MS = 30_000L
    const val WAIT_WORKING_MS = 90_000L
    const val WAIT_SLOW_MS = 120_000L

    /**
     * पहले शब्द का इंतज़ार कब तक. पहले हर बार 30 सेकंड = खोज / सोच वाले सवाल पर काम चलते-चलते "नेटवर्क धीमा" लिखकर रुक जाता था.
     * working = DK का काम (AgentRunner / अपना चैट job) अभी चल रहा है; slow = खोज / गहरी सोच / फ़ोन का काम.
     */
    fun waitLimit(working: Boolean, slow: Boolean): Long = when {
        !working -> WAIT_IDLE_MS
        slow -> WAIT_SLOW_MS
        else -> WAIT_WORKING_MS
    }

    /**
     * चैट में कई key की दौड़: 1-3 सेकंड बाद "साथ में अगली" सिर्फ़ तब जब वह दूसरी कंपनी हो.
     * उसी कंपनी (उसी key) की दूसरी कोशिश सिर्फ़ पहली के फ़ेल होने पर - पहले एक ही Gemini key पर दो माँग साथ जाती थीं = फ्री limit दोगुनी तेज़ खत्म.
     */
    fun raceOk(byTimer: Boolean, nextId: String, runningIds: Collection<String>): Boolean = !byTimer || nextId !in runningIds

    /** लंबा जवाब token की सीमा पर कटा (Gemini finishReason) = आगे का हिस्सा अपने-आप; ज़्यादा से ज़्यादा [MAX_CONTINUE] बार */
    const val MAX_CONTINUE = 2
    fun cutOff(finishReason: String?): Boolean = finishReason == "MAX_TOKENS"

    const val CONTINUE_PROMPT = "Your previous reply was cut off by the length limit. Continue EXACTLY from where it stopped - " +
        "do not repeat anything already written, do not greet, do not add any note about continuing. Same language and style."

    /** धीमा नेट / खोज में देर (पहला शब्द आने से पहले) = एक बार बिना Google खोज के दोबारा (जल्दी जवाब) */
    fun slowNet(e: Throwable): Boolean = e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException ||
        (e is java.io.IOException && (e.message.orEmpty().contains("timeout", true) || e.message.orEmpty().contains("reset", true)))

    /** गलती के संदेश में अंग्रेज़ी तकनीकी लाइन नहीं (पहले "(HTTP 404 models/... not found)" जैसा जुड़ता था) */
    fun plain(msg: String): String = msg.replace(Regex("\\s*\\((HTTP|http|[A-Za-z]+Exception)[^)]*\\)"), "").trim()
}
