package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "ऑफलाइन मोड में बार-बार माइक ऑन-ऑफ का साउंड"):
 * Live मोड + इंटरनेट नहीं = बैकअप मोड पहले हर बात के लिए फ़ोन का SpeechRecognizer खोलता था
 * (online-पहले, नेट नहीं तो तुरंत गलती) -> हर चालू/बंद पर Android की बीप, लगातार.
 * अब: बिना नेट DK का अपना माइक (AudioRecord - कोई बीप नहीं) लगातार सुनता है और
 * सिर्फ़ सिखाई आवाज़-कमांड (लॉक / अनलॉक - मालिक की आवाज़, "किधर हो", "मिल गया") पर काम करता है;
 * बाकी हर बात चुपचाप अनसुनी (न text, न जवाब, न आवाज़).
 */
object OfflineListen {
    /** नेट वापस आया / माइक छोड़ना है - कितनी देर में जाँचें */
    const val WATCH_MS = 1500L

    /** बैकअप मोड (Live नहीं जुड़ा) में सुनना: नेट नहीं = सिर्फ़ आवाज़-कमांड, बिना बीप वाला अपना माइक */
    fun cmdsOnly(backup: Boolean, liveOn: Boolean, online: Boolean): Boolean = backup && liveOn && !online

    /**
     * चलता सुनना कब छोड़ें: नेट लौटा (Google/Live वाला रास्ता), Live बंद, म्यूट या कॉल.
     * आप बोल रहे हों (capturing) तो बीच में नहीं काटते - बात पूरी होने दो.
     */
    fun stopNow(online: Boolean, liveOn: Boolean, micMuted: Boolean, inCall: Boolean, capturing: Boolean): Boolean =
        !capturing && (online || !liveOn || micMuted || inCall)

    /**
     * नवंबर 2026 (ठीक किया): बिना नेट फ़ोन की on-device पहचान से मिली लिखी बात - सिर्फ़ ऑफलाइन कमांड हो तो आगे
     * (loop वही पुराना लॉक / अनलॉक / "किधर हो" / "मिल गया" वाला हिस्सा चलाए), बाकी सब अनसुना.
     * पहले (#212) सिर्फ़ सिखाए आवाज़-नमूने देखे जाते थे - नमूने न हों तो कुछ भी नहीं सुनता था.
     */
    fun keepText(text: String?, names: List<String>, findActive: Boolean, lock: PhoneLockVoice.Cfg = PhoneLockVoice.cfg): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (PhoneLockVoice.match(t, needName = true, c = lock) != null) return true
        if (PhoneLockVoice.instant(t, names, needName = true, c = lock) == PhoneLockVoice.Act.UNLOCK) return true
        if (findActive && com.dk.assistant.voice.VoiceCmdMatch.foundText(t)) return true
        val rest = findRest(t, names) ?: return false
        return com.dk.assistant.voice.VoiceCmdMatch.findText(rest)
    }

    /**
     * "किधर हो" वाली बात में नाम: "हे DK किधर हो" (strictWake) या सीधे नाम से शुरू - "डीके कहाँ हो", "DK नहीं दिख रहे हो".
     * खोया फ़ोन ढूँढते समय "हे" कोई नहीं बोलता; आगे findText का पक्का वाक्य भी चाहिए, इसलिए यूँ ही नहीं बजेगा.
     */
    fun findRest(text: String, names: List<String>): String? {
        com.dk.assistant.voice.WakeMatch.strictWake(text, names)?.let { return it }
        val t = text.lowercase().replace(Regex("[,.!?।॥:;\"'()\\[\\]{}-]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (t.isEmpty()) return null
        val forms = com.dk.assistant.voice.WakeMatch.DK_FORMS +
            names.map { n -> n.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() } }.filter { it.isNotEmpty() }
        val f = forms.sortedByDescending { it.size }.firstOrNull { it.size <= t.size && t.subList(0, it.size) == it } ?: return null
        return t.drop(f.size).joinToString(" ")
    }
}
