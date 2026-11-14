package com.dk.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * नवंबर 2026 (मालिक): "DK लाइव चलते समय स्क्रीन भी देख सके - कोई शॉपिंग वाला खुला हो तो बताए कौन-सा प्रोडक्ट है,
 * उसका कलर क्या है, प्राइस कितना है, किस कंपनी का है".
 *
 * Live का look_screen tool: user के पूछने पर **एक बार** स्क्रीन देखो - screenshot (Accessibility, बिना popup) +
 * स्क्रीन पर लिखा text (प्राइस/ब्रांड अक्सर यहीं सही लिखा होता है) -> Gemini vision -> साफ़ जवाब.
 * ScreenGuard वाली बात: अपने-आप कभी नहीं, सिर्फ़ user के पूछने पर; पासवर्ड वाली स्क्रीन / लॉक फ़ोन = नहीं देखते.
 * screenshot नहीं मिला (बैंक जैसे सुरक्षित ऐप) = सच बताते हैं, अंदाज़ा नहीं.
 */
object ScreenLook {
    private val http by lazy { NetFix.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build() }

    /** user ने सच में स्क्रीन / दिख रही चीज़ के बारे में पूछा? (Gemini अपने-आप न बुलाए) */
    private val ASK = listOf("स्क्रीन", "screen", "दिख", "देख", "ये क्या", "यह क्या", "ये कौन", "यह कौन", "इसका", "इसकी", "इसके", "इस ", "कौन सा", "कौन-सा", "कौनसा",
        "कौन सी", "कौन-सी", "कितने का", "कितने की", "कितना", "price", "प्राइस", "दाम", "कीमत", "रेट", "रंग", "कलर", "color", "colour", "कंपनी", "company",
        "ब्रांड", "brand", "product", "प्रोडक्ट", "लिखा", "पढ़", "read", "what is this", "what's this", "which", "offer", "ऑफर", "डिस्काउंट", "discount",
        "रेटिंग", "rating", "साइज", "साइज़", "size", "look", "see", "this ")

    fun asked(text: String?): Boolean {
        val t = " " + text.orEmpty().lowercase().trim() + " "
        return t.isNotBlank() && ASK.any { t.contains(it) }
    }

    /** Gemini को सवाल (शुद्ध - JVM test) */
    internal fun prompt(question: String, pkg: String, screenText: String, hasImage: Boolean): String = buildString {
        append("You are looking at the user's Android phone screen right now")
        append(if (hasImage) " (screenshot attached)" else " (no screenshot available - only the on-screen text below)")
        append(". Foreground app package: ").append(pkg.ifBlank { "unknown" }).append(".\n")
        append("The user asked (Hindi/English): \"").append(question.take(300)).append("\"\n\n")
        append("ON-SCREEN TEXT read by the accessibility service (exact; prefer it for prices, names and numbers):\n")
        append(screenText.take(6000).ifBlank { "(none)" }).append("\n\n")
        append("Answer the user's question from what is REALLY visible. If a product is shown (shopping app/site), give: product name, brand/company, ")
        append("colour (as seen in the picture), price (selling price with currency, plus MRP / discount % if shown), rating & number of ratings, ")
        append("size/variant if shown, seller, delivery/offer lines. If several products are visible, list the main ones (max 5) in order. ")
        append("If something is not visible, say \"नहीं दिख रहा\" for it - NEVER guess a price, brand or colour. ")
        append("Reply in simple Hindi (Devanagari), short spoken sentences, no markdown, max 120 words.")
    }

    /** DK लाइव का look_screen */
    suspend fun liveTool(svc: DKAccessibilityService, question: String): String {
        if (!asked(question) && !asked(DKState.lastUser))
            return "NOT LOOKED: look_screen is used only when the user himself asks about what is on his screen. Ask him what he wants to know about the screen."
        val km = svc.getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return "NOT LOOKED: the phone is locked - tell the user to unlock it first."
        val screen = try { svc.readScreen() } catch (_: Exception) { null }
        if (svc.hasPasswordField()) return "NOT LOOKED: the screen has a password field - for privacy DK does not read it. Tell the user honestly."
        val pkg = screen?.packageName.orEmpty()
        val own = pkg == svc.packageName
        val img = svc.screenshotBase64(width = 900, quality = 80)
        val text = screen?.text.orEmpty()
        DKLog.d("स्क्रीन देखी: ${if (img != null) "screenshot" else "screenshot नहीं"}, text ${text.length} अक्षर, ऐप $pkg")
        if (img == null && text.isBlank())
            return "COULD NOT SEE: the screen could not be captured (secure app or Android blocked the screenshot) and no text was readable. Tell the user honestly; do not guess."
        val ownNote = if (own) " NOTE: DK's own screen is in front - if he meant another app (e.g. a shopping app), ask him to open it and ask again." else ""
        val keys = Prefs.liveGeminiKeys(svc)
        if (keys.isEmpty())
            return "SCREEN TEXT ONLY (no Gemini key for vision; colours cannot be seen). Answer from this text only, never guess:$ownNote\n" + text.take(3000)
        val ans = withContext(Dispatchers.IO) { vision(keys, prompt(question.ifBlank { DKState.lastUser }, pkg, text, img != null), img) }
            ?: return if (text.isNotBlank()) "VISION FAILED - SCREEN TEXT ONLY (colours unknown). Answer from this text only, never guess:$ownNote\n" + text.take(3000)
            else "COULD NOT SEE: the vision model did not answer. Tell the user honestly."
        return "SCREEN SEEN JUST NOW" + (if (img == null) " (text only - no screenshot, colours unknown)" else "") + " (app: ${pkg.ifBlank { "?" }}). " +
            "Tell the user exactly this, briefly, in his language; do not add anything:$ownNote\n$ans"
    }

    internal fun vision(keys: List<String>, ask: String, jpegB64: String?): String? {
        val parts = JSONArray().put(JSONObject().put("text", ask))
        if (jpegB64 != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", jpegB64)))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 700).put("temperature", 0.2)).toString()
        val models = (listOf(GeminiModels.CODER) + GeminiModels.LIGHT_CHAIN).distinct().take(4)
        for (key in keys.take(2)) for (m in models) {
            try {
                val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", key).post(body.toRequestBody("application/json".toMediaType())).build()
                val txt = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.let { p -> (0 until p.length()).joinToString("") { p.optJSONObject(it)?.takeIf { o -> !o.optBoolean("thought") }?.optString("text").orEmpty() } }
                }
                if (!txt.isNullOrBlank()) return txt.trim()
            } catch (e: Exception) { DKLog.d("स्क्रीन देखना: $m ${e.javaClass.simpleName}") }
        }
        return null
    }
}
