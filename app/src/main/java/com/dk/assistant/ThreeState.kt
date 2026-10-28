package com.dk.assistant

/**
 * v3.88: स्क्रीन पर सिर्फ़ तीन स्थितियाँ - Listening / Thinking / Speaking. चौथी कोई नहीं.
 *  - यूज़र बोल रहा (या Live का माइक खुला) = Listening
 *  - बात खत्म, जवाब बन रहा / फ़ोन पर काम चल रहा (WORKING भी) = Thinking
 *  - आवाज़ बज रही = Speaking
 *  null = कुछ नहीं चल रहा (होम पर "Tap the orb and speak" वाला इशारा)
 */
object ThreeState {
    const val LISTENING = "Listening"
    const val THINKING = "Thinking"
    const val SPEAKING = "Speaking"

    /** v3.90: [work] = सच में खोज/सोच/काम चल रहा है. Live में बिना काम के THINKING (बस जवाब का पल) = "Listening" ही */
    fun label(mode: Mode, live: Boolean, muted: Boolean, work: Boolean = true): String? = when {
        mode == Mode.SPEAKING -> SPEAKING
        mode == Mode.WORKING -> THINKING
        mode == Mode.THINKING && (work || !live) -> THINKING
        muted -> null
        mode == Mode.LISTENING -> LISTENING
        live -> LISTENING
        else -> null
    }

    /** ORB/चमक के लिए: WORKING = Thinking वाला रूप (तीन ही रूप) */
    fun look(mode: Mode, live: Boolean = false, work: Boolean = true): Mode = when {
        mode == Mode.WORKING -> Mode.THINKING
        mode == Mode.THINKING && live && !work -> Mode.LISTENING
        else -> mode
    }
}
