package com.dk.assistant

/**
 * v3.74: DK की आवाज़ का नियम (pure - JUnit से जाँचा जाता है)
 *  - फ़ोन की अपनी आवाज़ (Android TextToSpeech) कभी नहीं बोलेगी - न ऑनलाइन, न ऑफ़लाइन।
 *  - सिर्फ़ Gemini TTS या Gemini Live की अपनी आवाज़ बोलेगी।
 *  - इंटरनेट नहीं / key नहीं / Gemini की लिमिट खत्म -> DK चुप, जवाब सिर्फ़ लिखकर।
 */
object VoicePolicy {
    /** फ़ोन TTS चलाने की इजाज़त - हमेशा false */
    const val PHONE_TTS_ALLOWED = false

    enum class Why { OK, OFFLINE, NO_KEY, LIMIT }

    /** अभी Gemini आवाज़ बोल सकती है? नहीं तो क्यों नहीं */
    fun gemini(hasKey: Boolean, online: Boolean, now: Long, cooldownUntil: Long): Why = when {
        !online -> Why.OFFLINE
        !hasKey -> Why.NO_KEY
        now <= cooldownUntil -> Why.LIMIT
        else -> Why.OK
    }

    /** user को छोटा संदेश - क्या हुआ, वजह, हल */
    fun message(w: Why): String = when (w) {
        Why.OK -> ""
        Why.OFFLINE -> "इंटरनेट नहीं है — DK अभी नहीं बोलेगा, जवाब सिर्फ़ लिखकर। हल: इंटरनेट चालू करें।"
        Why.NO_KEY -> "Gemini की key नहीं है — DK नहीं बोलेगा। हल: सेटिंग्स में Gemini की नई key डालें।"
        Why.LIMIT -> "Gemini आवाज़ की लिमिट खत्म — थोड़ी देर DK नहीं बोलेगा। हल: थोड़ी देर रुकें या नई key डालें।"
    }

    /** Gemini wav का इंतज़ार (ms) - फ़ोन TTS पर नहीं जाना, इसलिए ज़्यादा देर रुको */
    fun waitMs(quick: Boolean): Long = if (quick) 6_000L else 20_000L
}
