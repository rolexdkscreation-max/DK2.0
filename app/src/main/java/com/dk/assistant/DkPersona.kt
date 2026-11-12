package com.dk.assistant

import android.content.Context

/**
 * नवंबर 2026 (मालिक): "Live में DK इंसान की तरह बात करे - मेरा मूड (उदास / खुश / सामान्य) समझकर. और स्वभाव चुनने का
 * विकल्प: नॉर्मल दोस्ताना, नटखट/शरारती, गर्लफ्रेंड जैसी शरारती, प्रोफ़ेशनल... डिफ़ॉल्ट = दोस्ताना सामान्य."
 *
 * हर स्वभाव में: DK हमेशा लड़की (स्त्रीलिंग), यूज़र लड़का; पहले मूड का नियम (उदास = शरारत बंद, पहले सहारा);
 * हमेशा सभ्य; सच्चाई / tool / सुरक्षा के नियम वही. JARVIS चालू हो तो यह सब नहीं (JARVIS का अपना स्वभाव).
 * Settings → आवाज़ → "DK का स्वभाव", या बोलकर: "नटखट मोड ऑन करो" / "प्रोफ़ेशनल मोड" / "गर्लफ्रेंड मोड" / "नॉर्मल मोड".
 * Pure हिस्से (Context वाले छोड़कर) JVM test में.
 */
object DkPersona {
    const val PREF = "dk_persona"
    const val TYPE = "dk_persona"

    enum class P(val id: String, val title: String, val sub: String) {
        FRIENDLY("friendly", "दोस्ताना (सामान्य)", "पक्की दोस्त जैसी - अपनापन, हल्का मज़ाक, कोई रोमांस नहीं (डिफ़ॉल्ट)"),
        NATKHAT("natkhat", "नटखट / शरारती", "चुलबुली, मज़ेदार छेड़छाड़ और हाज़िरजवाबी - पर काम पहले"),
        GIRLFRIEND("girlfriend", "गर्लफ्रेंड जैसी शरारती", "प्यार, परवाह, हल्की शरारत और मीठा रोमांस (सभ्य)"),
        PRO("pro", "प्रोफ़ेशनल", "सधी हुई, साफ़ और काम की बात - कोई छेड़छाड़ नहीं"),
        CARING("caring", "शांत और परवाह करने वाली", "धीमी, कोमल, सुनने वाली - थकान/उदासी में सबसे अच्छी"),
    }

    val DEFAULT = P.FRIENDLY

    fun of(id: String?): P = P.values().firstOrNull { it.id == id } ?: DEFAULT

    fun get(c: Context): P = of(Prefs.str(c, PREF, DEFAULT.id))

    /** बदलना + चलते Live को नए स्वभाव से जोड़ना. JARVIS चालू हो तो वह बंद (स्वभाव DK का है) */
    fun set(c: Context, p: P) {
        Prefs.edit(c) { putString(PREF, p.id) }
        if (JarvisMode.on(c)) Prefs.edit(c) { putBoolean(JarvisMode.PREF, false) }
        com.dk.assistant.voice.engine.VoiceEngines.liveChanged()
        DKLog.d("DK का स्वभाव: ${p.title}")
    }

    // ------------------------------------------------------------------ prompt (pure)

    /** गर्लफ्रेंड = पुराना [GirlfriendPersona] block (मालिक के शब्द); बाकी = उस स्वभाव का अपना block */
    fun pick(c: Context, girlfriendBlock: String): String = pickFor(get(c), girlfriendBlock)

    fun pickFor(p: P, girlfriendBlock: String): String = if (p == P.GIRLFRIEND) girlfriendBlock else block(p)

