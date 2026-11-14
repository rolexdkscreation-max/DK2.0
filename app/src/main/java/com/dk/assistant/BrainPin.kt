package com.dk.assistant

import android.content.Context

/**
 * 14 नवंबर 2026 (मालिक): "हर काम (चैट, लाइव, कोडिंग एजेंट, DK कंप्यूटर, वेबसाइट, कहानी) के लिए एक चुनने वाला -
 * AI दिमाग में डली कोई भी key (किसी भी कंपनी की) और उसका कोई भी मॉडल (नया लॉन्च हुआ फ्री वाला भी) चुनूँ.
 * चुना = सिर्फ़ उसी key + उसी मॉडल पर काम, कोई fallback नहीं (नया मॉडल जाँचने के लिए).
 * हटा दिया (खाली, डिफ़ॉल्ट) = पहले जैसा पूरा fallback क्रम."
 *
 * Key सेव नहीं होती - सिर्फ़ उसका पता ([KeyOpt.ref], जैसे "coder:gemini3"). Key हटाई / बदली = वही पता नई key देता है;
 * key खाली = चुना हुआ अपने-आप बेअसर (fallback चलता है).
 */
object BrainPin {
    const val CHAT = "chat"
    const val LIVE = "live"
    const val CODER = "coder"
    const val PC = "pc"
    const val SITE = "site"
    const val STORY = "story"

    val FEATURES = listOf(
        CHAT to "DK चैट (आम जवाब, बिना-Live आवाज़)",
        LIVE to "DK लाइव (आवाज़ वाली बात)",
        CODER to "कोडिंग एजेंट",
        PC to "DK कंप्यूटर",
        SITE to "वेबसाइट बिल्डर",
        STORY to "कहानी (स्टोरी राइटर)"
    )

    fun title(f: String) = FEATURES.firstOrNull { it.first == f }?.second ?: f

    /** इन कामों में सिर्फ़ Gemini चलता है (Gemini Live / वेबसाइट बिल्डर का अपना Gemini client) */
    fun geminiOnly(f: String) = f == LIVE || f == SITE

    data class Pin(val provider: String, val ref: String, val model: String)

    /** [ref] = कहाँ की key (सिर्फ़ पता), [label] = दिखाने को (key के आख़िरी 4 अक्षर ही) */
    data class KeyOpt(val ref: String, val provider: String, val key: String, val label: String)

    fun pref(f: String) = "brain_pin_$f"

    // ------------------------------------------------------------------ pure (JUnit)
    fun encode(p: Pin?): String = if (p == null || p.model.isBlank() || p.ref.isBlank()) "" else "${p.provider}|${p.ref}|${p.model.trim()}"

    fun decode(s: String?): Pin? {
        val a = s.orEmpty().split('|')
        if (a.size < 3 || a.any { it.isBlank() }) return null
        return Pin(a[0], a[1], a.drop(2).joinToString("|"))
    }

    fun tail(key: String) = if (key.length <= 4) "…" else "…" + key.takeLast(4)

    /** सारे slot (पता, कंपनी, key) से: खाली नहीं, एक key दो बार नहीं (पहला पता रहे) */
    fun options(slots: List<Triple<String, String, String>>, names: (String) -> String, where: (String) -> String): List<KeyOpt> {
        val seen = HashSet<String>()
        return slots.mapNotNull { (ref, id, k) ->
            val key = k.trim()
            if (key.isBlank() || !seen.add("$id|$key")) null
            else KeyOpt(ref, id, key, "${names(id)} • ${where(ref)} • ${tail(key)}")
        }
    }

    /** इस काम के लिए कौन-सी keys चुनी जा सकती हैं */
    fun allowed(f: String, opts: List<KeyOpt>): List<KeyOpt> = if (geminiOnly(f)) opts.filter { it.provider == "gemini" } else opts

    /** Settings की पंक्ति का लिखा */
    fun line(f: String, p: Pin?, keyLabel: String?): String =
        if (p == null || keyLabel == null) "अपने-आप (fallback क्रम) — कुछ नहीं चुना"
        else "✓ सिर्फ़ यही: $keyLabel • ${p.model} (fallback बंद)"

    /** पता -> हिस्सा + slot ("coder:gemini3" -> coder, gemini3) */
    fun split(ref: String): Pair<String, String> = ref.substringBefore(':') to ref.substringAfter(':', "")

    fun whereLabel(ref: String): String {
        val (sc, slot) = split(ref)
        val n = slot.removePrefix("gemini").toIntOrNull()
        val base = when (sc) {
            "forge" -> "वेबसाइट बिल्डर"
            "one" -> "एक key से सब"
            else -> ApiScopes.title(sc)
        }
        return if (n != null && n > 1) "$base key $n" else base
    }

    // ------------------------------------------------------------------ फ़ोन पर
    /** AI दिमाग में डली हर key (हर हिस्सा, हर कंपनी, बैकअप keys भी) */
    fun keyOptions(c: Context): List<KeyOpt> {
        val slots = ArrayList<Triple<String, String, String>>()
        slots.add(Triple("one:gemini", "gemini", Prefs.oneKey(c)))
        for (scope in listOf(ApiScopes.CHAT, ApiScopes.LIVE, ApiScopes.CODER, ApiScopes.PC)) for (id in ApiScopes.PROVIDERS) {
            val extra = when {
                id != "gemini" -> emptyList()
                scope == ApiScopes.CODER -> listOf("gemini2", "gemini3", "gemini4", "gemini5")
                scope == ApiScopes.LIVE -> listOf("gemini2", "gemini3")
                else -> emptyList()
            }
            for (slot in listOf(id) + extra) slots.add(Triple("$scope:$slot", id, Prefs.scopeKeyRaw(c, scope, slot)))
        }
        slots.add(Triple("forge:gemini", "gemini", Prefs.forgeRaw(c, Prefs.FORGE_KEY)))
        slots.add(Triple("forge:gemini2", "gemini", Prefs.forgeRaw(c, Prefs.FORGE_KEY2)))
        return options(slots, { Providers.byId(it)?.name ?: it }, ::whereLabel)
    }

