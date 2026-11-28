package com.dk.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 20 नवंबर 2026 (मालिक: "Gemini को सिर्फ़ मेरा नया सवाल जाए, पुरानी बातचीत तभी जब मैं खुद पूछूँ"):
 * लॉग में Live का context 17k -> 52k token तक जाता था और बोलना खत्म होने के 6-9 s बाद पहली आवाज़.
 *
 * सेटिंग "पुरानी बातचीत सिर्फ़ माँगने पर" ([PREF], डिफ़ॉल्ट चालू):
 *  - Gemini को = DK के निर्देश + ज़रूरी बातों की छोटी सूची ([compactFacts], 20 बातें / ~1500 अक्षर) + नया सवाल.
 *  - पुरानी बातचीत नहीं - सिर्फ़ छोटी पूँछ ([HISTORY_TAIL] संदेश, डिफ़ॉल्ट 2 = आख़िरी सवाल-जवाब).
 *  - server की अपनी याद छोटी: तरीका A = contextWindowCompression तंग सीमा पर ([tightCompression]);
 *    मॉडल सीमा न ले / सीमा असर न करे = तरीका B: शांत पल में पहले से तैयार नए (बिना handle) सत्र पर चुपचाप बदलो ([rotateDue]).
 *  - पुरानी बात पूछी = search_history tool ([toolDeclaration]) - फ़ोन पर ही (SQLite FTS4, [LocalMemory.searchHistory]).
 * बंद = पहले जैसा (LiveCarry, पूरी याद, 32k compression, recall_conversation).
 *
 * Pure logic (सिर्फ़ org.json + java.time) -> JVM unit test ([HistoryOnDemandTest]).
 */
object HistoryOnDemand {
    const val PREF = "history_on_demand"
    const val DEFAULT_ON = true

    /** हर नए सत्र में पिछली बातचीत के कितने संदेश (2 = आख़िरी सवाल + जवाब). 0 = कुछ नहीं */
    const val HISTORY_TAIL = 2
    /** पूँछ इससे पुरानी हो तो नहीं (ऐप कल खुला था = "उसे दोबारा बोलो" का मतलब नहीं) - सूची से खोली चैट पर नहीं लगता */
    const val TAIL_WINDOW_MS = 30 * 60_000L
    const val TAIL_TURN_CHARS = 300
    /** पूँछ की आख़िरी लाइन आपकी (उसका जवाब नहीं गया - जैसे नेट चला गया) */
    const val OLD_ASK = "(old, not answered then - ignore it unless he asks again)"

    const val FACTS_MAX = 20
    const val FACTS_CHARS = 1_500

    const val TOOL = "search_history"
    const val DEFAULT_LIMIT = 5
    const val MAX_LIMIT = 5
    const val RESULT_CHARS = 300

    // ------------------------------------------------------------------ server की याद (सेक्शन 2)
    /** स्थिर हिस्सा (निर्देश + tools + याद) अभी नापा नहीं = यह अंदाज़ा */
    const val FIXED_GUESS = 4_000L
    /** स्थिर हिस्से के ऊपर इतनी बातचीत होते ही छँटाई */
    const val TRIGGER_EXTRA = 3_500L
    /** छँटाई के बाद स्थिर हिस्से के ऊपर इतनी ताज़ा बातचीत बचे */
    const val TARGET_EXTRA = 1_500L
    const val TRIGGER_MIN = 5_000L
    /** मालिक की शर्त: 20 बातों के बाद भी context 10k के नीचे */
    const val TRIGGER_MAX = 9_000L
    /** इससे बड़े स्थिर हिस्से पर भी छँटाई के बाद हाल की [TARGET_EXTRA] बातें बचें */
    const val RECENT_FROM = 5_500L
    const val CONTEXT_LIMIT = 10_000L
    /** तरीका A के बावजूद context इतनी बार लगातार सीमा से ऊपर = छँटाई असर नहीं कर रही -> तरीका B */
    const val OVER_TURNS = 2
    /** तरीका B: इतने token (या स्थिर + 4k, जो बड़ा) या इतनी बारियों पर शांत पल में नया सत्र */
    const val ROTATE_AT_TOKENS = 8_000L
    const val ROTATE_TURNS = 10

    enum class Method(val label: String) { A_COMPRESSION("A (contextWindowCompression)"), B_ROTATE("B (सत्र चुपचाप नया)") }

    data class Compression(val trigger: Long, val target: Long)

