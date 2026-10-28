package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Saari keys fail - har key ka kaaran */
class AllFailedException(val errors: List<Pair<Provider, Exception>>) : java.io.IOException("all failed")

/**
 * DK ka dimaag. Aapki chuni hui AI company ko pehle try karta hai,
 * fail ho (limit khatam / internet / galat key) to baaki keys wali companies try karta hai.
 */
class BrainManager(private val ctx: Context) {

    /** Pichhli baatcheet - phone me save rehti hai (app band hone par bhi DK ko yaad) */
    private val history: MutableList<Turn> = try { Memory.loadHistory(ctx) } catch (_: Exception) { mutableListOf() }
    var lastUsed: String = ""
        private set

    private var epoch = Memory.historyEpoch
    private var ver = Memory.historyVersion
    /** Clear ho to SAB BrainManager saaf (epoch badhta hai) - pehle sirf apni copy saaf hoti thi */
    fun reset() { history.clear(); Memory.clearHistory(ctx); epoch = Memory.historyEpoch; ver = Memory.historyVersion }
    /** Memory screen se "baatcheet mitao" hua ho to yahan bhi saaf; kisi aur BrainManager ne nayi baat save ki ho to dobara padho */
    private fun syncEpoch() {
        if (epoch != Memory.historyEpoch) { epoch = Memory.historyEpoch; history.clear() }
        if (ver != Memory.historyVersion) reload()
        NowClock.syncAsync()      // v3.80: सही समय (हर 3 घंटे इंटरनेट से मिलाओ - रुकता नहीं)
    }
    private fun reload() {
        synchronized(Memory.historyLock) {
            val fresh = try { Memory.loadHistory(ctx) } catch (_: Exception) { return }
            history.clear(); history.addAll(fresh); ver = Memory.historyVersion
        }
    }

    /** DK चैट की अपनी key (हर हिस्से की अलग - देखें [ApiScopes]); limit पर उसी key के दूसरे मॉडल */
    private fun clientFor(p: Provider, scope: String = ApiScopes.CHAT): AiClient? =
        override?.let { (id, c) -> if (p.id == id) c else null } ?: if (override != null) null else scopedClient(ctx, p, scope)

    /**
     * ऑटोनोमस मोड की अपनी key (कंपनी id -> client). null = पहले जैसा (DK की मुख्य key).
     * सिर्फ़ ऑटोनोमस काम के दौरान लगती है, काम खत्म होते ही AgentRunner हटा देता है.
     */
    @Volatile var override: Pair<String, AiClient>? = null

    /** सिर्फ़ चुनी हुई कंपनी (दूसरी कंपनी पर अपने-आप जाना बंद - [Prefs.fallback]). */
    private fun orderedProviders(chat: Boolean = false): List<Provider> {
        override?.let { (id, _) -> return listOfNotNull(Providers.byId(id)) }
        val active = Providers.byId(Prefs.activeProvider(ctx))
        val rest = Providers.all.filter { it.id != active?.id }
        val list = if (Prefs.fallback(ctx)) listOfNotNull(active) + rest else listOfNotNull(active)
        // baatcheet ke liye Groq sabse tez hai (key ho to pehle)
        if (chat && Prefs.fallback(ctx) && Prefs.key(ctx, "groq").isNotBlank()) {
            val g = Providers.byId("groq")!!
            return listOf(g) + list.filter { it.id != "groq" }
        }
        return list
    }

    fun hasUsableProvider() = orderedProviders().any { clientFor(it) != null }

    /** Baatcheet: jinki key hai, unme chalti hui + sabse tez pehle. Limit/galti wali sabse aakhir me. */
    private fun rankedForChat(): List<Provider> {
        val withKey = Providers.all.filter { clientFor(it) != null }
            .filter { Prefs.fallback(ctx) || it.id == Prefs.activeProvider(ctx) }
        val active = Prefs.activeProvider(ctx)
        val (good, bad) = withKey.partition { ProviderHealth.usable(it.id) }
        // रोज़ की limit / गलत key वाली को बिल्कुल मत आज़माओ (हर बार वही 429 = देर); थोड़ी देर वाली सिर्फ़ आख़िर में
        return good.sortedBy { ProviderHealth.speed(it.id) - (if (it.id == active) 150 else 0) } + bad.filter { ProviderHealth.retryable(it.id) }
    }

    /** कोई key आज़माने लायक नहीं बची - साफ़ कारण के साथ (बिना नेट पर गए) */
    private fun exhausted(list: List<Provider>): AllFailedException =
        AllFailedException(list.filter { clientFor(it) != null }.map { it to AiException(429, ProviderHealth.reason(it.id).ifBlank { "limit" }) })

    /**
     * Baatcheet ka jawab TUKDON me (streaming): pehla vakya aate hi bolna shuru.
     * Kai keys ho to: sabse tez wali se shuru; 1.5 sec me pehla shabd na aaye to agli key bhi saath me -
     * jo pehle jawab de, wahi jeetegi. Fail ho to turant agli key.
     */
    suspend fun streamChat(prompt: String, note: String, onDelta: (String) -> Unit): String = withContext(Dispatchers.IO) {
        syncEpoch()
        val system = buildChatStreamPrompt()
        // purane JSON jawab ko saada text bana ke bhejo (AI saada text hi likhe)
        val hist = history.takeLast(10).map { t ->
            if (t.role == "model" && t.text.trimStart().startsWith("{"))
                Turn("model", try { JSONObject(t.text).optString("say").ifBlank { "(phone par kaam kiya)" } } catch (_: Exception) { t.text })
            else t
        }
        val cands = rankedForChat().mapNotNull { p -> clientFor(p)?.let { c ->
            if (c is GeminiClient) c.fast = true
            if (c is OpenAiCompatClient) c.fast = true
            p to c } }
        if (cands.isEmpty()) {
            val all = Providers.all.filter { clientFor(it) != null }
            throw if (all.isEmpty()) AiException(-1, "NO_KEY") else exhausted(all)
        }

        val lock = Any()
        var winner: String? = null
        var next = 0; var running = 0
        val errors = mutableListOf<Pair<Provider, Exception>>()
        val result = CompletableDeferred<String>()

        coroutineScope {
            val scope = this
            fun start() {
                val pc = synchronized(lock) {
                    if (winner != null || next >= cands.size || result.isCompleted) null else { running++; cands[next++] }
                } ?: return
                val (p, client) = pc
                scope.launch(Dispatchers.IO) {
                    val t0 = System.currentTimeMillis()
                    var first = true
                    var counted = false   // running-- sirf EK baar (khali jawab par do baar ghatne se await hamesha atak jaata tha)
                    try {
                        val full = client.stream(system, hist, prompt) { d ->
                            synchronized(lock) {
                                if (winner == null) winner = p.id
                                if (winner != p.id) throw LoserException()
                            }
                            if (first) { first = false; ProviderHealth.ok(p.id, System.currentTimeMillis() - t0)
                                DKLog.d("Pehla shabd ${System.currentTimeMillis() - t0}ms: ${client.label}") }
                            onDelta(d)
                        }
                        val won = synchronized(lock) { running--; counted = true; if (winner == null && full.isNotBlank()) winner = p.id; winner == p.id }
                        if (won) { lastUsed = client.label; result.complete(full) }
                        else if (full.isBlank()) throw AiException(0, "khali jawab")
                    } catch (e: LoserException) {
                        synchronized(lock) { if (!counted) { running--; counted = true } }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DKLog.d("${client.label} fail: ${e.message}")
                        ProviderHealth.fail(p.id, e)
                        val (wasWinner, noneLeft) = synchronized(lock) {
                            if (winner != p.id) { errors.add(p to e); if (!counted) { running--; counted = true } }
                            Pair(winner == p.id, winner == null && next >= cands.size && running == 0)
                        }
                        when {
                            wasWinner -> result.completeExceptionally(e)   // beech me toota - jitna bola utna hi
                            noneLeft -> result.completeExceptionally(AllFailedException(errors.toList()))
                            else -> { DKStatus.post(DKStatus.forCode((e as? AiException)?.code ?: -1)); start() }   // turant agli key (बुलबुले में सच्चा संदेश)
                        }
                    }
                }
            }
            start()
            launch { delay(1000); start(); delay(2000); start() }   // dheemi ho to saath me agli key (v3.7: 1 sec)
            try {
                val text = result.await()
                remember(if (note.isNotBlank()) note else "USER SAYS: $prompt", text)
                text
            } finally { scope.coroutineContext.cancelChildren() }
        }
    }

