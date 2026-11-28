package com.dk.assistant

import java.util.concurrent.ConcurrentHashMap

/**
 * 28 नवंबर 2026 (मालिक: "जो मॉडल ज़्यादा फास्ट काम कर रहा है उसी से काम ले, Gemini न चले तो अगली API, बग क्यों नहीं ढूँढ पाता").
 *
 * जड़ (CoderAi.chatChain की जाँच):
 *  1. Groq / OpenRouter / Z.ai / NVIDIA की limit (429), सर्वर गलती (5xx), नेटवर्क timeout या गलत key (401/403) का कोई याद नहीं
 *     रहता था - हर कदम पर (एक काम में 30-45 कदम) वही टूटी API पहले आज़माई जाती, फिर 2s/4s/8s रुककर अगली.
 *  2. 429 के बाद *दूसरी कंपनी* की API से पहले भी 2-8 सेकंड रुकना (उसकी limit तो अलग है) = बेवजह देर.
 *  3. Groq की free limit सिर्फ़ 8K tokens/मिनट (console.groq.com/docs/rate-limits) - कोडिंग की request (फ़ाइलें + नक्शा)
 *     413 देती, फिर request ~15-20 हज़ार अक्षर तक काटकर भेजी जाती = एजेंट को फ़ाइलें दिखती ही नहीं, बग नहीं ढूँढ पाता.
 *
 * इलाज (कोई API कभी पूरी तरह बंद नहीं - सिर्फ़ क्रम बदलता है, आख़िर में सब आज़माई जाती हैं):
 *  - [fail]: गलती के हिसाब से API कुछ देर "आराम" पर ([coolMs]) -> [order] में वह पीछे, चलती API आगे.
 *  - [ok]: हर सफल जवाब का समय (औसत) - चलती API में जो सबसे तेज़ उसी से पहले काम (मुख्य/चुनी API फिर भी सबसे आगे).
 *  - [tooSmall]: जिसकी request-सीमा कोडिंग के लिए बहुत छोटी, उस पर फ़ाइलें काटकर नहीं भेजते (बाकी API न हों तभी).
 * Pure Kotlin (समय बाहर से) -> JUnit.
 */
object CoderHealth {
    /** इससे छोटी request-सीमा (अक्षर) पर कोडिंग नहीं - फ़ाइलें कटकर एजेंट अंधा हो जाता है */
    const val MIN_USEFUL_CHARS = 40_000
    /** किसी API की तेज़ी का औसत (EMA) - नया जवाब इतना वज़न */
    private const val EMA_NEW = 0.4

    private val coolUntil = ConcurrentHashMap<String, Long>()
    private val speedMs = ConcurrentHashMap<String, Double>()

    /** गलती के हिसाब से कितनी देर पीछे रखें (ms). 0 = नहीं */
    fun coolMs(code: Int, retryAfterSec: Int): Long = when (code) {
        429 -> (if (retryAfterSec > 0) retryAfterSec.coerceIn(20, 300) else 60) * 1000L
        401, 403 -> 10 * 60_000L                  // key गलत / इजाज़त नहीं - हर कदम पर दोबारा बेकार
        413 -> 30 * 60_000L                       // request-सीमा छोटी (Groq free 8K TPM)
        500, 502, 503, 504 -> 30_000L             // सर्वर पर भीड़
        0, -1, 408 -> 30_000L                     // नेटवर्क / timeout
        else -> 0L
    }

    fun fail(label: String, code: Int, retryAfterSec: Int = 0, now: Long = System.currentTimeMillis()) {
        val ms = coolMs(code, retryAfterSec)
        if (ms > 0) coolUntil[label] = now + ms
    }

    fun ok(label: String, ms: Long) {
        coolUntil.remove(label)
        val old = speedMs[label]
        speedMs[label] = if (old == null) ms.toDouble() else old * (1 - EMA_NEW) + ms * EMA_NEW
    }

    fun cooling(label: String, now: Long = System.currentTimeMillis()): Boolean = (coolUntil[label] ?: 0L) > now
    fun speed(label: String): Double? = speedMs[label]

    /**
     * chain का नया क्रम (labels पहले से मालिक के क्रम में; पहला = मुख्य/चुनी API):
     *  1. मुख्य API (आराम पर नहीं हो तो) सबसे आगे - चुना मॉडल हमेशा पहले.
     *  2. बाकी चलती API: जिनकी तेज़ी नापी जा चुकी (हाल में सच में चलीं) तेज़ पहले; बाकी पुराने क्रम में.
     *  3. आराम वाली API सबसे पीछे (जिसका आराम पहले खत्म, वह पहले) - हटती नहीं, सब fail हों तो वे भी.
     */
    fun order(labels: List<String>, cooling: (String) -> Boolean, speed: (String) -> Double?): List<String> {
        if (labels.size <= 1) return labels
        val (live, cool) = labels.partition { !cooling(it) }
        val head = live.firstOrNull()?.takeIf { it == labels.first() }
        val rest = live.filter { it != head }
        val known = rest.filter { speed(it) != null }.sortedBy { speed(it) }
        val unknown = rest.filter { speed(it) == null }
        return listOfNotNull(head) + known + unknown + cool
    }

    fun order(labels: List<String>, now: Long = System.currentTimeMillis()): List<String> =
        order(labels, { cooling(it, now) }, { speed(it) })

    /**
     * request इस API की सीमा में नहीं आती (room = भेजने लायक अक्षर) और सीमा कोडिंग के लिए बहुत छोटी = इस API को छोड़ो
     * (फ़ाइलें काटकर अंधा जवाब नहीं). lastResort = इसके बाद कोई API नहीं बची - तब छोटी request ही सही.
     */
    fun tooSmall(room: Int, need: Int, lastResort: Boolean): Boolean = !lastResort && need > room && room < MIN_USEFUL_CHARS

    /** 429 के बाद अगली कोशिश से पहले रुकना तभी जब अगली API उसी कंपनी की हो (दूसरी कंपनी की limit अलग है) */
    fun waitBeforeNext(prevProvider: String?, nextProvider: String): Boolean = prevProvider != null && prevProvider == nextProvider

    /** tests के लिए */
    internal fun reset() { coolUntil.clear(); speedMs.clear() }
}