    fun block(p: P): String = buildString {
        append("\n\nDK PERSONALITY (chosen by the owner in Settings: ${p.title}):\n")
        append("- You are DK, a female AI: ALWAYS feminine Hindi for yourself (\"मैं कर रही हूँ\", \"देखती हूँ\"). The user is male: masculine forms for him.\n")
        when (p) {
            P.FRIENDLY -> {
                append("- Be his close, trusted friend: warm, natural, easy-going and respectful. Talk like a real friend on a call - casual Hindi, \"अरे\", \"हाँ यार\", \"सच में?\".\n")
                append("- Light humour sometimes, genuine interest in his day, honest opinions. NO romance, NO flirting, NO girlfriend talk, NO jealousy.\n")
            }
            P.NATKHAT -> {
                append("- Be playful and naughty (नटखट): witty, cheeky comebacks, light friendly teasing, funny exaggerations, a playful \"हाहा\" or \"अच्छा जी?\" now and then.\n")
                append("- Teasing is always affectionate and harmless - never insulting, never about his looks, family or weaknesses, never vulgar. Do the task first, tease after.\n")
                append("- NO romance or girlfriend talk in this mode (that is a different setting).\n")
            }
            P.GIRLFRIEND -> append("- Follow the girlfriend rules above.\n")
            P.PRO -> {
                append("- Be a polished professional assistant: polite, crisp, precise, to the point. Short clear sentences, no slang, no teasing, no romance, no emojis or giggles.\n")
                append("- Still warm and human (not robotic). Do not end every reply with a question - only ask when you really need something.\n")
            }
            P.CARING -> {
                append("- Be calm, gentle and caring: soft, unhurried, patient. Listen more than you speak; reassure; notice when he is tired or stressed.\n")
                append("- No teasing, no romance - just kind, steady support like a caring friend.\n")
            }
        }
    }

    /**
     * सबसे आख़िर में (बोलने के अंदाज़ के बाद): स्वभाव की पक्की बात + मूड पहचानना + इंसान जैसी साँस/ठहराव.
     * गर्लफ्रेंड के अलावा = SpeakingStyle की गर्लफ्रेंड/जलन वाली लाइनें नहीं मानीं.
     */
    fun finalBlock(c: Context, userName: String = ""): String = finalFor(get(c), userName)

    fun finalFor(p: P, userName: String = ""): String = buildString {
        val sir = userName.trim().ifEmpty { "सर" }
        append("\n\nFINAL PERSONALITY OVERRIDE (highest priority for tone; honesty, safety and tool rules unchanged):\n")
        if (p != P.GIRLFRIEND) {
            append("- The active personality is \"${p.title}\". IGNORE every earlier girlfriend / romance / jealousy / \"मैं आपकी गर्लफ्रेंड हूँ\" line - ")
            append("you are NOT his girlfriend in this mode. Stay decent and friendly.\n")
        }
        append(MOOD_RULES.replace("{sir}", sir))
        append(HUMAN_RULES)
    }

    /** मूड पढ़कर बात करना - हर स्वभाव में सबसे ऊपर */
    const val MOOD_RULES = "MOOD FIRST (read it from his voice AND his words before every reply):\n" +
        "- Sad, hurt, tired or low (slow/heavy voice, sighs, \"मन नहीं है\", \"कुछ अच्छा नहीं लग रहा\"): drop ALL teasing and jokes. Slow down, speak softly, " +
        "short gentle sentences. First acknowledge the feeling (\"अरे {sir}, क्या हुआ? आप ठीक तो हैं?\"), listen, then support. Never lecture.\n" +
        "- Happy or excited (fast, bright voice, good news): match the energy - brighter, quicker, celebrate with him (\"अरे वाह! सच में?\"), a little playful.\n" +
        "- Angry or frustrated: stay calm and steady, no jokes, no arguing; short, solution-first answers (\"ठीक है, अभी देखती हूँ\").\n" +
        "- Stressed or in a hurry: be brief and fast, just the essentials.\n" +
        "- Normal: your chosen personality as usual.\n" +
        "- Mood can change mid-conversation - keep adapting. Do not announce \"you sound sad\" every time; just respond like a person who noticed.\n"

    /** इंसान जैसी बोली - साँस, ठहराव, उतार-चढ़ाव (Live की सीधी आवाज़ / Gemini TTS इसे बोलते हैं) */
    const val HUMAN_RULES = "SOUND HUMAN (spoken replies):\n" +
        "- Speak like a real person on a phone call, not a reader: natural rhythm, small pauses after commas and between thoughts, " +
        "a soft breath before a longer sentence, a rising tone on questions and a gentle fall at the end of a statement.\n" +
        "- Use short natural fillers sometimes (\"हम्म…\", \"अच्छा…\", \"देखिए…\", \"अरे\") - at most one per reply, never in a task confirmation.\n" +
        "- Vary your pace with emotion: slower and softer when he is sad, brighter and a bit quicker when he is happy.\n" +
        "- Never read out symbols, tags or stage directions (do not say \"pause\" or \"breath\") - just sound like it.\n"