    /**
     * स्थिर हिस्से ([fixed] token, 0 = नहीं पता) के हिसाब से तंग सीमा: स्थिर + ~3.5k बातचीत पर छँटाई, छँटाई के बाद
     * स्थिर + ~1.5k. स्थिर हिस्सा छोटा हो तो सीमा 9k पर रुकती है (= कुल 10k के नीचे); स्थिर ही बड़ा हो तो
     * सीमा उससे कम नहीं हो सकती (server निर्देश/tools कभी नहीं छाँटता) - तब भी context लगातार नहीं बढ़ता.
     */
    fun tightCompression(fixed: Long): Compression {
        val f = if (fixed > 0) fixed else FIXED_GUESS
        // 20 नवंबर 2026 (मालिक: छँटाई में हाल की बातें बचें): स्थिर हिस्सा बड़ा (≥ 5.5k) = पहले f + 2k पर छँटाई और
        // ~1k ही ताज़ा बचता था; अब f + 3.5k पर छँटाई, f + 1.5k तक (हाल की ~1.5k token बातें बचें). छोटा स्थिर = पहले जैसा
        val trigger = maxOf(TRIGGER_MIN, minOf(TRIGGER_MAX, f + TRIGGER_EXTRA), f + if (f >= RECENT_FROM) TRIGGER_EXTRA else 2_000L)
        val target = minOf(f + TARGET_EXTRA, trigger - 1_000L).coerceAtLeast(2_000L)
        return Compression(trigger, target)
    }

    fun compressionJson(c: Compression): JSONObject = JSONObject()
        .put("triggerTokens", c.trigger)
        .put("slidingWindow", JSONObject().put("targetTokens", c.target))

    /** मॉडल ने setup में सीमा मानी (compressLimits) = A, मना की ("triggerTokens नहीं लेता") = B */
    fun method(limitsAccepted: Boolean, ineffective: Boolean): Method =
        if (limitsAccepted && !ineffective) Method.A_COMPRESSION else Method.B_ROTATE

    /** तरीका A चल रहा पर हर turn का context [OVER_TURNS] बार लगातार trigger + 2.5k से ऊपर = छँटाई असर नहीं कर रही */
    fun ineffective(recent: List<Long>, trigger: Long): Boolean =
        recent.size >= OVER_TURNS && recent.takeLast(OVER_TURNS).all { it > trigger + 2_500L }

    /** tool वाले turn की जोड़ी गिनती → एक बार सोचने का context (rounds = इस turn में आए toolCall) */
    fun perGeneration(raw: Long, rounds: Int): Long = if (rounds <= 0 || raw <= 0) raw else raw / (rounds + 1)

    /** तरीका B: नया सत्र कब (शांत पल का फ़ैसला GeminiLive करता है) */
    fun rotateDue(method: Method, ctxTokens: Long, turnsSinceFresh: Int, fixed: Long = 0L): Boolean =
        method == Method.B_ROTATE && (ctxTokens > maxOf(ROTATE_AT_TOKENS, (if (fixed > 0) fixed else FIXED_GUESS) + 4_000L) || turnsSinceFresh >= ROTATE_TURNS)

    /** अक्षरों से token का मोटा अंदाज़ा (setup लॉग के लिए; असली गिनती server के usageMetadata से) */
    fun estimateTokens(text: String): Long {
        var ascii = 0L; var other = 0L
        for (ch in text) if (ch.code < 128) ascii++ else other++
        return ascii / 4 + other / 2
    }

    // ------------------------------------------------------------------ Gemini को क्या जाए
    data class Turn(val role: String, val text: String, val time: Long)

    /** आख़िरी [n] संदेश (u/d), [TAIL_WINDOW_MS] के अंदर (reopened = समय की शर्त नहीं). कुछ न हो / n=0 = "" */
    fun tail(turns: List<Turn>, now: Long, n: Int = HISTORY_TAIL, reopened: Boolean = false): String {
        if (n <= 0) return ""
        val last = turns.filter { it.text.isNotBlank() && !it.text.endsWith("…") && (it.role == "u" || it.role == "d") &&
            (reopened || now - it.time in 0..TAIL_WINDOW_MS) }.takeLast(n)
        if (last.isEmpty()) return ""
        // 28 नवंबर 2026 (मालिक का लॉग): नेट जाने के बाद जुड़ते ही DK ने पूँछ वाला पुराना सवाल ("realme का दाम") खुद खोज डाला,
        // जबकि आपने कुछ और कहा था. अब साफ़: यह बात खत्म हो चुकी; आख़िरी लाइन आपकी (जवाब नहीं मिला) हो तो वह भी पुरानी.
        return "\n\nLAST EXCHANGE (already over - only for follow-ups like \"उसे दोबारा बोलो\"; never answer, search or act on it again " +
            "unless his newest words ask for it):\n" +
            last.withIndex().joinToString("\n") { (i, t) ->
                val x = t.text.replace(Regex("\\s+"), " ").trim().let { if (it.length > TAIL_TURN_CHARS) it.take(TAIL_TURN_CHARS) + "…" else it }
                (if (t.role == "u") "User: " else "DK: ") + x + if (t.role == "u" && i == last.lastIndex) " $OLD_ASK" else ""
            }
    }

