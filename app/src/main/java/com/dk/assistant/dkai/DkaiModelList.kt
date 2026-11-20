package com.dk.assistant.dkai

import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.ModelLister
import com.dk.assistant.Prefs
import com.dk.assistant.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 14 नवंबर 2026 (मालिक): "जेमिनी 3.6 / 3.5 Flash Lite जैसे फ्री मॉडल वहाँ क्यों नहीं दिखते? जो भी फ्री मॉडल है, और नया आए तो
 * अपने-आप उसमें आ जाए. AI दिमाग में जितनी कंपनियों की key है (Z.ai, NVIDIA, Groq, OpenRouter...) सबके फ्री मॉडल दिखें."
 *
 * वजह (पहले): होम का मॉडल शीट Gemini के लिए सिर्फ़ 4 तय कार्ड दिखाता था (Google की असली सूची देखता ही नहीं था),
 * और वो सूची (cache) सिर्फ़ Settings खोलने पर बनती थी - इसलिए नया मॉडल कभी अपने-आप नहीं आता था. दूसरी कंपनियों के 5 तक.
 *
 * अब यह फ़ाइल:
 *  - [extraGemini]: जाने-माने फ्री Gemini मॉडल + Google की ताज़ा सूची के सारे फ्री मॉडल (कार्ड वाले दोबारा नहीं)
 *  - [ordered]: बाकी कंपनियों के लिए जाने-माने तेज़ मॉडल पहले, फिर उसकी ताज़ा सूची
 *  - [refreshStale]: शीट खुलते ही पीछे से हर उस कंपनी की सूची ताज़ा जिसकी key है (6 घंटे में एक बार) - नया मॉडल अपने-आप
 * Pure हिस्से ([extraGemini], [ordered], [isStale]) JUnit से जाँचे जाते हैं.
 */
object DkaiModelList {

    /** हर कंपनी के ज़्यादा से ज़्यादा इतने मॉडल (पहले 5 थे) */
    const val MAX_EACH = 12

    /** Gemini के अलावा जिनके फ्री मॉडल होम पर दिखते हैं (key डली हो तब) */
    val OTHER_PROVIDERS = listOf("groq", "openrouter", "zai", "nvidia")

    /**
     * Google के free tier वाले जाने-माने मॉडल (ai.google.dev/gemini-api/docs/pricing, 14 नवंबर 2026).
     * ताज़ा सूची न मिले तब भी ये दिखें. 3.6 Flash 19 नवंबर 2026 को बंद होगा - तब Google की सूची से अपने-आप हट जाएगा.
     */
    val KNOWN_FREE_GEMINI = listOf("gemini-3.6-flash", "gemini-3-flash-preview")

    /** बंद / दूसरे मॉडल पर भेजे जा चुके (दिखाने का फ़ायदा नहीं): 3.5 Flash अब Google खुद 3.6 Flash पर भेजता है */
    val GONE_GEMINI = setOf("gemini-3.5-flash")

    /** 20 नवंबर 2026 (मालिक): सूची हर 24 घंटे में ताज़ा (या Settings → AI दिमाग → "मॉडल लिस्ट अभी ताज़ा करें") */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private fun atPref(id: String) = "models_at_$id"

    private fun norm(m: String) = m.trim().removePrefix("models/").lowercase(Locale.ROOT)

    /** "gemini-3.8-flash" -> 3.8 (नया पहले रखने के लिए); न मिले = 0 */
    fun version(m: String): Double = Regex("""(\d+(?:\.\d+)?)""").find(norm(m).removePrefix("gemini-").removePrefix("gemma-"))
        ?.value?.toDoubleOrNull() ?: 0.0

    /** कार्ड के बाद दिखने वाले Gemini फ्री मॉडल: जाने-माने + ताज़ा सूची, नया पहले, [have] वाले नहीं */
    fun extraGemini(cached: List<String>, have: Set<String>): List<String> {
        val seen = have.map { norm(it) }.toHashSet()
        val live = cached.map { norm(it) }.filter { it.isNotBlank() && DkaiKit.isFreeModel("gemini", it) && it !in GONE_GEMINI }
        val gemini = live.filter { it.startsWith("gemini-") }.sortedWith(compareByDescending<String> { version(it) }.thenBy { it.length })
        val gemma = live.filter { it.startsWith("gemma-") }.sortedByDescending { version(it) }
        return (KNOWN_FREE_GEMINI + gemini + gemma).filter { seen.add(it) }
    }

    /** दूसरी कंपनियाँ: जाने-माने (fallback) पहले, फिर ताज़ा सूची - दोहराव नहीं */
    fun ordered(fallback: List<String>, cached: List<String>): List<String> =
        (fallback + cached).map { it.trim() }.filter { it.isNotBlank() }.distinct()

    fun isStale(savedAt: Long, now: Long, maxAgeMs: Long = MAX_AGE_MS) = savedAt <= 0L || now - savedAt > maxAgeMs

