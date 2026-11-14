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

    const val MAX_AGE_MS = 6L * 60 * 60 * 1000
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

    /** Gemini की key: चैट/एक-key, नहीं तो DK लाइव वाली (वही नियम जो [DkaiSheets.hasKey] में) */
    private fun keyFor(c: Context, id: String): String =
        Prefs.key(c, id).ifBlank { if (id == "gemini") Prefs.liveGeminiKeys(c).firstOrNull().orEmpty() else "" }

    /**
     * शीट खुलते ही (पीछे से): जिन कंपनियों की key है और सूची 6 घंटे से पुरानी - उनकी सूची कंपनी से ताज़ा मँगाओ.
     * true = कोई सूची बदली (शीट फिर से बनाओ). गलती हो तो चुपचाप पुरानी सूची ही रहती है - कुछ नहीं टूटता.
     */
    suspend fun refreshStale(c: Context, now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        for (id in listOf("gemini") + OTHER_PROVIDERS) {
            val p = Providers.byId(id) ?: continue
            val key = keyFor(c, id)
            if (key.isBlank() || !isStale(Prefs.long(c, atPref(id), 0L), now)) continue
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
}
