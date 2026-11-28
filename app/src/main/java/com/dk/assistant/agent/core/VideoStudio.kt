package com.dk.assistant.agent.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * 28 नवंबर 2026 (मालिक): "DK, Gemini पर जाओ (या यह वेबसाइट खोलो), मेरी कहानी का 1 मिनट का 2D / 3D / इमोशनल वीडियो बनाओ" -
 * DK स्टूडियो का pure हिस्सा (JUnit से जाँचा जाता है).
 *
 * कहानी -> किरदारों की पक्की पहचान (हर भाग में शब्दशः वही, ताकि चेहरा न बदले) -> 8-8 सेकंड के भाग ->
 * हर भाग का prompt ("पिछला भाग जहाँ खत्म हुआ, वहीं से"). वीडियो बनवाना Gemini ऐप / वेबसाइट में होता है (Android हिस्सा).
 */
data class VideoChar(val name: String, val look: String)

data class VideoPart(val n: Int, val seconds: Int, val scene: String, val endsWith: String = "")

data class VideoPlan(
    val title: String,
    val style: String,
    val chars: List<VideoChar>,
    val parts: List<VideoPart>,
    val story: String = "",
) {
    val totalSeconds get() = parts.sumOf { it.seconds }

    fun toJson(): JSONObject = JSONObject().put("title", title).put("style", style).put("story", story)
        .put("characters", JSONArray().also { a -> chars.forEach { a.put(JSONObject().put("name", it.name).put("look", it.look)) } })
        .put("parts", JSONArray().also { a -> parts.forEach { a.put(JSONObject().put("n", it.n).put("seconds", it.seconds).put("scene", it.scene).put("ends_with", it.endsWith)) } })
}

object VideoStudio {
    /** Gemini ऐप (Pro) एक बार में 3-10 सेकंड बनाता है - 8 सेकंड का भाग सबसे भरोसेमंद */
    const val CLIP_SEC = 8
    const val MIN_SEC = 8
    /** एक बार में 3 मिनट तक (ज़्यादा = Gemini की limit जल्दी खत्म) */
    const val MAX_SEC = 180
    const val DEFAULT_SEC = 60
    const val MAX_CHARS = 3

    /** pure: कितने सेकंड का वीडियो - सीधा बताया / मिनट / बोली में ("1 मिनट", "डेढ़ मिनट", "30 सेकंड", "2 minute") */
    fun seconds(said: Int, minutes: Double, words: String): Int {
        val raw = when {
            said > 0 -> said
            minutes > 0 -> (minutes * 60).toInt()
            else -> fromWords(words) ?: DEFAULT_SEC
        }
        return raw.coerceIn(MIN_SEC, MAX_SEC)
    }

    private fun fromWords(w: String): Int? {
        val t = w.lowercase()
        if (Regex("डेढ़|डेढ|dedh|1\\.5\\s*(min|मिनट)").containsMatchIn(t)) return 90
        if (Regex("ढाई|dhai|2\\.5\\s*(min|मिनट)").containsMatchIn(t)) return 150
        if (Regex("आधा मिनट|आधे मिनट|half (a )?minute").containsMatchIn(t)) return 30
        Regex("(\\d{1,3})\\s*(सेकंड|सेकेंड|second|sec|s\\b)").find(t)?.let { return it.groupValues[1].toInt() }
        Regex("(\\d{1,2})\\s*(मिनट|minute|min)").find(t)?.let { return it.groupValues[1].toInt() * 60 }
        val num = mapOf("एक" to 1, "दो" to 2, "तीन" to 3, "one" to 1, "two" to 2, "three" to 3)
        Regex("(एक|दो|तीन|one|two|three)\\s*(मिनट|minute)").find(t)?.let { return (num[it.groupValues[1]] ?: 1) * 60 }
        return null
    }

    /** pure: भाग कितने, हर एक कितना लंबा (आख़िरी छोटा हो तो भी कम से कम 4 सेकंड) */
    fun split(total: Int, clip: Int = CLIP_SEC): List<Int> {
        val n = ceil(total / clip.toDouble()).toInt().coerceAtLeast(1)
        val out = MutableList(n) { clip }
        val last = total - clip * (n - 1)
        out[n - 1] = last.coerceIn(4, clip)
        return out
    }

