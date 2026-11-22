package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * नवंबर 2026 (मालिक): DK लाइव में Z.ai / NVIDIA का विकल्प. आवाज़ Gemini Live ही बनाता है; "गहरी सोच" (deep_think)
 * वाला text जवाब - Settings → AI दिमाग → DK लाइव में Z.ai / NVIDIA की key डली हो तो पहले वहीं से (तेज़, Gemini की
 * Live limit बचती है). न चले / key नहीं = पहले जैसा Gemini ([BrainManager.think]).
 */
object LiveTextBrain {
    /** इतनी देर में जवाब नहीं = Gemini पर (बातचीत रुके नहीं) */
    const val TIMEOUT_MS = 20_000L

    /**
     * 20 नवंबर 2026 (मालिक: deep_think "तकनीकी विवरण मुझे नहीं पता" कहता था): सवाल के साथ DK की क्षमताओं का छोटा सार -
     * DK के बारे में पूछा जाए तो सही बताए
     */
    const val DK_BRIEF = "About DK (the assistant you help; answer questions about it from this, never say you do not know its details):\n" +
        "1. DK is an Android voice assistant app (Hindi/Hinglish) with Live voice, text chat and an offline voice.\n" +
        "2. It controls the phone: opens apps, settings, alarms, calls, SMS, WhatsApp messages/calls/files, Instagram/Facebook posts, notifications.\n" +
        "3. It sees: camera, phone screen, photos; does web search, deep research and remembers facts and every past conversation.\n" +
        "4. Agents: DK Computer (background research, files, reports, games), Coding Agent on the owner's GitHub repo, Website Forge, App Studio, Story Mode, Teacher Mode, image generation.\n" +
        // 22 नवंबर 2026 (मालिक: "DK किसी भी AI कंपनी / API का नाम नहीं लेगा"): यहाँ से कंपनियों के नाम हटाए
        "5. It has its own brain built by its owner, with automatic backup so it keeps answering fast even when one path fails.\n" +
        "6. It knows its owner by voice, keeps memory on the phone, and has Hamdard (caring) mode."

    const val SYSTEM = "You help a live voice assistant. Think carefully, then give only the final answer, short and clear, " +
        "in the same language and script as the question. No markdown, no lists unless needed. Never repeat a sentence.\n" + DK_BRIEF +
        // 22 नवंबर 2026: यह जवाब DK बोलता है - NVIDIA / Nemotron जैसा मॉडल अपनी कंपनी का नाम न बोले
        "\n" + AssistantBrand.IDENTITY_RULE

    /** 20 नवंबर 2026 (मालिक: "Z.ai का timeout 8s से 3s"): Z.ai की एक कोशिश ज़्यादा से ज़्यादा इतनी */
    const val ZAI_MS = 3_000L

    /** इस कंपनी को कितना समय: Z.ai 3 s, बाकी [ThinkTime.stepMs]. Pure - JUnit से जाँच */
    fun stepFor(id: String, ms: Long): Long = if (id == "zai") minOf(ms, ZAI_MS) else ms

    /** किस कंपनी से (पहली जिसकी key डली है). [key] = DK लाइव में उस कंपनी की key. Pure - JUnit से जाँच */
    fun pick(key: (String) -> String): String? = ApiScopes.LIVE_TEXT.firstOrNull { key(it).isNotBlank() }

    /**
     * नवंबर 2026 (मालिक): सिर्फ़ पहली नहीं - जिन-जिन की key डली है, सब बारी-बारी (Z.ai → NVIDIA → Groq).
     * हर API अपने फ्री मॉडल पहले आज़माती है ([Providers.fallbackModels]), सब खत्म = अगली API, फिर Gemini. Pure.
     */
    // 20 नवंबर 2026 (मालिक: "सबसे पहले Groq (openai/gpt-oss-120b), फिर Z.ai, फिर NVIDIA")
    fun order(key: (String) -> String): List<String> = listOf("groq", "zai", "nvidia").filter { key(it).isNotBlank() }

    /** 20 नवंबर 2026 (मालिक: "सामान्य बातचीत, हालचाल और भावनात्मक बातों के लिए deep_think मत बुलाओ") */
    const val CASUAL_NOTE = "Not a hard question - no deep thinking needed. Answer the user yourself right now, warmly and briefly, in their language."

