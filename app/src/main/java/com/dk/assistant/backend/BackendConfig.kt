package com.dk.assistant.backend

import com.dk.assistant.BuildConfig

/**
 * 17 नवंबर 2026 (मालिक का "DK बैकएंड मास्टर प्रॉम्प्ट"): backend का पता और Firebase की सार्वजनिक पहचान - सिर्फ़ यही एक जगह.
 * सब build के समय GitHub Secrets (VITE_*) से आता है. ऐप में इन्हें डालने/बदलने का कोई बॉक्स नहीं (मालिक का सख़्त नियम).
 * बाद में दूसरा सर्वर आए तो भी पता सिर्फ़ यहीं से बदलेगा.
 */
object BackendConfig {
    /** test बदल सके (असली ऐप में BuildConfig) */
    @Volatile var override: Values? = null

    data class Values(val backendUrl: String, val projectId: String, val apiKey: String, val authDomain: String, val whatsapp: String)

    fun values(): Values = override ?: Values(
        BuildConfig.BACKEND_URL, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_API_KEY,
        BuildConfig.FIREBASE_AUTH_DOMAIN, BuildConfig.WHATSAPP_NUMBER,
    )

    /** आख़िरी "/" हटाकर; https नहीं = खाली (सादे http पर token कभी नहीं भेजते) */
    val url: String get() = clean(values().backendUrl)

    internal fun clean(u: String): String {
        val t = u.trim().trimEnd('/')
        return if (t.startsWith("https://")) t else ""
    }

    /** backend जुड़ सकता है? (पता + Firebase दोनों build में आए) */
    fun ready(): Boolean = url.isNotBlank() && values().projectId.isNotBlank() && values().apiKey.isNotBlank()

    /** "लाइसेंस की पाएँ" - WhatsApp पर तय संदेश. नंबर न हो = null (बटन नहीं दिखता) */
    fun whatsappLink(): String? {
        val n = values().whatsapp.filter { it.isDigit() }
        if (n.length < 8) return null
        return "https://wa.me/$n?text=" + java.net.URLEncoder.encode(LICENSE_ASK, "UTF-8").replace("+", "%20")
    }

    const val LICENSE_ASK = "नमस्ते, मुझे DK का लाइसेंस की चाहिए"
}