    /** systemInstruction में हर सत्र पर (resume हो या नहीं) */
    const val RULE = "\n\nPAST CONVERSATION (owner's rule): you are NOT given the old conversation. Answer ONLY the user's newest words - " +
        "never bring up, continue or summarise older topics on your own. When the user asks about an earlier conversation " +
        "(कल क्या कहा था, पहले क्या बात हुई थी, याद है क्या, पिछली बार क्या पूछा था, what did I say yesterday), call $TOOL and answer only " +
        "from its results (say the date/time naturally); if it returns nothing, say honestly that you do not find it. NEVER call $TOOL for normal questions." +
        // 20 नवंबर 2026 (मालिक: "याद रखने को कहा हो तो कभी भी पूछूँ, ढूँढकर बताए"): ऊपर की सूची में सिर्फ़ 20 बातें जाती हैं
        " Exception: if the user asks about something personal they once told you to remember (मेरी बाइक का नंबर क्या है, " +
        "मैंने क्या याद रखने को कहा था) and it is not in IMPORTANT FACTS, call $TOOL - it also searches every saved fact on the phone."

    /** ज़रूरी बातें: पहले पक्की बातें (नई पहले चुनो), फिर DK का सीखा - कुल [FACTS_MAX] बातें, [FACTS_CHARS] अक्षर */
    fun compactFacts(facts: List<String>, learned: List<String> = emptyList(), maxItems: Int = FACTS_MAX, maxChars: Int = FACTS_CHARS): String {
        val header = "IMPORTANT FACTS ABOUT THE USER (use only when relevant):\n"
        val picked = ArrayList<String>()
        var len = header.length
        var left = 0
        fun pick(src: List<String>) {
            for (f in src.asReversed()) {
                val line = "- " + f.replace(Regex("\\s+"), " ").trim()
                if (line.length <= 2) continue
                if (picked.size >= maxItems || len + line.length + 1 > maxChars) { left++; continue }
                picked.add(line); len += line.length + 1
            }
        }
        pick(facts.filter { it.isNotBlank() })
        val nFacts = picked.size
        pick(learned.filter { it.isNotBlank() })
        if (picked.isEmpty()) return ""
        // पक्की बातें पुराने क्रम में, फिर सीखा हुआ
        val out = picked.subList(0, nFacts).asReversed() + picked.subList(nFacts, picked.size).asReversed()
        val more = if (left > 0) "\n($left more facts are saved on this phone - only if the user asks about them, call $TOOL.)" else ""
        return (header + out.joinToString("\n") + more).take(maxChars + 120)
    }

    // ------------------------------------------------------------------ search_history tool
    const val TOOL_DESC = "Search the user's OLD conversations with DK, saved on this phone (works offline). " +
        "इसे सिर्फ़ तब बुलाओ जब यूज़र पुरानी बातचीत के बारे में पूछे, जैसे कल क्या कहा था, पहले क्या बात हुई थी, याद है क्या। आम सवाल पर कभी मत बुलाओ। " +
        "Call it ONLY when the user asks about a past conversation (what did I say / ask yesterday, what did we talk about, do you remember, " +
        "पिछली बार क्या पूछा था). NEVER call it for normal questions. Keep time words (कल, आज, 2 दिन पहले, पिछले हफ़्ते) inside query - " +
        "the phone understands them - or pass from_date/to_date. Empty/only time words query = the most recent earlier turns. " +
        "It also searches EVERY fact the user asked DK to remember - call it for a personal detail (number, date, place, name) not in the list you have."

    /**
     * 28 नवंबर 2026 (मालिक का लॉग): "क्या कर रहे हो" पर Live ने हर बार search_history बुलाया और पुरानी बातें पढ़कर
     * "मैं अभी पुरानी बातें चेक कर रही..." कहा. आपसे अभी की हालचाल वाली बात (पुरानी बातचीत का कोई इशारा नहीं) = खोज नहीं.
     */
    fun smallTalk(q: String): Boolean {
        val t = q.trim().lowercase()
        return t.isNotEmpty() && SMALL.containsMatchIn(t) && !PAST.containsMatchIn(t)
    }

