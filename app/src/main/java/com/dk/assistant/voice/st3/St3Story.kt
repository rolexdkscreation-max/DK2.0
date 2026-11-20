package com.dk.assistant.voice.st3

import java.util.Locale

/**
 * 20 नवंबर 2026 (मालिक: "स्टोरी का ऑडियो... नरेशन के लिए अलग और हर कैरेक्टर के लिए अलग आवाज़ - और जिस भाव में लिखा है
 * उसी तरह बोले: जोर से, चिल्ला के, धीमे से, डरा हुआ, हाँफते हुए..."): फ़ोन वाली आवाज़ (Supertonic 3) से कहानी की डबिंग.
 *
 *  - [assign]: Narrator की अपनी आवाज़; हर किरदार अलग - महिला F1-F5, पुरुष M1-M5 ([St3Config.MALE]). पहले से तय आवाज़
 *    ([keep]) वही रहती है (भाग 1 में रामू की जो, भाग 5 में भी वही). Narrator की आवाज़ किसी किरदार को तभी जब बाकी ख़त्म.
 *  - [mood]: लाइन का अंदाज़ (AI ने लिखा "shouting" / "डरते हुए" ...) -> Supertonic 3 के अपने साधन: रफ़्तार, आवाज़ की
 *    ऊँचाई (gain), `<breath>` / `<sigh>` / `<laugh>` टैग, विराम-चिह्न, और लाइन के बाद ठहराव. मॉडल में अलग "भाव" का
 *    बटन नहीं है - यही असली तरीका है.
 * Android import नहीं -> JVM test ([St3StoryTest]).
 */
object St3Story {
    const val NARRATOR = "Narrator"

    data class Mood(val name: String, val speed: Float = 1f, val gain: Float = 1f, val before: String = "", val after: String = "",
                    val pauseMs: Int = 380, val breathy: Boolean = false, val end: Char? = null,
                    // 20 नवंबर 2026 (मालिक: "डर, चीख, रोना - सुनने में पता नहीं चलता"): असली भाव के साधन ([St3Emotion.perform])
                    /** सुर: semitone (+ ऊपर / - नीचे) */
                    val pitch: Float = 0f,
                    /** काँपना 0..1 और कितनी बार प्रति सेकंड */
                    val shake: Float = 0f, val shakeHz: Float = 6.5f,
                    /** चीख/गुस्से का खिंचाव 0..1 */
                    val strain: Float = 0f,
                    /** फुसफुसाहट */
                    val airy: Boolean = false,
                    /** डर में पहला शब्द अटके ("मु… मुझे") */
                    val stutter: Boolean = false,
                    /** रोने/हाँफने में बात टूटे-टूटे ("," -> "…") */
                    val broken: Boolean = false)

    private fun has(t: String, vararg words: String) = words.any { t.contains(it) }

    /** अंदाज़ -> Mood. पहला मिलने वाला जीतता (क्रम = ज़्यादा ख़ास पहले) */
    fun mood(tone: String, narrator: Boolean = false): Mood {
        val t = tone.lowercase(Locale.ROOT)
        return when {
            has(t, "pant", "breathless", "out of breath", "gasp", "हाँफ", "हांफ", "साँस फूल", "सांस फूल") ->
                Mood("हाँफते हुए", speed = 1.04f, gain = 0.95f, before = "<breath>", pauseMs = 300, breathy = true, pitch = 0.6f, shake = 0.2f, shakeHz = 4.5f, broken = true)
            has(t, "shout", "yell", "scream", "roar", "चिल्ला", "चीख", "दहाड़", "ज़ोर से", "जोर से", "loud") ->
                Mood("चिल्लाकर", speed = 1.1f, gain = 1.7f, pauseMs = 320, end = '!', pitch = 2.2f, strain = 0.65f)
            has(t, "angry", "furious", "enraged", "गुस्स", "क्रोध", "नाराज़") ->
                Mood("गुस्से में", speed = 1.06f, gain = 1.45f, pauseMs = 340, end = '!', pitch = 0.8f, strain = 0.45f)
            has(t, "whisper", "फुसफुस", "कान में") ->
                Mood("फुसफुसाकर", speed = 0.9f, gain = 0.45f, pauseMs = 420, airy = true)
            has(t, "terrified", "horrified", "panic", "trembl", "shiver", "काँप", "कांप", "थरथर") ->
                Mood("काँपते हुए", speed = 1.06f, gain = 0.85f, before = "<breath>", pauseMs = 440, breathy = true, pitch = 2f, shake = 0.9f, shakeHz = 7.5f, stutter = true)
            has(t, "scared", "afraid", "fear", "terrified", "nervous", "panic", "trembl", "डर", "घबरा", "काँप", "कांप", "सहम") ->
                Mood("डरते हुए", speed = 1.05f, gain = 0.8f, before = "<breath>", pauseMs = 420, breathy = true, pitch = 1.6f, shake = 0.65f, shakeHz = 7f, stutter = true)
            has(t, "cry", "crying", "sob", "tear", "रोते", "रो ", "सिसक") ->
                Mood("रोते हुए", speed = 0.86f, gain = 0.8f, before = "<sigh>", pauseMs = 520, pitch = 0.8f, shake = 0.75f, shakeHz = 5f, broken = true)
            has(t, "sad", "grief", "दुख", "दुःख", "उदास", "भारी मन") ->
                Mood("दुख में", speed = 0.88f, gain = 0.8f, before = "<sigh>", pauseMs = 480, pitch = -1.3f, shake = 0.15f, shakeHz = 5f)
            has(t, "sigh", "tired", "exhaust", "weary", "थक", "आह") ->
                Mood("थककर", speed = 0.9f, gain = 0.85f, before = "<sigh>", pauseMs = 440, pitch = -1f)
            has(t, "laugh", "giggl", "chuckl", "हँस", "हंस") ->
                Mood("हँसते हुए", speed = 1.05f, gain = 1.1f, before = "<laugh>", pauseMs = 360, pitch = 1.4f, shake = 0.3f, shakeHz = 5f)
            has(t, "excit", "happy", "joy", "cheer", "proud", "खुश", "उत्साह", "जोश", "गर्व") ->
                Mood("खुशी में", speed = 1.08f, gain = 1.2f, pauseMs = 340, end = '!', pitch = 1.8f, strain = 0.1f)
            has(t, "soft", "gentle", "quiet", "slow", "low voice", "धीम", "धीरे", "प्यार", "नरम", "हल्के") ->
                Mood("धीमे से", speed = 0.93f, gain = 0.7f, pauseMs = 420, pitch = -0.4f)
            has(t, "mysterious", "suspense", "serious", "रहस्य", "गंभीर") ->
                Mood("गंभीर", speed = 0.9f, gain = 0.95f, pauseMs = 520, pitch = -1.2f)
            has(t, "urgent", "hurr", "quick", "fast", "जल्दी") ->
                Mood("जल्दी में", speed = 1.14f, gain = 1.1f, pauseMs = 260, pitch = 0.8f)
            narrator -> Mood("कहानी", speed = 0.97f, pauseMs = 480)
            else -> Mood("सामान्य")
        }
    }

