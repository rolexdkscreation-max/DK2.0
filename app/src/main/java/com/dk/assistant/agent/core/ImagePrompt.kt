package com.dk.assistant.agent.core

import org.json.JSONObject

/**
 * v3.153 (मालिक): "जैसा बोलूँ वैसा ही मत लिखो - खुद अच्छा, प्रोफेशनल prompt बनाओ"।
 * आपकी बात (हिंदी/मिली-जुली) -> साफ़ अंग्रेज़ी image prompt: विषय, जगह, स्टाइल, कैमरा/बनावट, रोशनी, रंग, मूड, क्वालिटी, साइज़।
 * आपकी हर ज़रूरी चीज़ (नाम, लिखा जाने वाला text, रंग, गिनती) वैसी ही रहती है - DK अपनी तरफ़ से नया विषय नहीं जोड़ता।
 * पहले Gemini (वही key) से बनता है; न बने तो नीचे वाला नियम-आधारित तरीका।
 */
object ImagePrompt {
    enum class Kind(val aspect: String, val guide: String) {
        LOGO("1:1", "clean modern vector logo, flat design, simple memorable shapes, strong negative space, centered on a plain background, crisp edges, no mockup, no extra text unless given"),
        POSTER("4:5", "professional poster design, bold clear hierarchy, eye-catching composition, cinematic lighting, print quality, text perfectly spelled and legible"),
        THUMBNAIL("16:9", "YouTube thumbnail style, bold subject in the foreground, high contrast, vibrant colours, clear focal point, room for large readable text"),
        WALLPAPER("9:16", "phone wallpaper, vertical composition, stunning detailed scene, depth, soft cinematic lighting, no text, no watermark"),
        PORTRAIT("4:5", "professional portrait photography, 85mm lens look, shallow depth of field, soft key light with gentle rim light, natural skin texture, sharp eyes"),
        PRODUCT("1:1", "high-end commercial product photography, studio lighting, soft shadows, clean background, sharp detail, premium look"),
        GENERAL("1:1", "high-quality professional image, balanced composition, natural soft lighting, rich realistic colours, sharp focus, highly detailed, no watermark"),
    }

    private val KIND_WORDS: List<Pair<Kind, List<String>>> = listOf(
        Kind.LOGO to listOf("logo", "लोगो", "brand mark", "icon for"),
        Kind.THUMBNAIL to listOf("thumbnail", "थंबनेल", "youtube"),
        Kind.POSTER to listOf("poster", "पोस्टर", "banner", "बैनर", "flyer", "invitation", "निमंत्रण", "card", "कार्ड"),
        Kind.WALLPAPER to listOf("wallpaper", "वॉलपेपर", "वालपेपर", "background for phone"),
        Kind.PRODUCT to listOf("product", "प्रोडक्ट", "bottle", "packaging"),
        Kind.PORTRAIT to listOf("portrait", "पोर्ट्रेट", "my photo", "मेरी फोटो", "profile", "प्रोफाइल", "face", "चेहरा"),
    )

    fun kind(text: String): Kind {
        val t = text.lowercase()
        return KIND_WORDS.firstOrNull { (_, w) -> w.any { t.contains(it) } }?.first ?: Kind.GENERAL
    }

    /** पहले से ही अच्छा, लंबा prompt (Live ने खुद बनाया) - दोबारा नहीं बदलना */
    fun isDetailed(p: String): Boolean =
        p.trim().split(Regex("\\s+")).size >= 30 &&
            Regex("(lighting|light|style|composition|lens|background|colou?r|palette|detailed|aspect|mood)", RegexOption.IGNORE_CASE).containsMatchIn(p)

    /** Gemini को निर्देश - आपकी बात से पेशेवर prompt */
    fun ask(user: String): String {
        val k = kind(user)
        return "You are an expert prompt engineer for AI image generators (ChatGPT image / Gemini Imagen).\n" +
            "Turn the user's request (may be Hindi, Hinglish or English) into ONE excellent English image prompt.\n" +
            "Rules:\n" +
            "- Keep EVERY detail the user gave exactly: subject, people, names, numbers, colours, place, mood. Never drop or change them; never add new main subjects, brands or people.\n" +
            "- Any words that must appear IN the image: keep them exactly as given (same language and spelling) inside double quotes.\n" +
            "- Add professional detail: setting/background, art style or medium, composition and camera angle, lighting, colour palette, mood, level of detail.\n" +
            "- Type guess: ${k.name.lowercase()} -> ${k.guide}.\n" +
            "- End with: Aspect ratio ${k.aspect}. (use the user's ratio/size instead if they gave one)\n" +
            "- 60 to 120 words, one paragraph, no lists, no preamble, no explanation, do not start with 'Prompt:'.\n" +
            "User request: \"${user.trim()}\""
    }

    /** मॉडल का जवाब साफ़ करो; ख़राब/खाली = नियम वाला */
    fun clean(llm: String?, user: String): String {
        var t = llm.orEmpty().trim().removePrefix("```").removeSuffix("```").trim()
        t = t.replace(Regex("^(prompt|image prompt|here is[^:]*)\\s*:\\s*", RegexOption.IGNORE_CASE), "").trim().trim('"').trim()
        if (t.length < 40 || t.split(Regex("\\s+")).size < 8) return fallback(user)
        return t.take(1_200)
    }

    /** बिना AI के: आपकी बात + उसके प्रकार की पेशेवर बातें */
    fun fallback(user: String): String {
        val k = kind(user)
        val u = user.trim().trimEnd('.', '।')
        return "$u. ${k.guide.replaceFirstChar { it.uppercase() }}. Aspect ratio ${k.aspect}."
    }
}

/** Gemini vision का "कहाँ है" जवाब -> स्क्रीन pixel. point = [y, x] 0-1000, या box_2d = [ymin, xmin, ymax, xmax] */
object VisionPoint {
    fun parse(txt: String?, width: Int, height: Int): Pair<Int, Int>? {
        return try {
        val raw = txt.orEmpty().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = JSONObject(raw.substring(raw.indexOf('{').coerceAtLeast(0), raw.lastIndexOf('}') + 1))
        if (!o.optBoolean("found", true)) null else {
            val p = o.optJSONArray("point")
            val b = o.optJSONArray("box_2d")
            val (y, x) = when {
                p != null && p.length() >= 2 -> p.getDouble(0) to p.getDouble(1)
                b != null && b.length() >= 4 -> (b.getDouble(0) + b.getDouble(2)) / 2 to (b.getDouble(1) + b.getDouble(3)) / 2
                else -> return null
            }
            if (x !in 0.0..1000.0 || y !in 0.0..1000.0) null
            else (x / 1000.0 * width).toInt().coerceIn(1, width - 1) to (y / 1000.0 * height).toInt().coerceIn(1, height - 1)
        }
        } catch (_: Exception) { null }
    }

    fun ask(goal: String): String =
        "This is a screenshot of an Android phone screen. Find: $goal.\n" +
            "Reply with ONLY JSON: {\"found\": true, \"point\": [y, x]} where point is the CENTER of that element, " +
            "normalized 0-1000 (y from the top, x from the left). If it is not clearly visible, reply {\"found\": false}."
}