    const val SMALL_TALK_REPLY = "NOT SEARCHED: this is not about an old conversation - he is talking to you right now. " +
        "Do not search. Answer his newest words directly in 1-2 short natural lines (what you are doing / how you are, in your own words)."

    private val SMALL = Regex("क्या\\s*कर\\s*र(?:हे|ही|हा)|कैसे\\s*हो|कैसी\\s*हो|क्या\\s*हाल|कहाँ\\s*हो|सुन\\s*र(?:हे|ही)|" +
        "what\\s*are\\s*you\\s*doing|how\\s*are\\s*you|kya\\s*kar\\s*rah|kaise\\s*ho")
    private val PAST = Regex("कल|परसों|पहले|याद|पिछल|था|थी|थे|बताया|yesterday|remember|ago|last\\s*time|\\d{4}-\\d{2}")

    fun toolDeclaration(): JSONObject = JSONObject().put("name", "search_history")
        .put("description", TOOL_DESC)
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("query", JSONObject().put("type", "STRING").put("description", "Words to search, Hindi and/or English, in the user's words (may be empty)"))
                .put("from_date", JSONObject().put("type", "STRING").put("description", "Optional start date YYYY-MM-DD"))
                .put("to_date", JSONObject().put("type", "STRING").put("description", "Optional end date YYYY-MM-DD (inclusive)"))
                .put("limit", JSONObject().put("type", "INTEGER").put("description", "How many results (default 5, max 5)")))
            .put("required", JSONArray()))

    /** tools की सूची में recall_conversation की जगह search_history (बाकी सब वैसे ही). लौटाता है: बदला? */
    fun swapRecallTool(tools: JSONArray): Boolean {
        for (i in 0 until tools.length()) {
            val decl = tools.optJSONObject(i)?.optJSONArray("functionDeclarations") ?: continue
            for (j in 0 until decl.length()) {
                if (decl.optJSONObject(j)?.optString("name") == "recall_conversation") { decl.put(j, toolDeclaration()); return true }
            }
        }
        return false
    }

    data class Req(val words: List<String>, val from: Long, val to: Long, val who: String?, val limit: Int, val label: String)

    private val R_I = Regex("(मैंने|मैने|मै ने|मैं ने|हमने|maine|mainne|humne|hamne|\\bi\\s+(said|asked|told|say|ask)|\\bdid\\s+i\\b)")
    /** "पिछली बार क्या पूछा था" = यूज़र ने ही पूछा था */
    private val R_ASKED = Regex("(पूछा|पूछी|पूछे|puchha|pucha|poocha|\\basked\\b)")
    private val R_YOU = Regex("(तुमने|तुम ने|आपने|आप ने|तूने|tumne|aapne|\\byou\\s+(said|told|say)|\\bdid\\s+you\\b)")

    /** tool के args -> खोज. शब्द LocalRecall.keywords से (रोक-शब्द / समय-शब्द हटे), समय: from_date/to_date या query के समय-शब्द */
    fun request(query: String, fromDate: String?, toDate: String?, limit: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Req {
        val q = LocalRecall.parse(query, now, zone)
        val n = LocalRecall.norm(query)
        var from = q.from; var to = q.to; var label = q.label
        parseDate(fromDate, zone)?.let { from = it; label = fromDate!!.trim() }
        parseDate(toDate, zone)?.let { to = minOf(now, it + LocalRecall.DAY_MS - 1); label = (if (label.isBlank()) "" else "$label..") + toDate!!.trim() }
        if (to < from) { val x = from; from = to; to = x }
        val who = when { R_YOU.containsMatchIn(n) -> "d"; R_I.containsMatchIn(n) || R_ASKED.containsMatchIn(n) -> "u"; else -> null }
        val words = MemoryMeaning.expand(q.words.filter { it !in EXTRA_STOP })
        return Req(words, from, to, who, (if (limit <= 0) DEFAULT_LIMIT else limit).coerceIn(1, MAX_LIMIT), label)
    }

    /** "पिछली बार क्या पूछा था" = कोई खोज-शब्द नहीं (सबसे हाल की पिछली बारियाँ) */
    private val EXTRA_STOP = setOf("पिछली", "पिछला", "पिछले", "पीछे", "बार", "पहली", "आखिरी", "आख़िरी", "आखरी", "pichli", "pichhli", "bar", "baar",
        "last", "time", "previous", "earlier", "before", "conversation", "history", "पुरानी", "पुराना", "purani", "बोलो", "सुनाओ").map { LocalRecall.norm(it) }.toSet()

    fun parseDate(s: String?, zone: ZoneId): Long? = try {
        s?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it.take(10)).atStartOfDay(zone).toInstant().toEpochMilli() }
    } catch (_: Exception) { null }

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

    /** FTS4 MATCH: हर शब्द अलग से (कोई भी मिले = OR), prefix (बात* = बातें भी). null = कोई शब्द नहीं */
    fun ftsQuery(words: List<String>): String? {
        val toks = words.flatMap { w -> WORD.findAll(LocalRecall.norm(w)).map { it.value }.toList() }
            .filter { it.length >= 2 }.distinct().take(12)
        if (toks.isEmpty()) return null
        return toks.joinToString(" OR ") { "$it*" }
    }

    data class Row(val id: Long, val who: String, val text: String, val at: Long, val session: String = "")

    /** कितने अलग खोज-शब्द इस text में (prefix से, छोटे-बड़े अक्षर एक) */
    fun matchCount(words: List<String>, text: String): Int {
        val toks = WORD.findAll(LocalRecall.norm(text)).map { it.value }.toList()
        return words.map { LocalRecall.norm(it) }.distinct().count { w -> toks.any { it.startsWith(w) } }
    }

    /**
     * क्रम: पहले मिलान (कितने शब्द मिले), फिर नयापन. शब्द न हों = सबसे नई पिछली बारियाँ.
     * याद वाले सवाल खुद (और उनके ठीक बाद का DK जवाब) नतीजों में नहीं; अभी का सवाल ([current]) भी नहीं.
     */
    fun rank(rows: List<Row>, req: Req, current: String = ""): List<Row> {
        val cur = LocalRecall.norm(current.trimEnd('…'))
        val skip = HashSet<Long>()
        val byTime = rows.sortedWith(compareBy<Row> { it.at }.thenBy { it.id })
        for ((i, r) in byTime.withIndex()) {
            val isAsk = r.who == "u" && (LocalRecall.isRecall(r.text) || (cur.isNotEmpty() && LocalRecall.norm(r.text) == cur))
            if (isAsk) { skip.add(r.id); byTime.getOrNull(i + 1)?.takeIf { it.who == "d" }?.let { skip.add(it.id) } }
        }
        val ok = byTime.filter { it.id !in skip && it.at in req.from..req.to && (req.who == null || it.who == req.who) && it.text.isNotBlank() }
        if (req.words.isEmpty()) return ok.takeLast(req.limit).asReversed()
        val n = ok.size
        return ok.withIndex()
            .map { (i, r) -> Triple(r, matchCount(req.words, r.text), i) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<Row, Int, Int>> { it.second * 10.0 + 5.0 * it.third / maxOf(1, n) }.thenByDescending { it.first.at })
            .take(req.limit).map { it.first }
    }

    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** Gemini को tool का जवाब: हर नतीजा तारीख़-समय, किसने कहा, text (300 अक्षर तक) */
    /**
     * 20 नवंबर 2026: [reply] + "याद रखो" वाली पक्की बातें जो सवाल से मिलीं ([LocalRecall.matchFacts]).
     * बातचीत में कुछ न मिले पर पक्की बात मिले = "मिला" (Gemini "नहीं मिला" न बोले).
     */
    fun withFacts(reply: String, facts: List<String>): String {
        if (facts.isEmpty()) return reply
        val o = try { JSONObject(reply) } catch (_: Exception) { JSONObject() }
        o.put("saved_facts", JSONArray().apply { facts.forEach { put(it) } })
        o.put("note", "SAVED FACTS found - the user asked DK to remember these. Answer from saved_facts (and results, if any), exactly; never invent.")
        return o.toString()
    }

    fun reply(rows: List<Row>, req: Req, zone: ZoneId = ZoneId.systemDefault()): String {
        val arr = JSONArray()
        rows.take(MAX_LIMIT).forEach { r ->
            arr.put(JSONObject()
                .put("when", Instant.ofEpochMilli(r.at).atZone(zone).format(FMT))
                .put("who", if (r.who == "u") "user" else "DK")
                .put("text", r.text.replace(Regex("\\s+"), " ").trim().let { if (it.length > RESULT_CHARS) it.take(RESULT_CHARS) + "…" else it }))
        }
        return JSONObject()
            .put("count", arr.length())
            .put("results", arr)
            .put("note", if (arr.length() == 0) "NOTHING FOUND in the saved conversations" + (if (req.label.isNotBlank()) " for ${req.label}" else "") +
                ". Tell the user honestly you do not find it - never invent." else
                "These are REAL saved turns from this phone (newest/most relevant first). Answer only from them, mention when it was said; never invent.")
            .toString()
    }
}
