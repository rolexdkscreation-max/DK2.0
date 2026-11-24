package com.dk.assistant

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 24 नवंबर 2026 (मालिक: "DK CONTENT WRITING"): एक ही लेखक एजेंट - कहानी + बाकी कंटेंट.
 *  - कहानी (STORY) = पुराना Story Writer, बिल्कुल वैसा ही ([StoryPlan] / [StoryPrompt] / [StorySession] को छुआ नहीं).
 *  - बाकी (फैक्ट्स, स्क्रिप्ट, कैप्शन, स्क्रिप्ट सुधार, फ़ोटो से) = यहाँ: हुक, आसान बोलचाल की हिंदी, curiosity gap, CTA.
 *  - सिर्फ़ जो जानकारी छूटी है वही पूछना ([ContentPlan.missing]); कंटेंट दोहराना नहीं ([ContentMemory]).
 * Pure हिस्सा ([ContentPlan]) JVM test में ([ContentWritingTest]).
 */
object ContentPlan {
    /** [defSec] = डिफ़ॉल्ट लंबाई (सेकंड); 0 = लंबाई की ज़रूरत नहीं */
    enum class Type(val id: String, val hi: String, val defSec: Int, val needsTopic: Boolean) {
        STORY("story", "कहानी", 0, false),
        FACTS("facts", "फैक्ट्स वीडियो", 40, true),
        SCRIPT("script", "वीडियो स्क्रिप्ट", 60, true),
        CAPTION("caption", "कैप्शन + हैशटैग", 0, true),
        FIX("fix", "स्क्रिप्ट सुधारो", 0, true),
        IMAGE("image", "फ़ोटो से स्क्रिप्ट", 45, false),
    }

    /** छोटे वीडियो की लंबाई (सेकंड) */
    val LENGTHS = listOf(30, 45, 60, 90)
    /** बोलचाल की हिंदी ~ 140 शब्द / मिनट (Story Writer वाला ही हिसाब) */
    const val WORDS_PER_MIN = StoryPlan.WORDS_PER_MIN

    fun words(sec: Int) = (sec * WORDS_PER_MIN / 60.0).roundToInt().coerceAtLeast(20)

    /** text बोलने में लगभग कितने सेकंड */
    fun estimateSec(text: String): Int = (StoryPlan.wordCount(text) * 60.0 / WORDS_PER_MIN).roundToInt()

    fun durationHi(sec: Int): String = if (sec < 60) "लगभग $sec सेकंड" else "लगभग ${sec / 60} मिनट" + (if (sec % 60 >= 10) " ${sec % 60} सेकंड" else "")

    /** सिर्फ़ जो छूटा है वही पूछो. null = सब है, लिखो */
    fun missing(t: Type, topic: String, hasImage: Boolean): String? = when {
        t == Type.IMAGE && !hasImage -> "कौन-सी फ़ोटो? नीचे \"फ़ोटो चुनें\" दबाइए।"
        t == Type.FIX && topic.isBlank() -> "जो स्क्रिप्ट सुधारनी है, वह ऊपर डालिए।"
        t == Type.CAPTION && topic.isBlank() -> "किस वीडियो / पोस्ट का कैप्शन? एक लाइन में बताइए।"
        t.needsTopic && topic.isBlank() -> "किस विषय पर? एक लाइन में बताइए।"
        else -> null
    }

    val SYSTEM = """
You are "DK Content Writing", an expert Hindi short-video writer for Instagram Reels, YouTube Shorts and Facebook.
Rules for every script:
1. HOOK: the first line must stop the scroll - a surprising fact, a question or a bold claim (max 12 words).
2. LANGUAGE: very simple spoken Hindi (Devanagari), the way people talk at home. No heavy Sanskrit or literary words. Short sentences.
3. CURIOSITY GAP: keep one open question so the viewer watches till the end; reveal it near the end.
4. CTA: end with one short natural call to action (follow / comment / share).
5. FACTS: only true, well-known, checkable facts. If you are not sure about a fact, do not use it. Never invent numbers, dates or quotes.
6. ORIGINAL: never repeat earlier scripts, hooks or facts listed by the user.
7. OUTPUT: only the clean script text, ready to speak. No headings like "Hook:", no notes, no emojis, no markdown, no word counts.
""".trim()