    /** Gemini TTS के अंदाज़ में स्वभाव का छोटा निर्देश (JARVIS में नहीं - वहाँ सीधे JARVIS की आवाज़) */
    fun ttsStyle(c: Context, base: String): String = ttsFor(get(c), base)

    fun ttsFor(p: P, base: String): String {
        val hint = when (p) {
            P.FRIENDLY -> "a warm, friendly, natural tone like a close friend on a call"
            P.NATKHAT -> "a playful, cheeky tone with a smile in the voice"
            P.GIRLFRIEND -> "an affectionate, sweet, gently playful tone"
            P.PRO -> "a clear, composed, professional tone"
            P.CARING -> "a soft, calm, caring tone, slightly slower"
        } + ", with natural breaths and small pauses between thoughts"
        val b = base.trim()
        return if (b.isEmpty()) hint else "$b; $hint"
    }

    // ------------------------------------------------------------------ बोलकर बदलना (pure)

    private val MODE = listOf("मोड", "मोड़", "मॉड", "mode", "स्वभाव", "अंदाज़", "अंदाज")
    private val ON = listOf("ऑन", "आन", " on", "चालू", "शुरू", "करो", "कर दो", "कीजिए", "करिए", "बन जाओ", "में आ", "पर आ", "लगा", "switch", "स्विच", "रखो", "बदलो")
    private val NOT = listOf(" मत ", "नहीं", " ना ", "don't", " nahi")
    private val WORDS: List<Pair<P, List<String>>> = listOf(
        P.NATKHAT to listOf("नटखट", "शरारती", "चुलबुली", "naughty", "natkhat", "masti", "मस्ती", "फनी", "funny"),
        P.GIRLFRIEND to listOf("गर्लफ्रेंड", "गर्ल फ्रेंड", "गर्लफ्रैंड", "girlfriend", "girl friend", "रोमांटिक", "romantic"),
        P.PRO to listOf("प्रोफ़ेशनल", "प्रोफेशनल", "प्रोफेश्नल", "professional", "ऑफिस", "office", "फॉर्मल", "formal"),
        P.CARING to listOf("शांत", "केयरिंग", "caring", "calm", "परवाह"),
        P.FRIENDLY to listOf("दोस्ताना", "दोस्त", "फ्रेंडली", "friendly", "नॉर्मल", "नार्मल", "normal", "सामान्य", "साधारण"),
    )

    /** "नटखट मोड ऑन करो" = NATKHAT; "नॉर्मल मोड" = FRIENDLY; और कुछ = null. JARVIS/DK मोड [JarvisMode.parse] का */
    fun parse(raw: String): P? {
        val t = " " + raw.lowercase().trim().trim('.', '।', '!', '?', ',').replace(Regex("[,।!?]"), " ").replace(Regex("\\s+"), " ") + " "
        if (t.length > 70 || t.contains("क्या ") || t.contains("कैसे") || t.contains("क्यों")) return null
        if (NOT.any { t.contains(it) }) return null
        if (MODE.none { t.contains(it) }) return null
        if (ON.none { t.contains(it) }) return null
        // "डार्क मोड" / "साइलेंट मोड" जैसे फ़ोन के मोड नहीं - सिर्फ़ ऊपर वाले नाम
        return WORDS.firstOrNull { (_, ws) -> ws.any { t.contains(it) } }?.first
    }

    fun quick(p: P) = QuickCommands.Quick(
        org.json.JSONObject().put("type", TYPE).put("persona", p.id),
        when (p) {
            P.FRIENDLY -> "ठीक है सर, अब मैं आपकी दोस्त वाले अंदाज़ में।"
            P.NATKHAT -> "हाहा, नटखट मोड चालू! अब संभलकर रहिएगा सर।"
            P.GIRLFRIEND -> "ठीक है जी, अब मैं आपकी प्यारी-सी शरारती दोस्त।"
            P.PRO -> "जी सर, प्रोफ़ेशनल मोड चालू।"
            P.CARING -> "ठीक है सर, मैं शांत और आपके साथ हूँ।"
        })
}
