package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * नवंबर 2026 (Live जाँच #2): हर connection पर ~31 tools (~15,000 अक्षर विवरण) जाते थे = setup भारी, पहला जवाब देर से,
 * मॉडल कभी गलत tool चुनता / tool बुलाए बिना "कर रहा हूँ" बोल देता.
 *
 * हल: Live को सिर्फ़ रोज़ के [CORE] tools पूरे विवरण के साथ. बाकी सब एक ही router tool [ROUTER] (`dk_agent`) के पीछे -
 * उसके विवरण में हर tool की एक छोटी लाइन + उसके parameter. मॉडल `dk_agent(tool=..., args_json=...)` बुलाता है,
 * DK फ़ोन पर [unwrap] करके वही पुराना tool चलाता है (कोई काम हटा नहीं, सिर्फ़ रास्ता छोटा).
 *
 * Pure logic (सिर्फ़ org.json) -> JVM unit test. सेटिंग: [LiveSmooth.PREF_SLIM_TOOLS] (डिफ़ॉल्ट चालू), बंद = पहले जैसा.
 */
object LiveToolSlim {
    const val ROUTER = "dk_agent"

    /** बातचीत में सबसे ज़्यादा और तुरंत चाहिए - इनका पूरा विवरण Live में रहे */
    val CORE: Set<String> = setOf(
        "phone_task", "web_search", "current_time", "deep_think", "whatsapp_task", "phone_call",
        "task_status", "notifications", "local_info", "remember_fact",
        "recall_conversation", "search_history", "look_screen",
        "deep_research", "look_photo",
        // 20 नवंबर 2026 (मालिक): "Gemini Live में generate_image(prompt) tool घोषित करो" - सीधे, router के पीछे नहीं
        "generate_image",
        // 25 नवंबर 2026 (मालिक: "दिखाओ तो पेज खोल के दिखाए") - तुरंत चाहिए, router के पीछे नहीं
        "show_page",
        // 20 नवंबर 2026 (मालिक का लॉग: निर्देश + tools ≈ 14100 token): कम चलने वाले notification_reply, screen_share,
        // camera, social_media_task_control अब dk_agent के पीछे (बुलाने पर वही काम, सिर्फ़ setup हल्का)
    )

    /**
     * 15 नवंबर 2026 (Dynamic Tool Router): routed tools इससे ज़्यादा हों तो router में हर tool की पूरी लाइन नहीं,
     * सिर्फ़ नाम + एक [SEARCH] tool - मॉडल उससे पूछे "कौन सा tool, कौन से parameter" ([search]: keyword से top [TOP_K]).
     * आज (~35 tools) यह सीमा नहीं छूती = पहले जैसा ही; सैकड़ों tools जुड़ें तब भी Live setup हल्का रहे.
     */
    const val COMPACT_AT = 45
    const val SEARCH = "dk_tool_search"
    const val TOP_K = 12

    /** हर routed tool की पूरी लाइन (नाम -> "name(a, b): विवरण") - [apply] भरता है, [search] पढ़ता है */
    @Volatile var catalog: Map<String, String> = emptyMap()
        private set

    /** router की एक लाइन में विवरण कितना लंबा */
    const val LINE_MAX = 90

    data class Result(val tools: JSONArray, val kept: Int, val routed: List<String>, val charsBefore: Int, val charsAfter: Int)

    /** tools (Live setup वाला JSONArray) में से भारी tools router के पीछे. [on]=false = जैसा था */
    fun apply(tools: JSONArray, on: Boolean): Result {
        val before = tools.toString().length
        if (!on) return Result(tools, -1, emptyList(), before, before)
        val out = JSONArray(); var kept = 0; val routed = mutableListOf<String>(); val lines = StringBuilder()
        val fp = HashMap<String, String>(); val cat = LinkedHashMap<String, String>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i)
            val decls = t?.optJSONArray("functionDeclarations")
            if (decls == null) { out.put(t ?: tools.opt(i)); continue }
            val keep = JSONArray()
            for (j in 0 until decls.length()) {
                val d = decls.optJSONObject(j) ?: continue
                val name = d.optString("name")
                if (name in CORE || name == ROUTER) { keep.put(d); kept++ }
                else { routed += name; val l = line(d); cat[name] = l; lines.append("\n- ").append(l); firstParamOf(d)?.let { fp[name] = it } }
            }
            if (routed.size > COMPACT_AT) {
                keep.put(router(routed, "\n" + routed.joinToString(", ") + "\nNot sure which one or its arguments? Call $SEARCH first."))
                keep.put(searchDecl())
            } else if (routed.isNotEmpty()) keep.put(router(routed, lines.toString()))
            out.put(JSONObject().put("functionDeclarations", keep))
        }
        firstParams = fp; catalog = cat
        return Result(out, kept, routed, before, out.toString().length)
    }

    /** वही JSONArray बदलो (setup में `.put("tools", LiveStay.cleanTools(tools))` जैसा रहे) */
    fun slimInPlace(tools: JSONArray, on: Boolean): Result {
        val r = apply(tools, on)
        if (r.kept < 0) return r
        while (tools.length() > 0) tools.remove(tools.length() - 1)
        for (i in 0 until r.tools.length()) tools.put(r.tools.get(i))
        return r
    }

    /** "name(a, b): पहला वाक्य" */
    internal fun line(d: JSONObject): String {
        val props = d.optJSONObject("parameters")?.optJSONObject("properties")
        val ps = props?.keys()?.asSequence()?.toList().orEmpty()
        val desc = firstSentence(d.optString("description"))
        return d.optString("name") + (if (ps.isEmpty()) "" else "(" + ps.joinToString(", ") + ")") + ": " + desc
    }

    internal fun firstSentence(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        val cut = Regex("(?<=[.!?।])\\s").find(t)?.range?.first ?: t.length
        val one = t.substring(0, cut).trim()
        return if (one.length <= LINE_MAX) one else one.take(LINE_MAX - 1).trimEnd() + "…"
    }

    internal fun router(names: List<String>, lines: String): JSONObject = JSONObject()
        .put("name", ROUTER)
        .put("description", "Run one of DK's other abilities. Use it exactly like calling that tool directly: " +
            "set tool to the ability name and args_json to a JSON object of its arguments. If any instruction or system " +
            "note mentions a tool name that is not in your list, call it through dk_agent. Abilities:" + lines)
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("tool", JSONObject().put("type", "STRING").put("description", "ability name")
                    .put("enum", JSONArray(names)))
                .put("args_json", JSONObject().put("type", "STRING")
                    .put("description", "JSON object with that ability's arguments, e.g. {\"task\":\"...\"}")))
            .put("required", JSONArray().put("tool")))

    internal fun searchDecl(): JSONObject = JSONObject().put("name", SEARCH)
        .put("description", "Find which of DK's other abilities (called via $ROUTER) fits a request, with its arguments. Give a few words of what the user wants.")
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().put("query", JSONObject().put("type", "STRING").put("description", "what the user wants, few words")))
            .put("required", JSONArray().put("query")))

    /** Pure: [catalog] में keyword से सबसे मिलते [k] tools (नाम + parameter + विवरण) */
    fun search(query: String, k: Int = TOP_K, cat: Map<String, String> = catalog): String {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}_]+")).filter { it.length > 1 }
        if (cat.isEmpty()) return "No extra abilities."
        val scored = cat.entries.map { (n, l) ->
            val hay = l.lowercase(); val nm = n.lowercase()
            Triple(n, words.sumOf { w -> (if (nm.contains(w)) 3 else 0) + (if (hay.contains(w)) 1 else 0) }, l)
        }
        val top = scored.filter { it.second > 0 }.sortedByDescending { it.second }.take(k)
        val list = top.ifEmpty { scored.take(k) }
        return "Call one through $ROUTER(tool=<name>, args_json={...}):\n" + list.joinToString("\n") { "- " + it.third }
    }

    /**
     * Router call -> असली tool. `dk_agent(tool=coding_task, args_json={"task":"..."})` = ("coding_task", {"task":"..."}).
     * args_json JSON न हो (मॉडल ने सीधा वाक्य लिखा) = उस tool का पहला ज़रूरी parameter ([firstParam]) उसी वाक्य से.
     * router नहीं = जैसा था.
     */
    fun unwrap(name: String, args: JSONObject, firstParam: (String) -> String? = { firstParams[it] }): Pair<String, JSONObject> {
        if (name != ROUTER) return name to args
        val tool = args.optString("tool").trim()
        if (tool.isEmpty()) return name to args
        val raw = args.opt("args_json")
        val inner: JSONObject = when (raw) {
            is JSONObject -> raw
            is String -> parseObj(raw) ?: JSONObject().apply { if (raw.isNotBlank()) put(firstParam(tool) ?: "task", raw.trim()) }
            else -> JSONObject()
        }
        // मॉडल ने कभी args_json के बिना ही बाकी keys सीधे रख दीं
        for (k in args.keys()) if (k != "tool" && k != "args_json" && !inner.has(k)) inner.put(k, args.get(k))
        return tool to inner
    }

    private fun parseObj(s: String): JSONObject? = try {
        val t = s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t.startsWith("{")) JSONObject(t) else null
    } catch (_: Exception) { null }

    /** सीधा वाक्य आए तो किस parameter में डालें: हर routed tool का पहला ज़रूरी (या पहला) parameter ([apply] भरता है) */
    @Volatile var firstParams: Map<String, String> = emptyMap()
        private set

    internal fun firstParamOf(d: JSONObject): String? {
        val p = d.optJSONObject("parameters") ?: return null
        p.optJSONArray("required")?.optString(0)?.takeIf { it.isNotBlank() }?.let { return it }
        return p.optJSONObject("properties")?.keys()?.asSequence()?.firstOrNull()
    }
}

/** नवंबर 2026 (Live जाँच): नई सेटिंग्स की keys एक जगह */
object LiveSmooth {
    /** #1 कॉल-mode स्पीकर: फ़ोन का पक्का echo-cancel (डिफ़ॉल्ट बंद - हर फ़ोन पर आवाज़ थोड़ी धीमी लग सकती है) */
    const val PREF_CALL_SPEAKER = "live_call_speaker"
    /** #2 हल्के tools (router) - डिफ़ॉल्ट चालू */
    const val PREF_SLIM_TOOLS = "live_slim_tools"
    /** #3 स्मार्ट VAD (Silero, फ़ोन पर) - डिफ़ॉल्ट चालू; model न खुले तो पुराना RMS */
    const val PREF_SMART_VAD = "live_smart_vad"
    /** #4 caption बंद/टूटा हो तो Live का अपना लिखा (inputTranscription) बात-खत्म के फ़ैसले में - डिफ़ॉल्ट चालू */
    const val PREF_HEARD_FALLBACK = "live_heard_fallback"
}
