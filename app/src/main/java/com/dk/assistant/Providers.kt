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
        Provider("nvidia", "NVIDIA NIM", true, "फ्री (nvapi- key) • Llama / Nemotron / DeepSeek • सिर्फ़ टेक्स्ट",
            "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct", false, "https://build.nvidia.com/settings/api-keys")
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** जिनकी /models list न खुले (Z.ai) - तब ये मॉडल दिखें. पहला = सबसे तेज़/फ्री */
    fun fallbackModels(id: String): List<String> = when (id) {
        "zai" -> listOf("glm-4.7-flash", "glm-4.5-flash", "glm-4.6v-flash")
        "nvidia" -> listOf("meta/llama-3.3-70b-instruct", "meta/llama-3.1-8b-instruct", "nvidia/llama-3.3-nemotron-super-49b-v1.5", "deepseek-ai/deepseek-v3.1",
            "openai/gpt-oss-120b", "qwen/qwen3-coder-480b-a35b-instruct")
        // नवंबर 2026 (मालिक): हर API के अपने फ्री मॉडल - एक की limit खत्म = उसी API का अगला (Settings में सूची न खोली हो तब भी)
        "groq" -> listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant")
        "openrouter" -> listOf("openrouter/free", "google/gemma-4-31b-it:free", "google/gemma-4-26b-a4b-it:free")
        else -> emptyList()
    }
}
