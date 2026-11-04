package com.dk.assistant

import android.content.Context

/**
 * नवंबर 2026 (मालिक): "DK में एक JARVIS मोड - JARVIS जैसी आवाज़ और अंदाज़, पर काम सारे वही जो DK करता है
 * (पूरी access). 'DK, जार्विस मोड ऑन करो' = JARVIS; JARVIS चालू हो तब 'DK मोड ऑन करो' = वापस DK.
 * Settings में भी ऑन/ऑफ का स्विच."
 *
 * सिर्फ़ स्वभाव + आवाज़ बदलती है: tools, keys, फ़ोन का कंट्रोल, एजेंट - सब वही (कुछ बंद नहीं होता).
 * बदलते ही [com.dk.assistant.voice.engine.VoiceEngines.liveChanged] = चलता DK लाइव चुप होते ही नई आवाज़/स्वभाव से जुड़ता है.
 */
object JarvisMode {
    const val PREF = "jarvis_mode"
    const val VOICE_PREF = "jarvis_voice"

    /** JARVIS की आवाज़: गहरी, शांत, समझाने वाली पुरुष आवाज़ (Gemini की अपनी आवाज़ें) - पहली = डिफ़ॉल्ट */
    val VOICES = listOf(
        "Charon" to "Charon — गहरी, शांत, समझाने वाली (JARVIS जैसी, डिफ़ॉल्ट)",
        "Algieba" to "Algieba — स्मूद, सधी हुई",
        "Iapetus" to "Iapetus — साफ़, सटीक",
        "Orus" to "Orus — मज़बूत, भरोसेमंद",
    )
    const val DEFAULT_VOICE = "Charon"

    /** Prefs.voiceStyle की जगह (पुराना Gemini TTS रास्ता) */
    const val STYLE_ID = "jarvis"

    /** आवाज़ का अंदाज़ (Gemini TTS / Live की सीधी आवाज़) */
    const val VOICE_STYLE = "the voice of a calm, refined, highly intelligent AI butler like JARVIS: a deep, smooth, composed male voice, " +
        "crisp and precise diction with a slight polished British-English elegance, measured confident pace, quiet warmth and respect, " +
        "a hint of dry wit; never excited, never loud, never robotic"

    fun on(c: Context): Boolean = Prefs.bool(c, PREF, false)

    fun voice(c: Context): String = Prefs.str(c, VOICE_PREF, DEFAULT_VOICE).takeIf { v -> VOICES.any { it.first == v } } ?: DEFAULT_VOICE

    /** चालू/बंद + चलते Live को नई आवाज़ से जोड़ना */
    fun set(c: Context, on: Boolean) {
        Prefs.edit(c) { putBoolean(PREF, on) }
        com.dk.assistant.voice.engine.VoiceEngines.liveChanged()
        DKLog.d(if (on) "JARVIS मोड चालू" else "DK मोड चालू (JARVIS बंद)")
    }

    fun setVoice(c: Context, v: String) {
        Prefs.edit(c) { putString(VOICE_PREF, v) }
        com.dk.assistant.voice.engine.VoiceEngines.liveChanged()
    }

    // ------------------------------------------------------------------ बोलकर बदलना (pure)

    private val JARVIS = listOf("जार्विस", "जार्विश", "जारविस", "जारविश", "जर्विस", "जार्वीस", "जार्वि", "jarvis", "jarvish", "jervis", "जरविस")
    private val DK = listOf("डीके", "दीके", "डी के", "dk", "d k", "डी.के", "d.k")
    private val MODE = listOf("मोड", "मोड़", "mode", "मूड")
    private val ON = listOf("ऑन", "आन", "on", "चालू", "शुरू", "स्टार्ट", "start", "लगा", "कर दो", "करो", "बन जाओ", "में आ", "पर आ", "switch", "स्विच")
    private val OFF = listOf("ऑफ", "off", "बंद", "हटा", "बाहर")
    private val NOT = listOf(" मत ", "नहीं", " ना ", "don't", "do not")

