package com.dk.assistant

/**
 * जवाब वाले बुलबुले में, DK की तरंग के ठीक बगल का हल्का स्थिति-संदेश.
 * सिर्फ़ असली हालत (धीमा / दोबारा कोशिश / मॉडल बदला / सीमा पूरी) - कोई झूठा संदेश नहीं.
 * BrainManager / CoderAi असली घटना पर post() करते हैं; बुलबुला label() से पढ़ता है.
 */
object DKStatus {
    enum class Kind { SLOW, RETRY, SWITCH, LIMIT, LIMIT_ALL, MODEL_CHANGE }

    /** इतनी देर में पहला शब्द न आए = "सामान्य से ज़्यादा समय" */
    const val SLOW_MS = 8_000L
    /** post किया संदेश इतनी देर तक ताज़ा */
    const val FRESH_MS = 20_000L

    fun text(k: Kind): String = when (k) {
        Kind.SLOW -> "सामान्य से ज़्यादा समय लग रहा है…"
        Kind.RETRY -> "सामान्य से ज़्यादा समय लग रहा है। थोड़ी देर में फिर कोशिश कर रहा हूँ…"
        Kind.SWITCH -> "यह मॉडल अभी जवाब नहीं दे रहा — दूसरे मॉडल से कोशिश कर रहा हूँ…"
        Kind.LIMIT -> "इस मॉडल की सीमा पूरी हो गई — दूसरे मॉडल से कोशिश कर रहा हूँ…"
        Kind.LIMIT_ALL -> "सारे मॉडल की सीमा पूरी हो गई है…"
        Kind.MODEL_CHANGE -> "मॉडल बदल रहा हूँ…"
    }

    @Volatile var kind: Kind? = null
        private set
    @Volatile var at = 0L
        private set

    fun post(k: Kind, now: Long = System.currentTimeMillis()) { kind = k; at = now }

    /** API की गलती का code -> कौन-सा संदेश (429 = सीमा पूरी, बाकी = दूसरा मॉडल) */
    fun forCode(code: Int): Kind = if (code == 429) Kind.LIMIT else Kind.SWITCH

    fun clear() { kind = null; at = 0L }

    /** इस जवाब (since के बाद) का ताज़ा संदेश, नहीं तो null */
    fun fresh(since: Long, now: Long = System.currentTimeMillis()): String? {
        val k = kind ?: return null
        if (at < since || now - at > FRESH_MS) return null
        return text(k)
    }

    /**
     * बुलबुले का संदेश: असली रुकावट > खोज/गहरी सोच का संकेत > फ़ोन का काम > धीमा > "" (सिर्फ़ तरंग).
     * @param since जवाब का इंतज़ार कब से (0 = पता नहीं -> "धीमा" नहीं)
     */
    fun label(since: Long, hint: String = "", working: Boolean = false, now: Long = System.currentTimeMillis(), slowOk: Boolean = true): String =
        fresh(since, now)
            ?: hint.trim().takeIf { it.isNotEmpty() }
            ?: (if (working) "फ़ोन पर काम कर रहा हूँ…" else null)
            ?: if (slowOk && since > 0 && now - since > SLOW_MS) text(Kind.SLOW) else ""
}

/** इनपुट बॉक्स (DKComposer) का एक ही आकार - हर स्क्रीन पर */
object ComposerShape {
    /** हल्की गोलाई वाले आयताकार कोने (dp) - गोली (पूरा गोल) नहीं */
    const val RADIUS_DP = 18f
    /** अंदर के बटन (संलग्न / माइक / भेजना) */
    const val BUTTON_DP = 38
    const val SEND_DP = 40
}

/** बुलबुले का स्मूद बदलाव (तरंग फीकी -> उसी जगह जवाब) */
object BubbleFade {
    const val OUT_MS = 220L
    const val IN_MS = 240L
}

/**
 * इनपुट बार के अंदर बोलकर लिखना (Google का पॉप-अप नहीं) - नियम, ताकि JVM test हो सकें.
 * Error code = android.speech.SpeechRecognizer.ERROR_* (वही अंक).
 */
