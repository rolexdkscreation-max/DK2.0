package com.dk.assistant

/**
 * Gemini Live setup के नियम - Google के docs से मिलाए (नवंबर 2026), JVM test में चलते हैं.
 * https://ai.google.dev/gemini-api/docs/live-guide
 *
 *  - "native audio models only support AUDIO response modality": Live से TEXT कभी नहीं माँगते.
 *    Kokoro / Gemini TTS जैसा अलग आवाज़ इंजन चुना हो = AUDIO + outputAudioTranscription, Live की आवाज़ नहीं बजती,
 *    उसकी लिखावट वह इंजन बोलता है (पहले TEXT माँगते थे -> setup मना -> Live जुड़ता ही नहीं था).
 *  - native audio मॉडल "don't support explicitly setting the language code" -> speechConfig.languageCode नहीं
 *    (हिंदी में बोलना system instruction से).
 *  - enableAffectiveDialog: 3.1 Flash Live नहीं लेता, 3.8-live के लिए docs साफ़ नहीं -> पहले setup में नहीं.
 */
object LiveSetupRules {
    const val MODALITY = "AUDIO"

    /**
     * 18 नवंबर 2026 (ऑडिट G2, मालिक: "1 घंटा+ बिना रुके बातचीत"): पहले `slidingWindow: {}` (Google default संख्या नहीं बताता)
     * -> context 128k तक बढ़ता, हर नई बात पर पूरा दोबारा गिना जाता = लंबी बात में जवाब धीमे, मुफ़्त quota जल्दी खत्म.
     * अब: 32k token (~20 मिनट की आवाज़) पर पुरानी बातें हटें, ~16k (~10 मिनट) ताज़ा याद बचे. पुरानी बातें DK की
     * अपनी याद (memory / LiveCarry) से. setup मना करे = पहले जैसा खाली (GeminiLive.compressLimits).
     * https://ai.google.dev/gemini-api/docs/live-api/best-practices
     */
    const val COMPRESS_TRIGGER_TOKENS = 32_000L
    const val COMPRESS_TARGET_TOKENS = 16_000L

    fun compression(limits: Boolean): org.json.JSONObject =
        if (!limits) org.json.JSONObject().put("slidingWindow", org.json.JSONObject())
        else org.json.JSONObject()
            .put("triggerTokens", COMPRESS_TRIGGER_TOKENS)
            .put("slidingWindow", org.json.JSONObject().put("targetTokens", COMPRESS_TARGET_TOKENS))

    /**
     * 18 नवंबर 2026 (ऑडिट G3): gemini-3.8-live पर function calling का default NON_BLOCKING (async) है - tool चलते-चलते
     * मॉडल "हो गया" बोल सकता है / नतीजा बीच बात में टोकता है. DK का पूरा tool-flow (LivePromise, ActHold, AGENT_WAIT)
     * blocking मानकर बना है (2.5 / 3.1 जैसा) -> 3.8 पर हर tool पर साफ़ `behavior: BLOCKING`. बाकी मॉडल पर कुछ नहीं भेजते.
     * https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live
     */
    const val TOOL_BEHAVIOR = "BLOCKING"
    fun blockingTools(model: String): Boolean = model.lowercase().startsWith("gemini-3.8-live")

    /** tools array की हर functionDeclaration पर behavior लगाओ (जो पहले से लिखा हो उसे नहीं छेड़ते). लौटाता है कितनों पर लगा */
    fun stampBehavior(tools: org.json.JSONArray, behavior: String = TOOL_BEHAVIOR): Int {
        var n = 0
        for (i in 0 until tools.length()) {
            val decl = tools.optJSONObject(i)?.optJSONArray("functionDeclarations") ?: continue
            for (j in 0 until decl.length()) {
                val d = decl.optJSONObject(j) ?: continue
                if (!d.has("behavior")) { d.put("behavior", behavior); n++ }
            }
        }
        return n
    }

    /** Live (native audio) मॉडल? - नाम में "live" / "native-audio" */
    fun isLiveAudioModel(model: String): Boolean {
        val m = model.lowercase()
        return m.contains("live") || m.contains("native-audio")
    }

    fun sendLanguageCode(model: String): Boolean = !isLiveAudioModel(model)

    @Suppress("UNUSED_PARAMETER")
    fun sendAffective(model: String): Boolean = false

    /** Live से TEXT जवाब माँगना - कभी नहीं (बँटवारे में भी AUDIO की लिखावट) */
    @Suppress("UNUSED_PARAMETER")
    fun askText(model: String, split: Boolean): Boolean = false
}
