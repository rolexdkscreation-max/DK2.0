package com.dk.assistant.dkai

import java.util.Locale

/**
 * 20 नवंबर 2026 (मालिक): चिप की मॉडल लिस्ट अपने-आप - कंपनी के हिसाब से.
 *
 *  - सिर्फ़ उन कंपनियों के मॉडल जिनकी key ऐप में सेव है ([build] में `providers`).
 *  - हर कंपनी की अपनी model-list API की लाइव सूची (cache, 24 घंटे) - नया मॉडल ऐप अपडेट किए बिना दिखे.
 *  - key का tier: FREE = सिर्फ़ वही जो फ्री में चलते हैं; PAID = सब; UNKNOWN = सब (चलाते समय गलती आए तो साफ़ संदेश).
 *  - कंपनी के नाम से group, हर group में सबसे नया ऊपर. पुराने / deprecated / सिर्फ़ embedding-image-tts वाले छिपे.
 *  - Gemini के आवाज़ वाले (Live / native-audio) मॉडल [Meta.voice] = true - चिप में "खुद आवाज़" के निशान के साथ.
 * Android पर निर्भर नहीं - JUnit.
 */
object ModelCatalog {

    /** कंपनी की सूची का एक मॉडल. [created] = Unix सेकंड (OpenAI-जैसी सूची देती है; Gemini नहीं = 0) */
    data class Meta(val id: String, val created: Long = 0L, val voice: Boolean = false, val deprecated: Boolean = false, val name: String = "")

    enum class Tier { FREE, PAID, UNKNOWN }

    data class Entry(val provider: String, val model: String, val title: String, val sub: String,
                     val paid: Boolean, val supportsVoice: Boolean)

    data class Group(val provider: String, val title: String, val entries: List<Entry>)

    /** group का क्रम (बाकी इसके बाद नाम से) */
    val ORDER = listOf("gemini", "groq", "openrouter", "zai", "nvidia", "anthropic", "openai", "deepseek")

    /** एक कंपनी के ज़्यादा से ज़्यादा इतने मॉडल (OpenRouter जैसी लंबी सूची पूरी स्क्रीन न भरे) */
    const val MAX_EACH = 30

    /** चैट वाले नहीं: embedding / image / tts / सुनने वाले / जाँच वाले ... */
    private val NOT_CHAT = Regex(
        "embed|embedding|imagen|image|dall-e|gpt-image|tts|whisper|transcribe|veo|lyria|moderation|guard|safeguard|rerank|ocr|aqa|" +
            "robotics|computer-use|search-preview|orpheus|playai|distil|compound|-audio-preview|sora|clip|bge|e5-|nv-embed|parse|reward",
        RegexOption.IGNORE_CASE)

    /** Gemini Live / native-audio (आवाज़ वाले) */
    private val VOICE = Regex("native-audio|-live|live-", RegexOption.IGNORE_CASE)

    /** बहुत पुराने परिवार (दिखाने का फ़ायदा नहीं) */
    private val OLD = Regex(
        "^(gpt-3|gpt-4-|gpt-4$|text-|davinci|babbage|curie|ada|claude-(1|2|instant|3-)|chatgpt-4o|o1-preview|o1-mini|" +
            "llama-2|llama2|llama-3-|llama3-|mixtral-8x7b|gemma-[12]|gemma2|deepseek-chat-v2)",
        RegexOption.IGNORE_CASE)

    /** dated snapshot: "-05-20", "-2024-08-06", "-0613", "-preview-09-2025" */
    private val DATED = Regex("-(\\d{2}-\\d{2}|\\d{4}-\\d{2}-\\d{2}|\\d{4}|\\d{2}-\\d{4})$")

    fun norm(m: String) = m.trim().removePrefix("models/")

