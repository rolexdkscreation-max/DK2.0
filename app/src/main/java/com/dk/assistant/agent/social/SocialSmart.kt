package com.dk.assistant.agent.social

import org.json.JSONObject

/**
 * 16 नवंबर 2026 (मालिक: "वीडियो देखेगा, वीडियो किस चीज़ पर है, उसके हिसाब से कैप्शन और हैशटैग; गाना बोला तो ही लगाएगा;
 * किस-किस को टैग करना है, वो भी करेगा; और जो काम हुआ / नहीं हुआ, सच बताए"): pure हिस्सा (JVM test).
 * Gemini को क्या पूछना, उसका जवाब कैसे साफ़ करना, कैप्शन + हैशटैग कैसे जोड़ना, आख़िरी सवाल में क्या-क्या बताना.
 */
object SocialSmart {
    const val SONG_AUTO = "auto"
    const val YT_TITLE_MAX = 100

    /** यूज़र ने क्या माँगा (Live tool के parameters से) */
    data class Ask(
        val autoCaption: Boolean,
        /** यूज़र का अपना कैप्शन (हो तो वैसा ही रहे, सिर्फ़ हैशटैग जुड़ें) */
        val captionGiven: String = "",
        /** यूज़र के शब्द / मूड ("दोस्तों के साथ मस्ती", "funny") */
        val hint: String = "",
        /** "" = गाना नहीं, "auto" = मूड के हिसाब से, नहीं तो गाने का नाम */
        val song: String = "",
        val tags: List<String> = emptyList(),
        val language: String = "hinglish",
    )

    data class Out(val about: String, val caption: String, val hashtags: List<String>, val song: String)

    fun maxTags(p: Platform): Int = when (p) { Platform.INSTAGRAM -> 12; Platform.FACEBOOK -> 5; Platform.YOUTUBE -> 3; Platform.WHATSAPP -> 0 }

    fun normSong(s: String?): String {
        val t = s.orEmpty().trim()
        return when (t.lowercase()) {
            "", "none", "no", "false", "नहीं", "no song" -> ""
            "auto", "yes", "true", "any", "koi bhi", "कोई भी", "हाँ", "trending" -> SONG_AUTO
            else -> t.take(80)
        }
    }

    /** "Ravi, Amit और Sonu" -> [Ravi, Amit, Sonu] (@ हटाकर, दोहराव नहीं, 10 तक) */
    fun parseTags(s: String?): List<String> = s.orEmpty()
        .split(Regex("\\s*(,|;|\\n| और | aur | and | & |/)\\s*", RegexOption.IGNORE_CASE))
        .map { it.trim().trimStart('@').trim() }
        .filter { it.length >= 2 }
        .distinctBy { it.lowercase() }
        .take(10)

    fun prompt(p: Platform, a: SocialAction, isVideo: Boolean, frames: Int, ask: Ask): String = buildString {
        val what = when {
            p == Platform.YOUTUBE -> "a YouTube Short"
            a == SocialAction.POST_REEL -> "an Instagram Reel"
            a == SocialAction.POST_STORY -> "a ${p.label} story"
            isVideo -> "a ${p.label} video post"
            else -> "a ${p.label} photo post"
        }
        append("You are a careful social media expert. The user will publish ").append(what).append(". ")
        append(if (isVideo) "The images are $frames frames taken in order from the start to the end of the video. " else "The image is the photo. ")
        append("Look carefully and describe ONLY what is really visible (people, place, activity, food, objects, mood, text written in it). ")
        append("Never invent names, places, brands, events or facts that are not visible or not told by the user. If unsure, stay general. ")
        if (ask.hint.isNotBlank()) append("The user said about it: \"").append(ask.hint.take(300)).append("\". ")
        if (ask.captionGiven.isNotBlank()) append("The user already wrote the caption \"").append(ask.captionGiven.take(500)).append("\" - do NOT change it; caption must be exactly that. ")
        else append("Write one natural, catchy caption (max ${if (p == Platform.YOUTUBE) 70 else 220} characters, 0-2 fitting emojis) in ").append(langName(ask.language)).append(". ")
        append("Give ${maxTags(p)} relevant hashtags that match the content (mix of popular and specific, no spaces, no #fyp spam, no misleading tags). ")
        when {
            ask.song == SONG_AUTO -> append("Also suggest ONE real, well-known song (\"Title - Artist\") whose mood fits this content, that is likely in the Instagram/YouTube music library. ")
            ask.song.isNotBlank() -> append("The user chose the song \"").append(ask.song).append("\" - return it unchanged. ")
        }
        append("Reply ONLY with JSON: {\"about\":\"one short Hindi sentence: what the ${if (isVideo) "video" else "photo"} shows\",\"caption\":\"...\",\"hashtags\":[\"...\"],\"song\":\"\"}")
    }