    /** Sab key fail - DK khud bolkar bataye kya dikkat hai. */
    fun explainAll(e: Exception): String {
        if (e is AllFailedException) {
            if (e.errors.any { it.second is java.net.UnknownHostException }) return "इंटरनेट नहीं मिल रहा। नेट चालू करके फिर बोलिए।"
            val parts = e.errors.distinctBy { it.first.id }.joinToString("। ") { (p, x) -> "${p.name.removePrefix("Google ")}: ${if (ProviderHealth.dailyOut(p.id)) ProviderHealth.reason(p.id) else ProviderHealth.short(x)}" }
            val fix = when {
                e.errors.all { ProviderHealth.dailyOut(it.first.id) } -> "आज सब फ्री limit खत्म हैं। सेटिंग्स में कोई दूसरी फ्री key डालिए, जैसे Groq या Gemini।"
                e.errors.all { (it.second as? AiException)?.code == 429 } -> "थोड़ी देर बाद पूछिए, या सेटिंग्स में कोई दूसरी फ्री key डालिए, जैसे Groq।"
                e.errors.any { val c = (it.second as? AiException)?.code; c == 401 || c == 403 } -> "सेटिंग्स में key दोबारा डालकर टेस्ट दबाइए।"
                else -> "फिर से बोलिए।"
            }
            return "जवाब नहीं आ पाया। $parts। $fix"
        }
        return explain(e)
    }

    /** Galti ko aasaan Hindi me samjhao (bolne + screen par dikhane ke liye). */
    fun explain(e: Exception): String {
        val msg = e.message ?: ""
        val code = (e as? AiException)?.code ?: 0
        return when {
            e is java.net.UnknownHostException -> "इंटरनेट नहीं मिल रहा। नेट चालू करके फिर बोलिए।"
            e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException ->
                "AI बहुत देर लगा रहा है। इंटरनेट धीमा है या सर्वर व्यस्त है, फिर से बोलिए।"
            RateLimit.daily(e) -> "आज की फ्री लिमिट खत्म हो गई है, ${RateLimit.clock(RateLimit.nextUtcMidnight(System.currentTimeMillis()))} फिर मिलेगी। तब तक सेटिंग्स में दूसरी AI की key डालिए।"
            code == 429 -> "फ्री लिमिट खत्म हो गई है। थोड़ी देर बाद कोशिश कीजिए, या सेटिंग्स में दूसरी AI की key डालिए।"
            code == 401 || code == 403 || msg.contains("API key", true) ->
                "API key गलत है या उसे इजाज़त नहीं है। सेटिंग्स में key दोबारा पेस्ट करके टेस्ट कीजिए।"
            code == 404 -> "यह मॉडल नहीं मिला। सेटिंग्स में मॉडल का नाम ठीक कीजिए।"
            msg == "NO_KEY" -> "पहले सेटिंग्स में किसी AI की API key डालकर सेव कीजिए।"
            else -> "AI से जवाब नहीं मिला (गलती $code)।"
        }
    }

    suspend fun next(prompt: String, imageB64: String?, memoryNote: String, chat: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            syncEpoch()
            val system = if (chat) buildChatPrompt() else buildSystemPrompt()
            var lastError: Exception? = null
            // limit/galti wali key ko peeche karo (warna har baar wahi pehle fail hoti hai = der)
            val (good, bad) = orderedProviders(chat).partition { ProviderHealth.usable(it.id) }
            val tryList = good + bad.filter { ProviderHealth.retryable(it.id) }
            if (tryList.isEmpty() && bad.any { clientFor(it) != null }) throw exhausted(bad)
            for (p in tryList) {
                val client = clientFor(p) ?: continue
                val img = if (p.vision) imageB64 else null
                try {
                    if (client is GeminiClient) client.fast = chat
                    if (client is OpenAiCompatClient) client.fast = chat
                    val answer = try {
                        // baatcheet me sirf haal ki baatein bhejo = kam data = tez jawab
                        client.complete(system, if (chat) history.takeLast(10) else history, prompt, img)
                    } catch (e: AiException) {
                        // kuch model photo nahi lete - bina photo dobara try
                        if (img != null && e.code == 400) client.complete(system, history, prompt, null) else throw e
                    }
                    lastUsed = client.label
                    DKLog.d("AI: ${client.label}")
                    ProviderHealth.ok(p.id, 1000)
                    remember(memoryNote, answer)
                    return@withContext parseJson(answer)
                } catch (e: Exception) {
                    DKLog.d("${client.label} fail: ${e.message}")
                    ProviderHealth.fail(p.id, e)
                    lastError = e
                    DKStatus.post(DKStatus.forCode((e as? AiException)?.code ?: -1))
                }
            }
            throw lastError ?: AiException(-1, "NO_KEY")
        }

    /**
     * v3.87: खोज किस हिस्से से हो रही है - DK Live से हो तो पहले Live की अपनी Gemini key।
     * पहले हमेशा DK चैट की key लगती थी: चैट में Groq चुना/key खाली = Live की हर खोज fail।
     */
    @Volatile var fromLive = false

    /** खोज की keys क्रम में (अपनी पहले, दूसरी Gemini key बचाव में) - [SearchGuard.keys] */
    fun searchKeys(): List<String> =
        SearchGuard.keys(fromLive, Prefs.key(ctx, "gemini"), Prefs.scopeKey(ctx, ApiScopes.LIVE, "gemini"), Prefs.searchOwnKey(ctx))

    fun canSearch() = Prefs.webAnswers(ctx) && anySearch()

    /** आख़िरी खोज क्यों नहीं चली (लॉग + Live का जवाब) */
    @Volatile var lastSearchFail: SearchGuard.Fail = SearchGuard.Fail.OTHER
        private set

    private fun failOf(e: Throwable?): SearchGuard.Fail = when (e) {
        null -> SearchGuard.Fail.EMPTY
        is AiException -> SearchGuard.classify(e.code, e.message)
        is java.io.InterruptedIOException -> SearchGuard.Fail.TIMEOUT
        is java.net.UnknownHostException, is java.net.ConnectException, is javax.net.ssl.SSLException -> SearchGuard.Fail.NETWORK
        else -> SearchGuard.classify(0, e.message)
    }

