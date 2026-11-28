package com.dk.assistant

import android.content.Context
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 28 नवंबर 2026 (मालिक): "One AI API Key" (oneaiapikey.com) - OpenAI जैसी बीच की सेवा, एक key से कई कंपनियों के मॉडल.
 *
 * ऐप में क्रम ([AiRouter.client]): उस काम की अपनी key → बाकी डली keys → **ऐप की One AI key (यही)** → DK सर्वर
 * (सर्वर की अपनी श्रृंखला में भी One AI है). हर गड़बड़ी (429 / 5xx / समय खत्म / खाली जवाब / 4 सेकंड में पहला शब्द नहीं)
 * पर चुपचाप अगला मॉडल / अगली key / अगली कड़ी - user को कभी गलती नहीं दिखती.
 *
 * जान-बूझकर [Providers.all] / [ApiScopes.PROVIDERS] में नहीं जोड़ा - वे सूचियाँ पुराने काम (रेडियो बटन, key का क्रम) तय करती हैं.
 * One AI अपनी अलग कड़ी है. Keys उसी ताले वाली परत ([SecretPrefs]) में जिसमें Gemini की ("key_oneai", "live_key_oneai" …).
 */
object OneAi {
    const val ID = "oneai"
    const val NAME = "One AI"
    const val BASE = "https://api.oneaiapikey.com/v1"
    const val SIGNUP = "https://oneaiapikey.com/console/sign-up"

    /** "एक key से सब (ऑल-राउंडर)" वाले One AI कार्ड की key */
    const val ALL_KEY = "one_key_oneai"
    /** AI दिमाग का बटन (शुरू में बंद): चालू = मॉडल-सूची में सिर्फ़ One AI के मॉडल */
    const val ONLY_PREF = "oneai_models_only"
    /** सूची से चुना One AI मॉडल (सबसे पहले यही आज़माया जाता है) */
    const val MODEL_PREF = "oneai_model"
    const val MODELS_PREF = "models_oneai"

    /** पहला शब्द इतने में नहीं = अगला मॉडल / अगली कड़ी */
    const val FIRST_TOKEN_MS = 4_000L
    /** एक key पर एक समय में एक ही अनुरोध - बाकी कतार में इतना रुकें, फिर अगली key / कड़ी */
    const val QUEUE_WAIT_MS = 3_000L
    const val MODELS_CACHE_MS = 10 * 60_000L
    /** मुफ़्त सीमा पूरे खाते की (रोज़ 100) - इसलिए एक सवाल पर ज़्यादा से ज़्यादा इतने मॉडल */
    const val MAX_TRIES = 3
    const val BADGE = "मुफ़्त"
    /** 429 + "daily_limit_free" = आज की मुफ़्त सीमा (पूरे खाते की) खत्म */
    const val DAILY = "oneai daily"

    val BANNED = listOf("uncensored", "roleplay", "stepaudio")

    /** chat और phone (तेज़) */
    val FAST = listOf("deepseek-deepseek-v4.1-flash:free", "z-ai-glm-5.3-flash:free", "minimaxai-minimax-m3-1-flash:free", "grok-4.7-fast:free")
    /** coding और deep_research (बड़े) */
    val BIG = listOf("moonshotai-kimi-k3.1-pro:free", "z-ai-glm-5.3:free", "nvidia-nemotron-3-ultra-550b-a55b:free")

    // ------------------------------------------------------------------ pure (JUnit)
    /** "मुफ़्त" सिर्फ़ ID के अंत में :free देखकर */
    fun isFree(id: String): Boolean = id.trim().endsWith(":free", ignoreCase = true)

    fun banned(id: String): Boolean = id.lowercase().let { m -> BANNED.any { m.contains(it) } }

    /** सूची में दिखाने का नाम: ID + अलग "मुफ़्त" बैज */
    fun label(id: String): String = if (isFree(id)) "$id   • $BADGE" else id