    /** pure: बोली से अंदाज़ (2D / 3D / इमोशनल / असली जैसा). कुछ न कहा = अच्छा 2D कार्टून */
    fun style(words: String): String {
        val t = words.lowercase()
        val parts = mutableListOf<String>()
        when {
            Regex("3d|थ्री ?डी|3 डी|pixar|पिक्सार").containsMatchIn(t) -> parts += "high quality 3D animated film style (Pixar-like), soft lighting, expressive faces"
            Regex("real|असली|रियल|cinematic|सिनेमैटिक|फ़िल्म जैसा|फिल्म जैसा").containsMatchIn(t) -> parts += "cinematic realistic live-action film look, natural lighting"
            Regex("anime|एनीमे|ऐनिमे").containsMatchIn(t) -> parts += "Japanese anime style 2D animation, clean line art"
            else -> parts += "clean, colourful 2D cartoon animation style (smooth hand-drawn look, flat colours, clear outlines)"
        }
        if (Regex("emotional|इमोशनल|भावुक|दुख|sad|रुला").containsMatchIn(t)) parts += "emotional, heartfelt mood, gentle background music"
        if (Regex("funny|कॉमेडी|comedy|मज़ेदार|मजेदार").containsMatchIn(t)) parts += "light, funny comedy mood"
        if (Regex("बच्चों|kids|बच्चे").containsMatchIn(t)) parts += "child-friendly"
        return parts.joinToString(", ")
    }

    /** pure: कहानी से पूरा plan बनवाने का सवाल (DK की अपनी AI को - JSON जवाब) */
    fun planAsk(story: String, total: Int, style: String, charsWanted: Int = 0): String {
        val lens = split(total)
        return """You are a film director planning a ${total}-second animated video that will be generated in ${lens.size} separate clips of about $CLIP_SEC seconds each (an AI video generator makes one clip at a time).
The user's idea / story (may be Hindi): "$story"
Visual style: $style.

Rules:
- If the idea is short, invent a good, simple, touching story from it with a clear beginning, middle and end.
- Use ${if (charsWanted in 1..MAX_CHARS) "exactly $charsWanted" else "1 to $MAX_CHARS"} main characters only. For each give a FIXED appearance sheet in English (age, gender, face shape, skin tone, hair style + colour, eye colour, body, exact clothes + colours, one unique detail). This sheet will be repeated word for word in every clip so the face never changes.
- Split the story into exactly ${lens.size} consecutive clips with these lengths in seconds: ${lens.joinToString(", ")}.
- Each clip's "scene" (English, 35-70 words): who is there, exact action and camera, setting, light, and any short spoken line in Hindi in quotes. One clear action per clip.
- Each clip must START exactly where the previous clip ENDED (same place, same pose, same clothes, same light) - continuous story, no jumps. "ends_with" = one sentence describing the very last frame of that clip.
- No text, subtitles or logos on screen.
Return ONLY JSON: {"title":"short Hindi title","characters":[{"name":"...","look":"..."}],"parts":[{"n":1,"seconds":8,"scene":"...","ends_with":"..."}]}"""
    }