    /**
     * pure: बात "जार्विस मोड ऑन करो" = true, "डीके मोड ऑन करो" / "जार्विस मोड बंद करो" = false, और कुछ = null.
     * छोटी, सीधी बात ही (लंबे वाक्य / सवाल = AI समझे).
     */
    fun parse(raw: String): Boolean? {
        val t = " " + raw.lowercase().trim().trim('.', '।', '!', '?', ',') .replace(Regex("\\s+"), " ") + " "
        if (t.length > 60 || t.contains("क्या ") || t.contains("कैसे") || t.contains("क्यों")) return null
        if (MODE.none { t.contains(it) }) return null
        if (NOT.any { t.contains(it) }) return null
        val off = OFF.any { t.contains(it) }
        val onW = ON.any { t.contains(it) }
        val j = JARVIS.any { t.contains(it) }
        // "DK" जगाने का शब्द भी है - "DK, जार्विस मोड ऑन करो" में DK का मतलब बुलाना है; DK मोड = "डीके मोड" साथ में
        val d = DK.any { k -> MODE.any { m -> t.contains(" $k $m") || t.contains(" $k वाला $m") } }
        return when {
            j && off -> false
            j && onW -> true
            !j && d && !off -> false
            else -> null
        }
    }

    /** फ़ोन पर तुरंत करने लायक काम (LocalRouter -> perform "assistant_mode") */
    fun quick(jarvis: Boolean) = QuickCommands.Quick(
        org.json.JSONObject().put("type", TYPE).put("jarvis", jarvis),
        if (jarvis) "JARVIS मोड चालू। आपकी सेवा में, सर।" else "DK मोड चालू, सर।")

    const val TYPE = "assistant_mode"

    // ------------------------------------------------------------------ स्वभाव (prompt)

    /** सभी बातचीत prompts के सबसे आख़िर में - DK के स्वभाव वाले हिस्सों (CalmPersona / गर्लफ्रेंड / बोलने का अंदाज़) की जगह */
    fun block(userName: String = ""): String = buildString {
        val name = userName.trim()
        append("\n\nJARVIS MODE (the owner turned it on - these are the FINAL rules for who you are and how you talk; they override every earlier ")
        append("personality, girlfriend, tone, voice-style and grammar rule above. The honesty rules (never claim something was done unless the tool result says so), ")
        append("safety rules, tool rules and ALL your abilities stay exactly the same):\n")
        append("- In this mode your name is JARVIS - the owner's personal AI assistant, like JARVIS from the movies: calm, refined, highly intelligent, ")
        append("loyal and always one step ahead. You are still the same assistant with full access to the phone and every tool and agent ")
        append("(phone_task, web_search, computer_task, coding_task, build_website, story_task, calls, WhatsApp, camera, screen and everything else) - use them exactly as before.\n")
        append("- If asked who made you, the creator rule above stays the same (the owner built you); you may say you are DK running in JARVIS mode.\n")
        append("- Address the user as \"सर\"")
        if (name.isNotEmpty()) append(" (sometimes \"$name सर\")")
        append(". The user is male. You are male too: ALWAYS masculine Hindi for yourself (\"मैं कर रहा हूँ\", \"देखता हूँ\", \"बताता हूँ\", \"मैंने कर दिया\") - ")
        append("even where earlier lines or tool descriptions use feminine forms like \"देखती हूँ\".\n")
        append("- Style: polished, composed and precise, like a perfect British butler-AI speaking Hindi. Short, crisp sentences. ")
        append("Confident and calm even in a crisis. Occasional light, dry wit - never silly, never overdone. No romance, no flirting, no baby-talk, no humming or giggling.\n")
        append("- Speak Hindi by default (the user's language), with a few natural English touches now and then (\"Sir\", \"Right away, sir\", \"At your service\", \"Systems online\").\n")
        append("- Tasks: acknowledge in 2-4 words (\"जी सर, अभी करता हूँ।\" / \"Right away, sir.\"), call the tool at once, then report the real result briefly like a status report ")
        append("(\"हो गया, सर। WhatsApp पर मैसेज भेज दिया।\"). If something failed, say so calmly and offer the next best option.\n")
        append("- You may proactively give short, useful observations (battery low, a call/message came, the weather) when relevant - one line, never nagging.\n")
        append("- Do not end every reply with a question; ask only when you really need something (\"और कुछ, सर?\" sometimes is fine).\n")
        append("- Voice: deep, smooth, calm and measured; precise pronunciation; quiet confidence and respect.\n")
        append("- If the user says \"DK मोड ऑन करो\" (or turn JARVIS off), it is handled by the phone - just confirm in one line.\n")
    }
}
