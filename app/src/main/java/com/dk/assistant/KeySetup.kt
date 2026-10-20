package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Koi bhi key daalo" - DK khud pehchaanta hai ki key kis company ki hai,
 * us company ke FREE models laata hai, sabse tez wale ko asli test karke chunta hai aur save kar deta hai.
 *  - Jawab (text) ki key  -> setupText()
 *  - Awaaz (sunna) ki key -> setupVoice()  (Groq / Gemini - dono free)
 */
object KeySetup {

    data class Result(val ok: Boolean, val provider: String, val model: String, val ms: Long, val msg: String)

    /** Key ke shuru ke akshar se company */
    fun detect(k0: String): String? {
        val k = k0.trim()
        if (k.length < 16) return null
        return when {
            k.startsWith("AIza") -> "gemini"
            k.startsWith("gsk_") -> "groq"
            k.startsWith("sk-or-") -> "openrouter"
            k.startsWith("xai-") -> "xai"
            k.startsWith("sk-") -> "openai"
            Regex("^[0-9a-fA-F]{32}\\.[A-Za-z0-9]{8,}$").matches(k) -> "zai"
            Regex("^[A-Za-z0-9]{32}$").matches(k) -> "mistral"
            else -> null
        }
    }

    fun name(id: String) = Providers.byId(id)?.name ?: id

