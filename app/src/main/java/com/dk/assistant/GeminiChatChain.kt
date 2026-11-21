package com.dk.assistant

/**
 * 21 नवंबर 2026 (मालिक: "DK चैट और DK लाइव की text बातचीत में free limit ज़्यादा से ज़्यादा, 429 पर कभी न रुके"):
 * Gemini text चैट का पक्का fallback क्रम (REST v1beta `models/{model}:streamGenerateContent` / `:generateContent`).
 *
 *  1. gemini-3.5-flash-lite  - मुख्य: अच्छा संतुलन + ज़्यादा रोज़ की request (RPD)
 *  2. gemini-3.1-flash-lite  - बहुत तेज़ + ज़्यादा RPD
 *  3. gemma-4-31b-it         - open-weight बैकअप, सबसे ज़्यादा RPD (पर TPM कम ~16K - इसलिए छोटी history ज़रूरी)
 *
 * 3.8 Flash / 3.6 Flash चैट के default क्रम में नहीं (उनकी free RPD बहुत कम). पुराने 1.x / 2.x / 1.5 / 2.0 / 2.5 भी नहीं.
 *
 * कई keys (अलग-अलग keys वाला मोड: DK चैट Key 1/2/3, DK लाइव Key 1/2/3) = "key-पहले" क्रम:
 *   Key 1 पर 3.5 → 3.1 → Gemma, तीनों 429/503 → Key 2 पर वही तीनों → Key 3 पर वही तीनों.
 * एक-key (ऑल-राउंडर) मोड = वही एक key, उसी पर तीनों मॉडल.
 *
 * Pure (Android import नहीं) - [GeminiChatChainTest] से JVM पर जाँच.
 */
object GeminiChatChain {
    const val PRIMARY = "gemini-3.5-flash-lite"
    const val SECONDARY = "gemini-3.1-flash-lite"
    const val TERTIARY = "gemma-4-31b-it"
    val MODELS = listOf(PRIMARY, SECONDARY, TERTIARY)

    /** चैट के default में कभी नहीं (free RPD बहुत कम) - सिर्फ़ मालिक खुद चुने तब */
    val LOW_RPD = setOf("gemini-3.8-flash", "gemini-3.6-flash")

    /** हर request में सिर्फ़ आख़िरी 10 बातें (5 user + 5 जवाब) - TPM की limit कभी न टूटे */
    const val HISTORY_MAX = 10
    /** जवाब छोटा, तेज़, कम tokens */
    const val MAX_OUTPUT = 1024

    fun bare(m: String) = m.trim().removePrefix("models/")
    fun isGemma(m: String) = bare(m).startsWith("gemma-")

    /**
     * मॉडल-क्रम: मालिक ने Settings में खुद कोई नया (पुराना 1.x/2.x नहीं) मॉडल चुना हो = वह सबसे पहले, फिर पक्का क्रम.
     * बाकी हर हाल में सिर्फ़ [MODELS].
     */
    fun models(chosen: String = "", keepFirst: Boolean = false): List<String> {
        val c = bare(chosen)
        val first = if (keepFirst && c.isNotBlank() && !GeminiModels.isLegacy(c)) listOf(c) else emptyList()
        return (first + MODELS).distinct()
    }

    /**
     * Sliding window: आख़िरी [HISTORY_MAX] बातें. खाली बातें हटें, और पहली बात "model" की न हो
     * (Gemini / Gemma को बातचीत user से शुरू चाहिए).
     */
    fun window(history: List<Turn>, max: Int = HISTORY_MAX): List<Turn> {
        val w = history.filter { it.text.isNotBlank() }.takeLast(max).toMutableList()
        while (w.isNotEmpty() && w.first().role != "user") w.removeAt(0)
        return w
    }

    /** इस गलती पर अगले मॉडल पर वही request: 429 (RESOURCE_EXHAUSTED) या 503 (भीड़ / UNAVAILABLE) */
    fun shouldFallback(code: Int, msg: String = ""): Boolean =
        code == 429 || code == 503 || msg.contains("RESOURCE_EXHAUSTED", true) || msg.contains("UNAVAILABLE") ||
            msg.contains("overloaded", true)

    /** key ही गलत / बंद (हर मॉडल पर यही आएगा) = इस key के बाकी मॉडल छोड़ो, सीधे अगली key */
    fun keyBroken(code: Int, msg: String): Boolean =
        code == 401 || ((code == 400 || code == 403) && (msg.contains("API key", true) || msg.contains("API_KEY", true)))

    /** key-पहले क्रम: हर key पर पूरा मॉडल-क्रम, फिर अगली key. खाली / दोहराई keys नहीं */
    fun plan(keys: List<String>, models: List<String> = MODELS): List<Pair<String, String>> =
        GeminiModels.distinctKeys(keys).flatMap { k -> models.map { m -> k to m } }

    /** log में छोटा नाम: "gemini-3.5-flash-lite" -> "3.5-flash-lite" */
    fun short(m: String) = bare(m).removePrefix("gemini-")

    fun servedLog(m: String, keyNo: Int = 1, keys: Int = 1) =
        "[AI Service] Served by: ${bare(m)}" + if (keys > 1) " (key $keyNo/$keys)" else ""

    fun switchLog(code: Int, from: String, to: String) =
        "[AI Service] ${if (code > 0) code.toString() else "error"} hit on ${short(from)}, switching to ${short(to)}..."

    fun keySwitchLog(fromKey: Int, toKey: Int, keys: Int, why: String) =
        "[AI Service] key $fromKey/$keys: $why, switching to key $toKey/$keys..."

    const val ALL_FAILED_LOG = "[AI Service] सारे मॉडल / keys व्यस्त (429/503) - Server busy"

    /** सिर्फ़ तब जब सारे मॉडल (और सारी keys) fail - यूज़र की भाषा में */
    const val BUSY_HI = "सर्वर अभी व्यस्त है, कृपया एक मिनट बाद फिर कोशिश करें।"
    const val BUSY_EN = "Server busy, please try again in a minute."

    /** सारी गलतियाँ भीड़ / limit वाली (429 / 503)? - तब ऊपर वाला दोस्ताना संदेश */
    fun allBusy(codes: List<Int>): Boolean = codes.isNotEmpty() && codes.all { it == 429 || it == 503 }

    /**
     * Gemma 4 (Gemini API पर): system instruction चलता है; सोच (thinking) default बंद; googleSearch tool भरोसे का नहीं
     * (pricing page पर Gemma के लिए grounding "उपलब्ध नहीं") - इसलिए Gemma पर tool नहीं, thinkingConfig नहीं.
     */
    fun searchToolOk(m: String) = !isGemma(m)
}