    /**
     * 15 नवंबर 2026 (मालिक: कोडिंग एजेंट हर कंपनी के कोडिंग वाले सबसे अच्छे मॉडल पर): कोडिंग शुरू होते ही पीछे से
     * Groq / OpenRouter / Z.ai / NVIDIA की ताज़ा सूची (कोई भी डली key - कोडिंग / कंप्यूटर / चैट), 6 घंटे में एक बार. काम नहीं रुकता.
     */
    fun refreshForCoder(c: Context, keyOf: (String) -> String) {
        val now = System.currentTimeMillis()
        val due = OTHER_PROVIDERS.mapNotNull { id -> keyOf(id).takeIf { it.isNotBlank() && isStale(Prefs.long(c, atPref(id), 0L), now) }?.let { id to it } }
        if (android.os.Build.FINGERPRINT == "robolectric") return   // JUnit में नेटवर्क नहीं
        if (due.isEmpty() || !busy.compareAndSet(false, true)) return
        Thread {
            try {
                for ((id, key) in due) {
                    val p = Providers.byId(id) ?: continue
                    try {
                        val list = kotlinx.coroutines.runBlocking { ModelLister.fetch(p, key, Prefs.baseUrl(c, id).ifBlank { p.baseUrl }) }
                        if (list.isNotEmpty()) Prefs.edit(c) { putString("models_$id", list.joinToString("\n")); putLong(atPref(id), now) }
                    } catch (e: Exception) {
                        Prefs.edit(c) { putLong(atPref(id), now - MAX_AGE_MS + 60L * 60 * 1000) }
                        DKLog.d("कोडिंग: मॉडल सूची ($id) नहीं मिली: ${e.message?.take(60)}")
                    }
                }
            } finally { busy.set(false) }
        }.apply { isDaemon = true; name = "dk-coder-models" }.start()
    }
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Gemini की key: चैट/एक-key, नहीं तो DK लाइव वाली (वही नियम जो [DkaiSheets.hasKey] में) */
    private fun keyFor(c: Context, id: String): String =
        Prefs.key(c, id).ifBlank { if (id == "gemini") Prefs.liveGeminiKeys(c).firstOrNull().orEmpty() else "" }

    /**
     * शीट खुलते ही (पीछे से): जिन कंपनियों की key है और सूची 6 घंटे से पुरानी - उनकी सूची कंपनी से ताज़ा मँगाओ.
     * true = कोई सूची बदली (शीट फिर से बनाओ). गलती हो तो चुपचाप पुरानी सूची ही रहती है - कुछ नहीं टूटता.
     */
    suspend fun refreshStale(c: Context, now: Long = System.currentTimeMillis(), force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        for (id in listOf("gemini") + OTHER_PROVIDERS) {
            val p = Providers.byId(id) ?: continue
            val key = keyFor(c, id)
            if (key.isBlank() || (!force && !isStale(Prefs.long(c, atPref(id), 0L), now))) continue
            try {
                val list = ModelLister.fetch(p, key, Prefs.baseUrl(c, id))
                if (list.isEmpty()) continue
                val old = Prefs.models(c, id)
                Prefs.edit(c) { putString("models_$id", list.joinToString("\n")); putLong(atPref(id), now) }
                if (old != list) { changed = true; DKLog.d("मॉडल सूची ताज़ा: $id (${list.size})") }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                // अगली बार फिर कोशिश (पर तुरंत बार-बार नहीं - 1 घंटे बाद)
                Prefs.edit(c) { putLong(atPref(id), now - MAX_AGE_MS + 60L * 60 * 1000) }
                DKLog.d("मॉडल सूची ($id) नहीं मिली: ${e.message?.take(60)}")
            }
        }
        changed
    }

    // ================================================================== 20 नवंबर 2026: चिप की पूरी लिस्ट ([ModelCatalog])
    private fun catPref(id: String) = "catalog_$id"
    private fun catAtPref(id: String) = "catalog_at_$id"
    private fun tierPref(id: String) = "key_tier_$id"
    /** tier किस key का है (आख़िरी 6 अक्षर) - key बदली = फिर से जाँच */
    private fun tierKeyPref(id: String) = "key_tier_key_$id"

    /** चिप की लिस्ट में जिन कंपनियों के मॉडल आ सकते हैं (key हो तब) */
    val CATALOG_PROVIDERS = listOf("gemini") + OTHER_PROVIDERS + listOf("anthropic", "openai", "deepseek")

    /** इन कंपनियों की key ऐप में सेव है (Gemini: एक-key / चैट / DK लाइव; मज़बूत मॉडल वाली key भी) */
    fun keyedProviders(c: Context): List<String> = CATALOG_PROVIDERS.filter { id ->
        when {
            id == "gemini" -> DkaiSheets.hasKey(c, id)
            Providers.byId(id) != null -> DkaiSheets.hasKey(c, id)
            else -> !Prefs.oneKeyOn(c) && SelectedModel.strong(c)?.provider?.id == id
        }
    }

    private fun providerOf(c: Context, id: String): com.dk.assistant.Provider? =
        Providers.byId(id) ?: SelectedModel.strong(c)?.provider?.takeIf { it.id == id }

