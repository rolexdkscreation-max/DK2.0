package com.dk.assistant.wake

import kotlin.math.sqrt

/**
 * 16 नवंबर 2026 (मालिक: नया "हे डीके / हेलो डीके" वेक वर्ड): सारे pure नियम - Android import नहीं, JVM test ([WakeRulesTest]).
 *
 * इंजन: openWakeWord (melspectrogram -> embedding -> हर शब्द का छोटा classifier), सब ONNX, फ़ोन पर ही.
 * मॉडल APK में नहीं - पहली बार इंटरनेट से ([WakeModels]), फिर बिना इंटरनेट.
 */
object WakeRules {
    const val RATE = 16_000
    /** openWakeWord का एक कदम: 80 ms = 1280 sample */
    const val STEP = 1280
    /** melspectrogram को हर कदम पर इतने पिछले sample और चाहिए (3 x 10ms) */
    const val MEL_CTX = 480
    const val MEL_BINS = 32
    const val EMB_WIN = 76
    const val EMB_DIM = 96
    const val FEAT_FRAMES = 16
    /** VAD बंद से खुलने पर पिछला इतना ऑडियो दोबारा (शब्द का शुरू का हिस्सा न कटे). सिर्फ़ RAM में, कभी सेव नहीं */
    const val RING_STEPS = 25            // 25 x 80ms = 2.0 s
    /** एक बार जागने के बाद इतनी देर दोबारा नहीं */
    const val REFRACTORY_MS = 2_000L
    /** चुप्पी के बाद Live बंद (मालिक: डिफ़ॉल्ट 8 सेकंड, बदल सकते हैं) */
    const val SILENCE_DEFAULT_MS = 8_000
    val SILENCE_CHOICES = listOf(5_000, 8_000, 12_000, 20_000, 30_000)
    /** Live का माइक खुलने तक बाद की आवाज़ इतनी ही रखो (फिर मिटा दो) */
    const val HANDOFF_MAX_MS = 6_000L

    enum class Word(val id: String, val hi: String, val pref: String) {
        HEY("hey_dk", "हे डीके", "wake2_w_hey"),
        HELLO("hello_dk", "हेलो डीके", "wake2_w_hello");
        companion object { fun byId(id: String?) = values().firstOrNull { it.id == id } }
    }

    /** सुनने का मोड (क-घ) */
    enum class Listen(val id: String, val hi: String) {
        ALWAYS("always", "(क) हमेशा सुनो"),
        SCREEN_ON("screen", "(ख) सिर्फ़ जब स्क्रीन चालू हो"),
        CHARGING("charging", "(ग) सिर्फ़ जब फ़ोन चार्ज हो रहा हो"),
        BUTTON("button", "(घ) वेक वर्ड बंद - सिर्फ़ बटन से");
        companion object { fun byId(id: String?) = values().firstOrNull { it.id == id } ?: ALWAYS }
    }

    /** इस समय माइक खोलकर वेक वर्ड सुनें? (मोड + स्क्रीन + चार्जिंग) */
    fun allowedNow(mode: Listen, screenOn: Boolean, charging: Boolean): Boolean = when (mode) {
        Listen.ALWAYS -> true
        Listen.SCREEN_ON -> screenOn
        Listen.CHARGING -> charging
        Listen.BUTTON -> false
    }

    /**
     * संवेदनशीलता: 3 कदम. th = classifier score की सीमा, need = लगातार कितने 80 ms कदम.
     * तय किया (16 नवंबर 2026, tools/wake/bench.py): 660+660 नकली-आवाज़ (TTS) clip + LibriSpeech test-clean 5.4 घंटे (ट्रेनिंग में नहीं):
     *  कम   0.80 x4 -> पहचान हे 89% / हेलो 95%, गलत जागना ~0.2 / घंटा
     *  बीच  0.70 x3 -> पहचान हे 94% / हेलो 96%, गलत जागना ~0.9 / घंटा
     *  ज़्यादा 0.50 x3 -> पहचान हे 96% / हेलो 97%, गलत जागना ~2 / घंटा
     */
    enum class Sense(val id: String, val hi: String, val th: Float, val need: Int, val warn: String) {
        LOW("low", "कम", 0.80f, 4, "कम गलत जागना, पर धीमी / दूर की आवाज़ कभी-कभी छूटेगी"),
        MID("mid", "बीच का", 0.70f, 3, "ज़्यादातर घरों के लिए ठीक (सुझाया)"),
        HIGH("high", "ज़्यादा", 0.50f, 3, "⚠ चेतावनी: TV / बातचीत से गलत जागना बढ़ सकता है");
        companion object { fun byId(id: String?) = values().firstOrNull { it.id == id } ?: MID }
    }

