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
    fun keepText(text: String?, names: List<String>, findActive: Boolean, lock: PhoneLockVoice.Cfg = PhoneLockVoice.cfg,
                 ownerOk: Boolean = false, fg: String = ""): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (PhoneLockVoice.match(t, needName = true, c = lock) != null) return true
        // नवंबर 2026 (मालिक: "ऑफलाइन में लॉक स्क्रीन नहीं होता"): जड़ - यहाँ सिर्फ़ UNLOCK माना जाता था, Settings में लॉक का
        // switch बंद (default) हो तो "DK फोन लॉक करो" अनसुना रह जाता था. अब LOCK भी (नाम के साथ).
        if (PhoneLockVoice.instant(t, names, needName = true, c = lock) != null) return true
        if (findActive && com.dk.assistant.voice.VoiceCmdMatch.foundText(t)) return true
        // SMS "भेजूँ?" का हाँ / ना - पूछा हुआ सवाल है, नाम दोबारा नहीं बोलते
        if (OfflineCommands.confirm(t, System.currentTimeMillis()) != null) return true
        val rest = findRest(t, names)
        if (rest != null && com.dk.assistant.voice.VoiceCmdMatch.findText(rest)) return true
        // नवंबर 2026: बाकी फ़ोन वाले काम भी बिना नेट (टॉर्च, आवाज़, ऐप खोलो, अलार्म, टाइमर, कॉल, SMS, होम / बैक, स्क्रीनशॉट…) -
        // DK का नाम पहले बोला हो, या आवाज़ मालिक की पक्की हो. इंटरनेट वाले काम (YouTube, Maps, सर्च, शॉपिंग) नहीं.
        val cmd = rest ?: if (ownerOk) t else return false
        return localCmd(cmd, names, fg)
    }

    /** ये काम बिना इंटरनेट नहीं होते - ऑफलाइन में इन्हें नहीं चलाते (वरना ऐप खुलकर "नेट नहीं" दिखाता) */
    val NEEDS_NET = setOf("youtube_play", "youtube_search", "browser_search", "shop_search", "map_search", "directions", "navigate", "distance")

    /** पूरी बात फ़ोन पर ही (LocalRouter) हो सकती है और उसमें नेट नहीं चाहिए? */
    fun localCmd(text: String, names: List<String>, fg: String = ""): Boolean {
        if (text.isBlank()) return false
        if (PhoneLockVoice.instant(text, names, needName = false, c = PhoneLockVoice.cfg) != null) return true
        val q = try { LocalRouter.route(text, names, fg) } catch (_: Exception) { null } ?: return false
        val type = q.action.optString("type")
        // "गाना चलाओ" = आम तौर पर YouTube वाला गाना (नेट) - अंदाज़े से कोई भी प्लेयर नहीं चलाते; रोकना / अगला चलेगा
        if (type == "media" && q.action.optString("key") == "play") return false
        return type !in NEEDS_NET
    }

    /**
     * "किधर हो" वाली बात में नाम: "हे DK किधर हो" (strictWake) या सीधे नाम से शुरू - "डीके कहाँ हो", "DK नहीं दिख रहे हो".
     * खोया फ़ोन ढूँढते समय "हे" कोई नहीं बोलता; आगे findText का पक्का वाक्य भी चाहिए, इसलिए यूँ ही नहीं बजेगा.
     */
    /** बिना नेट वाली पहचान "डीके" को ऐसे लिखती है ([PhoneLockVoice.MISHEARD_NAMES]) */
    private val MISHEARD = listOf(listOf("डीजे"), listOf("डी", "जे"), listOf("दीजे"), listOf("डिजे"), listOf("dj"), listOf("d", "j"), listOf("टीके"), listOf("टी", "के"))

    fun findRest(text: String, names: List<String>): String? {
        com.dk.assistant.voice.WakeMatch.strictWake(text, names)?.let { return it }
        val t = text.lowercase().replace(Regex("[,.!?।॥:;\"'()\\[\\]{}-]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (t.isEmpty()) return null
        val forms = com.dk.assistant.voice.WakeMatch.DK_FORMS + MISHEARD +
            names.map { n -> n.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() } }.filter { it.isNotEmpty() }
        val f = forms.sortedByDescending { it.size }.firstOrNull { it.size <= t.size && t.subList(0, it.size) == it } ?: return null
        return t.drop(f.size).joinToString(" ")
    }
}
