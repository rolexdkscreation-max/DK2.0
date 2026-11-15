package com.dk.assistant

/**
 * 15 नवंबर 2026 (मालिक): "एक key वाले में सब उसी पर चले, पर भारी काम (जिसमें ज़्यादा request लगती हैं) सिर्फ़ बातचीत वाले
 * मॉडल से मत कराओ. बातचीत के मॉडल की free request वैसी ही बची रहे - ताकि बात अच्छे से हो और फ़ोन के काम भी चलें."
 *
 * Gemini की free limit हर मॉडल की अलग होती है (ai.google.dev/gemini-api/docs/rate-limits - per project, per model).
 * इसलिए एक ही key पर हर काम की अपनी "लेन" (अपने मॉडल) - एक काम दूसरे की limit नहीं खाता:
 *  - TALK  (बातचीत: चैट स्क्रीन, होम चैट, आम सवाल, खोज वाला जवाब)  : 3.5 Flash-Lite - सबसे ज़्यादा रोज़ की request
 *  - PHONE (फ़ोन के काम: हर कदम, स्क्रीन देखना, WhatsApp ऑटो-जवाब, सारांश) : 3.1 Flash-Lite, फिर 3 Flash (preview)
 *  - HEAVY (कोडिंग, DK कंप्यूटर, कहानी, वेबसाइट, गहरी खोज / गहरी सोच)   : 3.8 Flash, 3.6 Flash, आख़िर में 3 Flash (preview)
 * भारी और फ़ोन वाले काम बातचीत का मॉडल कभी नहीं छूते. बातचीत कभी न रुके: उसकी अपनी limit खत्म हो तभी आख़िर में फ़ोन वाली लेन.
 * सिर्फ़ एक-key मोड में ([on]); अलग-अलग keys वाले users का क्रम जैसा का तैसा. Pure (JUnit).
 */
object OneKeyLanes {
    enum class Lane { TALK, PHONE, HEAVY }

    /** एक-key मोड चालू? ([Prefs.oneKeyOn] हर बार पढ़ते समय यहाँ भी लिखता है - Android के बिना client भी जानें) */
    @Volatile var on = false

    const val TALK_MODEL = "gemini-3.5-flash-lite"
    const val PHONE_MODEL = "gemini-3.1-flash-lite"
    const val SHARED = "gemini-3-flash-preview"

    /** हर लेन के अपने मॉडल (पहला = मुख्य) */
    val OWN: Map<Lane, List<String>> = mapOf(
        Lane.TALK to listOf(TALK_MODEL),
        Lane.PHONE to listOf(PHONE_MODEL, SHARED),
        Lane.HEAVY to listOf(GeminiModels.CODER, "gemini-3.6-flash"),
    )
    /** अपनी लेन खत्म हो तो आख़िर में उधार (बातचीत का मॉडल किसी का उधार नहीं) */
    val SPARE: Map<Lane, List<String>> = mapOf(
        Lane.TALK to listOf(PHONE_MODEL, SHARED),
        Lane.PHONE to emptyList(),
        Lane.HEAVY to listOf(SHARED),
    )

    private fun bare(m: String) = m.trim().removePrefix("models/")

    /** यह मॉडल किसी लेन का अपना है? (कौन-सी) */
    fun ownerOf(m: String): Lane? = OWN.entries.firstOrNull { bare(m) in it.value }?.key

    /** इस लेन में यह मॉडल चल सकता है? अनजान मॉडल (Pro / Argon / flash-latest) = हाँ */
    fun allowed(lane: Lane, m: String): Boolean {
        val o = ownerOf(m) ?: return true
        return o == lane || bare(m) in SPARE.getValue(lane)
    }

    /**
     * किसी काम का मॉडल-क्रम इस लेन के हिसाब से: दूसरी लेन के मॉडल हटें, बाकी क्रम वही, अपनी लेन के मॉडल ज़रूर हों, उधार वाले सबसे आख़िर.
     * [keepFirst] = मालिक ने Settings में खुद यह मॉडल चुना है - वह सबसे पहले (उसकी मर्ज़ी).
     * एक-key बंद / [lane] null = जैसा था वैसा. कभी खाली नहीं.
     */
    fun order(lane: Lane?, models: List<String>, keepFirst: Boolean = false, enabled: Boolean = on): List<String> {
        if (!enabled || lane == null || models.isEmpty()) return models
        val spare = SPARE.getValue(lane)
        val input = models.map(::bare).filter { it.isNotBlank() }
        val first = if (keepFirst) input.take(1) else emptyList()
        val mine = input.filter { allowed(lane, it) && it !in spare }
        val out = (first + mine + OWN.getValue(lane) + spare).distinct()   // चुना क्रम (जैसे गहरी सोच में Argon पहले) बना रहे
        return out.filter { it in first || allowed(lane, it) }.ifEmpty { models }
    }

    /**
     * key की सेहत ([ProviderHealth]) किस नाम से: एक key में Gemini की हर लेन अलग (फ़ोन के काम की limit = बातचीत बंद नहीं).
     * बातचीत = पुराना नाम "gemini" (पहले जैसा). बाकी कंपनियाँ / एक-key बंद = वही id.
     */
    fun healthId(id: String, lane: Lane, enabled: Boolean = on): String =
        if (enabled && id == "gemini" && lane != Lane.TALK) "gemini:" + lane.name.lowercase() else id

    /** Settings के काम के हिस्से की लेन: चैट / लाइव = बातचीत, कोडिंग / कंप्यूटर = भारी */
    fun forScope(scope: String): Lane = when (scope) {
        ApiScopes.CODER, ApiScopes.PC -> Lane.HEAVY
        else -> Lane.TALK
    }

    /** ऐप के उस काम की आसान हिंदी (Settings में दिखाने को) */
    fun hindi(): String =
        "एक key में हर काम का अपना मॉडल: बातचीत = 3.5 Flash-Lite, फ़ोन के काम = 3.1 Flash-Lite, " +
            "कोडिंग / कंप्यूटर / कहानी / वेबसाइट / गहरी खोज = 3.8 Flash. भारी काम बातचीत की limit नहीं खाते।"
}
