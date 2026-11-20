package com.dk.assistant

/** Saari AI companies jinka API DK me laga sakte hain: Google Gemini, Groq, OpenRouter, Z.ai aur NVIDIA (नवंबर 2026). */
data class Provider(
    val id: String,
    val name: String,
    val free: Boolean,
    val note: String,
    val baseUrl: String,       // OpenAI-compatible base URL (Gemini ke liye khali)
    val defaultModel: String,
    val vision: Boolean,       // screenshot samajh sakta hai?
    val keyUrl: String,
    val isGemini: Boolean = false,
    /** नवंबर 2026 (मज़बूत मॉडल): जवाब की लंबाई वाला field (OpenAI के नए मॉडल = max_completion_tokens) */
    val tokenField: String = "max_tokens",
    /** false = temperature न भेजो (नए Claude/OpenAI reasoning मॉडल 0.2 पर 400 देते हैं) */
    val sendTemp: Boolean = true
)

object Providers {
    val all = listOf(
        Provider("gemini", "Google Gemini", true, "फ्री • बिना कार्ड • स्क्रीनशॉट समझता है",
            "", "gemini-3.5-flash-lite", true, "https://aistudio.google.com/apikey", isGemini = true),
        Provider("groq", "Groq", true, "फ्री • बहुत तेज़ • सिर्फ़ टेक्स्ट",
            "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", false, "https://console.groq.com/keys"),
        Provider("openrouter", "OpenRouter", true, "फ्री मॉडल • रोज़ ~50 रिक्वेस्ट",
            "https://openrouter.ai/api/v1", "openrouter/free", true, "https://openrouter.ai/keys"),
        // नवंबर 2026 (मालिक): "Extra API" (कोई भी कंपनी) काम नहीं करता था - पूरी तरह हटाया. उसकी जगह Z.ai और NVIDIA
        Provider("zai", "Z.ai (GLM)", true, "फ्री GLM-4.7-Flash • तेज़ • सिर्फ़ टेक्स्ट",
            "https://api.z.ai/api/paas/v4", "glm-4.7-flash", false, "https://z.ai/manage-apikey/apikey-list"),
        Provider("nvidia", "NVIDIA NIM", true, "फ्री (nvapi- key) • Nemotron / Gemma • सिर्फ़ टेक्स्ट",
            "https://integrate.api.nvidia.com/v1", "nvidia/nemotron-3.5-lightning-30b-a3b", false, "https://build.nvidia.com/settings/api-keys")
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** जिनकी /models list न खुले (Z.ai) - तब ये मॉडल दिखें. पहला = सबसे तेज़/फ्री */
    fun fallbackModels(id: String): List<String> = when (id) {
        "zai" -> listOf("glm-4.7-flash", "glm-4.5-flash", "glm-4.6v-flash")
        // 20 नवंबर 2026 (मालिक: "NVIDIA का llama 410 Gone"): NVIDIA ने llama-3.3-70b, nemotron-super-49b, gpt-oss-120b आदि के फ्री
        // endpoint बंद किए (410). उनकी जगह अभी चालू फ्री मॉडल
        "nvidia" -> listOf("nvidia/nemotron-3.5-lightning-30b-a3b", "google/gemma-4-31b-it", "nvidia/nemotron-3.5-super-vl-120b-a12b",
            "openai/gpt-oss-20b", "nvidia/nemotron-3-ultra-550b-a55b")
        // नवंबर 2026 (मालिक): हर API के अपने फ्री मॉडल - एक की limit खत्म = उसी API का अगला (Settings में सूची न खोली हो तब भी)
        // 20 नवंबर 2026: Groq ने llama-3.3-70b-versatile / llama-3.1-8b-instant 16 अगस्त 2026 को बंद किए - उनकी जगह qwen3.8-27b
        "groq" -> listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b")
        "openrouter" -> listOf("openrouter/free", "google/gemma-4-31b-it:free", "google/gemma-4-26b-a4b-it:free")
        else -> emptyList()
    }

    /**
     * 20 नवंबर 2026 (मालिक: "कंपनी जो मॉडल बंद करे, उसे हटा के नया डाल दिया करो"): कंपनी ने बंद किए (410 / shutdown) मॉडल -> उसी
     * कंपनी का चालू फ्री मॉडल. Settings में पहले से सहेजा पुराना नाम भी अपने-आप नए पर जाए
     */
    val RETIRED: Map<String, Map<String, String>> = mapOf(
        "nvidia" to mapOf(
            "meta/llama-3.3-70b-instruct" to "nvidia/nemotron-3.5-lightning-30b-a3b",
            "meta/llama-3.1-70b-instruct" to "nvidia/nemotron-3.5-lightning-30b-a3b",
            "nvidia/llama-3.3-nemotron-super-49b-v1.5" to "nvidia/nemotron-3.5-super-vl-120b-a12b",
            "nvidia/nemotron-3-nano-30b-a3b" to "nvidia/nemotron-3.5-lightning-30b-a3b",
            "meta/llama-4-maverick-17b-128e-instruct" to "google/gemma-4-31b-it",
            "openai/gpt-oss-120b" to "openai/gpt-oss-20b"),
        "groq" to mapOf(
            "llama-3.3-70b-versatile" to "openai/gpt-oss-120b",
            "llama-3.1-8b-instant" to "openai/gpt-oss-20b",
            "qwen/qwen3.6-27b" to "qwen/qwen3.8-27b"))

    /** बंद मॉडल का नाम -> उसकी जगह वाला चालू मॉडल; बाकी नाम वैसे ही */
    fun current(id: String, model: String): String = RETIRED[id]?.get(model.trim()) ?: model
}