    private val CHAT = listOf("कैसे हो", "कैसी हो", "कैसा है", "क्या हाल", "हालचाल", "हाल चाल", "kaise ho", "kya haal", "how are you",
        "नमस्ते", "नमस्कार", "hello", "good morning", "good night", "शुभ", "धन्यवाद", "शुक्रिया", "thank", "सुप्रभात")
    private val HARD = listOf("क्यों", "कैसे", "समझाओ", "तुलना", "अंतर", "फर्क", "फ़र्क", "हिसाब", "गणना", "कैलकुलेट", "प्लान", "योजना",
        "कोड", "प्रोग्राम", "सवाल हल", "solve", "explain", "why", "how", "compare", "difference", "calculate", "code", "plan", "proof")
    private val FEEL = listOf("उदास", "दुखी", "दुख", "अकेला", "अकेली", "रोना", "रो रहा", "रो रही", "गुस्सा", "डर लग", "टेंशन", "परेशान",
        "मन नहीं", "मूड", "प्यार", "याद आ", "miss", "sad", "lonely", "upset", "angry", "love you", "tension", "दिल")

    /**
     * हालचाल / नमस्ते / भावनात्मक या बहुत छोटी आम बात = deep_think नहीं (Live खुद तुरंत जवाब दे). गणित, क्यों/कैसे,
     * तुलना, कोड, प्लान = मुश्किल सवाल. Pure - JUnit से जाँच
     */
    fun casual(question: String): Boolean {
        val q = question.lowercase().trim()
        if (q.isBlank()) return true
        if (CHAT.any { q.contains(it) }) return true
        if (Regex("\\d\\s*[-+*/x×÷^%]\\s*\\d").containsMatchIn(q) || HARD.any { q.contains(it) }) return false
        if (FEEL.any { q.contains(it) }) return true
        return q.split(Regex("\\s+")).size <= 3
    }

    /** [deadline] = पूरे deep_think tool का आख़िरी पल ([ThinkTime]) - हर कोशिश ज़्यादा से ज़्यादा [ThinkTime.STEP_MS] */
    suspend fun answer(c: Context, question: String, deadline: Long = System.currentTimeMillis() + ThinkTime.TOTAL_MS): String? {
        if (question.isBlank()) return null
        // 20 नवंबर 2026 (मालिक): चिप में चुना मॉडल पहले; न चले = बताकर पहले जैसा रास्ता
        // 20 नवंबर 2026 (लॉग: 41 s रुकावट): पहले 20 s + blocking call नहीं रुकती थी -> अब [ThinkTime.within] (8 s में अगला)
        try { ThinkTime.within(ThinkTime.stepMs(deadline, System.currentTimeMillis())) { BrainManager(c).chipAsk(SYSTEM, question) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { DKLog.d("deep_think: चिप वाला मॉडल नहीं चला (${e.message?.take(60)})"); null }
            ?.let { DKLog.d("deep_think: चिप के चुने मॉडल से जवाब"); return it }
        val ids = order { Prefs.scopeKey(c, ApiScopes.LIVE, it) }
        if (ids.isEmpty()) return null
        // 20 नवंबर 2026 (मालिक: "तीनों API एक साथ चलाओ, जो पहले जवाब दे वही लो"): Groq, Z.ai, NVIDIA एक साथ; पहला
        // भरा जवाब जीते, बाकी वहीं रद्द. हर API का अपना समय (Z.ai 3 s, बाकी 8 s), सब मिलाकर पूरे tool का बचा समय
        val left = ThinkTime.stepMs(deadline, System.currentTimeMillis())
        if (left <= 0L) { DKLog.d("deep_think: ${ThinkTime.TOTAL_MS / 1000} s पूरे - आगे की API नहीं"); return null }
        return ThinkTime.firstOf(ids.map { stepFor(it, left) }) { i ->
            val id = ids[i]
            val p = Providers.byId(id) ?: return@firstOf null
            val base = Prefs.scopeBase(c, ApiScopes.LIVE, id); val model = Prefs.scopeModel(c, ApiScopes.LIVE, id)
            if (base.isBlank() || model.isBlank()) return@firstOf null
            val client = OpenAiCompatClient(p.name, base, Prefs.scopeKey(c, ApiScopes.LIVE, id), model)
                .apply { fast = true; backups = Providers.fallbackModels(id) }
            val ms = stepFor(id, left)
            try {
                val r = ThinkTime.within(ms) { withContext(Dispatchers.IO) { client.complete(SYSTEM, emptyList(), question, null) } }?.trim()
                if (r == null) DKLog.d("deep_think: ${p.name} ने ${ms / 1000} s में जवाब नहीं दिया - अगली API")
                else if (r.isNotBlank()) DKLog.d("deep_think: ${client.label} से जवाब (सबसे पहले)")
                r?.takeIf { it.isNotBlank() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                DKLog.d("deep_think: ${p.name} के सारे मॉडल नहीं चले (${e.message?.take(80)}) - अगली API"); null
            }
        }?.second ?: run { DKLog.d("deep_think: Z.ai/NVIDIA/Groq नहीं चले - Gemini से"); null }
    }
}