    /** लिखने की request (user turn) */
    fun request(t: Type, topic: String, sec: Int, avoid: List<String>): String {
        val tp = topic.trim()
        val len = sec.takeIf { it > 0 } ?: t.defSec
        val avoidLine = avoid.filter { it.isNotBlank() }.take(15).takeIf { it.isNotEmpty() }
            ?.let { "\nDo NOT repeat these earlier pieces / hooks: " + it.joinToString(" | ") { a -> a.take(80) } } ?: ""
        return when (t) {
            Type.FACTS -> "Write a $len-second Hindi facts video script (about ${words(len)} words) on: $tp. 3-5 amazing true facts, each in 1-2 short lines.$avoidLine"
            Type.SCRIPT -> "Write a $len-second Hindi video script (about ${words(len)} words) on: $tp.$avoidLine"
            Type.CAPTION -> "Write one short Hindi caption (1-2 lines, with a hook) and then 10-15 relevant hashtags (mix of Hindi-English, popular + niche) on one line, for: $tp.$avoidLine"
            Type.FIX -> "Improve this script. Keep its meaning and facts, keep the length similar. Make the first line a strong hook, use simple spoken Hindi, add a curiosity gap and a short CTA. Script:\n$tp"
            Type.IMAGE -> "Look at this photo and write a $len-second Hindi video script (about ${words(len)} words) about what is in it" +
                (if (tp.isNotBlank()) " ($tp)" else "") + ". Only describe what is really visible; do not guess names or places.$avoidLine"
            Type.STORY -> tp
        }
    }

    /** "बदलो" - पिछला text + मालिक का निर्देश */
    fun modify(prev: String, how: String): String =
        "Rewrite the script below as the owner asks: \"${how.trim()}\". Keep all the rules. Script:\n$prev"

    /** लिखे script से कैप्शन + हैशटैग */
    fun captionFor(script: String): String = request(Type.CAPTION, script.take(600), 0, emptyList())

    /** AI जवाब साफ़ */
    fun clean(raw: String): String = StoryPlan.clean(raw)
        .lines().filterNot { it.trim().matches(Regex("(?i)^(hook|cta|script|स्क्रिप्ट)\\s*[:：]?\\s*$")) }
        .joinToString("\n").replace(Regex("(?im)^(hook|cta)\\s*[:：]\\s*"), "").trim()
}

/** पहले लिखे कंटेंट की छोटी याद (हुक) - दोबारा वही न लिखे */
object ContentMemory {
    private const val PREF = "dk_content_writing"
    private const val MAX = 30
    fun recent(c: Context): List<String> = try {
        JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("recent", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) { emptyList() }

    fun add(c: Context, text: String) {
        val first = text.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(100) ?: return
        val l = (listOf(first) + recent(c).filter { it != first }).take(MAX)
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("recent", JSONArray(l).toString()).apply()
    }
}

/** लिखने वाला AI: वही "AI दिमाग" जो Story Writer इस्तेमाल करता है ([StoryAi]); फ़ोटो = Gemini (मुफ़्त key) */
object ContentAi {
    private val http by lazy { NetFix.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build() }
    private val VISION = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite")

    /** blocking - IO thread से. गलती = IllegalStateException (हिंदी में) */
    fun write(c: Context, msgs: List<Pair<String, String>>): String {
        val ai = StoryAi.fromPrefs(c) ?: throw IllegalStateException("AI key नहीं - सेटिंग्स → AI दिमाग में डालें")
        val out = try { ai.write(ContentPlan.SYSTEM, msgs) } catch (e: Exception) {
            // एक बार दोबारा, फिर साफ़ गलती
            try { ai.write(ContentPlan.SYSTEM, msgs) } catch (e2: Exception) { throw IllegalStateException("लिखा नहीं जा सका: ${(e2.message ?: e.message).orEmpty().take(120)}") }
        }
        return ContentPlan.clean(out.text).ifBlank { throw IllegalStateException("AI ने खाली जवाब दिया, फिर से कोशिश करें") }
    }

    /** फ़ोटो देखकर (Gemini vision) */
    fun fromImage(c: Context, img: Uri, prompt: String): String {
        val bytes = c.contentResolver.openInputStream(img)?.use { it.readBytes() } ?: throw IllegalStateException("फ़ोटो नहीं खुली")
        if (bytes.size > 15 * 1024 * 1024) throw IllegalStateException("फ़ोटो 15MB से बड़ी है")
        val mime = c.contentResolver.getType(img)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val keys = (listOf(Prefs.oneKey(c), Prefs.key(c, "gemini")) + Prefs.liveGeminiKeys(c)).filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) throw IllegalStateException("फ़ोटो से लिखने के लिए Gemini key चाहिए - सेटिंग्स → AI दिमाग")
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", ContentPlan.SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64)))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 4096).put("temperature", 0.8)).toString()
        var last = "Gemini से जवाब नहीं मिला"
        for (k in keys) for (m in VISION) {
            try {
                val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", k).post(body.toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { r ->
                    val s = r.body?.string().orEmpty()
                    if (!r.isSuccessful) { last = "Gemini ${r.code}"; return@use }
                    val p = JSONObject(s).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@use
                    val sb = StringBuilder()
                    for (i in 0 until p.length()) { val x = p.optJSONObject(i) ?: continue; if (!x.optBoolean("thought")) sb.append(x.optString("text")) }
                    if (sb.isNotBlank()) return ContentPlan.clean(sb.toString())
                }
            } catch (e: Exception) { last = e.message ?: last }
        }
        throw IllegalStateException(last)
    }
}
