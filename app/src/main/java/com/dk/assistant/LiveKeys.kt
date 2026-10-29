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
}