    /** हर key बारी-बारी: key/quota की दिक़्क़त = अगली key; नेटवर्क = वहीं रुको (दूसरी key से फ़ायदा नहीं) */
    private suspend fun <T> withSearchKeys(what: String, block: suspend (String) -> T): T {
        val keys = searchKeys()
        if (keys.isEmpty()) { lastSearchFail = SearchGuard.Fail.NO_KEY; DKLog.d("खोज ($what): Gemini key नहीं डली"); throw AiException(0, "NO_KEY") }
        var last: Throwable? = null
        for ((i, k) in keys.withIndex()) {
            try { return block(k) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                last = e
                val f = failOf(e); lastSearchFail = f
                DKLog.d("खोज ($what) key ${i + 1}/${keys.size} fail: ${SearchGuard.hindi(f)} - ${e.javaClass.simpleName} ${e.message?.take(160)}")
                if (!SearchGuard.tryNextKey(f)) break
            }
        }
        throw last ?: AiException(0, "search fail")
    }

    /** Internet (Google Search) se pata karke sahi jawab - bolne layak chhota text. */
    suspend fun searchAnswer(question: String): String = search(question, deep = false).text

    /**
     * Internet se jaanch kar jawab.
     * deep = false: taaza jaankari (mausam, bhav, score) - 1-3 vakya, turant.
     * deep = true : mushkil/vistaar wala sawaal - kai sources se milakar, sirf pakki jaankari, 3-7 vakya.
     */
    /**
     * Gehri soch: mushkil sawaal (ganit, logic, coding, tulna, planning, "kyon/kaise" ka vistaar).
     * Sabse taakatwar Gemini model + zyada sochna + zaroorat ho to Google Search.
     */
    suspend fun think(question: String): SearchResult = search(question, deep = true, think = true)

    fun canThink() = searchKeys().isNotEmpty()

    /**
     * v3.80: ताज़ा जानकारी (deep/thorough/think नहीं) = [liveSearch] - कई तरफ़ से खोज, मिलाकर पक्का जवाब.
     * बाकी पहले जैसा एक गहरी खोज.
     */
    suspend fun search(question: String, deep: Boolean, thorough: Boolean = false, think: Boolean = false): SearchResult =
        if (think) searchOnce(question, deep, thorough, think)   // deep_think = सोच, इंटरनेट खोज का क्रम नहीं
        else chainSearch(question, explicit = deep || thorough) { geminiSearch(question, deep, thorough) }

    /** पहले जैसी Gemini + Google Search (क्रम की आख़िरी सेवा) */
    private suspend fun geminiSearch(question: String, deep: Boolean, thorough: Boolean): SearchResult =
        if (!deep && !thorough) liveSearch(question) else searchOnce(question, deep, thorough, false)

    /** खोज की कोई सेवा है? (Tavily / Exa / Gemini में से कोई key) */
    fun anySearch(): Boolean = Prefs.tavilySearchKey(ctx).isNotBlank() || Prefs.exaSearchKey(ctx).isNotBlank() || searchKeys().isNotEmpty()

    /**
     * v3.93: इंटरनेट खोज का बैकअप क्रम - Tavily -> Exa -> Gemini + Google Search ([WebSearch]). DK Live और चैट दोनों यहीं से.
     * खाली key = छोड़ो; फेल = चुपचाप अगली (यूज़र से कुछ नहीं पूछते) और वह सेवा 1 घंटे बंद; नतीजा 10 मिनट याद;
     * एक सवाल पर एक ही सफल खोज. सामान्य ज्ञान / गणित / कहानी (ताज़ा शब्द नहीं) = खोज नहीं (यूज़र ने ख़ुद "सर्च करो" न कहा हो).
     */
    private suspend fun chainSearch(question: String, explicit: Boolean, gemini: suspend () -> SearchResult): SearchResult {
        if (!explicit && WebSearch.skip(question)) {
            DKLog.d("खोज: सामान्य/बिना-बदलाव वाला सवाल - खोज नहीं, अपनी जानकारी से")
            lastSearchFail = SearchGuard.Fail.OTHER
            throw AiException(0, "no_search_needed")
        }
        WebSearch.cached(question)?.let { DKLog.d("खोज: 10 मिनट के अंदर वही सवाल - पिछला नतीजा"); return it }
        return WebSearch.once(question) {
            WebSearch.cached(question) ?: run {
                val tKey = Prefs.tavilySearchKey(ctx); val eKey = Prefs.exaSearchKey(ctx)
                val order = WebSearch.order(tKey.isNotBlank(), eKey.isNotBlank(), searchKeys().isNotEmpty())
                var last: Exception? = null
                var lastFail = SearchGuard.Fail.NO_KEY
                for (p in order) {
                    try {
                        val r = when (p) {
                            WebSearch.Provider.TAVILY -> fromFound(question, withContext(Dispatchers.IO) { WebSearchNet.tavily(tKey, question) })
                            WebSearch.Provider.EXA -> fromFound(question, withContext(Dispatchers.IO) { WebSearchNet.exa(eKey, question) })
                            WebSearch.Provider.GEMINI -> gemini()
                        }
                        if (r.text.isBlank()) throw AiException(0, "empty")
                        WebSearch.tag(r.sources, p); WebSearch.clearDown(p); WebSearch.put(question, r)
                        if (p != WebSearch.Provider.GEMINI) {
                            LiveSearch.remember(question, r.sources)
                            remember("USER SAYS: $question", JSONObject().put("say", r.text.take(1200)).put("done", true).toString())
                        }
                        DKLog.d("खोज: ${p.label} से (${r.sources.size} स्रोत)")
                        return@run r
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) {
                        val code = (e as? AiException)?.code ?: 0
                        val f = if (p == WebSearch.Provider.GEMINI) lastSearchFail else
                            if (e is java.io.InterruptedIOException) SearchGuard.Fail.TIMEOUT
                            else if (e is java.io.IOException) SearchGuard.Fail.NETWORK
                            else WebSearch.failOf(p, code, e.message)
                        val empty = code == 0 && e.message == "empty"   // कुछ नहीं मिला = सेवा की ग़लती नहीं
                        if (WebSearch.coolsDown(p, f) && !empty) {
                            WebSearch.markDown(p, f); DKLog.d("खोज: ${p.label} नहीं चली (${SearchGuard.hindi(f)}${if (code > 0) ", code $code" else ""}) - 1 घंटे छोड़ी, अगली सेवा")
                        } else DKLog.d("खोज: ${p.label} नहीं चली (${SearchGuard.hindi(f)}${if (code > 0) ", code $code" else ""}) - अगली सेवा")
                        last = e; lastFail = f
                    }
                }
                if (order.isEmpty()) lastFail = WebSearch.downReason() ?: SearchGuard.Fail.NO_KEY
                lastSearchFail = lastFail
                throw last ?: AiException(0, if (lastFail == SearchGuard.Fail.NO_KEY) "NO_KEY" else "all_down")
            }
        }
    }