    /** ऐप का काम (AiRouter.Task / हिस्सा) → One AI की प्राथमिकता-सूची का नाम (backend जैसा) */
    fun taskOf(task: String): String = when (task) {
        AiRouter.Task.CODER, AiRouter.Task.COMPUTER, AiRouter.Task.HEAVY, ApiScopes.CODER, ApiScopes.PC -> "coding"
        AiRouter.Task.RESEARCH -> "deep_research"
        "phone" -> "phone"
        else -> "chat"
    }

    fun defaults(task: String): List<String> = if (taskOf(task) == "coding" || taskOf(task) == "deep_research") BIG else FAST

    /**
     * किन मॉडलों को किस क्रम में आज़माएँ: चुना मॉडल (मुफ़्त हो तो) → काम की सूची (असली सूची में हो तो) → बाकी मुफ़्त.
     * सिर्फ़ :free, "uncensored / roleplay / stepaudio" नहीं, असली सूची खाली (अभी आई नहीं) = शुरुआती सूची ही.
     */
    fun order(task: String, live: List<String>, chosen: String?, skip: (String) -> Boolean = { false }): List<String> {
        val have = live.toSet()
        fun ok(m: String) = m.isNotBlank() && isFree(m) && !banned(m) && (have.isEmpty() || m in have)
        val base = defaults(task)
        val rest = live.filter { ok(it) && it !in base }.sorted()
        val all = (listOfNotNull(chosen?.trim()) + base + (if (base.none(::ok)) rest else emptyList())).filter(::ok).distinct()
        val (fresh, cooled) = all.partition { !skip(it) }
        return (fresh + cooled).take(MAX_TRIES)
    }

    /** key किस-किस setting से: उस हिस्से की अपनी → बाकी हिस्सों की → ऑल-राउंडर कार्ड की. खाली / दोहराई नहीं */
    fun keyOrder(scope: String, get: (String) -> String): List<String> {
        val names = listOf(ApiScopes.pref(scope, "key", ID)) +
            ApiScopes.ALL.filter { it != scope }.map { ApiScopes.pref(it, "key", ID) } + ALL_KEY
        return names.map { get(it).trim() }.filter { it.length >= 8 }.distinct()
    }

    /** काम → उसका हिस्सा (key ढूँढने के लिए) */
    fun scopeOf(task: String): String = when (task) {
        AiRouter.Task.CODER, AiRouter.Task.HEAVY, ApiScopes.CODER -> ApiScopes.CODER
        AiRouter.Task.COMPUTER, ApiScopes.PC -> ApiScopes.PC
        AiRouter.Task.LIVE, AiRouter.Task.VOICE, ApiScopes.LIVE -> ApiScopes.LIVE
        else -> ApiScopes.CHAT
    }

