package com.dk.assistant

/**
 * Mic error 12/13 का पक्का इलाज (JVM test होने वाले नियम).
 *
 * जड़: 12 = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE.
 * हल्के मोड (धीमा नेट) में VoiceListener हर बार "hi-IN + EXTRA_PREFER_OFFLINE" माँगता था; फ़ोन में
 * हिंदी का offline पैक न हो तो पहचान तुरंत 13 देती है (फ़ोन खुद online पर नहीं जाता)। पुराना hearLight()
 * नई भाषा वाला Intent बनाता था पर कभी भेजता नहीं था - वही नाकाम माँग 5 बार, हर बार 3s चुप्पी।
 *
 * अब: हर कोशिश की (भाषा, offline) जोड़ी - 12/13 मिले तो तुरंत (बिना रुके) अगली जोड़ी,
 * नाकाम जोड़ी याद (BAD_FOR_MS तक दोबारा नहीं), जो चली वही आगे पहले।
 */
object MicLang {
    const val ERR_LANG_NOT_SUPPORTED = 12
    const val ERR_LANG_UNAVAILABLE = 13
    const val HI = "hi-IN"
    const val EN = "en-IN"
    /** नाकाम जोड़ी इतनी देर नहीं आज़माते (पैक बाद में डाउनलोड हो जाए तो फिर काम करे) */
    const val BAD_FOR_MS = 10 * 60_000L

    data class Plan(val lang: String, val offline: Boolean)

    fun isLangError(code: Int) = code == ERR_LANG_NOT_SUPPORTED || code == ERR_LANG_UNAVAILABLE

    /** कोशिशों का क्रम: (hi offline, अगर माँगा) -> hi online -> en-IN online -> फ़ोन की भाषा online */
    fun plans(preferOffline: Boolean, deviceLang: String): List<Plan> {
        val l = mutableListOf<Plan>()
        if (preferOffline) l += Plan(HI, true)
        l += Plan(HI, false)
        l += Plan(EN, false)
        val d = deviceLang.trim()
        if (d.isNotEmpty() && d != "und" && d != HI && d != EN) l += Plan(d, false)
        return l
    }

    /** Hinglish: मुख्य भाषा के साथ दूसरी भी */
    fun extraLang(lang: String): String? = when (lang) { HI -> EN; EN -> HI; else -> null }

    /** कौन सी जोड़ी कब नाकाम हुई (एक VoiceListener के लिए) */
    class Memory {
        private val badAt = HashMap<Plan, Long>()
        var lastGood: Plan? = null
            private set

        fun bad(p: Plan, now: Long) { badAt[p] = now; if (lastGood == p) lastGood = null }
        fun good(p: Plan) { badAt.remove(p); lastGood = p }
        fun isBad(p: Plan, now: Long): Boolean = badAt[p]?.let { now - it < BAD_FOR_MS } ?: false

        /** इस बार आज़माने का क्रम: पिछली चली पहले, फिर बाकी, नाकाम (ताज़ा) छोड़कर. सब नाकाम = पूरी सूची फिर से */
        fun order(all: List<Plan>, now: Long): List<Plan> {
            val ok = all.filterNot { isBad(it, now) }
            val base = ok.ifEmpty { all }
            val g = lastGood
            return if (g != null && g in base) listOf(g) + (base - g) else base
        }
    }
}