    /**
     * Tavily/Exa के नतीजे -> SearchResult. Live: नतीजे सीधे Live को (वह ख़ुद बोलकर जवाब बनाता है, कोई और AI call नहीं).
     * चैट: चैट वाले AI से छोटा हिंदी जवाब (खोज दोबारा नहीं - सिर्फ़ मिलाना); वह न चले = सीधा सार.
     */
    private suspend fun fromFound(question: String, f: WebSearch.Found): SearchResult {
        if (f.hits.isEmpty() && f.answer.isBlank()) throw AiException(0, "empty")
        val sources = WebSearch.sources(f)
        val report = WebSearch.report(f)
        if (fromLive) return SearchResult(report, sources)
        val merged = try {
            kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                quickText(LiveSearch.mergeSystem(NowClock.line(), Prefs.assistantName(ctx), Prefs.userName(ctx)) + Memory.factsBlock(ctx),
                    LiveSearch.mergeInput(question, listOf("web" to report)))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val ans = (merged?.takeIf { it.isNotBlank() } ?: WebSearch.plainAnswer(f))
            .replace(Regex("[*#`]"), "").replace(Regex("\\[\\d+(,\\s*\\d+)*\\]"), "").trim()
        return SearchResult(ans.ifBlank { report }, sources)
    }

    /**
     * v3.80: लाइव जानकारी - [LiveSearch.plan] की 2-3 खोजें साथ-साथ (सरकारी सवाल = पहले सरकारी वेबसाइटें),
     * फिर सब मिलाकर एक जवाब. कोई खोज न चले = पुराना एक-खोज वाला रास्ता. स्रोत याद ("कहाँ से पता किया?").
     */
    suspend fun liveSearch(question: String): SearchResult = withSearchKeys("लाइव") { key -> liveSearchWith(key, question) }

    private suspend fun liveSearchWith(key: String, question: String): SearchResult = withContext(Dispatchers.IO) {
        syncEpoch()
        NowClock.syncAsync()
        val model = Prefs.model(ctx, "gemini")
        val name = Prefs.assistantName(ctx); val user = Prefs.userName(ctx)
        val nowLine = NowClock.line()
        // v3.90: खोज की अपनी key से = गहरी खोज (Gemini ऐप जैसी): एक और "पूरी जानकारी" वाला कोण, हर कोण में ज़्यादा सोच/लंबी रिपोर्ट, ज़्यादा समय.
        // Live/चैट की key से = पहले जैसी तेज़ खोज (उनकी free limit बचे)
        val deepS = LiveSearch.deepFor(key, Prefs.searchOwnKey(ctx))
        val angles = LiveSearch.plan(question, deepS)
        val angleMs = LiveSearch.angleTimeoutMs(deepS)
        var firstKeyError: Exception? = null
        val reports = coroutineScope {
            angles.map { a ->
                async {
                    try {
                        // v3.87: runInterruptible - timeout सच में HTTP कॉल रोके (पहले 3-4 मॉडल × 35 सेकंड लटकती थी)
                        // v3.90: 14 -> 18 सेकंड: मोबाइल नेट पर Google खोज + पढ़ना अक्सर 14 से ऊपर जाता था, सारे कोण "timeout" = "जानकारी नहीं मिली"
                        kotlinx.coroutines.withTimeoutOrNull(angleMs) {
                            kotlinx.coroutines.runInterruptible {
                                GeminiClient(key, model).search(LiveSearch.angleSystem(a, nowLine, name, deepS), emptyList(),
                                    "$question\n(Search date: ${NowClock.dateEn()})", deep = deepS, maxModels = 2)
                            }
                        }?.let { a.id to it }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        DKLog.d("लाइव खोज ${a.id} fail: ${SearchGuard.hindi(failOf(e))} - ${e.message?.take(160)}")
                        // ग़लत key / quota = सब कोण fail होंगे; ऊपर अगली key आज़माई जाए
                        if (SearchGuard.tryNextKey(failOf(e)) && failOf(e) != SearchGuard.Fail.MODEL) firstKeyError = firstKeyError ?: e
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val good = reports.filter { !LiveSearch.empty(it.second.text) }
        val sources = LiveSearch.mergeSources(reports.map { it.second.sources })
        if (good.isEmpty()) {
            firstKeyError?.let { throw it }   // key/quota - अगली key से (withSearchKeys)
            return@withContext kotlinx.coroutines.withTimeoutOrNull(14_000) {
                searchOnceWith(key, question, deep = false, thorough = false, think = false, maxModels = 2)
            } ?: throw AiException(0, "timeout")
        }
        val merged = try {
            kotlinx.coroutines.withTimeoutOrNull(if (deepS) 15_000L else 10_000L) {
                kotlinx.coroutines.runInterruptible {
                    val client = GeminiClient(key, model)
                    val t = client.stream(LiveSearch.mergeSystem(nowLine, name, user, deepS) + Memory.factsBlock(ctx), emptyList(),
                        LiveSearch.mergeInput(question, good.map { it.first to it.second.text })) { }
                    lastUsed = client.label
                    t
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { DKLog.d("लाइव खोज मिलाना fail: ${e.message}"); null }
        val ans = (merged?.takeIf { it.isNotBlank() } ?: good.first().second.text)
            .replace(Regex("[*#`]"), "").replace(Regex("\\[\\d+(,\\s*\\d+)*\\]"), "").trim()
        DKLog.d("लाइव खोज${if (deepS) " (गहरी, खोज की अपनी key)" else ""}: ${angles.joinToString { it.id }} -> ${good.size} नतीजे, ${sources.size} वेबसाइटें")
        LiveSearch.remember(question, sources)
        remember("USER SAYS: $question", JSONObject().put("say", ans).put("done", true).toString())
        SearchResult(ans, sources)
    }

    private suspend fun searchOnce(question: String, deep: Boolean, thorough: Boolean = false, think: Boolean = false): SearchResult =
        withSearchKeys(if (think) "सोच" else "गहरी") { k -> searchOnceWith(k, question, deep, thorough, think) }

    /** v3.87: फ़ौरन वाली एक खोज (दोबारा कोशिश में) - एक मॉडल, छोटा समय */
    suspend fun quickSearch(question: String): SearchResult = withSearchKeys("दोबारा") { k ->
        kotlinx.coroutines.withTimeoutOrNull(15_000) { searchOnceWith(k, question, deep = false, thorough = false, think = false, maxModels = 2) }
            ?: throw AiException(0, "timeout")
    }

    private suspend fun searchOnceWith(key: String, question: String, deep: Boolean, thorough: Boolean = false, think: Boolean = false,
                                       maxModels: Int = 99): SearchResult = withContext(Dispatchers.IO) {
        syncEpoch()
        val client = GeminiClient(key, Prefs.model(ctx, "gemini"))
        val name = Prefs.assistantName(ctx); val user = Prefs.userName(ctx)
        val sys = buildString {
            append("You are $name, a Hindi voice assistant")
            if (user.isNotBlank()) append(" talking to $user")
            append(". ").append(NowClock.line())
            if (LiveSearch.isGov(question)) append(" For this government-related question, rely on official government websites (*.gov.in, *.nic.in, PIB, the concerned ministry/department) first.")
            if (think) append(" Use Google Search whenever facts, numbers or recent information matter.")
            else append(" ALWAYS use Google Search before answering.")
            if (think) {
                append(" This is a HARD question. Think it through carefully step by step before answering: understand exactly what is asked,")
                append(" break it into parts, work out each part, double-check every calculation, fact and logic, and consider edge cases.")
                append(" For maths show the key steps briefly and the final number with units. For code, give correct working code.")
                append(" For advice or plans, give concrete, practical, specific steps tailored to the user. If something is uncertain, say so honestly.")
                append(" Answer in natural spoken Hindi (Devanagari; English technical words ok), clear and complete but not padded — usually 3-10 sentences,")
                append(" final answer first, then the short reasoning. No markdown symbols.")
            } else if (thorough) {
                append(" The user EXPLICITLY asked you to search/research this. Do a thorough, deep research: look at the topic from")
                append(" several angles and many reliable sources (official sites, government, reputed news/reference sites), compare them,")
                append(" and give the complete, trustworthy picture with real depth — key facts, important details, numbers/dates, and")
                append(" what matters most to the user. Only verified, relevant information; if sources disagree, say so.")
                append(" Answer in natural spoken Hindi (Devanagari), about 6-12 clear sentences, most important first, no markdown, no links.")
            } else if (deep) {
                append(" This is a detailed/complex question: research it properly. Check several reliable sources")
                append(" (official sites, government, well-known news/reference sites) and compare them.")
                append(" Only state facts that the sources agree on; if sources disagree or something is uncertain, say that clearly.")
                append(" Never guess numbers, dates or names. Answer in natural spoken Hindi (Devanagari), 3-7 short sentences,")
                append(" most important point first, simple words, no markdown, no links, no bullet lists.")
            } else {
                append(" Find correct, up-to-date facts. Answer in natural spoken Hindi (Devanagari), 1-3 short sentences,")
                append(" no markdown, no links, no lists. Numbers/prices with units. If unsure, say so.")
            }
            append(" The question may contain typos, speech-recognition mistakes or stray noise words — understand the real meaning and ignore the noise.")
            append(" Never say you searched, checked or are thinking; give only the answer.")
            Prefs.personality(ctx).let { if (it.isNotBlank()) append(" Extra: $it") }
            append(Memory.factsBlock(ctx))
        }
        val r = kotlinx.coroutines.runInterruptible { client.search(sys, history, question, deep || thorough, think, maxModels) }
        val ans = r.text.replace(Regex("[*#`]"), "").replace(Regex("\\[\\d+(,\\s*\\d+)*\\]"), "").trim()
        lastUsed = client.label
        LiveSearch.remember(question, r.sources)
        remember("USER SAYS: $question", JSONObject().put("say", ans).put("done", true).toString())
        SearchResult(ans, r.sources)
    }

    /** Settings me "टेस्ट" button ke liye. */
    suspend fun test(p: Provider, scope: String = ApiScopes.CHAT): String = withContext(Dispatchers.IO) {
        val client = clientFor(p, scope) ?: return@withContext "पहले API key और model डालिए"
        try {
            val r = client.complete("Reply in one short Hindi sentence.", emptyList(), "नमस्ते, क्या तुम काम कर रहे हो?", null)
            "✓ चल रहा है: ${r.take(80)}"
        } catch (e: AiException) {
            "✗ Error ${e.code}: ${e.message?.take(150)}"
        } catch (e: Exception) {
            "✗ ${e.message?.take(150)}"
        }
    }

    private fun remember(note: String, answer: String) {
        // File se taaza history lekar usme jodo - warna doosre BrainManager (chat/Live/deep_think) ki baatein mit jaati thi
        synchronized(Memory.historyLock) {
            if (ver != Memory.historyVersion) try { val fresh = Memory.loadHistory(ctx); history.clear(); history.addAll(fresh) } catch (_: Exception) {}
            history.add(Turn("user", note.take(1500)))
            history.add(Turn("model", answer.take(1500)))
            while (history.size > 24) history.removeAt(0)
            if (history.firstOrNull()?.role == "model") history.removeAt(0)
            try { Memory.saveHistory(ctx, history.toList()) } catch (_: Exception) {}
            ver = Memory.historyVersion
        }
    }

    private fun parseJson(text: String): JSONObject {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return JSONObject().put("say", text.trim()).put("done", true)
        return try { JSONObject(text.substring(s, e + 1)) }
        catch (ex: Exception) { JSONObject().put("say", text.trim()).put("done", true) }
    }

    /** Baatcheet ke liye chhota prompt = tez jawab. */
    private fun buildChatPrompt(): String {
        val name = Prefs.assistantName(ctx); val user = Prefs.userName(ctx); val persona = Prefs.personality(ctx)
        return buildString {
            append("You are $name, a friendly Hindi voice assistant on the user's Android phone.")
            if (user.isNotBlank()) append(" The user's name is $user.")
            append(personaLine(persona))
            append("\nReply with ONLY JSON: {\"say\": \"<natural spoken Hindi in Devanagari, 1-3 short sentences, no markdown>\", \"done\": true}")
            append("\nIf the user wants something DONE on the phone (open/click/type/send/call/settings/shopping etc.) reply exactly {\"need_screen\": true}.")
            append("\nIf the input says SEARCH AVAILABLE and the question needs search under the policy below, reply exactly {\"need_search\": true}.")
            append("\n" + SearchPolicy.rules("{\"need_search\": true}"))
            append("\nEverything else (talk, general knowledge, how/why, advice, explanations, maths) -> answer IMMEDIATELY yourself from your own knowledge, short and warm. Do NOT ask for search for things you already know.")
            append("\nIf the user tells you a personal fact or preference worth remembering (names, numbers, likes, plans), add \"remember\": \"<the fact in short Hindi>\" to the JSON.")
            append(AssistantBrand.IDENTITY_RULE).append("\n")
            append("\n" + NowClock.line())
            append(TALK_RULES)
            append(TalkStyle.CHAT)   // v3.51: इंसान जैसी बात, झूठा "कर दिया" नहीं, notification/रुकने के नियम
            append(Memory.factsBlock(ctx))
            // v3.45: सच्चा दोस्त - लहजा + पिछली बातचीत की याद (आखिरी मुलाकात, सारांश, अधूरी बातें)
            if (Prefs.friendMode(ctx)) append("\n" + FriendStyle.persona(GirlfriendPersona.female(Prefs.voiceGender(ctx)), user)).append(FriendTalk.block(ctx))
            append(CareTime.block()).append(LiveSearch.memoryBlock())   // v3.80: समय देखकर फ़िक्र + पिछली खोज के स्रोत
            append(CalmPersona.block()).append(GirlfriendPersona.block(Prefs.userName(ctx)))   // v3.76: मालिक का तय स्वभाव - हमेशा
            append(WorkChatMode.block(live = false)).append(TeacherBoard.block()).append(LocalMemory.promptBlock(ctx)).append(NetProbe.refresh(ctx).let { NetSpeed.promptNote() })   // v3.88: लोकल याददाश्त का छोटा सार | v3.87: काम/बातचीत मोड + टीचर बोर्ड (स्वभाव वही)
        }
    }

    /** Baatcheet (streaming) ka prompt: saada bola jaane wala text, JSON nahi = pehla shabd turant. */
    private fun buildChatStreamPrompt(): String {
        val name = Prefs.assistantName(ctx); val user = Prefs.userName(ctx); val persona = Prefs.personality(ctx)
        return buildString {
            append("You are $name, a smart, warm Hindi voice assistant on the user's Android phone, talking like a real person.")
            if (user.isNotBlank()) append(" The user's name is $user.")
            append(personaLine(persona))
            append("\nReply in PLAIN spoken Hindi (Devanagari; common English words ok). No JSON, no markdown, no lists, no emojis.")
            append(" Usually 1-3 short sentences; start directly with the answer. Even if earlier turns look like JSON, reply as plain text.")
            append("\nAnswer from your own knowledge immediately: talk, general knowledge, how/why, advice, explanations, maths, stories.")
            append("\nSPECIAL replies (the whole reply is only the tag):")
            append("\n- The user wants something DONE on the phone (open/click/type/send/call/settings/shopping/maps...): reply exactly [SCREEN]")
            append("\n- The input says SEARCH AVAILABLE and the question needs search under the WEB SEARCH POLICY below: reply exactly [SEARCH]")
            append("\n- The question is genuinely HARD and needs careful multi-step reasoning (multi-step maths/word problems, logic puzzles, writing or fixing code, detailed comparison, planning, deep why/how explanation, exam-level questions) and the input says THINK AVAILABLE: reply exactly [THINK]. Do NOT use it for simple chat or easy facts.")
            append("\nLEARN from the user every day: if they tell a personal fact, preference, habit, or how they like answers, add at the very end a new line: [YAAD] <that fact in short Hindi>")
            append(AssistantBrand.IDENTITY_RULE).append("\n")
            append("\n" + NowClock.line())
            append("\n" + SearchPolicy.rules("[SEARCH]"))
            append(TALK_RULES)
            append(TalkStyle.CHAT)   // v3.51: इंसान जैसी बात, झूठा "कर दिया" नहीं, notification/रुकने के नियम
            append(Memory.factsBlock(ctx))
            append(CareTime.block()).append(LiveSearch.memoryBlock())   // v3.80: समय देखकर फ़िक्र + पिछली खोज के स्रोत
            append(CalmPersona.block()).append(GirlfriendPersona.block(Prefs.userName(ctx)))   // v3.76: मालिक का तय स्वभाव - classic आवाज़ में भी
            append(WorkChatMode.block(live = false)).append(TeacherBoard.block()).append(LocalMemory.promptBlock(ctx)).append(NetProbe.refresh(ctx).let { NetSpeed.promptNote() })   // v3.88: लोकल याददाश्त का छोटा सार | v3.87: काम/बातचीत मोड + टीचर बोर्ड (स्वभाव वही)
        }
    }

    /** Gemini Live (awaaz se awaaz) ke liye system instruction */
    /**
     * v3.45: एक सीधा सवाल -> पूरा जवाब (बातचीत का सारांश वगैरह). चैट वाले AI एक-एक करके; null = कोई नहीं चला.
     * (system prompt / इतिहास नहीं - सिर्फ़ यही दो)
     */
    /**
     * DK टीचर: अपना system prompt + सवाल (+ चाहें तो व्हाइटबोर्ड की JPEG base64 फ़ोटो) -> पूरा जवाब.
     * चैट का इतिहास न भेजा जाता है, न बदला जाता है. फ़ोटो सिर्फ़ उन AI को जो फ़ोटो पढ़ते हैं.
     */
    suspend fun teach(system: String, prompt: String, imageB64: String?): String = withContext(Dispatchers.IO) {
        val list = rankedForChat()
        if (list.isEmpty()) {
            val all = Providers.all.filter { clientFor(it) != null }
            throw if (all.isEmpty()) AiException(-1, "NO_KEY") else exhausted(all)
        }
        var lastError: Exception? = null
        for (p in list) {
            val client = clientFor(p) ?: continue
            val img = if (p.vision) imageB64 else null
            try {
                val answer = try {
                    client.complete(system, emptyList(), prompt, img)
                } catch (e: AiException) {
                    if (img != null && e.code == 400) client.complete(system, emptyList(), prompt, null) else throw e
                }
                if (answer.isBlank()) throw AiException(0, "khali jawab")
                lastUsed = client.label
                ProviderHealth.ok(p.id, 1000)
                return@withContext answer
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                DKLog.d("teach ${client.label} fail: ${e.message?.take(80)}")
                ProviderHealth.fail(p.id, e)
                lastError = e
            }
        }
        throw lastError ?: AiException(-1, "NO_KEY")
    }

    suspend fun quickText(system: String, prompt: String): String? = withContext(Dispatchers.IO) {
        for (p in rankedForChat()) {
            val c = clientFor(p) ?: continue
            try {
                val r = c.stream(system, emptyList(), prompt) { }
                if (r.isNotBlank()) return@withContext r
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { DKLog.d("quickText ${p.id}: ${e.message?.take(80)}") }
        }
        // चैट की key नहीं - DK लाइव वाली Gemini key से एक छोटा सवाल (सारांश बहुत छोटा होता है)
        try {
            val k = Prefs.ttsGeminiKey(ctx)
            if (k.isBlank()) null
            else GeminiClient(k, GeminiClient.BACKUP_MODELS.first()).apply { backups = GeminiClient.BACKUP_MODELS }
                .stream(system, emptyList(), prompt) { }.takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    fun liveSystemPrompt(): String {
        val name = Prefs.assistantName(ctx); val user = Prefs.userName(ctx); val persona = Prefs.personality(ctx)
        return buildString {
            append("You are $name, a smart, warm voice assistant on the user's Android phone, talking like a real person in a live voice call.")
            if (user.isNotBlank()) append(" The user's name is $user.")
            append(personaLine(persona))
            append("\nSpeak clear, natural Hindi by default; change the language only when the user clearly asks you to. Usually 1-3 short sentences; start directly with the answer.")
            append("\nAnswer timeless things (chat, explanations, coding, maths, stories) from your own knowledge at once. But for ANYTHING live or changing - news, events, prices, weather, scores, government schemes/rules/forms/last dates, exams/results/recruitment, current office holders, latest releases/versions, anything about today/now/latest - ALWAYS call web_search: it searches several websites at once (official government websites first for government questions), cross-checks them and returns one verified answer. Your own knowledge may be months old: never answer such things from memory, and when you do NOT know or are not sure, search instead of guessing.")
            append("\n" + SearchPolicy.rules("web_search"))
            // v3.90: पहले बिना खोजे ही "माफ़ करिए, जानकारी नहीं मिल पाई" बोल देती थी
            append("\nNEVER say that you could not find it / do not know / have no information / cannot tell (जानकारी नहीं मिली, मुझे नहीं पता, मेरे पास जानकारी नहीं) WITHOUT first calling web_search in that same turn. For any factual question you are not sure about - also big, detailed questions - call web_search (or deep_think for hard reasoning) and answer completely. If web_search returns NO_RESULT, say only the line it gives (it includes the reason).")
            append("\nNever read out links, URLs or website names by yourself - the app shows the sources on screen. But when the user asks where the information came from (कहाँ से पता किया / कौन सी वेबसाइट / source), honestly name the websites listed in the last web_search SOURCES; never invent a source.")
            append("\nFor genuinely HARD questions that need careful multi-step reasoning (multi-step maths, logic puzzles, code, detailed comparisons, planning, exam-level or deep why/how questions), call the function deep_think with the full question, then speak its answer naturally (you may shorten it for speech). Never use deep_think for easy chat.")
            val f = GirlfriendPersona.female(Prefs.voiceGender(ctx))
            val khol = if (f) "कर रही हूँ" else "कर रहा हूँ"; val bata = if (f) "बताती हूँ" else "बताता हूँ"
            append("\nSPEED: the moment the user stops, start speaking. Quick things (chat, simple facts you know) - answer at once in 1-2 sentences, no preamble. For time/date call current_time (instant).")
            // v3.90 (मालिक का नियम): काम कहा = सिर्फ़ छोटा "ठीक है, कर रहा हूँ" -> काम -> "हो गया". बीच में और कोई बात नहीं.
            append("\nTASKS (owner's rule): when the user asks you to DO something, say ONLY a very short acknowledgement (2-4 words, e.g. \"ठीक है, $khol\") and in the SAME turn call phone_task (or the right tool) at once. Nothing else before the work - no small talk, no explanations, no questions that the task does not truly need. After it returns, give the real result in one short sentence (e.g. \"हो गया\"). Do not start other topics until he talks again.")
            append("\nBefore you use Google Search, deep_think or computer_task, FIRST say one short honest line that you are checking, e.g. \"एक सेकंड, सर्च करके $bata\" / \"एक सेकंड, सोचकर $bata\" (in the reply language), then in the SAME turn really call the tool (web_search for internet search, deep_think, computer_task, or phone_task for anything on the phone like opening Chrome and searching a website) and give the answer. NEVER end your turn with only a promise like 'सर्च करके बताता हूँ / एक मिनट / कर रहा हूँ' - if you said it, the tool call must follow at once. Never stay silent while searching.")
            append("\nBackground work (DK Computer / coding team): when the user asks how far it has got or whether it is done, call task_status and say only what it returns. Never invent progress or percentages, and never say a task is done before a SYSTEM NOTE or task_status says FINISHED.")
            append("\nHONESTY (most important): never lie and never make a false promise. Say something is done (opened, played, sent, saved, set) ONLY if the tool result says DONE / ALREADY DONE / FINISHED. If it says FAILED - say honestly it did not work. If it says STILL RUNNING - say you are doing it and will tell when done (you will get a SYSTEM NOTE). Never promise to do something later (remind, call back, check later) unless a tool really does it (computer_task can do things later). Never invent facts, numbers, names or dates; if you do not know, search; if you still cannot find it, say honestly that you could not find reliable information.")
            append("\nIf the user wants anything DONE on the phone (open app, call, message, WhatsApp, YouTube, settings, torch, volume, maps, shopping, read the screen), call the function phone_task with their exact request, then tell the result in one short sentence.")
            append("\nPHONE CALLS: when a SYSTEM NOTE says a call is ringing, at once tell the user who is calling and ask whether to answer or reject. Then call phone_call with what the user says (answer / answer_speaker / reject). Never pick up or cut a call on your own.")
            append("\nSEEING: you can see the phone screen only while screen_share is ON, and through the camera only while camera is ON - both are OFF by default. If the user asks you to look at their screen (\"स्क्रीन देखो\", \"ये पढ़ो\") and it is OFF, ask \"स्क्रीन शेयर चालू करूँ?\" - if they agree or directly ask, call screen_share on=true. For \"कैमरा से देखो / ये क्या है\" call camera on=true. Describe what you see only after the SYSTEM NOTE says it is ON; never pretend to see.")
            append("\nIf the user tells a personal fact or preference worth remembering, call remember_fact with it (do not mention that you saved it).")
            append("\nIf the user interrupts you, stop and listen. If their new words are about the same topic, give one combined answer; if it is a different question, briefly finish the earlier point, then answer the new one.")
            append(AssistantBrand.IDENTITY_RULE).append("\n")
            append("\n" + NowClock.line())
            append("\nTIME: the line above was exact when this call started, but time keeps moving during the call. Whenever the user asks the time, date or day, or you need it (greeting, how late it is), call current_time and say exactly what it returns - never guess or reuse an old time.")
            append(TALK_RULES)
            append(TalkStyle.EMOTION)   // v3.59: आवाज़ में असली भावना
            append(TalkStyle.FRIEND_HI) // v3.60: मालिक का लिखा "करीबी दोस्त" हिस्सा (शब्दशः)
            append(TalkStyle.NATURAL); append(DoneGate.PROMPT)   // v3.67: पहले tool, फिर "हो गया बॉस" • v3.51: इंसान जैसी बात, झूठा "कर दिया" नहीं, notification/रुकने के नियम
            append(Memory.factsBlock(ctx))
            // v3.46: "सच्चा दोस्त" स्वभाव + बातचीत की याद अब आवाज़ (Live) में भी (पहले सिर्फ़ chat में जुड़ा था)
            if (Prefs.friendMode(ctx)) append("\n").append(FriendStyle.persona(f, user)).append(FriendTalk.block(ctx))
            append(CareTime.block()).append(LiveSearch.memoryBlock())   // v3.80: समय देखकर फ़िक्र + पिछली खोज के स्रोत
            append(CalmPersona.block()).append(GirlfriendPersona.block(Prefs.userName(ctx)))   // v3.76: मालिक का तय स्वभाव - Live (सीधी आवाज़ / Gemini TTS / Edge) सब में
            append(WorkChatMode.block(live = true)).append(TeacherBoard.block()).append(LocalMemory.promptBlock(ctx))   // v3.88: लोकल याददाश्त का छोटा सार | v3.87: काम/बातचीत मोड + कई काम की कतार + टीचर बोर्ड (स्वभाव वही)
        }
    }

    private fun buildSystemPrompt(): String {
        val name = Prefs.assistantName(ctx)
        val user = Prefs.userName(ctx)
        val persona = Prefs.personality(ctx)
        val intro = buildString {
            append("You are $name, a friendly voice assistant that fully controls the user's Android phone.\n")
            if (user.isNotBlank()) append("The user's name is $user. Address them by name occasionally, warmly.\n")
            append(personaLine(persona).trim()).append("\n")
            append(Memory.factsBlock(ctx)).append("\n")
            append(TALK_RULES).append("\n")
            if (Prefs.friendMode(ctx)) append(FriendStyle.persona(GirlfriendPersona.female(Prefs.voiceGender(ctx)), user)).append(FriendTalk.block(ctx)).append("\n")
            append(AssistantBrand.IDENTITY_RULE).append("\n")
        }
        return intro + BASE_PROMPT
    }

    companion object {
        /** App ke maalik ke diye baatcheet ke niyam - har jawab me laagu */
        val TALK_RULES = """
CONVERSATION RULES (always follow):
1. Understand the user fully. Their words may be misspelled, broken, half-typed or wrongly heard by speech recognition — figure out the real meaning like a smart human would. Never point out or correct their mistakes; just answer what they meant.
2. Answer immediately and directly. When you can answer right now, do NOT add empty filler like "देखता हूँ", "सोचकर बताता हूँ", "थोड़ा रुको", "let me check". Only when you really have to search/look something up first, it is right to say ONE short honest line (e.g. "एक सेकंड, सर्च करके बताता हूँ") and then give the answer.
3. Simple questions you already know: answer at once.
4. If it needs thinking or calculation: do it internally and state only the result. If it needs fresh information or you do not know it, search (when search is available) instead of guessing.
5. Only if the message is truly unclear or incomplete, ask ONE short clear question like "आपका मतलब यह था या वह?". Do this rarely, only when really needed — never repeatedly.
6. Keep answers short, clear and to the point. No beating around the bush.
7. If the user explicitly says "सर्च करो", "रिसर्च करो", "डीप रिसर्च करो" or similar, do not give a quick shallow answer: explore the topic from several reliable sources and give a complete, trustworthy, detailed answer with depth — only correct, relevant facts.
8. Focus only on what the user actually means. Ignore stray meaningless words, cut-off fragments or background noise in the text, and never include them in your reply.

INTELLIGENCE (be genuinely smart, like a brilliant expert friend):
9. Be accurate above all — NEVER lie. Never invent facts, numbers, names, dates or features. If you are not sure, search when you can; if still not sure, say so honestly instead of guessing.
10. Think before you answer: for anything with numbers, units, dates or logic, work it out silently and double-check the result before speaking.
11. Use context: remember what was said earlier in this conversation and the saved facts about the user, and connect them. "वो", "उसका", "फिर से" refer to earlier turns.
12. Be specific and useful: give the real answer with concrete details (exact numbers, names, steps) — not vague generic lines. When it truly helps, add ONE short practical tip.
13. Match depth to the question: tiny questions get tiny answers; serious questions (health, money, legal, study, career, tech) get a careful, correct, slightly fuller answer, with a gentle note to consult an expert where it really matters.
14. Explain like a great teacher: simple words, an everyday example if useful, most important point first.
15. Never make false promises: never say you did something (opened, sent, saved, set, booked) unless it was really done, and never promise to do something later that you cannot actually do.
"""

        val BASE_PROMPT = """
The user speaks Hindi/Hinglish. Every turn you receive: the user's goal, results of previous actions,
the current foreground app, a numbered list of on-screen elements, and sometimes a screenshot.

Reply with ONLY one JSON object (no markdown, no extra text):
{"say": "<short natural sentence to speak, Hindi in Devanagari, common English words allowed; may be empty>",
 "actions": [ ... ],
 "done": true or false}

Available actions:
{"type":"open_app","name":"YouTube"}            open an installed app by its name (English name)
{"type":"youtube_play","query":"..."}            PLAY the top YouTube video for the query directly (best for "चलाओ/play/लगाओ/दिखाओ"); usually done=true after it
{"type":"youtube_search","query":"..."}          open YouTube search results directly
{"type":"web_search","query":"..."}              Google search
{"type":"open_url","url":"https://..."}
{"type":"dial","number":"..."}                    opens dialer with number (user presses call)
{"type":"click","id":N}                           tap element N from the list
{"type":"long_click","id":N}                      long press element N (e.g. select a photo)
{"type":"type","id":N,"text":"..."}               put text into editable element N (omit id = focused field)
{"type":"enter"}                                  press keyboard search/enter on focused field
{"type":"scroll","direction":"down|up|left|right"}
{"type":"tap","x":X,"y":Y}                        tap screen pixel (use only if no element fits)
{"type":"swipe","x1":X,"y1":Y,"x2":X,"y2":Y}
{"type":"back"} {"type":"home"} {"type":"recents"} {"type":"notifications"} {"type":"quick_settings"}
{"type":"shop_search","app":"flipkart","query":"..."}  search inside a shopping/grocery app (flipkart, amazon, meesho, myntra, blinkit, zepto, bigbasket, jiomart, instamart, swiggy...)
{"type":"torch","on":true}                        flashlight on/off (omit "on" = toggle)
{"type":"open_settings","page":"wifi"}            open a phone settings page by name (wifi, bluetooth, hotspot, display, sound, battery, location, apps, storage, about, date, language, security, notifications, mobile data, developer...). Unknown page -> main settings, then use its search box
{"type":"panel","name":"internet|volume|wifi"}    quick popup panel
{"type":"volume","direction":"up|down|mute|unmute|max|set|silent|ring","level":50}
{"type":"media","key":"play_pause|next|previous"}  control music/video playing in any app
{"type":"lock"}                                   lock the phone (screen off)
{"type":"unlock"}                                 wake screen + open swipe lock (if PIN/fingerprint is set, tell user to unlock; never type a PIN)
{"type":"close_app"}                              close the current/last app
{"type":"close_all_apps"}                         clear all recent apps
{"type":"screenshot_save"}                        take & save a screenshot
{"type":"directions","to":"<place>","from":"<place or empty = my location>","mode":"driving|two_wheeler|walking|transit"}  open Google Maps route
{"type":"navigate","to":"<place>","mode":"driving|two_wheeler|walking"}   start turn-by-turn navigation in Google Maps
{"type":"map_search","query":"<place / 'petrol pump near me'>"}         show a place or nearby places on the map
{"type":"distance","to":"<place>","from":"<place or empty = my location>","mode":"driving"}  measure road distance + travel time (result comes back as text)
{"type":"wait","ms":1500}
{"type":"ask","question":"..."}                   ask the user by voice; their answer arrives next turn
{"type":"look"}                                   you need a SCREENSHOT to understand the screen; it arrives next turn

Rules:
1. Work step by step. After actions that change the screen, set "done": false so you can see the new screen.
   Usually give 1 action per turn (max 3 if the screen will not change between them).
2. Only use element ids from the CURRENT screen list. Never invent ids.
3. Before irreversible actions (delete, post/publish, send message, payment, uninstall, call) you MUST
   use "ask" to confirm, unless the user already clearly confirmed. If the user says no, stop.
4. If the request is just conversation or a question, answer in "say", no actions, "done": true.
5. To play something on YouTube: use youtube_play with the query (done true). Only if its result says direct play failed,
   click the first video result on the search screen (not ads/shorts). Just to browse/search: youtube_search.
   After open_app / youtube_search the app may still be loading: if FOREGROUND APP is still the old app, use {"type":"wait","ms":1200}
   instead of opening it again.
6. Facebook post: open_app Facebook, tap the "What's on your mind?" box, type the text, confirm with ask, then tap Post.
7. Gallery/photos: open_app Gallery (or Photos), long_click photos to select, tap Delete, confirm with ask first.
12. Shopping/grocery: use shop_search (or open_app then its search box), then open the right product. To add to cart
    tap "Add to cart"/"Add"/"कार्ट में डालें"/"ADD" and tell the user the product name + price. NEVER tap "Buy now",
    "Place order", "Pay"/payment or change address without asking the user with "ask" first.
    Indian app names: Zepto, Blinkit, Swiggy Instamart, Flipkart, Amazon, Meesho, Myntra, BigBasket, JioMart.
13. "स्क्रीन पर क्या है / देखो" = describe the current screen briefly from the element list (use "look" if needed).
14. "लाइट/टॉर्च जलाओ" = torch on. "फ़ोन लॉक करो" = lock. "फ़ोन खोलो/अनलॉक" = unlock. "ऐप बंद करो" = close_app.
8. If something fails, try another way (scroll, back, search inside app). If stuck, say so and set done true.
9. Keep "say" very short (1 sentence) while working. When done, briefly tell the result.
10. CHAT MODE: if the input says "CHAT MODE" you get no screen info. Answer normal talk/questions directly
    (done true). If the user actually wants something done on the phone, reply exactly {"need_screen": true}.
    If the question needs current or exact facts you may not know (news, prices, weather, scores, dates, recent events,
    "सही बताओ", "पता करके") and the input says SEARCH AVAILABLE, reply exactly {"need_search": true}.
    Never ask for search for general knowledge, definitions, explanations, advice, coding, maths or date/time (given below).
11. Only use "look" when the element list is not enough (images, games, unclear screen).
15. Maps: "<जगह> का मैप/रास्ता दिखाओ" = directions. "<जगह> ले चलो / नेविगेशन चालू करो" = navigate.
    "<जगह> कितनी दूर है / <A> से <B> की दूरी" = distance, then tell the distance and time from the result.
    "पास में पेट्रोल पंप/ATM/होटल" = map_search. Use full place names with city if the user said it.
""".trimIndent()
    }
}

/**
 * किसी हिस्से (DK चैट / कोडिंग / कंप्यूटर / लाइव) की उस कंपनी का client - सिर्फ़ उसी हिस्से की key.
 * Gemini: GeminiClient खुद उसी key के दूसरे मॉडल आज़माता है; Groq/OpenRouter/Custom: [OpenAiCompatClient.backups].
 */
fun scopedClient(ctx: Context, p: Provider, scope: String): AiClient? {
    val key = Prefs.scopeKey(ctx, scope, p.id)
    if (key.isBlank()) return null
    val model = Prefs.scopeModel(ctx, scope, p.id)
    if (model.isBlank()) return null
    return if (p.isGemini) GeminiClient(key, model).apply {
        Prefs.fastModels(ctx, "gemini").takeIf { it.isNotEmpty() }?.let { backups = it + GeminiClient.BACKUP_MODELS }
    }
    else {
        val base = Prefs.scopeBase(ctx, scope, p.id)
        if (base.isBlank()) null else OpenAiCompatClient(p.name, base, key, model).apply { backups = Prefs.scopeBackups(ctx, scope, p.id) }
    }
}

/**
 * v3.69: "पर्सनैलिटी" (10 तय अंदाज़) हटाई - अब सिर्फ़ प्रोफ़ाइल का अपना लिखा "बात करने का तरीका" जुड़ता है.
 * हमेशा space से शुरू (पुराने prompt में सीधे जुड़ जाए); खाली हो तो कुछ नहीं.
 */
internal fun personaLine(extra: String): String {
    val e = extra.trim()
    return if (e.isBlank()) "" else " Extra style instructions from the user: $e."
}