    /** Pehchaan na ho to har company se poochho (jo models de de, key usi ki) */
    private suspend fun identify(k: String): String? = coroutineScope {
        val tries = listOf("gemini", "groq", "openrouter", "mistral", "zai").mapNotNull { Providers.byId(it) }.map { p ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(10_000) { try { ModelLister.fetch(p, k, p.baseUrl); p.id } catch (_: Exception) { null } }
            }
        }
        tries.awaitAll().firstOrNull { it != null }
    }

    /** Free + baatcheet wale models, andaze se tez pehle */
    fun candidates(id: String, list: List<String>): List<String> {
        val l = list.map { it.trim() }.filter { it.isNotBlank() }
        val bad = listOf("tts", "image", "live", "embed", "audio", "vision-preview", "guard", "safeguard", "whisper",
            "orpheus", "playai", "compound", "search", "transcribe", "realtime", "deep-research", "computer-use", "robotics")
        val ok = l.filter { m -> bad.none { m.lowercase().contains(it) } }
        fun ver(m: String) = Regex("(\\d+(?:\\.\\d+)?)").find(m)?.value?.toDoubleOrNull() ?: 0.0
        return when (id) {
            "gemini" -> ok.filter { (it.contains("flash") || it.contains("gemma")) && !it.contains("pro") && !it.contains("thinking") }
                .sortedWith(compareBy<String> { when { it.contains("flash-lite") -> 0; it.contains("flash") -> 1; else -> 3 } }
                    .thenByDescending { ver(it) }
                    .thenBy { it.contains("preview") || it.contains("exp") })
                .take(4)
            "groq" -> {
                val pref = listOf("llama-3.1-8b-instant", "openai/gpt-oss-20b", "llama-3.3-70b-versatile",
                    "meta-llama/llama-4-scout-17b-16e-instruct", "openai/gpt-oss-120b", "qwen/qwen3-32b", "moonshotai/kimi-k2-instruct")
                (pref.filter { it in ok } + ok.filter { it !in pref }).take(5)
            }
            "openrouter" -> {
                val free = ok.filter { it.endsWith(":free") }
                val fastWords = listOf("flash", "mini", "small", "nano", "lite", "instant", "fast", "8b", "9b", "7b", "12b")
                (free.sortedBy { m -> if (fastWords.any { m.lowercase().contains(it) }) 0 else 1 }.take(4) + "openrouter/free").distinct()
            }
            "mistral" -> {
                val pref = listOf("mistral-small-latest", "ministral-8b-latest", "ministral-3b-latest", "open-mistral-nemo", "mistral-medium-latest")
                (pref.filter { it in ok }.ifEmpty { pref.take(2) }).take(4)
            }
            "zai" -> ok.filter { it.contains("flash") }.sortedByDescending { ver(it) }.ifEmpty { listOf("glm-4.7-flash", "glm-4.5-flash") }.take(3)
            "openai" -> listOf("gpt-5-nano", "gpt-4.1-nano", "gpt-5-mini", "gpt-4o-mini").filter { l.isEmpty() || it in l }.take(3)
            "xai" -> ok.filter { it.contains("fast") || it.contains("mini") }.ifEmpty { ok }.take(3)
            else -> ok.take(3)
        }
    }

    /** Ek model ko asli sawaal bhejkar naapo: pehla shabd kitni der me aaya (ms). null = nahi chala. */
    private fun timeModel(p: Provider, key: String, model: String): Pair<Long?, Exception?> {
        val c: AiClient = if (p.isGemini) GeminiClient(key, model).apply { fast = true; only = true }
        else OpenAiCompatClient(p.name, p.baseUrl, key, model).apply { fast = true }
        val t0 = System.currentTimeMillis()
        var first = 0L
        return try {
            val out = c.stream("Reply in one very short Hindi sentence.", emptyList(), "नमस्ते, कैसे हो?") {
                if (first == 0L && it.isNotBlank()) first = System.currentTimeMillis()
            }
            if (out.isBlank()) null to AiException(0, "khali jawab") else ((if (first > 0) first else System.currentTimeMillis()) - t0) to null
        } catch (e: Exception) { null to e }
    }

    /** JAWAB (text) wali key: pehchaano -> free models -> sabse tez chuno -> save + chalu */
    suspend fun setupText(ctx: Context, key0: String): Result = withContext(Dispatchers.IO) {
        val key = key0.trim().trim('"', '\'')
        if (key.length < 16) return@withContext Result(false, "", "", 0, "यह key बहुत छोटी है, पूरी key पेस्ट करें।")
        val id = detect(key) ?: identify(key)
            ?: return@withContext Result(false, "", "", 0, "यह key किसी जानी-पहचानी कंपनी की नहीं लगी (या गलत है)।")
        val p = Providers.byId(id) ?: return@withContext Result(false, id, "", 0, "कंपनी नहीं मिली")
        val list = try { ModelLister.fetch(p, key, p.baseUrl) } catch (e: Exception) {
            val code = (e as? AiException)?.code ?: 0
            if (code == 401 || code == 403) return@withContext Result(false, id, "", 0, "${p.name} की key गलत है या बंद है — नई key बनाकर डालें।")
            emptyList()
        }
        val cands = candidates(id, list).ifEmpty { listOf(p.defaultModel) }
        val tests = coroutineScope {
            cands.map { m -> async(Dispatchers.IO) { m to (withTimeoutOrNull(12_000) { timeModel(p, key, m) } ?: (null to AiException(0, "timeout"))) } }.awaitAll()
        }
        // chhote (8b) model thode kam samajhdar - barabar tez ho to bada wala lo
        fun weight(m: String) = if (m.contains("8b") || m.contains("3b") || m.contains("nano")) 1.35 else 1.0
        val good = tests.filter { it.second.first != null }.sortedBy { it.second.first!! * weight(it.first) }
        if (good.isEmpty()) {
            val e = tests.firstNotNullOfOrNull { it.second.second } ?: AiException(0, "fail")
            return@withContext Result(false, id, "", 0, "${p.name} की key से कोई फ्री मॉडल नहीं चला: ${ProviderHealth.short(e)}।")
        }
        val best = good.first().first; val ms = good.first().second.first!!
        Prefs.edit(ctx) {
            putString("key_$id", key)
            putString("model_$id", best)
            putString("fast_$id", good.map { it.first }.joinToString("\n"))
            if (list.isNotEmpty()) putString("models_$id", list.joinToString("\n"))
            putString("active_provider", id)
            putBoolean("fallback", true)
        }
        ProviderHealth.ok(id, ms)
        val paid = if (!p.free) " (ध्यान दें: यह कंपनी पेड है)" else ""
        Result(true, id, best, ms, "✓ ${p.name} की key जुड़ गई$paid • सबसे तेज़ मॉडल: $best (${"%.1f".format(ms / 1000.0)} सेकंड) • ${good.size} मॉडल चले")
    }

    /** AAWAZ wali key (sunna): sirf Groq (Whisper) ya Gemini - dono free */
    suspend fun setupVoice(ctx: Context, key0: String): Result = withContext(Dispatchers.IO) {
        val key = key0.trim().trim('"', '\'')
        if (key.isBlank()) {
            Prefs.edit(ctx) { putString("voice_key", ""); putString("voice_provider", "") }
            return@withContext Result(true, "", "", 0, "आवाज़ वाली अलग key हटा दी — अब जवाब वाली key से सुनेगा।")
        }
        val id = detect(key) ?: identify(key)
        if (id != "groq" && id != "gemini")
            return@withContext Result(false, id ?: "", "", 0, "आवाज़ (सुनने) के लिए Groq या Gemini की key चाहिए — दोनों फ्री हैं। Groq सबसे तेज़ है।")
        val p = Providers.byId(id)!!
        val list = try { ModelLister.fetchRaw(p, key, p.baseUrl) } catch (e: Exception) {
            return@withContext Result(false, id, "", 0, "${p.name} की key नहीं चली: ${ProviderHealth.short(e)}।")
        }
        if (id == "groq" && list.none { it.contains("whisper") })
            return@withContext Result(false, id, "", 0, "इस Groq key पर सुनने वाला (Whisper) मॉडल नहीं मिला।")
        Prefs.edit(ctx) {
            putString("voice_key", key); putString("voice_provider", id)
            putString("listen_engine", "auto")
        }
        val what = if (id == "groq") "Groq Whisper से सुनेगा (सबसे तेज़)" else "Gemini से सुनेगा, और Gemini आवाज़ भी इसी key से"
        Result(true, id, "", 0, "✓ आवाज़ के लिए अलग ${p.name} key जुड़ गई • $what • अब जवाब वाली key से टकराव नहीं होगा")
    }
}