    // ------------------------------------------------------------ मालिक की आवाज़ (वॉइस गार्डियन वाला RedimNet)
    /** छोटे (~1 s) वेक वर्ड पर मिलान लंबे वाक्य से कम आता है - इसलिए वॉइस गार्डियन से अलग, थोड़ी नीची डिफ़ॉल्ट सीमा */
    const val OWNER_TH_DEFAULT = 0.42f
    const val OWNER_TH_MIN = 0.20f
    const val OWNER_TH_MAX = 0.80f
    fun clampOwner(t: Float) = if (t.isNaN()) OWNER_TH_DEFAULT else t.coerceIn(OWNER_TH_MIN, OWNER_TH_MAX)
    /** slider 0..60 -> 0.20..0.80 */
    fun ownerFromSlider(p: Int) = clampOwner(OWNER_TH_MIN + p.coerceIn(0, 60) / 100f)
    fun ownerToSlider(t: Float) = ((clampOwner(t) - OWNER_TH_MIN) * 100).toInt().coerceIn(0, 60)
    fun ownerOk(score: Float?, th: Float): Boolean = score != null && score.isFinite() && score >= th
    /** इस score पर मालिक की जाँच पहले से शुरू (शब्द पूरा होने का इंतज़ार नहीं - साथ-साथ) */
    fun earlyOwner(score: Float, th: Float) = score >= th * 0.5f
    /** मालिक की आवाज़ दर्ज करने के 5 वाक्य (दो में वेक वर्ड भी - छोटे शब्द पर मिलान बेहतर) */
    val ENROLL_PHRASES = listOf(
        "हे डीके, आज मौसम कैसा रहेगा, मुझे ज़रा बताओ।",
        "हेलो डीके, मेरे फ़ोन पर व्हाट्सऐप खोलो।",
        "कल सुबह सात बजे का अलार्म लगा देना।",
        "मम्मी को फ़ोन लगाओ और स्पीकर चालू कर दो।",
        "मैं इस फ़ोन का मालिक हूँ, मेरी आवाज़ अच्छे से पहचान लो।",
    )
    /** "सिर्फ़ मालिक" मोड तभी चुना जा सकता है जब voiceprint बना हो */
    fun ownerSelectable(enrolled: Boolean, modelReady: Boolean) = enrolled && modelReady

    // ------------------------------------------------------------ ऑडियो
    fun rms(x: ShortArray, n: Int = x.size): Double {
        if (n <= 0) return 0.0
        var s = 0.0; for (i in 0 until n) { val v = x[i].toDouble(); s += v * v }
        return sqrt(s / n)
    }

    /**
     * आवाज़ की धीमी बराबरी (AGC): लॉक / स्क्रीन बंद / दूर से बोले तो आवाज़ धीमी - पहले बराबर, फिर मॉडल.
     * gain धीरे बदलता है (एक शब्द के बीच झटके नहीं), 0.5x से 8x तक. शोर (VAD बंद) पर gain नहीं बढ़ता.
     */
    class Agc(private val target: Double = 3000.0, private val maxGain: Double = 8.0) {
        var gain = 1.0; private set
        fun apply(x: ShortArray, speech: Boolean) {
            val r = rms(x)
            if (speech && r > 1.0) {
                val want = (target / r).coerceIn(0.5, maxGain)
                gain += (want - gain) * (if (want < gain) 0.5 else 0.12)    // तेज़ आवाज़ पर जल्दी नीचे, धीमी पर धीरे ऊपर
            }
            for (i in x.indices) {
                val v = (x[i] * gain).toInt()
                x[i] = v.coerceIn(-32768, 32767).toShort()
            }
        }
        fun reset() { gain = 1.0 }
    }