    /** "gemini-3.8-flash" -> 3.8, "claude-opus-5-5" -> 5.5, "gpt-6.1-sol" -> 6.1 (नया पहले रखने को); न मिले = 0 */
    fun version(m: String): Double {
        val s = norm(m).lowercase(Locale.ROOT).substringAfterLast('/')
        val dotted = Regex("""(\d+)\.(\d+)""").find(s)?.value?.toDoubleOrNull()
        if (dotted != null) return dotted
        // "claude-opus-5-5" / "kimi-k2" जैसे: पहले दो छोटे अंक
        val parts = Regex("""(?<![\d])(\d{1,2})(?:-(\d{1,2}))?(?![\d])""").find(s) ?: return 0.0
        val major = parts.groupValues[1].toDoubleOrNull() ?: return 0.0
        val minor = parts.groupValues[2].toIntOrNull()
        return if (minor != null && minor < 10) major + minor / 10.0 else major
    }

    /** छिपाना है? (चैट नहीं / पुराना / deprecated / बंद). Gemini के आवाज़ वाले नहीं छिपते */
    fun hidden(provider: String, m: Meta, ids: Set<String>, nowSec: Long): Boolean {
        val id = norm(m.id).lowercase(Locale.ROOT)
        if (id.isBlank() || m.deprecated) return true
        val tail = id.substringAfterLast('/')
        if (provider == "gemini" && (m.voice || VOICE.containsMatchIn(id))) {
            // आवाज़ वाले: transcribe / tts नहीं; 2.0 जैसे पुराने नहीं
            return id.contains("transcribe") || id.contains("tts") || version(id) in 0.1..2.4
        }
        if (NOT_CHAT.containsMatchIn(id) || id.contains("realtime") || (id.contains("audio") && provider != "gemini")) return true
        if (id.contains("live") && provider == "gemini") return true
        if (OLD.containsMatchIn(tail)) return true
        if (provider == "gemini") {
            if (DkaiModelList.GONE_GEMINI.contains(id)) return true
            if (id.startsWith("gemini-") && version(id) in 0.1..2.4) return true
            if (id.contains("-exp") || id.contains("learnlm")) return true
        }
        // dated snapshot जिसका बिना-तारीख वाला रूप सूची में है = दोहराव
        val undated = DATED.replace(id, "")
        if (undated != id && undated in ids) return true
        // 3 साल से पुराना (कंपनी ने तारीख दी हो)
        if (m.created in 1..(nowSec - THREE_YEARS_SEC)) return true
        return false
    }

    private const val THREE_YEARS_SEC = 3L * 365 * 24 * 60 * 60

    /** इस tier पर यह मॉडल दिखे? FREE = सिर्फ़ फ्री वाले ([DkaiKit.isFreeModel]; Gemini के आवाज़ वाले फ्री में चलते हैं) */
    fun allowed(provider: String, m: Meta, tier: Tier): Boolean = when (tier) {
        Tier.PAID, Tier.UNKNOWN -> true
        Tier.FREE -> (provider == "gemini" && (m.voice || VOICE.containsMatchIn(m.id))) || DkaiKit.isFreeModel(provider, norm(m.id))
    }

    /** नया पहले: कंपनी ने तारीख दी = उसी से; नहीं = नाम के अंक से; फिर छोटा नाम (मुख्य मॉडल) पहले */
    fun newestFirst(list: List<Meta>): List<Meta> =
        list.sortedWith(compareByDescending<Meta> { it.created }.thenByDescending { version(it.id) }
            .thenBy { if (norm(it.id).contains("preview")) 1 else 0 }.thenBy { it.id.length }.thenBy { it.id })

    fun providerTitle(id: String): String = when (id) {
        "anthropic" -> "Claude (Anthropic)"; "openai" -> "OpenAI"; "deepseek" -> "DeepSeek"
        else -> DkaiKit.providerName(id)
    }