    /** GET /models का जवाब → मॉडल IDs */
    fun parseModels(json: String): List<String> {
        val arr = try { JSONObject(json).optJSONArray("data") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct()
    }

    /** Test बटन का नतीजा (✓ = सही, ✗ = गलत) */
    fun testMsg(code: Int, models: List<String>): String = when {
        code in 200..299 && models.isNotEmpty() -> "✓ One AI key सही है — ${models.size} मॉडल (${models.count(::isFree)} मुफ़्त)"
        code in 200..299 -> "⚠ key चली, पर कोई मॉडल नहीं मिला — console में key की allowed-model सूची देखें"
        code == 401 || code == 403 -> "✗ key गलत है या बंद है — oneaiapikey.com के console से नई key बनाएँ"
        code == 429 -> "⚠ key सही लगती है, अभी सीमा भरी है — थोड़ी देर बाद"
        code == 0 -> "⚠ इंटरनेट नहीं मिला — नेट जाँचकर फिर टेस्ट करें"
        else -> "⚠ One AI से जवाब नहीं आया (कोड $code) — थोड़ी देर बाद फिर टेस्ट करें"
    }

    /** इसके बाद अगला मॉडल / key / कड़ी? (सब पर हाँ - सिर्फ़ रुकावट पर नहीं) */
    fun next(e: Throwable): Boolean = e !is kotlinx.coroutines.CancellationException && e !is InterruptedException

    /** मुफ़्त सीमा (रोज़ 100) 00:00 UTC पर नई - तब तक वह key ठंडी */
    fun msToUtcMidnight(now: Long): Long = (86_400_000L - (now % 86_400_000L)).coerceAtLeast(60_000L)

    /** SSE की एक "data:" लाइन → नया टुकड़ा (null = कुछ नहीं). गलती वाला टुकड़ा = AiException */
    fun delta(data: String): String? {
        if (data.isBlank() || data.trim() == "[DONE]") return null
        val o = try { JSONObject(data) } catch (_: Exception) { return null }
        o.optJSONObject("error")?.let { throw AiException(it.optInt("code", 502).takeIf { c -> c > 0 } ?: 502, "oneai error") }
        val c = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.opt("content")
        return (c as? String)?.takeIf { it.isNotEmpty() }
    }

    /** चिप / चुना मॉडल के लिए पहचान ([Providers.all] में नहीं) */
    val provider = Provider(ID, NAME, true, "एक key से कई कंपनियों के मॉडल (oneaiapikey.com)", BASE, FAST.first(), false, SIGNUP)

    /** चिप की सूची (बटन चालू होने पर सिर्फ़ यही): मुफ़्त पहले, हर मॉडल के साथ अलग "मुफ़्त" बैज (सिर्फ़ :free से) */
    fun groupOf(list: List<String>): com.dk.assistant.dkai.ModelCatalog.Group? {
        val ms = list.filter { it.isNotBlank() && !banned(it) }.distinct().sortedWith(compareBy({ !isFree(it) }, { it }))
        if (ms.isEmpty()) return null
        return com.dk.assistant.dkai.ModelCatalog.Group(ID, NAME, ms.map { m ->
            com.dk.assistant.dkai.ModelCatalog.Entry(ID, m, m, if (isFree(m)) BADGE else "पैसे वाला", !isFree(m), false)
        })
    }

    // ------------------------------------------------------------------ फ़ोन पर
    /** चिप की One AI सूची (अभी की सेव सूची; खाली = शुरुआती मुफ़्त मॉडल) */
    fun group(c: Context): com.dk.assistant.dkai.ModelCatalog.Group? =
        if (!hasKey(c)) null else groupOf(cached(c).ifEmpty { FAST + BIG })

    /** पीछे से ताज़ा सूची (10 मिनट याद). true = कुछ बदला */
    fun refresh(c: Context): Boolean {
        val k = keys(c, ApiScopes.CHAT).firstOrNull() ?: return false
        val old = cached(c)
        return models(c, k) != old
    }

    /** कोडिंग एजेंट / DK कंप्यूटर (चिप में One AI मॉडल चुना): [model] पहले, फिर बाकी मुफ़्त - चुपचाप */
    fun coderChat(c: Context, task: String, model: String, system: String, msgs: List<Pair<String, String>>): String {
        val idx = msgs.indexOfLast { it.first == "user" }
        val last = msgs.getOrNull(idx)?.second.orEmpty()
        val hist = msgs.filterIndexed { i, _ -> i != idx }.map { (r, t) -> Turn(if (r == "user") "user" else "model", t) }
        val ks = keys(c, task).ifEmpty { throw AiException(-1, "NO_KEY") }
        return run(c, task, ks, model, system, hist, last) { }.ifBlank { throw AiException(502, "oneai empty") }
    }

    fun keys(c: Context, task: String): List<String> = keyOrder(scopeOf(task)) { Prefs.raw(c, it) }
        .filter { !Cooldown.blocked(dayKey(it)) }

    fun hasKey(c: Context): Boolean = keyOrder(ApiScopes.CHAT) { Prefs.raw(c, it) }.isNotEmpty()

    fun onlyOn(c: Context): Boolean = Prefs.bool(c, ONLY_PREF, false)
    fun chosen(c: Context): String = Prefs.raw(c, MODEL_PREF)
    fun cached(c: Context): List<String> = Prefs.raw(c, MODELS_PREF).split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun dayKey(k: String) = "oneai-day|" + k.takeLast(6)
    private fun modelKey(m: String) = "oneai-model|$m"

    private val JSON = "application/json".toMediaType()
    private val http by lazy { NetFix.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS).build() }
    private val locks = ConcurrentHashMap<String, Semaphore>()
    @Volatile private var listAt = 0L

