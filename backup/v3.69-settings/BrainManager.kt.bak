package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
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
    }
    private fun reload() {
        synchronized(Memory.historyLock) {
            val fresh = try { Memory.loadHistory(ctx) } catch (_: Exception) { return }
            history.clear(); history.addAll(fresh); ver = Memory.historyVersion
        }
    }

    /** DK चैट की अपनी key (हर हिस्से की अलग - देखें [ApiScopes]); limit पर उसी key के दूसरे मॉडल */
    private fun clientFor(p: Provider, scope: String = ApiScopes.CHAT): AiClient? = scopedClient(ctx, p, scope)

    /** सिर्फ़ चुनी हुई कंपनी (दूसरी कंपनी पर अपने-आप जाना बंद - [Prefs.fallback]). */
    private fun orderedProviders(chat: Boolean = false): List<Provider> {
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

    fun canSearch() = Prefs.webAnswers(ctx) && Prefs.key(ctx, "gemini").isNotBlank()

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

    fun canThink() = Prefs.key(ctx, "gemini").isNotBlank()

    suspend fun search(question: String, deep: Boolean, thorough: Boolean = false, think: Boolean = false): SearchResult = withContext(Dispatchers.IO) {
        syncEpoch()
        val client = GeminiClient(Prefs.key(ctx, "gemini"), Prefs.model(ctx, "gemini"))
        val name = Prefs.assistantName(ctx); val user = Prefs.userName(ctx)
        val today = java.text.SimpleDateFormat("d MMMM yyyy, EEEE", java.util.Locale.ENGLISH).format(java.util.Date())
        val sys = buildString {
            append("You are $name, a Hindi voice assistant")
            if (user.isNotBlank()) append(" talking to $user")
            append(". Today is $today.")
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
        val r = client.search(sys, history, question, deep || thorough, think)
        val ans = r.text.replace(Regex("[*#`]"), "").replace(Regex("\\[\\d+(,\\s*\\d+)*\\]"), "").trim()
        lastUsed = client.label
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
            append(Personality.promptLine(Prefs.personalityPreset(ctx), persona))
            append("\nReply with ONLY JSON: {\"say\": \"<natural spoken Hindi in Devanagari, 1-3 short sentences, no markdown>\", \"done\": true}")
            append("\nIf the user wants something DONE on the phone (open/click/type/send/call/settings/shopping etc.) reply exactly {\"need_screen\": true}.")
            append("\nIf the input says SEARCH AVAILABLE and the question needs search under the policy below, reply exactly {\"need_search\": true}.")
            append("\n" + SearchPolicy.rules("{\"need_search\": true}"))
            append("\nEverything else (talk, general knowledge, how/why, advice, explanations, maths) -> answer IMMEDIATELY yourself from your own knowledge, short and warm. Do NOT ask for search for things you already know.")
            append("\nIf the user tells you a personal fact or preference worth remembering (names, numbers, likes, plans), add \"remember\": \"<the fact in short Hindi>\" to the JSON.")
            append(AssistantBrand.IDENTITY_RULE).append("\n")
            append("\nToday: " + java.text.SimpleDateFormat("d MMMM yyyy, EEEE", java.util.Locale.ENGLISH).format(java.util.Date()))
            append(TALK_RULES)
            append(TalkStyle.CHAT)   // v3.51: इंसान जैसी बात, झूठा "कर दिया" नहीं, notification/रुकने के नियम
            append(Memory.factsBlock(ctx))
            // v3.45: सच्चा दोस्त - लहजा + पिछली बातचीत की याद (आखिरी मुलाकात, सारांश, अधूरी बातें)
            if (Prefs.friendMode(ctx)) append("\n" + FriendStyle.persona(Prefs.voiceGender(ctx) != "male", user)).append(FriendTalk.block(ctx))
        }
    }

    /** Baatcheet (streaming) ka prompt: saada bola jaane wala text, JSON nahi = pehla shabd turant. */
    private fun buildChatStreamPrompt(): String {
        val name = Prefs.assistantName(ctx); val user = Prefs.userName(ctx); val persona = Prefs.personality(ctx)
        return buildString {
            append("You are $name, a smart, warm Hindi voice assistant on the user's Android phone, talking like a real person.")
            if (user.isNotBlank()) append(" The user's name is $user.")
            append(Personality.promptLine(Prefs.personalityPreset(ctx), persona))
            append("\nReply in PLAIN spoken Hindi (Devanagari; common English words ok). No JSON, no markdown, no lists, no emojis.")
            append(" Usually 1-3 short sentences; start directly with the answer. Even if earlier turns look like JSON, reply as plain text.")
            append("\nAnswer from your own knowledge immediately: talk, general knowledge, how/why, advice, explanations, maths, stories.")
            append("\nSPECIAL replies (the whole reply is only the tag):")
            append("\n- The user wants something DONE on the phone (open/click/type/send/call/settings/shopping/maps...): reply exactly [SCREEN]")
            append("\n- The input says SEARCH AVAILABLE and the question needs search under the WEB SEARCH POLICY below: reply exactly [SEARCH]")
            append("\n- The question is genuinely HARD and needs careful multi-step reasoning (multi-step maths/word problems, logic puzzles, writing or fixing code, detailed comparison, planning, deep why/how explanation, exam-level questions) and the input says THINK AVAILABLE: reply exactly [THINK]. Do NOT use it for simple chat or easy facts.")
            append("\nLEARN from the user every day: if they tell a personal fact, preference, habit, or how they like answers, add at the very end a new line: [YAAD] <that fact in short Hindi>")
            append(AssistantBrand.IDENTITY_RULE).append("\n")
            append("\nToday: " + java.text.SimpleDateFormat("d MMMM yyyy, EEEE, h:mm a", java.util.Locale.ENGLISH).format(java.util.Date()))
            append("\n" + SearchPolicy.rules("[SEARCH]"))
            append(TALK_RULES)
            append(TalkStyle.CHAT)   // v3.51: इंसान जैसी बात, झूठा "कर दिया" नहीं, notification/रुकने के नियम
            append(Memory.factsBlock(ctx))
        }
    }

    /** Gemini Live (awaaz se awaaz) ke liye system instruction */
    /**
     * v3.45: एक सीधा सवाल -> पूरा जवाब (बातचीत का सारांश वगैरह). चैट वाले AI एक-एक करके; null = कोई नहीं चला.
     * (system prompt / इतिहास नहीं - सिर्फ़ यही दो)
     */
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
            append(Personality.promptLine(Prefs.personalityPreset(ctx), persona))
            append("\nSpeak clear, natural Hindi by default; change the language only when the user clearly asks you to. Usually 1-3 short sentences; start directly with the answer.")
            append("\nAnswer from your own knowledge immediately when you are sure. Google Search is a tool you MAY call, never by default - but when you do NOT know or are not sure, search instead of guessing.")
            append("\n" + SearchPolicy.rules("Google Search"))
            append("\nNever read out links, URLs or website names unless asked - the app shows the sources on screen.")
            append("\nFor genuinely HARD questions that need careful multi-step reasoning (multi-step maths, logic puzzles, code, detailed comparisons, planning, exam-level or deep why/how questions), call the function deep_think with the full question, then speak its answer naturally (you may shorten it for speech). Never use deep_think for easy chat.")
            val f = Prefs.voiceGender(ctx) != "male"
            val khol = if (f) "खोल रही हूँ" else "खोल रहा हूँ"; val bata = if (f) "बताती हूँ" else "बताता हूँ"
            append("\nSPEED: the moment the user stops, start speaking. Quick things (chat, simple facts you know, date/time) - answer at once in 1-2 sentences, no preamble.")
            append("\nBefore calling phone_task, first say a very short natural acknowledgement (2-4 words, e.g. \"ठीक है, $khol\") so the user is not left in silence; after it returns, give the result in one short sentence.")
            append("\nBefore you use Google Search, deep_think or computer_task, FIRST say one short honest line that you are checking, e.g. \"एक सेकंड, सर्च करके $bata\" / \"एक सेकंड, सोचकर $bata\" (in the reply language), then do it and give the answer. Never stay silent while searching.")
            append("\nBackground work (DK Computer / coding team): when the user asks how far it has got or whether it is done, call task_status and say only what it returns. Never invent progress or percentages, and never say a task is done before a SYSTEM NOTE or task_status says FINISHED.")
            append("\nHONESTY (most important): never lie and never make a false promise. Say something is done (opened, played, sent, saved, set) ONLY if the tool result says DONE / ALREADY DONE / FINISHED. If it says FAILED - say honestly it did not work. If it says STILL RUNNING - say you are doing it and will tell when done (you will get a SYSTEM NOTE). Never promise to do something later (remind, call back, check later) unless a tool really does it (computer_task can do things later). Never invent facts, numbers, names or dates; if you do not know, search; if you still cannot find it, say honestly that you could not find reliable information.")
            append("\nIf the user wants anything DONE on the phone (open app, call, message, WhatsApp, YouTube, settings, torch, volume, maps, shopping, read the screen), call the function phone_task with their exact request, then tell the result in one short sentence.")
            append("\nPHONE CALLS: when a SYSTEM NOTE says a call is ringing, at once tell the user who is calling and ask whether to answer or reject. Then call phone_call with what the user says (answer / answer_speaker / reject). Never pick up or cut a call on your own.")
            append("\nSEEING: you can see the phone screen only while screen_share is ON, and through the camera only while camera is ON - both are OFF by default. If the user asks you to look at their screen (\"स्क्रीन देखो\", \"ये पढ़ो\") and it is OFF, ask \"स्क्रीन शेयर चालू करूँ?\" - if they agree or directly ask, call screen_share on=true. For \"कैमरा से देखो / ये क्या है\" call camera on=true. Describe what you see only after the SYSTEM NOTE says it is ON; never pretend to see.")
            append("\nIf the user tells a personal fact or preference worth remembering, call remember_fact with it (do not mention that you saved it).")
            append("\nIf the user interrupts you, stop and listen. If their new words are about the same topic, give one combined answer; if it is a different question, briefly finish the earlier point, then answer the new one.")
            append(AssistantBrand.IDENTITY_RULE).append("\n")
            append("\nToday: " + java.text.SimpleDateFormat("d MMMM yyyy, EEEE, h:mm a", java.util.Locale.ENGLISH).format(java.util.Date()))
            append(TALK_RULES)
            append(TalkStyle.EMOTION)   // v3.59: आवाज़ में असली भावना
            append(TalkStyle.FRIEND_HI) // v3.60: मालिक का लिखा "करीबी दोस्त" हिस्सा (शब्दशः)
            append(TalkStyle.NATURAL); append(DoneGate.PROMPT)   // v3.67: पहले tool, फिर "हो गया बॉस" • v3.51: इंसान जैसी बात, झूठा "कर दिया" नहीं, notification/रुकने के नियम
            append(Memory.factsBlock(ctx))
            // v3.46: "सच्चा दोस्त" स्वभाव + बातचीत की याद अब आवाज़ (Live) में भी (पहले सिर्फ़ chat में जुड़ा था)
            if (Prefs.friendMode(ctx)) append("\n").append(FriendStyle.persona(f, user)).append(FriendTalk.block(ctx))
        }
    }

    private fun buildSystemPrompt(): String {
        val name = Prefs.assistantName(ctx)
        val user = Prefs.userName(ctx)
        val persona = Prefs.personality(ctx)
        val intro = buildString {
            append("You are $name, a friendly voice assistant that fully controls the user's Android phone.\n")
            if (user.isNotBlank()) append("The user's name is $user. Address them by name occasionally, warmly.\n")
            append(Personality.promptLine(Prefs.personalityPreset(ctx), persona).trim()).append("\n")
            append(Memory.factsBlock(ctx)).append("\n")
            append(TALK_RULES).append("\n")
            if (Prefs.friendMode(ctx)) append(FriendStyle.persona(Prefs.voiceGender(ctx) != "male", user)).append(FriendTalk.block(ctx)).append("\n")
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
