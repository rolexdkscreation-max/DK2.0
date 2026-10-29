package com.dk.assistant

/**
 * AI दिमाग के 4 अलग हिस्से - हर एक की अपनी, निजी API keys (Android पर निर्भर नहीं - unit test हो सकते हैं):
 *  - DK लाइव   (LIVE)  : सिर्फ़ Google Gemini - Gemini Live, Gemini आवाज़ में बोलना, सुनना. सुनने (आवाज़ → text) के लिए Groq भी.
 *  - DK चैट    (CHAT)  : Gemini / Groq / OpenRouter / Custom - चैट, आम जवाब, बिना-Live आवाज़ मोड का जवाब, search, गहरी सोच, कहानी
 *  - कोडिंग एजेंट (CODER): Gemini / Groq / OpenRouter / Custom
 *  - DK कंप्यूटर (PC)  : Gemini / Groq / OpenRouter / Custom
 *
 * नियम: हर हिस्सा सिर्फ़ अपनी चुनी API को कॉल करता है - किसी दूसरे हिस्से की key या दूसरी कंपनी पर नहीं जाता.
 * Limit (429) / मॉडल बंद होने पर सिर्फ़ उसी key के दूसरे (फ्री) मॉडल पर अपने-आप स्विच.
 */
object ApiScopes {
    const val LIVE = "live"
    const val CHAT = "chat"
    const val CODER = "coder"
    const val PC = "pc"
    val ALL = listOf(LIVE, CHAT, CODER, PC)

    /** सारी कंपनियाँ (Providers.all के id) */
    val PROVIDERS = listOf("gemini", "groq", "openrouter", "custom")

    /** एक बार की नक़ल (पुरानी साझा keys → हर हिस्से की अपनी) हो चुकी - इसका निशान */
    const val MIGRATED = "api_scopes_v1"

    fun title(scope: String) = when (scope) {
        LIVE -> "DK लाइव"; CHAT -> "DK चैट"; CODER -> "कोडिंग एजेंट"; PC -> "DK कंप्यूटर"; else -> scope
    }

    /** इस हिस्से में कौन-सी कंपनियाँ (DK लाइव: सिर्फ़ Gemini जवाब/बोलने के लिए + Groq सिर्फ़ सुनने के लिए) */
    fun providers(scope: String): List<String> = if (scope == LIVE) listOf("gemini", "groq") else PROVIDERS

    /**
     * Setting का नाम. DK चैट = पुराने नाम (key_gemini, model_groq…) ताकि पुरानी keys जैसी की तैसी चलें;
     * बाकी: live_key_gemini, coder_model_groq, pc_base_custom …
     */
    fun pref(scope: String, field: String, id: String): String =
        if (scope == CHAT) "${field}_$id" else "${scope}_${field}_$id"

    /** चुनी हुई (मुख्य) कंपनी की setting */
    fun activePref(scope: String): String = when (scope) {
        CHAT -> "active_provider"
        else -> "${scope}_active"
    }

    /** Key के शुरू के अक्षर से कंपनी (KeySetup.detect जैसा) */
    fun detect(k: String): String? = when {
        k.length < 16 -> null
        k.startsWith("AIza") -> "gemini"
        k.startsWith("gsk_") -> "groq"
        k.startsWith("sk-or-") -> "openrouter"
        else -> null
    }

