package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "कोडर, डिबगर और प्लानर के लिए ताक़तवर मॉडल जोड़ो").
 *
 * कोडिंग एजेंट का "मज़बूत मॉडल" - Settings → कोडिंग एजेंट → मज़बूत मॉडल. अपनी अलग (paid) key + मॉडल,
 * सिर्फ़ कोड लिखने/गलती की जड़ ढूँढने/प्लान बनाने वाले 3 सदस्यों के लिए (सबसे ज़्यादा सोचने वाले काम).
 * बाकी एजेंट पहले जैसे (कोडिंग एजेंट की API + हल्का मॉडल) - खर्च कम.
 * मज़बूत मॉडल न चले (key गलत, पैसे खत्म 402, limit 429, server) = उसी काम में अपने-आप पुरानी chain (Gemini → OpenRouter → …) - काम रुकता नहीं.
 * Key खाली = यह फ़ीचर बंद, सब पहले जैसा.
 *
 * सब OpenAI जैसे /chat/completions पर (Anthropic का OpenAI-compatible रास्ता भी), Gemini अपना.
 * Android पर निर्भर नहीं - unit test.
 */
object StrongAi {
    const val PREF_PROVIDER = "coder_strong_provider"
    const val PREF_KEY = "coder_strong_key"
    const val PREF_MODEL = "coder_strong_model"

    /** किन सदस्यों को मज़बूत मॉडल */
    val MEMBERS: Set<Seven.Member> = setOf(Seven.Member.CODER, Seven.Member.DEBUGGER, Seven.Member.PLANNER)
    fun uses(a: Seven.Agent): Boolean = a.member in MEMBERS

    /**
     * नवंबर 2026 (मालिक: "कोडिंग एजेंट को Perplexity जितना ताक़तवर बनाओ"): मज़बूत मॉडल किन-किन को.
     *  core  = पहले जैसा (कोडर, डिबगर, प्लानर)
     *  gates = + रिव्यूअर/परफॉरमेंस, टेस्टर/QA, मैनेजर/प्रॉम्प्ट - जो "हाँ, ठीक है" कहकर गलत कोड आगे जाने देते थे (नया default)
     *  all   = सब एजेंट (सबसे महँगा)
     */
    const val PREF_SCOPE = "coder_strong_scope"
    const val SCOPE_CORE = "core"
    const val SCOPE_GATES = "gates"
    const val SCOPE_ALL = "all"
    val SCOPES: List<Pair<String, String>> = listOf(
        SCOPE_GATES to "कोडर, डिबगर, प्लानर + जाँचने वाले (रिव्यूअर, QA, मैनेजर) — सुझाया",
        SCOPE_CORE to "सिर्फ़ कोडर, डिबगर, प्लानर (सबसे कम खर्च)",
        SCOPE_ALL to "सारे एजेंट (सबसे महँगा)")
    val GATE_MEMBERS: Set<Seven.Member> = MEMBERS + setOf(Seven.Member.REVIEWER, Seven.Member.TESTER, Seven.Member.MANAGER)
    fun scope(v: String?): String = if (v in SCOPES.map { it.first }) v!! else SCOPE_GATES
    fun uses(a: Seven.Agent, scope: String): Boolean = when (scope(scope)) {
        SCOPE_CORE -> uses(a)
        SCOPE_ALL -> true
        else -> a.member in GATE_MEMBERS
    }

    /** id, Provider, पहले से भरा मॉडल, Settings में दिखने वाला नाम */
    data class Choice(val id: String, val provider: Provider, val model: String, val label: String)

    val CHOICES: List<Choice> = listOf(
        Choice("anthropic", Provider("anthropic", "Claude (Anthropic)", false, "paid • कोडिंग में सबसे मज़बूत",
            "https://api.anthropic.com/v1", "claude-opus-5-5", true, "https://console.anthropic.com/settings/keys",
            tokenField = "max_tokens", sendTemp = false),
            "claude-opus-5-5", "Claude (Anthropic) — सुझाया: claude-opus-5-5 • सबसे ताक़तवर: claude-fable-5-2 • सस्ता: claude-sonnet-5-5"),
        Choice("openai", Provider("openai", "OpenAI", false, "paid",
            "https://api.openai.com/v1", "gpt-6-astra", true, "https://platform.openai.com/api-keys",
            tokenField = "max_completion_tokens", sendTemp = false),
            "gpt-6-astra", "OpenAI — gpt-6-astra (कोडिंग वाला) • gpt-6.1-sol भी चलेगा"),
        Choice("gemini", Providers.byId("gemini")!!, "gemini-4-argon", "Google Gemini (paid key) — gemini-4-argon"),
        Choice("deepseek", Provider("deepseek", "DeepSeek", false, "paid • बहुत सस्ता",
            "https://api.deepseek.com", "deepseek-v4-pro", false, "https://platform.deepseek.com/api_keys"),
            "deepseek-v4-pro", "DeepSeek — deepseek-v4-pro (सबसे सस्ता)"),
        Choice("openrouter", Provider("openrouter", "OpenRouter (मज़बूत)", false, "paid मॉडल",
            "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-5.5", true, "https://openrouter.ai/keys", sendTemp = false),
            "anthropic/claude-sonnet-5.5", "OpenRouter (credit वाली key) — कोई भी मॉडल, जैसे anthropic/claude-sonnet-5.5")
    )

    fun choice(id: String): Choice = CHOICES.firstOrNull { it.id == id } ?: CHOICES.first()

    /** key के शुरू से कंपनी (पक्का पता हो तभी; DeepSeek/OpenAI दोनों sk- हैं तो वो user चुने) */
    fun detect(key: String): String? {
        val k = key.trim()
        return when {
            k.startsWith("sk-ant-") -> "anthropic"
            k.startsWith("sk-or-") -> "openrouter"
            k.startsWith("AIza") -> "gemini"
            k.startsWith("sk-proj-") || k.startsWith("sk-svcacct-") -> "openai"
            else -> null
        }
    }

    /** Settings के मान -> (Provider, key, model, base) या null = बंद */
    data class Setup(val provider: Provider, val key: String, val model: String, val base: String)

    fun setup(providerId: String?, key: String?, model: String?): Setup? {
        val k = key.orEmpty().trim()
        if (k.length < 8) return null
        val found = detect(k)
        val ch = choice(found ?: providerId.orEmpty())
        // key किसी दूसरी कंपनी की निकली = उस कंपनी का अपना मॉडल (दूसरी कंपनी का मॉडल-नाम वहाँ नहीं चलता)
        val m = if (found != null && providerId != null && found != providerId) ch.model
            else model.orEmpty().trim().removePrefix("models/").ifBlank { ch.model }
        return Setup(ch.provider, k, m, ch.provider.baseUrl)
    }
}
