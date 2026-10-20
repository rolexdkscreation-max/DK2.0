package com.dk.assistant

/** Saari AI companies jinka API DK me laga sakte hain. */
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
    val isCustom: Boolean = false
)

object Providers {
    val all = listOf(
        Provider("gemini", "Google Gemini", true, "फ्री • बिना कार्ड • स्क्रीनशॉट समझता है",
            "", "gemini-3.1-flash-lite", true, "https://aistudio.google.com/apikey", isGemini = true),
        Provider("groq", "Groq", true, "फ्री • बहुत तेज़ • सिर्फ़ टेक्स्ट",
            "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", false, "https://console.groq.com/keys"),
        Provider("openrouter", "OpenRouter", true, "फ्री मॉडल • रोज़ ~50 रिक्वेस्ट",
            "https://openrouter.ai/api/v1", "openrouter/free", true, "https://openrouter.ai/keys"),
        Provider("mistral", "Mistral AI", true, "फ्री मोड • बिना कार्ड",
            "https://api.mistral.ai/v1", "mistral-small-latest", true, "https://console.mistral.ai/api-keys"),
        Provider("zai", "Z.ai GLM", true, "Flash मॉडल फ्री",
            "https://api.z.ai/api/paas/v4", "glm-4.7-flash", false, "https://z.ai/manage-apikey/apikey-list"),
        Provider("openai", "OpenAI (ChatGPT)", false, "पेड • क्रेडिट डालना पड़ता है",
            "https://api.openai.com/v1", "gpt-5-mini", true, "https://platform.openai.com/api-keys"),
        Provider("xai", "xAI Grok", false, "पेड • क्रेडिट डालना पड़ता है",
            "https://api.x.ai/v1", "grok-4.3", true, "https://console.x.ai"),
        Provider("custom", "Custom API", true, "कोई भी OpenAI जैसा API (Base URL डालें)",
            "", "", true, "", isCustom = true)
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