    /**
     * पहली बार: पुरानी साझा settings से हर हिस्से की अपनी keys, ताकि अपडेट के बाद कुछ न टूटे.
     * उसके बाद हर हिस्सा पूरी तरह अलग - एक में key बदलने से दूसरे पर असर नहीं.
     * @param get पुरानी setting पढ़ो (null = नहीं है)
     * @return जो नई settings लिखनी हैं (सिर्फ़ खाली न हों)
     */
    fun migrate(get: (String) -> String?): Map<String, String> {
        if (get(MIGRATED) != null) return emptyMap()
        val out = linkedMapOf<String, String>()
        fun put(k: String, v: String?) { if (!v.isNullOrBlank()) out[k] = v.trim() }
        fun valid(id: String?) = id != null && id in PROVIDERS
        val chatActive = get("active_provider")?.takeIf { valid(it) } ?: "gemini"

        // DK लाइव: पहले की "आवाज़ के लिए अलग key", नहीं तो चैट वाली Gemini/Groq key
        val vk = get("voice_key").orEmpty().trim()
        val vp = get("voice_provider").orEmpty().ifBlank { detect(vk) ?: "" }
        put(pref(LIVE, "key", "gemini"), if (vk.isNotBlank() && vp == "gemini") vk else get("key_gemini"))
        put(pref(LIVE, "key", "groq"), if (vk.isNotBlank() && vp == "groq") vk else get("key_groq"))

        // कोडिंग एजेंट और DK कंप्यूटर: चैट वाली keys की नक़ल, फिर उनकी अपनी पुरानी अलग setting ऊपर
        for (scope in listOf(CODER, PC)) {
            for (id in PROVIDERS) {
                put(pref(scope, "key", id), get("key_$id"))
                put(pref(scope, "model", id), get("model_$id"))
                put(pref(scope, "base", id), get("base_$id"))
            }
            val own = get(if (scope == CODER) "coder_provider" else "pc_provider")
            if (valid(own)) {
                out[activePref(scope)] = own!!
                if (scope == CODER) {
                    put(pref(scope, "key", own), get("coder_key"))
                    put(pref(scope, "model", own), get("coder_model"))
                    put(pref(scope, "base", own), get("coder_base"))
                } else put(pref(scope, "model", own), get("pc_model"))
            } else {
                out[activePref(scope)] = chatActive
                // पहले "वही AI" + सिर्फ़ मॉडल अलग चुना था (कंप्यूटर)
                if (scope == PC) put(pref(scope, "model", chatActive), get("pc_model"))
            }
        }
        out[MIGRATED] = "1"
        return out
    }

    // ---- v3.91: "एक key से सब" (ऑल-राउंडर) - एक Gemini key से DK लाइव, चैट, खोज, सुनना/बोलना, कोडिंग, कंप्यूटर
    const val ONE_KEY_ON = "one_key_on"
    const val ONE_KEY = "one_key_gemini"
    /** पुरानी अलग-अलग keys की settings - इनमें से कोई भरी = पुराना user, उसकी अलग keys वैसे ही चलें */
    val OLD_KEY_PREFS: List<String> = PROVIDERS.flatMap { id -> listOf("key_$id") + listOf(LIVE, CODER, PC).map { pref(it, "key", id) } } +
        listOf("voice_key", "coder_key", "live_key_caption", "live_key_search", "coder_key_gemini2", "coder_key_gemini3",
            "coder_key_gemini4", "coder_key_gemini5")

    /** पहली बार: नया install (कोई key नहीं) = एक key वाला चालू; पुराना user = बंद (अलग keys जैसी की तैसी) */
    fun oneKeyDefault(get: (String) -> String?): Boolean = OLD_KEY_PREFS.none { !get(it).isNullOrBlank() }

    /** एक-key मोड में किस कंपनी की key: Gemini = वही एक key; Groq/OpenRouter/Custom/बैकअप keys = कोई नहीं (सब Gemini से) */
    fun oneKeyFor(id: String, oneKey: String): String = if (id == "gemini") oneKey.trim() else ""

    private val notChat = listOf("whisper", "tts", "guard", "safeguard", "playai", "orpheus", "distil", "embed", "image", "audio", "transcribe", "compound")

    /**
     * उसी key के बैकअप मॉडल (Groq / OpenRouter / Custom): मुख्य मॉडल की limit (429) खत्म या मॉडल बंद हो तो
     * इन पर अपने-आप - दूसरी कंपनी/दूसरी key पर नहीं. Gemini के बैकअप GeminiClient खुद संभालता है.
     * @param fast  तुरंत सेटअप ने जो फ्री मॉडल चलते पाए (तेज़ पहले)
     * @param cached उस कंपनी के मॉडलों की list (Settings में लाई गई)
     */
    fun backupModels(provider: String, primary: String, fast: List<String>, cached: List<String>, max: Int = 4): List<String> {
        val fromList = when (provider) {
            "openrouter" -> cached.filter { it.endsWith(":free") } + "openrouter/free"
            "groq" -> cached.filter { m -> notChat.none { m.lowercase().contains(it) } }
            else -> emptyList()
        }
        return (fast + fromList).map { it.trim() }.filter { it.isNotBlank() && it != primary.trim() }.distinct().take(max)
    }
}