    /** मॉडल-सूची (GET /models - मुफ़्त, सीमा में नहीं गिनती). 10 मिनट याद. गड़बड़ी = पुरानी सूची */
    fun models(c: Context?, key: String, force: Boolean = false): List<String> {
        val old = c?.let { cached(it) }.orEmpty()
        if (!force && old.isNotEmpty() && System.currentTimeMillis() - listAt < MODELS_CACHE_MS) return old
        val (code, list) = fetch(key)
        if (code in 200..299 && list.isNotEmpty()) {
            listAt = System.currentTimeMillis()
            c?.let { ctx -> Prefs.edit(ctx) { putString(MODELS_PREF, list.joinToString("\n")) } }
            return list
        }
        return old
    }

    private fun fetch(key: String): Pair<Int, List<String>> = try {
        http.newCall(Request.Builder().url("$BASE/models").header("Authorization", "Bearer $key").get().build()).execute().use { r ->
            r.code to (if (r.isSuccessful) parseModels(r.body?.string().orEmpty()) else emptyList())
        }
    } catch (_: Exception) { 0 to emptyList() }

    /** Settings का "टेस्ट करें" (नेट वाला - IO thread पर बुलाएँ). अनुरोध की मुफ़्त सीमा खर्च नहीं होती */
    fun check(c: Context?, key: String): String {
        val (code, list) = fetch(key.trim())
        if (code in 200..299 && list.isNotEmpty()) {
            listAt = System.currentTimeMillis()
            c?.let { ctx -> Prefs.edit(ctx) { putString(MODELS_PREF, list.joinToString("\n")) } }
        }
        return testMsg(code, list)
    }

    /** इस काम का One AI client (कोई key नहीं = null). [pinned] = AI दिमाग में चुना मॉडल सबसे पहले */
    fun client(c: Context, task: String, pinned: String? = null): AiClient? {
        val ks = keys(c, task)
        if (ks.isEmpty()) return null
        return OneAiClient(c.applicationContext, task, ks, pinned ?: chosen(c).takeIf { it.isNotBlank() })
    }

    // ---- नेट (OneAiClient)
    internal fun run(c: Context?, task: String, keys: List<String>, chosen: String?, system: String, history: List<Turn>,
                     prompt: String, onDelta: (String) -> Unit): String {
        var sent = false
        val out = StringBuilder()
        var last: Throwable = AiException(503, "oneai")
        for (k in keys) {
            val lock = locks.getOrPut(k) { Semaphore(1) }
            if (!lock.tryAcquire(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) { last = AiException(503, "oneai busy"); continue }
            try {
                val list = order(task, models(c, k), chosen) { Cooldown.blocked(modelKey(it)) }
                for (m in list) {
                    try {
                        val t = once(k, m, system, history, prompt) { d -> sent = true; out.append(d); onDelta(d) }
                        if (t.isNotBlank()) return t
                        last = AiException(502, "oneai empty")
                    } catch (e: Exception) {
                        if (!next(e)) throw e
                        last = e
                        if (sent) return out.toString()        // आधा जवाब आ चुका = वही (गलती नहीं दिखती)
                        val code = (e as? AiException)?.code ?: 0
                        if (code == 401 || code == 403 || code == 402) break    // key / बैलेंस = अगली key
                        if (code == 429 && e.message == DAILY) { Cooldown.block(dayKey(k), msToUtcMidnight(System.currentTimeMillis())); break }
                        Cooldown.block(modelKey(m), if (code == 404) 86_400_000L else 10 * 60_000L)
                    }
                }
            } finally { lock.release() }
        }
        throw (last as? AiException) ?: AiException(503, "oneai")
    }

    private fun once(key: String, model: String, system: String, history: List<Turn>, prompt: String, onDelta: (String) -> Unit): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", Lang.sys(system)))
        history.filter { it.text.isNotBlank() }.forEach { msgs.put(JSONObject().put("role", if (it.role == "user") "user" else "assistant").put("content", it.text)) }
        msgs.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true).put("max_tokens", 2_000)
        val call: Call = http.newCall(Request.Builder().url("$BASE/chat/completions").header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream").post(body.toString().toRequestBody(JSON)).build())
        // 4 सेकंड में पहला शब्द नहीं = यह call बंद, अगला मॉडल
        val got = java.util.concurrent.atomic.AtomicBoolean(false)
        val dog = Thread { try { Thread.sleep(FIRST_TOKEN_MS); if (!got.get()) call.cancel() } catch (ignored: InterruptedException) { Quiet.note(ignored) } }
        dog.isDaemon = true; dog.start()
        val full = StringBuilder()
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) {
                    val daily = r.code == 429 && (r.body?.string().orEmpty()).contains("daily_limit")
                    throw AiException(r.code, if (daily) DAILY else "oneai ${r.code}", r.header("Retry-After")?.toIntOrNull() ?: 0)
                }
                // मुफ़्त मॉडल फेल = सेवा पैसे वाले मॉडल से जवाब देती है (X-Fallback-From) - वह नहीं लेना
                if (!r.header("X-Fallback-From").isNullOrBlank()) throw AiException(503, "oneai paid fallback")
                val src = r.body?.source() ?: throw AiException(502, "oneai empty")
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val d = line.removePrefix("data:").trim()
                    if (d == "[DONE]") break
                    val t = delta(d) ?: continue
                    got.set(true); full.append(t); onDelta(t)
                }
            }
        } catch (e: AiException) { throw e
        } catch (e: java.io.IOException) {
            if (full.isNotEmpty()) return full.toString()
            throw AiException(if (!got.get()) 504 else 502, "oneai net")
        } finally { got.set(true); dog.interrupt() }
        return full.toString().replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }
}