    private val COMMA = Regex("""\s*,\s+""")
    private val SIGNS = Regex("[\u0900-\u0903\u093A-\u094F\u0951-\u0957\u0962\u0963]")

    /**
     * 20 नवंबर 2026: भाव की बात शब्दों में भी - डर = पहला शब्द अटके ("मुझे" -> "मु… मुझे"), रोना/हाँफना = बात टूटे-टूटे
     * ("," -> "…"). [lineText] से पहले. "…" (एक चिह्न) ताकि [lineText] की साँस वाली जगह (". ") न बने.
     */
    fun act(text: String, m: Mood): String {
        var s = text.trim()
        if (s.isEmpty()) return s
        if (m.broken) s = COMMA.replace(s, "… ")
        if (m.stutter) {
            val w = s.substringBefore(' ')
            val first = w.firstOrNull()
            if (w.length >= 2 && first != null && first.isLetter() && !w.startsWith("<")) {
                var end = 1
                while (end < w.length && SIGNS.matches(w[end].toString()) && w[end] != '\u094D') end++
                s = "${w.substring(0, end)}… $s"
            }
        }
        return s
    }

    private val CLAUSE = Regex("""([,;।.!?])\s+""")

    /** लाइन + Mood -> बोलने वाला text: टैग आगे, हाँफने/डरने में बीच-बीच में साँस, चिल्लाने में अंत "!" */
    fun lineText(text: String, m: Mood): String {
        var s = text.trim()
        if (s.isEmpty()) return s
        if (m.breathy) {
            var n = 0
            s = CLAUSE.replace(s) { r -> n++; if (n % 2 == 1) "${r.groupValues[1]} <breath> " else r.value }
        }
        if (m.end != null) {
            val last = s.trimEnd().last()
            s = when {
                last == m.end -> s
                last in ".।" -> s.trimEnd().dropLast(1) + m.end
                last.isLetterOrDigit() -> s + m.end
                else -> s
            }
        }
        if (m.before.isNotEmpty() && !s.startsWith("<")) s = "${m.before} $s"
        if (m.after.isNotEmpty()) s = "$s ${m.after}"
        return s
    }

    /** gain लगाओ; 1 से ऊपर गया तो नरम सीमा (फटे नहीं) */
    fun applyGain(w: FloatArray, gain: Float): FloatArray {
        if (gain == 1f) return w
        val out = FloatArray(w.size)
        for (i in w.indices) {
            val y = w[i] * gain
            val a = kotlin.math.abs(y)
            out[i] = if (a <= 0.85f) y else Math.signum(y) * (0.85f + 0.15f * kotlin.math.tanh(((a - 0.85f) / 0.15f).toDouble()).toFloat())
        }
        return out
    }

    /**
     * कौन किस आवाज़ में. [speakers] = नाम -> महिला? (क्रम = पहली बार कब बोले). Narrator: महिला = F1, पुरुष = M1.
     * किरदार: अपने लिंग की अगली खाली आवाज़ (Narrator वाली सबसे आख़िर में), सब भर गईं = फिर से शुरू से.
     */
    fun assign(speakers: Map<String, Boolean>, keep: Map<String, String> = emptyMap()): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        for ((n, v) in keep) if (n in speakers) { out[n] = v; used += v }
        val narratorFemale = speakers[NARRATOR] ?: true
        if (NARRATOR in speakers && NARRATOR !in out) { val v = if (narratorFemale) "F1" else "M1"; out[NARRATOR] = v; used += v }
        val narratorVoice = out[NARRATOR]
        for ((n, female) in speakers) {
            if (n in out) continue
            val pool = (if (female) St3Config.VOICES else St3Config.MALE).map { it.id }.sortedBy { if (it == narratorVoice) 1 else 0 }
            val v = pool.firstOrNull { it !in used } ?: pool[out.values.count { it in pool } % pool.size]
            out[n] = v; used += v
        }
        return out
    }

    /** स्क्रीन पर दिखने वाला नाम */
    fun voiceName(id: String): String = St3Config.castVoice(id).let { v -> if (v.id == id) "${v.name} ($id)" else id }
}