    /** pure: AI का JSON -> plan. टूटा/अधूरा = जो मिला उसे भरकर, कुछ नहीं = [fallback] */
    fun parsePlan(text: String?, story: String, total: Int, style: String): VideoPlan {
        val lens = split(total)
        val o = jsonIn(text) ?: return fallback(story, total, style)
        val chars = o.optJSONArray("characters")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { c ->
                val n = c.optString("name").trim(); val l = c.optString("look").trim()
                if (n.isBlank() || l.length < 10) null else VideoChar(n.take(40), l.take(400))
            }
        }.orEmpty().take(MAX_CHARS)
        val raw = o.optJSONArray("parts")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .map { it.optString("scene").trim() to it.optString("ends_with").trim() }.filter { it.first.length >= 10 }
        if (raw.isEmpty()) return fallback(story, total, style).copy(chars = chars.ifEmpty { fallback(story, total, style).chars })
        // भाग कम आए = आख़िरी वाला आगे बढ़ाओ; ज़्यादा = काट दो - कुल लंबाई वही जो मालिक ने कही
        val parts = lens.mapIndexed { i, s ->
            val (scene, end) = raw.getOrNull(i) ?: (raw.last().first + " The story continues naturally from the previous moment toward its ending." to "")
            VideoPart(i + 1, s, scene.take(700), end.take(250))
        }
        return VideoPlan(o.optString("title").trim().ifBlank { story.take(40) }.take(60), style,
            chars.ifEmpty { listOf(VideoChar("Main character", "the main character of the story, same face, hair and clothes in every shot")) }, parts, story)
    }

    /** AI न चली = कहानी को सीधे भागों में (किरदार एक, कहानी वही) */
    fun fallback(story: String, total: Int, style: String): VideoPlan {
        val lens = split(total)
        val beats = listOf("opening: introduce the character and the place", "the problem begins", "the character tries hard", "an emotional turning point",
            "things get better", "the happy, touching ending")
        val parts = lens.mapIndexed { i, s ->
            val beat = beats[(i * beats.size / lens.size).coerceAtMost(beats.size - 1)]
            VideoPart(i + 1, s, "Story: $story. This clip ($beat), clip ${i + 1} of ${lens.size}.")
        }
        return VideoPlan(story.take(40), style, listOf(VideoChar("Main character", "the main character of this story, with the same face, hair and clothes in every clip")), parts, story)
    }

    private fun jsonIn(t: String?): JSONObject? {
        val s = t?.trim().orEmpty().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (s.isEmpty()) return null
        return try { JSONObject(s) } catch (_: Exception) {
            val a = s.indexOf('{'); val b = s.lastIndexOf('}')
            if (a < 0 || b <= a) null else try { JSONObject(s.substring(a, b + 1)) } catch (_: Exception) { null }
        }
    }

    /** किरदारों की पक्की पहचान - हर भाग में शब्दशः यही (चेहरा न बदले) */
    fun sheet(plan: VideoPlan): String = plan.chars.joinToString(" ") { "${it.name}: ${it.look}." }

    /**
     * pure: भाग [i] (0 से) का prompt. पहला = पूरा परिचय; अगले = उसी chat में "पिछला वीडियो जहाँ खत्म हुआ, वहीं से".
     * हर भाग में वही किरदार-पत्री + वही अंदाज़ - Gemini उसी बातचीत में किरदार याद भी रखता है.
     */
    fun partPrompt(plan: VideoPlan, i: Int, sameChat: Boolean = true): String {
        val p = plan.parts[i]
        val n = plan.parts.size
        val keep = "Keep the characters EXACTLY the same in every clip (same face, hair, body, clothes, colours): ${sheet(plan)}"
        val head = if (i == 0 || !sameChat) "Create a video" else "Now create the NEXT video"
        val cont = when {
            i == 0 -> ""
            else -> " It must start exactly where the previous clip ended" +
                (plan.parts[i - 1].endsWith.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + " - same place, same characters, same light, continuous action."
        }
        return "$head (${p.seconds} seconds, 16:9, clip ${i + 1} of $n of one continuous story \"${plan.title}\")." +
            " Style: ${plan.style}.$cont $keep Scene: ${p.scene} No on-screen text or subtitles."
    }

    /** pure: वेबसाइट वाले एजेंट का एक भाग का लक्ष्य (किसी भी वीडियो साइट पर) */
    fun webGoal(plan: VideoPlan, i: Int, site: String): String =
        "On the video-generation website \"$site\" that is open in the browser: make ONE new video of about ${plan.parts[i].seconds} seconds " +
            "(16:9 if there is a choice) using EXACTLY this prompt (paste it into the site's prompt box): \"${partPrompt(plan, i, sameChat = false)}\". " +
            "Then start the generation, wait until the video is fully ready, and DOWNLOAD it to the phone."

    /** हर भाग का छोटा हाल (task_status / island) */
    fun stepLine(i: Int, n: Int, what: String) = "भाग ${i + 1}/$n: $what"

    /** pure: नतीजे की हिंदी पंक्ति (DK लाइव बोलकर बताए) */
    fun doneLine(plan: VideoPlan, made: Int, joined: Boolean, where: String, stopWhy: String): String = buildString {
        val n = plan.parts.size
        if (made == n) append("वीडियो \"${plan.title}\" तैयार है - $n भाग, करीब ${plan.totalSeconds} सेकंड।")
        else append("\"${plan.title}\" के $n में से $made भाग बने।")
        if (made > 0) append(if (joined) " सारे भाग जोड़कर एक वीडियो गैलरी में सेव किया ($where)।" else " भाग अलग-अलग गैलरी में सेव हैं ($where)।")
        if (stopWhy.isNotBlank()) append(" रुकने की वजह: $stopWhy")
    }
}