    /**
     * बैटरी बचत (VAD gate): कमरे का शोर-स्तर खुद सीखता है. उससे साफ़ ऊपर आवाज़ = खोलो (मॉडल चले);
     * [closeMs] चुप = बंद (मॉडल नहीं चलता, सिर्फ़ 2 s की RAM वाली अंगूठी भरती है).
     */
    class Gate(private val stepMs: Int = 80, private val closeMs: Int = 1_200, private val minRms: Double = 180.0) {
        var floor = 300.0; private set
        var open = false; private set
        private var quietMs = 0
        /** true = अभी-अभी खुला (पिछला ऑडियो भरना है) */
        fun feed(r: Double): Boolean {
            val loud = r > maxOf(floor * 2.2, minRms)
            if (!loud) floor += (r - floor) * (if (r < floor) 0.2 else 0.02)
            floor = floor.coerceIn(30.0, 6000.0)
            if (loud) {
                quietMs = 0
                if (!open) { open = true; return true }
            } else if (open) {
                quietMs += stepMs
                if (quietMs >= closeMs) open = false
            }
            return false
        }
        fun reset() { open = false; quietMs = 0 }
    }

    /**
     * जागने का फ़ैसला: score सीमा पार *लगातार [need] कदम* (हर कदम 80 ms) + पिछले जागने से [REFRACTORY_MS] बाद.
     * लगातार वाला नियम गलत जागना बहुत घटाता है (बातचीत / TV में एक-आध कदम का उछाल अनदेखा) - देरी सिर्फ़ ~80-160 ms.
     */
    class Decider {
        private var lastHit = Long.MIN_VALUE / 2     // शुरू में कोई पिछला जागना नहीं
        private var run = 0
        fun hit(score: Float, th: Float, now: Long, need: Int = 1): Boolean {
            if (!score.isFinite() || score < th) { run = 0; return false }
            run++
            if (run < need.coerceAtLeast(1)) return false
            if (now - lastHit < REFRACTORY_MS) return false
            lastHit = now; run = 0; return true
        }
        fun reset() { lastHit = Long.MIN_VALUE / 2; run = 0 }
    }

    /** भरोसेमंद शब्द-अंत: VAD में आख़िरी तेज़ आवाज़ कब थी (जागने का समय = पहचान - शब्द-अंत) */
    fun latencyMs(hitAt: Long, lastLoudAt: Long): Long = if (lastLoudAt <= 0 || lastLoudAt > hitAt) 0 else hitAt - lastLoudAt

    // ------------------------------------------------------------ लॉक स्क्रीन (मालिक: निजी काम = पहले अनलॉक, इमरजेंसी = लॉक पर भी)
    /** फ़ोन लॉक होने पर वेक वर्ड वाले सत्र में ये काम बिना अनलॉक नहीं (निजी जानकारी पढ़ना / भेजना) */
    private val PRIVATE_TOOLS = setOf("notifications", "notification_reply", "look_screen", "look_photo", "recall_conversation",
        "social_insights", "reel_stats", "connector_task", "screen_share", "social_media_task", "chat_pick", "chat_self", "chat_boss",
        "chat_reply", "send_contact")
    /** whatsapp_task में ये लॉक पर भी चलें (कॉल / लोकेशन भेजना = इमरजेंसी) */
    private val WA_EMERGENCY = setOf("voice_call", "video_call", "send_location", "send_live_location")

    fun lockedPrivate(name: String, waAction: String?): Boolean {
        val n = name.trim().lowercase()
        if (n == "whatsapp_task") return waAction?.trim()?.lowercase() !in WA_EMERGENCY
        return n in PRIVATE_TOOLS
    }