/** One AI की कड़ी ([AiClient]) - बातचीत वाले काम = [AnswerGuard] (DK की पहचान), कोडिंग / कंप्यूटर बिना छेड़े */
class OneAiClient(private val c: Context?, private val task: String, private val keys: List<String>, private val chosen: String?) : AiClient {
    override val label: String = OneAi.NAME
    private val talk = OneAi.taskOf(task) == "chat"

    override fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String {
        if (imageB64 != null) throw AiException(415, "oneai no image")   // तस्वीर = अगली कड़ी
        val t = OneAi.run(c, task, keys, chosen, system, if (talk) guardHist(history) else history, prompt) { }
        return if (talk) AnswerGuard.clean(t) else t
    }

    override fun stream(system: String, history: List<Turn>, prompt: String, onDelta: (String) -> Unit): String =
        if (!talk) OneAi.run(c, task, keys, chosen, system, history, prompt, onDelta)
        else AnswerGuard.stream(onDelta) { d -> OneAi.run(c, task, keys, chosen, system, guardHist(history), prompt, d) }
}

/**
 * ऐप की One AI key, फिर DK सर्वर ([BackendAiClient]) - चुपचाप. सर्वर इस user के लिए न हो (मुफ़्त / सर्वर नहीं)
 * तो One AI की ही गलती (आगे वाला code वही पुराना संदेश / अगला रास्ता तय करता है).
 */
class OneAiThenServer(private val one: AiClient, private val server: () -> AiClient?) : AiClient {
    override val label: String = one.label

    override fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String = try {
        one.complete(system, history, prompt, imageB64).ifBlank { throw AiException(502, "oneai empty") }
    } catch (e: Exception) {
        if (!OneAi.next(e)) throw e
        (server() ?: throw e).complete(system, history, prompt, imageB64)
    }

    override fun stream(system: String, history: List<Turn>, prompt: String, onDelta: (String) -> Unit): String {
        var sent = false
        return try {
            one.stream(system, history, prompt) { sent = true; onDelta(it) }.ifBlank { throw AiException(502, "oneai empty") }
        } catch (e: Exception) {
            if (!OneAi.next(e) || sent) throw e
            (server() ?: throw e).stream(system, history, prompt, onDelta)
        }
    }
}
