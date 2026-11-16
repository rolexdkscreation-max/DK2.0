package com.dk.assistant

/**
 * DK लाइव की Gemini keys: मुख्य + बैकअप 2 + बैकअप 3 (कोडिंग एजेंट जैसा).
 * एक key गलत/बंद हो या उसके सारे Live मॉडल की limit खत्म हो -> चुपचाप अगली key, बातचीत चलती रहे.
 */
object LiveKeys {
    /** खाली और दोहराई keys नहीं; मुख्य key हमेशा पहले */
    fun order(keys: List<String>): List<String> = GeminiModels.distinctKeys(keys)

    /** अगली key का नंबर, या -1 (और कोई key नहीं) */
    fun next(ki: Int, size: Int): Int = if (ki + 1 < size) ki + 1 else -1

    /** मॉडल की limit हर key की अलग: पहली key का नाम पुराना ही ("live:मॉडल"), बाकी "live:2:मॉडल" */
    fun cooldownName(ki: Int, model: String): String = if (ki <= 0) "live:$model" else "live:${ki + 1}:$model"

    /** v4.2: इस key के सारे Live मॉडल की limit (आज) खत्म? तो जुड़ने की कोशिश में समय मत गँवाओ - सीधे अगली key */
    fun allBlocked(models: List<String>, blocked: (String) -> Boolean): Boolean = models.isNotEmpty() && models.all(blocked)

    /**
     * 16 नवंबर 2026 (मालिक: "DK लाइव की बैकअप keys पर भी सारी keys वाला स्विच"): Round-Robin में keys की सूची का क्रम
     * नहीं बदलता (limit / सेहत का हिसाब key के नंबर से है - वह सही रहे), सिर्फ़ **शुरुआत** वाली key बदलती है.
     * [ownCount] = DK लाइव की अपनी keys (1-3) - उधार की keys पर बारी नहीं. [turn] = यह कौन-सी नई बातचीत है.
     * स्विच बंद / एक ही key = 0 (पहले जैसा). Pure.
     */
    fun startKey(useAll: Boolean, ownCount: Int, turn: Int): Int =
        if (!useAll || ownCount < 2) 0 else Math.floorMod(turn, ownCount)

    /** [start] से घूमकर अगली key: start=1, size=3 -> 1, 2, 0, फिर -1. start=0 = पुराना [next] जैसा. Pure */
    fun next(ki: Int, size: Int, start: Int): Int {
        if (size <= 0) return -1
        val s = start.coerceIn(0, size - 1)
        val order = (s until size) + (0 until s)
        val i = order.indexOf(ki)
        return if (i < 0 || i + 1 >= order.size) -1 else order[i + 1]
    }

    /** दौड़ की जोड़ियों में बारी वाली key [start] की जोड़ियाँ सबसे आगे (उनका आपसी क्रम वैसा ही). start<0 = कुछ नहीं. Pure */
    fun <T> preferKey(queue: List<T>, start: Int, keyOf: (T) -> Int): List<T> {
        if (start < 0) return queue
        val (a, b) = queue.partition { keyOf(it) == start }
        return a + b
    }
}
