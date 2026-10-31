package com.dk.assistant

/**
 * v5 (मालिक): "Custom API (Base URL डालो) हर जगह से हटाओ. उसकी जगह - किसी भी कंपनी (OpenAI या कोई भी) की key डालो,
 * कंपनी का नाम अपने-आप आ जाए, और मॉडल चुनने का विकल्प हो."
 *
 * Pure logic (कोई Android import नहीं) -> JVM unit test. key देखकर कंपनी पहचानना + उसका OpenAI-जैसा पता (base URL).
 * जिन keys का निशान एक-सा है (जैसे "sk-" = OpenAI / DeepSeek / Kimi / Qwen) उनके लिए उम्मीदवार क्रम से -
 * फ़ोन वाला हिस्सा ([ApiCompanyProbe]) हर उम्मीदवार की /models से जाँचकर असली कंपनी चुनता है.
 */
object ApiCompanies {
    /** @param prefixes key इनसे शुरू हो तो यही कंपनी (सबसे लंबा निशान पहले मिलता है) @param models /models न मिले तो ये */
    data class Company(val id: String, val name: String, val base: String, val prefixes: List<String>, val models: List<String>,
                       val keyUrl: String = "")

    val ALL: List<Company> = listOf(
        Company("openai", "OpenAI (ChatGPT)", "https://api.openai.com/v1", listOf("sk-proj-", "sk-svcacct-", "sk-admin-", "sk-"),
            listOf("gpt-5", "gpt-5-mini", "gpt-4.1", "gpt-4o-mini"), "https://platform.openai.com/api-keys"),
        Company("anthropic", "Anthropic (Claude)", "https://api.anthropic.com/v1", listOf("sk-ant-"),
            listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-4-5"), "https://console.anthropic.com/settings/keys"),
        Company("xai", "xAI (Grok)", "https://api.x.ai/v1", listOf("xai-"), listOf("grok-4", "grok-3-mini"), "https://console.x.ai"),
        Company("perplexity", "Perplexity", "https://api.perplexity.ai", listOf("pplx-"),
            listOf("sonar-pro", "sonar", "sonar-reasoning-pro"), "https://www.perplexity.ai/settings/api"),
        Company("groq", "Groq", "https://api.groq.com/openai/v1", listOf("gsk_"), listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile"),
            "https://console.groq.com/keys"),
        Company("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", listOf("sk-or-"), listOf("openrouter/free"), "https://openrouter.ai/keys"),
        Company("gemini", "Google (Gemini)", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("AIza"),
            listOf("gemini-2.5-flash", "gemini-2.5-pro"), "https://aistudio.google.com/apikey"),
        Company("cerebras", "Cerebras", "https://api.cerebras.ai/v1", listOf("csk-"), listOf("gpt-oss-120b", "llama-3.3-70b"), "https://cloud.cerebras.ai"),
        Company("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", listOf("nvapi-"), listOf("meta/llama-3.3-70b-instruct"), "https://build.nvidia.com"),
        Company("huggingface", "Hugging Face", "https://router.huggingface.co/v1", listOf("hf_"), listOf("openai/gpt-oss-120b"), "https://huggingface.co/settings/tokens"),
        Company("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", listOf("fw_"),
            listOf("accounts/fireworks/models/gpt-oss-120b"), "https://fireworks.ai/account/api-keys"),
        Company("together", "Together AI", "https://api.together.xyz/v1", listOf("tgp_"), listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo"),
            "https://api.together.ai/settings/api-keys"),
        Company("deepseek", "DeepSeek", "https://api.deepseek.com/v1", emptyList(), listOf("deepseek-chat", "deepseek-reasoner"), "https://platform.deepseek.com/api_keys"),
        Company("moonshot", "Moonshot (Kimi)", "https://api.moonshot.ai/v1", emptyList(), listOf("kimi-k2-0905-preview"), "https://platform.moonshot.ai"),
        Company("qwen", "Alibaba (Qwen)", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", emptyList(), listOf("qwen-plus", "qwen-max"),
            "https://bailian.console.alibabacloud.com"),
        Company("zhipu", "Zhipu (GLM)", "https://api.z.ai/api/paas/v4", emptyList(), listOf("glm-4.6"), "https://z.ai/manage-apikey/apikey-list"),
        Company("mistral", "Mistral AI", "https://api.mistral.ai/v1", emptyList(), listOf("mistral-large-latest", "mistral-small-latest"),
            "https://console.mistral.ai/api-keys"),
        Company("sambanova", "SambaNova", "https://api.sambanova.ai/v1", emptyList(), listOf("Meta-Llama-3.3-70B-Instruct"), "https://cloud.sambanova.ai/apis"),
    )

    fun byId(id: String): Company? = ALL.firstOrNull { it.id == id }
    fun byBase(base: String): Company? = base.trim().trimEnd('/').let { b -> ALL.firstOrNull { it.base.equals(b, ignoreCase = true) } }

    /** "sk-" वाली keys (प्रोजेक्ट/एडमिन निशान के बिना) - OpenAI जैसी दिखती हैं, पर DeepSeek / Kimi / Qwen की भी यही शक्ल */
    private val SK_LOOKALIKE = listOf("openai", "deepseek", "moonshot", "qwen")
    /** कोई निशान नहीं - इन्हें क्रम से जाँचो, SambaNova और अन्य सभी थर्ड-पार्टी एपीआई की के लिए */
    private val NO_PREFIX = listOf("sambanova", "mistral", "together", "deepseek", "zhipu", "moonshot", "qwen")

    /**
     * key देखकर कंपनी के उम्मीदवार, सबसे पक्के पहले. खाली = key बहुत छोटी.
     * पहला उम्मीदवार पक्का हो (अनोखा निशान) तो सूची में सिर्फ़ वही.
     */
    fun candidates(key: String): List<Company> {
        val k = key.trim()
        if (k.length < 8) return emptyList()
        // सबसे लंबा मिलता निशान (sk-ant- / sk-or- पहले, फिर sk-)
        val hit = ALL.filter { it.prefixes.isNotEmpty() }.flatMap { c -> c.prefixes.map { it to c } }.filter { (p, _) -> k.startsWith(p) }.maxByOrNull { it.first.length }
        if (hit != null) {
            val (p, c) = hit
            if (c.id == "openai" && p == "sk-") return SK_LOOKALIKE.mapNotNull { byId(it) }
            return listOf(c)
        }
        // Zhipu की key में बीच में "." होता है
        if (k.contains('.') && !k.contains(' ')) return listOfNotNull(byId("zhipu")) + NO_PREFIX.filter { it != "zhipu" }.mapNotNull { byId(it) }
        // 64 अक्षर hex = Together
        if (k.length == 64 && k.all { it.isDigit() || it in 'a'..'f' }) return listOfNotNull(byId("together")) + NO_PREFIX.filter { it != "together" }.mapNotNull { byId(it) }
        // यदि कोई भी एपीआई की बिना प्रीफिक्स या अन्य है, तो सभी समर्थित कंपनियों को उम्मीदवार के रूप में लौटाएं ताकि प्रोब सही से जांच सके
        return ALL.map { it.id }.distinct().mapNotNull { byId(it) }
    }

    /** पक्की पहचान (बिना इंटरनेट) - सिर्फ़ एक उम्मीदवार हो तब */
    fun sure(key: String): Company? = candidates(key).singleOrNull()

    /** Settings में दिखने वाली लाइन */
    fun label(c: Company?): String = if (c == null) "कंपनी: पहचान नहीं हुई — \"कंपनी बदलें\" से चुनें" else "कंपनी: ${c.name} ✓"
}