    const val LOCKED_PRIVATE_NOTE = "NOT DONE: the phone is LOCKED and this was started by the wake word. This task reads or sends personal data " +
        "(messages / notifications / photos / screen), so DK must not do it on the lock screen. Tell him in one short Hindi line: " +
        "\"यह निजी काम है - पहले फ़ोन अनलॉक कीजिए, फिर दोबारा बोलिए।\" Emergency tasks (call, location, torch, alarm) are allowed while locked."

    // ------------------------------------------------------------ हिंदी लेख
    const val LIMITS = "ईमानदार सीमाएँ:\n" +
        "• कोई भी वेक वर्ड या आवाज़-पहचान 100% सही नहीं होती - कभी-कभी गलत जागेगा, कभी आपकी बात छूटेगी।\n" +
        "• किसी की आवाज़ की रिकॉर्डिंग (या नकली आवाज़) \"सिर्फ़ मालिक\" वाली जाँच को धोखा दे सकती है - " +
        "पैसे, पासवर्ड या बैंक जैसे काम के लिए सिर्फ़ इस पर भरोसा मत कीजिए।\n" +
        "• वेक वर्ड से पहले का कोई ऑडियो न सेव होता है, न इंटरनेट पर जाता है (सिर्फ़ 2 सेकंड RAM में, फिर अपने-आप मिटता है)।\n" +
        "• माइक चालू रहने पर Android का हरा माइक-निशान और DK की सूचना दिखेगी - इन्हें छिपाया नहीं जाता।"

    const val A14_NOTE = "Android 14 और उसके बाद: Android पीछे से (ऐप बंद हो तब) माइक वाली सेवा शुरू नहीं करने देता। " +
        "इसलिए यह सेवा ऐप खुला होने पर, या DK को डिफ़ॉल्ट असिस्टेंट रखने पर चालू होती है, और फ़ोन चालू होने (boot) पर फिर से कोशिश करती है। " +
        "अगर Android ने रोक दिया, तो DK एक बार खोल दीजिए - सुनना फिर शुरू हो जाएगा।"

    /** Android के अपने hotword (AlwaysOnHotwordDetector) की हालत -> छोटा id. ENROLLED (2) ही काम का; बाकी = हमारा मॉडल */
    fun hotwordState(status: Int): String = when (status) {
        2 -> "enrolled"; 1 -> "not_enrolled"; -1 -> "unsupported"; -2 -> "unavailable"; 3 -> "error"; else -> "unknown"
    }
    fun hotwordText(state: String): String = when (state) {
        "enrolled" -> "Android का अपना hotword: तैयार"
        "" -> "Android का अपना hotword: अभी जाँचा नहीं (DK को डिफ़ॉल्ट असिस्टेंट बनाने पर जाँच होगी) - DK का अपना मॉडल चलेगा"
        else -> "Android का अपना hotword: इस फ़ोन पर आम ऐप के लिए उपलब्ध नहीं - DK का अपना मॉडल (फ़ोन पर ही) चल रहा है"
    }

    /** DK की सूचना (notification) - छिपाई नहीं जाती */
    const val NOTIF_TEXT = "\"हे डीके\" / \"हेलो डीके\" सुन रहा हूँ - फ़ोन पर ही, कुछ बाहर नहीं जाता"
    const val FGS_BLOCKED = "⚠ Android ने पीछे से माइक वाली सेवा रोक दी (Android 14+ का नियम)। अभी वेक वर्ड नहीं सुन रहा - DK एक बार खोल दीजिए।"

    fun latencyText(ms: Long, ownerMs: Long?): String =
        "जागने में लगा समय: $ms ms (शब्द खत्म होने से पहचान तक)" + (ownerMs?.let { "\nमालिक की आवाज़ जाँच: $it ms (साथ-साथ चली)" } ?: "") +
            "\n" + if (ms <= 1_000) "✓ 1 सेकंड से कम" else "⚠ 1 सेकंड से ज़्यादा - फ़ोन पर बहुत काम चल रहा है या बैटरी बचत चालू है"

}
