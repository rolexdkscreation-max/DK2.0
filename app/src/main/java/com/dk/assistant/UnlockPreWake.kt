package com.dk.assistant

/**
 * 22 नवंबर 2026 (मालिक: "जैसे ही बोला जाए फोन अनलॉक करो, तुरंत स्क्रीन ऑन हो और PIN डालने का हिस्सा तुरंत आए,
 * नेट रहे न रहे"). जड़ (पहले का क्रम, सब एक के बाद एक):
 *  1. बात पूरी खत्म होने का इंतज़ार (Live: बोलना-खत्म + 200-450 ms; आम सुनना: इंटरनेट वाला STT 3 सेकंड तक),
 *  2. फिर स्क्रीन जगाना + 1.2s तक इंतज़ार + हर बार पूरे 350 ms रुकना,
 *  3. फिर आवाज़ की जाँच (3s तक) - तब जाकर PIN.
 * अब: caption में "...फोन अनलॉक" आते ही स्क्रीन जगती है और PIN वाला हिस्सा खुलता है ([DKAccessibilityService.preWakeUnlock]),
 * आम सुनने में फ़ोन का अपना caption ही काफ़ी (STT / इंटरनेट नहीं), आवाज़ की जाँच साथ-साथ, और PIN हिस्सा दिखते ही आगे.
 * यहाँ सिर्फ़ समय और "एक बार" का हिसाब - Android के बिना test होता है.
 */
object UnlockPreWake {
    /** इतनी देर तक पहले जगाई स्क्रीन "अभी खुली" मानी जाती है (UnlockWakeActivity दोबारा नहीं) */
    const val FRESH_MS = 6_000L
    /** PIN वाला हिस्सा आने का ज़्यादा से ज़्यादा इंतज़ार (Android ने खुद माँगा) - दिखते ही आगे (पहले हमेशा 350 ms) */
    const val BOUNCER_WAIT_MS = 350L
    const val BOUNCER_WAIT_SHORT_MS = 150L
    /** swipe के बाद (पहले 400 ms) - PIN हिस्सा फिर PhoneUnlocker खुद ढूँढता है */
    const val AFTER_SWIPE_MS = 250L
    const val POLL_MS = 40L

    @Volatile private var at = 0L

    /** true = अभी जगाओ (पिछली बार [FRESH_MS] से पहले) */
    @Synchronized fun begin(now: Long): Boolean {
        if (at > 0 && now - at < FRESH_MS) return false
        at = now; return true
    }

    /** अभी-अभी जगाई थी? (तब [unlock] दोबारा जगाने वाली स्क्रीन नहीं खोलता) */
    fun fresh(now: Long): Boolean = at > 0 && now - at in 0 until FRESH_MS

    fun clear() { at = 0L }

    /**
     * अधूरी बात (caption) पर पहले से जगाना ठीक है? सिर्फ़ अनलॉक, और "मत / नहीं" वाली बात नहीं ([PhoneLockVoice.match] खुद रोकता है).
     * यहाँ PIN कभी नहीं डलता - बस स्क्रीन + PIN वाला हिस्सा.
     */
    fun wanted(caption: String, names: List<String>, needName: Boolean): Boolean =
        caption.isNotBlank() && PhoneLockVoice.instant(caption, names, needName) == PhoneLockVoice.Act.UNLOCK
}
