package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    fun reset() { history.clear(); Memory.saveHistory(ctx, history) }
    /** Memory screen se "baatcheet mitao" hua ho to yahan bhi saaf */
    private fun syncEpoch() { if (epoch != Memory.historyEpoch) { epoch = Memory.historyEpoch; history.clear() } }

    private fun clientFor(p: Provider): AiClient? {
        val key = Prefs.key(ctx, p.id)
        if (key.isBlank()) return null
        val model = Prefs.model(ctx, p.id)
        if (model.isBlank()) return null
        return if (p.isGemini) GeminiClient(key, model)
        else {
            val base = Prefs.baseUrl(ctx, p.id)
            if (base.isBlank()) null else OpenAiCompatClient(p.name, base, key, model)
        }
    }

    /** Pehle chuna hua provider, phir (fallback ON ho to) baaki. */
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

    /** Galti ko aasaan Hindi me samjhao (bolne + screen par dikhane ke liye). */
    fun explain(e: Exception): String {
        val msg = e.message ?: ""
        val code = (e as? AiException)?.code ?: 0
        return when {
            e is java.net.UnknownHostException -> "इंटरनेट नहीं मिल रहा। नेट चालू करके फिर बोलिए।"
            e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException ->
                "AI बहुत देर लगा रहा है। इंटरनेट धीमा है या सर्वर व्यस्त है, फिर से बोलिए।"
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
            for (p in orderedProviders(chat)) {
                val client = clientFor(p) ?: continue
                val img = if (p.vision) imageB64 else null
                try {
                    if (client is GeminiClient) client.fast = chat
                    if (client is OpenAiCompatClient) client.fast = chat
                    val answer = try {
                        client.complete(system, history, prompt, img)
                    } catch (e: AiException) {
                        // kuch model photo nahi lete - bina photo dobara try
                        if (img != null && e.code == 400) client.complete(system, history, prompt, null) else throw e
                    }
                    lastUsed = client.label
                    DKLog.d("AI: ${client.label}")
                    remember(memoryNote, answer)
                    return@withContext parseJson(answer)
                } catch (e: Exception) {
                    DKLog.d("${client.label} fail: ${e.message}")
                    lastError = e
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
    suspend fun search(question: String, deep: Boolean): SearchResult = withContext(Dispatchers.IO) {
        syncEpoch()
        val client = GeminiClient(Prefs.key(ctx, "gemini"), Prefs.model(ctx, "gemini"))
        val name = Prefs.assistantName(ctx); val user = Prefs.userName(ctx)
        val today = java.text.SimpleDateFormat("d MMMM yyyy, EEEE", java.util.Locale.ENGLISH).format(java.util.Date())
        val sys = buildString {
            append("You are $name, a Hindi voice assistant")
            if (user.isNotBlank()) append(" talking to $user")
            append(". Today is $today. ALWAYS use Google Search before answering.")
            if (deep) {
                append(" This is a detailed/complex question: research it properly. Check several reliable sources")
                append(" (official sites, government, well-known news/reference sites) and compare them.")
                append(" Only state facts that the sources agree on; if sources disagree or something is uncertain, say that clearly.")
                append(" Never guess numbers, dates or names. Answer in natural spoken Hindi (Devanagari), 3-7 short sentences,")
                append(" most important point first, simple words, no markdown, no links, no bullet lists.")
            } else {
                append(" Find correct, up-to-date facts. Answer in natural spoken Hindi (Devanagari), 1-3 short sentences,")
                append(" no markdown, no links, no lists. Numbers/prices with units. If unsure, say so.")
            }
            Prefs.personality(ctx).let { if (it.isNotBlank()) append(" Extra: $it") }
            append(Memory.factsBlock(ctx))
        }
        val r = client.search(sys, history, question, deep)
        val ans = r.text.replace(Regex("[*#`]"), "").replace(Regex("\\[\\d+(,\\s*\\d+)*\\]"), "").trim()
        lastUsed = client.label
        remember("USER SAYS: $question", JSONObject().put("say", ans).put("done", true).toString())
        SearchResult(ans, r.sources)
    }

    /** Settings me "टेस्ट" button ke liye. */
    suspend fun test(p: Provider): String = withContext(Dispatchers.IO) {
        val client = clientFor(p) ?: return@withContext "पहले API key और model डालिए"
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
        history.add(Turn("user", note.take(1500)))
        history.add(Turn("model", answer.take(1500)))
        while (history.size > 24) history.removeAt(0)
        if (history.firstOrNull()?.role == "model") history.removeAt(0)
        try { Memory.saveHistory(ctx, history.toList()) } catch (_: Exception) {}
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
            if (persona.isNotBlank()) append(" Personality: $persona.")
            append("\nReply with ONLY JSON: {\"say\": \"<natural spoken Hindi in Devanagari, 1-3 short sentences, no markdown>\", \"done\": true}")
            append("\nIf the user wants something DONE on the phone (open/click/type/send/call/settings/shopping etc.) reply exactly {\"need_screen\": true}.")
            append("\nIf the question needs current or exact facts (news, prices, weather, scores, dates, recent events) and the input says SEARCH AVAILABLE, reply exactly {\"need_search\": true}.")
            append("\nIf it is a complex/detailed question (how/why, comparisons, health, law, money, history, technical, 'पूरी जानकारी') where a wrong answer would mislead, and SEARCH AVAILABLE, reply exactly {\"need_research\": true}.")
            append("\nSimple talk (hello, how are you, jokes, simple general knowledge) -> answer immediately yourself, short and warm.")
            append("\nIf the user tells you a personal fact or preference worth remembering (names, numbers, likes, plans), add \"remember\": \"<the fact in short Hindi>\" to the JSON.")
            append("\nONLY IF the user asks who made/created/built you or who your boss/owner is, answer: \"मुझे धीरज कुमार सिंह जी ने बनाया है। मैं धीरज सर के लिए काम करता हूँ, वही मेरे बॉस हैं।\" Never mention this otherwise.")
            append("\nToday: " + java.text.SimpleDateFormat("d MMMM yyyy, EEEE", java.util.Locale.ENGLISH).format(java.util.Date()))
            append(Memory.factsBlock(ctx))
        }
    }

    private fun buildSystemPrompt(): String {
        val name = Prefs.assistantName(ctx)
        val user = Prefs.userName(ctx)
        val persona = Prefs.personality(ctx)
        val intro = buildString {
            append("You are $name, a friendly voice assistant that fully controls the user's Android phone.\n")
            if (user.isNotBlank()) append("The user's name is $user. Address them by name occasionally, warmly.\n")
            if (persona.isNotBlank()) append("Personality / extra instructions from the user: $persona\n")
            append(Memory.factsBlock(ctx)).append("\n")
            append("Only if the user asks who made/created you or who your boss is: say \"मुझे धीरज कुमार सिंह जी ने बनाया है। मैं धीरज सर के लिए काम करता हूँ, वही मेरे बॉस हैं।\" Never mention it otherwise.\n")
        }
        return intro + BASE_PROMPT
    }

    companion object {
        val BASE_PROMPT = """
The user speaks Hindi/Hinglish. Every turn you receive: the user's goal, results of previous actions,
the current foreground app, a numbered list of on-screen elements, and sometimes a screenshot.

Reply with ONLY one JSON object (no markdown, no extra text):
{"say": "<short natural sentence to speak, Hindi in Devanagari, common English words allowed; may be empty>",
 "actions": [ ... ],
 "done": true or false}

Available actions:
{"type":"open_app","name":"YouTube"}            open an installed app by its name (English name)
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
5. To play something on YouTube: youtube_search, then click the first video result (not ads/shorts if possible).
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
11. Only use "look" when the element list is not enough (images, games, unclear screen).
15. Maps: "<जगह> का मैप/रास्ता दिखाओ" = directions. "<जगह> ले चलो / नेविगेशन चालू करो" = navigate.
    "<जगह> कितनी दूर है / <A> से <B> की दूरी" = distance, then tell the distance and time from the result.
    "पास में पेट्रोल पंप/ATM/होटल" = map_search. Use full place names with city if the user said it.
""".trimIndent()
    }
}