    /**
     * पूरी लिस्ट. [providers] = जिनकी key ऐप में सेव है (बाकी कंपनियाँ नहीं दिखतीं). [lists] = कंपनी -> उसकी सूची
     * (खाली = अभी नहीं आई: तब [fallback] के जाने-माने मॉडल). [name] = साफ़ नाम.
     */
    fun build(providers: List<String>, tier: (String) -> Tier, lists: (String) -> List<Meta>, fallback: (String) -> List<String>,
              name: (Meta) -> String, nowSec: Long = System.currentTimeMillis() / 1000, maxEach: Int = MAX_EACH): List<Group> {
        val ids = providers.distinct().sortedBy { ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
        return ids.mapNotNull { p ->
            val t = tier(p)
            val raw = lists(p).ifEmpty { fallback(p).map { Meta(it) } }
            val seen = raw.map { norm(it.id).lowercase(Locale.ROOT) }.toSet()
            val shown = newestFirst(raw.distinctBy { norm(it.id).lowercase(Locale.ROOT) }
                .filter { !hidden(p, it, seen, nowSec) && allowed(p, it, t) }).take(maxEach)
            if (shown.isEmpty()) return@mapNotNull null
            Group(p, providerTitle(p), shown.map { m ->
                val voice = p == "gemini" && (m.voice || VOICE.containsMatchIn(m.id))
                val free = DkaiKit.isFreeModel(p, norm(m.id)) || (voice && p == "gemini")
                val paid = !free && t != Tier.UNKNOWN
                val price = when { free -> "Free"; paid -> "Paid key"; else -> "Free/Paid?" }
                val kind = if (voice) "खुद आवाज़ (Live)" else com.dk.assistant.ModelNames.desc(m.id)
                Entry(p, norm(m.id), name(m), "$kind • $price", paid = !free, supportsVoice = voice)
            })
        }
    }

    // ------------------------------------------------------------------ cache का रूप (Prefs में एक string)
    fun encode(list: List<Meta>): String = list.joinToString("\n") { m ->
        listOf(m.id, m.created.toString(), if (m.voice) "1" else "0", if (m.deprecated) "1" else "0", m.name.replace('\t', ' ').replace('\n', ' ')).joinToString("\t")
    }

    fun decode(s: String?): List<Meta> = s.orEmpty().lines().mapNotNull { l ->
        val a = l.split('\t')
        val id = a.getOrNull(0)?.trim().orEmpty()
        if (id.isBlank()) null
        else Meta(id, a.getOrNull(1)?.toLongOrNull() ?: 0L, a.getOrNull(2) == "1", a.getOrNull(3) == "1", a.getOrNull(4).orEmpty())
    }

    fun tierOf(s: String?): Tier = when (s) { "free" -> Tier.FREE; "paid" -> Tier.PAID; else -> Tier.UNKNOWN }
    fun tierName(t: Tier): String = when (t) { Tier.FREE -> "free"; Tier.PAID -> "paid"; Tier.UNKNOWN -> "unknown" }

    /**
     * Gemini की key का tier जाँच (paid मॉडल पर 1 token की छोटी कोशिश) का नतीजा: 200 = PAID,
     * 429 + "limit: 0"/free_tier = FREE, बाकी = UNKNOWN.
     */
    fun geminiProbe(code: Int, body: String): Tier {
        val b = body.lowercase(Locale.ROOT)
        return when {
            code in 200..299 -> Tier.PAID
            code == 429 && (b.contains("limit: 0") || b.contains("free_tier") || b.contains("freetier")) -> Tier.FREE
            else -> Tier.UNKNOWN
        }
    }

    /** OpenRouter /api/v1/key का जवाब -> tier */
    fun openRouterTier(json: String): Tier = try {
        val d = org.json.JSONObject(json).optJSONObject("data")
        when {
            d == null || !d.has("is_free_tier") -> Tier.UNKNOWN
            d.optBoolean("is_free_tier", false) -> Tier.FREE
            else -> Tier.PAID
        }
    } catch (_: Exception) { Tier.UNKNOWN }
}
