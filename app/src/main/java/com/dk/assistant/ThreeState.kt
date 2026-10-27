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

    fun label(mode: Mode, live: Boolean, muted: Boolean): String? = when {
        mode == Mode.SPEAKING -> SPEAKING
        mode == Mode.THINKING || mode == Mode.WORKING -> THINKING
        muted -> null
        mode == Mode.LISTENING -> LISTENING
        live -> LISTENING
        else -> null
    }

    /** ORB/चमक के लिए: WORKING = Thinking वाला रूप (तीन ही रूप) */
    fun look(mode: Mode): Mode = if (mode == Mode.WORKING) Mode.THINKING else mode
}