    private fun langName(l: String) = when (l.trim().lowercase()) {
        "hindi", "हिंदी", "hi" -> "Hindi (Devanagari script)"
        "english", "en", "अंग्रेज़ी" -> "English"
        "marathi", "मराठी" -> "Marathi (Devanagari script)"
        "urdu", "اردو" -> "Urdu"
        else -> "Hinglish (Hindi in Roman letters mixed with English, like Indian creators write)"
    }

    /** Gemini का जवाब (```json ...``` भी) -> साफ़ नतीजा. कुछ काम का नहीं = null */
    fun parse(reply: String?, p: Platform, ask: Ask): Out? {
        val raw = reply.orEmpty()
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val o = try { JSONObject(raw.substring(s, e + 1)) } catch (_: Exception) { return null }
        val tagsRaw = o.optJSONArray("hashtags")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            ?: o.optString("hashtags").split(Regex("[\\s,]+"))
        val caption = if (ask.captionGiven.isNotBlank()) ask.captionGiven.trim() else o.optString("caption").trim().removeSurrounding("\"")
        val song = when { ask.song.isBlank() -> ""; ask.song == SONG_AUTO -> o.optString("song").trim().take(80); else -> ask.song }
        val out = Out(o.optString("about").trim().take(200), caption, cleanTags(tagsRaw, maxTags(p)), song)
        return out.takeIf { it.caption.isNotBlank() || it.hashtags.isNotEmpty() }
    }

    fun cleanTags(raw: List<String>, max: Int): List<String> = raw.asSequence()
        .map { it.trim().trimStart('#').replace(Regex("[^\\p{L}\\p{M}\\p{N}_]"), "") }
        .filter { it.length in 2..40 && !it.all { ch -> ch.isDigit() } }
        .distinctBy { it.lowercase() }
        .take(max).map { "#$it" }.toList()

    /** ऐप में लिखा जाने वाला पूरा text. YouTube = शीर्षक (100 अक्षर तक, #shorts के साथ) */
    fun compose(p: Platform, caption: String, tags: List<String>): String {
        val c = caption.trim()
        if (p == Platform.YOUTUBE) {
            var title = c.take(YT_TITLE_MAX)
            for (t in (listOf("#shorts") + tags).distinctBy { it.lowercase() }) {
                if (title.contains(t, true)) continue
                val next = if (title.isEmpty()) t else "$title $t"
                if (next.length <= YT_TITLE_MAX) title = next
            }
            return title.trim()
        }
        val fresh = tags.filterNot { c.contains(it, true) }
        return when { fresh.isEmpty() -> c; c.isEmpty() -> fresh.joinToString(" "); else -> c + "\n\n" + fresh.joinToString(" ") }
    }

    /** आख़िरी सवाल में यूज़र को सब सुनाओ - क्या चुना, क्या लिखा, गाना, टैग */
    fun summary(media: String, about: String, caption: String, tags: List<String>, song: String, people: List<String>): String = buildString {
        if (media.isNotBlank()) append(media)
        if (about.isNotBlank()) append(" (").append(about.trimEnd('.', '।')).append(")")
        if (caption.isNotBlank()) append(", कैप्शन: \"").append(caption.take(160)).append('"')
        if (tags.isNotEmpty()) append(", हैशटैग: ").append(tags.joinToString(" "))
        if (song.isNotBlank()) append(", गाना: ").append(song)
        if (people.isNotEmpty()) append(", टैग: ").append(people.joinToString(", "))
    }.trim().trimStart(',').trim()
}