object DictationRules {
    const val LANG = "hi-IN"
    const val EXTRA_LANG = "en-IN"

    const val ERR_NETWORK_TIMEOUT = 1
    const val ERR_NETWORK = 2
    const val ERR_AUDIO = 3
    const val ERR_SERVER = 4
    const val ERR_CLIENT = 5
    const val ERR_SPEECH_TIMEOUT = 6
    const val ERR_NO_MATCH = 7
    const val ERR_BUSY = 8
    const val ERR_PERMISSIONS = 9
    const val ERR_TOO_MANY = 10
    const val ERR_SERVER_DISCONNECTED = 11
    const val ERR_LANG_NOT_SUPPORTED = 12
    const val ERR_LANG_UNAVAILABLE = 13

    /** लगातार चुप्पी पर इतनी बार अपने-आप फिर सुनो (फिर रुको) */
    const val MAX_SILENT = 3
    /** पहचान व्यस्त हो तो इतनी बार थोड़ा रुककर फिर */
    const val MAX_BUSY = 3

    enum class Next { RESTART, RETRY_SOON, STOP_KEEP, STOP_MESSAGE }

    fun next(code: Int, silentRuns: Int, busyRuns: Int, hasText: Boolean): Next = when (code) {
        ERR_SPEECH_TIMEOUT, ERR_NO_MATCH ->
            if (silentRuns < MAX_SILENT) Next.RESTART else if (hasText) Next.STOP_KEEP else Next.STOP_MESSAGE
        ERR_BUSY, ERR_CLIENT, ERR_SERVER_DISCONNECTED -> if (busyRuns < MAX_BUSY) Next.RETRY_SOON else Next.STOP_MESSAGE
        else -> Next.STOP_MESSAGE
    }

    const val NO_SERVICE = "इस फ़ोन में बोलकर लिखने की सुविधा (Google आवाज़-पहचान) नहीं मिली। Google ऐप चालू/अपडेट करके फिर माइक दबाइए, या लिखकर भेजिए।"
    const val NO_PERMISSION = "माइक की इजाज़त नहीं है। फ़ोन की Settings → Apps → DK → Permissions में माइक चालू कीजिए।"

    fun message(code: Int): String = when (code) {
        ERR_NETWORK_TIMEOUT, ERR_NETWORK, ERR_SERVER, ERR_SERVER_DISCONNECTED -> "इंटरनेट धीमा या बंद है — आवाज़ नहीं पहचान पाया। नेट देखकर फिर माइक दबाइए।"
        ERR_AUDIO -> "माइक नहीं खुला (शायद कोई और ऐप माइक इस्तेमाल कर रहा है)। फिर माइक दबाइए।"
        ERR_PERMISSIONS -> "आवाज़-पहचान (Google ऐप) को माइक की इजाज़त नहीं है। Settings → Apps → Google → Permissions में माइक चालू कीजिए।"
        ERR_SPEECH_TIMEOUT, ERR_NO_MATCH -> "कुछ सुनाई नहीं दिया — फिर से माइक दबाकर बोलिए।"
        ERR_TOO_MANY -> "आवाज़-पहचान अभी व्यस्त है — थोड़ी देर बाद फिर माइक दबाइए।"
        ERR_LANG_NOT_SUPPORTED, ERR_LANG_UNAVAILABLE -> "इस फ़ोन में हिंदी (भारत) आवाज़-पहचान नहीं मिली। Google ऐप → Settings → Voice में हिंदी जोड़िए।"
        else -> "आवाज़ नहीं पहचान पाया — फिर से माइक दबाइए।"
    }

    /** पक्का लिखा + अभी बोला जा रहा हिस्सा (शब्द-दर-शब्द) */
    fun join(committed: String, part: String): String =
        listOf(committed.trim(), part.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    /** onRmsChanged (लगभग -2..10 dB) -> 0..1 रेखा की ऊँचाई */
    fun level(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
}