    fun catalog(c: Context, id: String): List<ModelCatalog.Meta> = ModelCatalog.decode(Prefs.str(c, catPref(id), ""))

    /** key का tier: Gemini में "मेरी key paid है" चालू = PAID; Groq/NVIDIA = सब मॉडल एक जैसे (PAID); मज़बूत (Claude...) = PAID */
    fun tier(c: Context, id: String): ModelCatalog.Tier {
        if (id == "gemini" && Prefs.bool(c, DkaiKit.PREF_GEMINI_PAID, false)) return ModelCatalog.Tier.PAID
        if (id == "groq" || id == "nvidia" || id in listOf("anthropic", "openai", "deepseek")) return ModelCatalog.Tier.PAID
        val key = SelectedModel.keyFor(c, id)
        if (Prefs.str(c, tierKeyPref(id), "") != key.takeLast(6)) return ModelCatalog.Tier.UNKNOWN
        return ModelCatalog.tierOf(Prefs.str(c, tierPref(id), ""))
    }

    /** चिप की लिस्ट (group, सबसे नया ऊपर) - cache से; अभी cache नहीं = जाने-माने मॉडल */
    fun groups(c: Context): List<ModelCatalog.Group> = ModelCatalog.onlyFree(allGroups(c), Prefs.bool(c, DkaiKit.PREF_GEMINI_PAID, false))

    /** 20 नवंबर 2026: पूरी लिस्ट (free + paid) - चिप [groups] इसमें से सिर्फ़ फ्री दिखाता है */
    fun allGroups(c: Context): List<ModelCatalog.Group> = ModelCatalog.build(
        providers = keyedProviders(c), tier = { tier(c, it) },
        lists = { id -> catalog(c, id).ifEmpty { Prefs.models(c, id).map { ModelCatalog.Meta(it) } } },
        fallback = { id -> Providers.fallbackModels(id).ifEmpty { if (id == "gemini") KNOWN_FREE_GEMINI + DkaiKit.MODEL_CARDS.mapNotNull { it.model } else listOfNotNull(SelectedModel.strong(c)?.takeIf { s -> s.provider.id == id }?.model) } },
        name = { m -> com.dk.assistant.ModelNames.label(m.id, m.name.ifBlank { ModelLister.names[m.id] }) })

    /**
     * हर कंपनी (जिसकी key है) की लाइव सूची + key का tier. [force] = Settings का refresh बटन (24 घंटे का इंतज़ार नहीं).
     * true = कुछ बदला. गलती = पुरानी सूची ही रहती है, 1 घंटे बाद फिर कोशिश.
     */
    suspend fun refreshCatalog(c: Context, force: Boolean = false, now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        for (id in keyedProviders(c)) {
            val p = providerOf(c, id) ?: continue
            val key = SelectedModel.keyFor(c, id)
            if (key.isBlank() || (!force && !isStale(Prefs.long(c, catAtPref(id), 0L), now))) continue
            try {
                val base = if (p.isGemini) "" else Prefs.baseUrl(c, id).ifBlank { p.baseUrl }
                val list = ModelLister.catalog(p, key, base)
                if (list.isEmpty()) continue
                val old = Prefs.str(c, catPref(id), "")
                val enc = ModelCatalog.encode(list)
                Prefs.edit(c) { putString(catPref(id), enc); putLong(catAtPref(id), now) }
                if (old != enc) { changed = true; DKLog.d("चिप: मॉडल सूची ताज़ा $id (${list.size})") }
                val t = detectTier(id, key, list)
                if (t != null) Prefs.edit(c) { putString(tierPref(id), ModelCatalog.tierName(t)); putString(tierKeyPref(id), key.takeLast(6)) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                Prefs.edit(c) { putLong(catAtPref(id), now - MAX_AGE_MS + 60L * 60 * 1000) }
                DKLog.d("चिप: मॉडल सूची ($id) नहीं मिली: ${e.message?.take(60)}")
            }
        }
        // पुरानी (होम/कोडिंग) सूची भी उसी समय
        try { if (refreshStale(c, now, force)) changed = true } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { DKLog.d("सूची: ${e.message?.take(60)}") }
        changed
    }

    /** key का tier पता करो. null = नहीं जाँचा (पहले जैसा रहे) */
    private fun detectTier(id: String, key: String, list: List<ModelCatalog.Meta>): ModelCatalog.Tier? = try {
        when (id) {
            "openrouter" -> ModelCatalog.openRouterTier(ModelLister.openRouterKeyInfo(key))
            "gemini" -> {
                // paid मॉडल (Pro) पर 1 token: free key = 429 "limit: 0"
                val pro = list.map { it.id }.firstOrNull { m -> !m.contains("live") && !m.contains("audio") && m.startsWith("gemini-") && m.contains("pro") && !DkaiKit.isFreeModel("gemini", m) }
                if (pro == null) null else ModelLister.geminiProbe(key, pro).let { (code, body) -> ModelCatalog.geminiProbe(code, body) }
            }
            else -> null
        }
    } catch (e: Exception) { DKLog.d("tier ($id): ${e.message?.take(60)}"); null }
}
