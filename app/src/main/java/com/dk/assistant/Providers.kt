package com.dk.assistant

/** Saari AI companies jinka API DK me laga sakte hain: sirf Google Gemini, Groq, OpenRouter aur Custom API (v3.7). */
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
            "", "gemini-3.5-flash-lite", true, "https://aistudio.google.com/apikey", isGemini = true),
        Provider("groq", "Groq", true, "फ्री • बहुत तेज़ • सिर्फ़ टेक्स्ट",
            "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", false, "https://console.groq.com/keys"),
        Provider("openrouter", "OpenRouter", true, "फ्री मॉडल • रोज़ ~50 रिक्वेस्ट",
            "https://openrouter.ai/api/v1", "openrouter/free", true, "https://openrouter.ai/keys"),
        Provider("custom", "Custom API", true, "कोई भी OpenAI जैसा API (Base URL डालें)",
            "", "", true, "", isCustom = true)
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