    fun keyFor(c: Context, ref: String): String {
        val (sc, slot) = split(ref)
        return when (sc) {
            "one" -> Prefs.oneKey(c)
            "forge" -> Prefs.forgeRaw(c, if (slot == "gemini2") Prefs.FORGE_KEY2 else Prefs.FORGE_KEY)
            else -> Prefs.scopeKeyRaw(c, sc, slot)
        }.trim()
    }

    fun get(c: Context, f: String): Pin? = decode(Prefs.raw(c, pref(f)))

    fun set(c: Context, f: String, p: Pin?) {
        Prefs.edit(c) { putString(pref(f), encode(p)) }
        if (f == LIVE) com.dk.assistant.voice.engine.VoiceEngines.liveChanged()
        DKLog.d("AI दिमाग: ${title(f)} = " + (p?.let { "${it.provider} ${whereLabel(it.ref)} ${it.model}" } ?: "अपने-आप (fallback)"))
    }

    class Resolved(val provider: Provider, val key: String, val model: String, val base: String)

    /** चुना हुआ और उसकी key अभी भी डली = वही; वरना null (= पहले जैसा fallback) */
    fun resolve(c: Context, f: String): Resolved? {
        val p = get(c, f) ?: return null
        val prov = Providers.byId(p.provider) ?: return null
        if (geminiOnly(f) && !prov.isGemini) return null
        val key = keyFor(c, p.ref)
        if (key.isBlank()) return null
        val sc = split(p.ref).first
        val base = if (prov.isGemini) "" else (if (sc in ApiScopes.ALL) Prefs.scopeBase(c, sc, prov.id) else "").ifBlank { prov.baseUrl }
        if (!prov.isGemini && base.isBlank()) return null
        return Resolved(prov, key, p.model, base)
    }

    /** चैट वाला client: सिर्फ़ यही मॉडल (Gemini के बैकअप मॉडल नहीं, OpenAI-जैसे के बैकअप नहीं) */
    fun chatClient(c: Context, f: String = CHAT): Pair<String, AiClient>? {
        val r = resolve(c, f) ?: return null
        val cl: AiClient = if (r.provider.isGemini) GeminiClient(r.key, r.model).apply { only = true; backups = emptyList() }
        else OpenAiCompatClient(r.provider.name, r.base, r.key, r.model).apply { backups = emptyList() }
        return r.provider.id to cl
    }

    /** कोडिंग एजेंट / DK कंप्यूटर: सिर्फ़ यही key + मॉडल, कोई fallback नहीं */
    fun coder(c: Context, f: String, sink: (String, String) -> Unit): CoderAi? {
        val r = resolve(c, f) ?: return null
        return CoderAi(r.provider, r.key, r.model, r.base, emptyList(), note = ", आपका चुना", sink = sink).pinOnly()
            .also { it.waitOnLimit = false }.applyThink(ThinkLevel.get(c, if (f == PC) ApiScopes.PC else ApiScopes.CODER))
    }

    /**
     * चैट का पूरा fallback (मालिक: "जो भी key डाली है सब पर fallback, क्रम से"): DK चैट में इस कंपनी की अपनी key नहीं
     * तो AI दिमाग में कहीं और डली उसी कंपनी की पहली key ([ApiScopes.spareKeys] का क्रम). null = कहीं नहीं.
     */
    fun borrowed(c: Context, p: Provider): AiClient? {
        val spare = ApiScopes.spareKeys({ sc, slot -> Prefs.scopeKeyRaw(c, sc, slot) }, emptySet())
            .firstOrNull { it.second == p.id && it.first != ApiScopes.CHAT } ?: return null
        val (sc, _, key) = spare
        return if (p.isGemini) GeminiClient(key, Prefs.scopeModel(c, ApiScopes.CHAT, p.id).ifBlank { p.defaultModel })
        else {
            val base = Prefs.scopeBase(c, sc, p.id).ifBlank { p.baseUrl }
            val model = Prefs.scopeModel(c, sc, p.id).ifBlank { p.defaultModel }
            if (base.isBlank() || model.isBlank()) null
            else OpenAiCompatClient(p.name, base, key, model).apply { backups = ApiScopes.backupModels(p.id, model, emptyList(), Prefs.models(c, p.id)) }
        }
    }

    /** Live / वेबसाइट का बैकअप: AI दिमाग में कहीं भी डली बाकी Gemini keys (अपनी keys के बाद) */
    fun spareGemini(c: Context, have: List<String>): List<String> {
        val own = have.map { it.trim() }.toSet()
        val all = listOf(Prefs.oneKey(c)) + ApiScopes.spareKeys({ sc, slot -> Prefs.scopeKeyRaw(c, sc, slot) }, emptySet())
            .filter { it.second == "gemini" }.map { it.third } + listOf(Prefs.forgeRaw(c, Prefs.FORGE_KEY), Prefs.forgeRaw(c, Prefs.FORGE_KEY2))
        return all.map { it.trim() }.filter { it.isNotBlank() && it !in own }.distinct()
    }
}
