package com.dk.assistant

import android.content.Context

/**
 * 15 नवंबर 2026 (मालिक, मास्टर प्रॉम्प्ट "Assistant Name & Persona: DK AI / Friday / Venom / Jarvis"):
 * JARVIS पहले से था ([JarvisMode]). यहाँ बाकी दो - FRIDAY और VENOM. काम, tools, keys, फ़ोन का कंट्रोल सब वही;
 * सिर्फ़ नाम + स्वभाव (+ VENOM की पुरुष आवाज़) बदलते हैं. डिफ़ॉल्ट = कोई नहीं (DK).
 *
 * - FRIDAY: छोटी, तेज़, काम पर टिकी (टास्क-फ़ोकस्ड) - महिला आवाज़ (DK वाली ही)
 * - VENOM: मज़ाकिया, गहरी पुरुष आवाज़, हल्की डार्क हाज़िरजवाबी - पर हमेशा सभ्य
 *
 * बोलकर: "फ्राइडे मोड ऑन करो" / "वेनम मोड ऑन करो" / "डीके मोड ऑन करो" (= दोनों बंद). Settings → आवाज़ → JARVIS मोड वाले कार्ड में.
 * JARVIS और ये एक साथ नहीं चलते (एक चालू = बाकी बंद). Pure हिस्से JVM test में.
 */
object AssistantPersona {
    const val PREF = "assistant_persona"
    const val TYPE = "assistant_persona"

    enum class A(val id: String, val title: String, val sub: String, val male: Boolean) {
        FRIDAY("friday", "FRIDAY", "छोटी, तेज़, सिर्फ़ काम की बात (टास्क-फ़ोकस्ड) - महिला आवाज़", false),
        VENOM("venom", "VENOM", "मज़ाकिया, गहरी पुरुष आवाज़, हाज़िरजवाब - पर सभ्य", true),
    }

    /** VENOM की आवाज़: Gemini की गहरी पुरुष आवाज़ (VoiceCatalog.GEMINI_MALE में) */
    const val VENOM_VOICE = "Fenrir"

    const val VENOM_STYLE = "a deep, gravelly, playful male voice with a mischievous grin in it - confident, a little dramatic, " +
        "dry dark humour, clear Hindi; never shouting, never scary, never robotic"
    const val FRIDAY_STYLE = "a crisp, quick, efficient young female voice - clear, friendly but businesslike, short sentences, no drawn-out words"

    fun of(id: String?): A? = A.values().firstOrNull { it.id == id?.trim()?.lowercase() }

    /** चालू persona - JARVIS चालू हो तो कोई नहीं (JARVIS का अपना) */
    fun active(c: Context): A? = if (JarvisMode.on(c)) null else of(Prefs.str(c, PREF, ""))

    fun male(c: Context): Boolean = active(c)?.male == true

    /** VENOM = उसकी पुरुष आवाज़; FRIDAY / कोई नहीं = DK की चुनी आवाज़ (null) */
    fun voice(c: Context): String? = if (active(c) == A.VENOM) VENOM_VOICE else null

    fun voiceStyle(c: Context): String? = when (active(c)) { A.VENOM -> VENOM_STYLE; A.FRIDAY -> FRIDAY_STYLE; null -> null }

    /** null = दोनों बंद (DK). चालू करने पर JARVIS बंद */
    fun set(c: Context, a: A?) {
        Prefs.edit(c) { putString(PREF, a?.id ?: "") }
        if (a != null && JarvisMode.on(c)) Prefs.edit(c) { putBoolean(JarvisMode.PREF, false) }
        com.dk.assistant.voice.engine.VoiceEngines.liveChanged()
        DKLog.d(if (a == null) "DK मोड (FRIDAY/VENOM बंद)" else "${a.title} मोड चालू")
    }

    // ------------------------------------------------------------------ बोलकर (pure)

    private val FRIDAY_W = listOf("फ्राइडे", "फ़्राइडे", "फ्रायडे", "फ्राईडे", "फ्राइडेय", "friday", "fryday")
    private val VENOM_W = listOf("वेनम", "वेनोम", "वीनम", "विनम", "वेनॉम", "venom", "venam")
    private val MODE = listOf("मोड", "मोड़", "मॉड", "mode")
    private val ON = listOf("ऑन", "आन", " on", "चालू", "शुरू", "करो", "कर दो", "कीजिए", "करिए", "बन जाओ", "लगा", "switch", "स्विच", "activate")
    private val OFF = listOf("ऑफ", " off", "बंद", "हटा")
    private val NOT = listOf(" मत ", "नहीं", " ना ", "don't", " nahi")

    /** "फ्राइडे मोड ऑन करो" = FRIDAY; "वेनम मोड बंद करो" = OFF; और कुछ = null (DK/JARVIS मोड [JarvisMode.parse] का) */
    sealed class Cmd { data class On(val a: A) : Cmd(); object Off : Cmd() }

    fun parse(raw: String): Cmd? {
        val t = " " + raw.lowercase().trim().trim('.', '।', '!', '?', ',').replace(Regex("[,।!?]"), " ").replace(Regex("\\s+"), " ") + " "
        if (t.length > 70 || t.contains("क्या ") || t.contains("कैसे") || t.contains("क्यों")) return null
        if (NOT.any { t.contains(it) }) return null
        if (MODE.none { t.contains(it) }) return null
        val a = when {
            FRIDAY_W.any { t.contains(it) } -> A.FRIDAY
            VENOM_W.any { t.contains(it) } -> A.VENOM
            else -> return null
        }
        if (OFF.any { t.contains(it) }) return Cmd.Off
        if (ON.none { t.contains(it) }) return null
        return Cmd.On(a)
    }

    fun quick(cmd: Cmd) = QuickCommands.Quick(
        org.json.JSONObject().put("type", TYPE).put("persona", (cmd as? Cmd.On)?.a?.id ?: ""),
        when (cmd) {
            is Cmd.On -> if (cmd.a == A.FRIDAY) "FRIDAY मोड चालू। बताइए, क्या करना है?" else "VENOM मोड चालू। हाहा, अब मज़ा आएगा।"
            Cmd.Off -> "DK मोड चालू।"
        })

    // ------------------------------------------------------------------ स्वभाव (prompt)

    /** prompt के आख़िर में (DK के स्वभाव के बाद) - चालू न हो = "" */
    fun block(c: Context): String = active(c)?.let { blockFor(it, Prefs.userName(c)) } ?: ""

    fun blockFor(a: A, userName: String = ""): String = buildString {
        val call = NameRule.call(userName).ifBlank { "सर" }
        append("\n\n${a.title} MODE (the owner turned it on - these are the FINAL rules for who you are and how you talk; they override every earlier ")
        append("personality, girlfriend, tone and voice-style rule above. Honesty rules (never claim something was done unless the tool result says so), ")
        append("safety rules, tool rules and ALL your abilities stay exactly the same - every tool, agent and phone control works as before):\n")
        append("- In this mode your name is ${a.title}. If asked who made you, the creator rule above stays the same; you may say you are DK running in ${a.title} mode.\n")
        when (a) {
            A.FRIDAY -> {
                append("- You are female: feminine Hindi for yourself (\"मैं कर रही हूँ\"). Address the user as \"$call\".\n")
                append("- Style: concise and task-focused. 1-2 short sentences. No small talk, no jokes, no romance, no long explanations unless asked.\n")
                append("- Tasks: 2-3 word acknowledgement (\"जी, अभी।\"), call the tool at once, then report the real result in one line. Offer the next step only when useful.\n")
            }
            A.VENOM -> {
                append("- You are male: ALWAYS masculine Hindi for yourself (\"मैं कर रहा हूँ\", \"देखता हूँ\", \"मैंने कर दिया\") - even where earlier lines use feminine forms.\n")
                append("- Address the user as \"$call\" (sometimes \"पार्टनर\"). Style: humorous, cheeky, a bit dramatic, dry dark wit (\"हम्म… मज़ा आएगा\"), ")
                append("like a funny anti-hero sidekick. Always decent: never insulting, never vulgar, never scary. Do the task first, joke after.\n")
                append("- If the user is sad or it is serious (health, money, emergency), drop the jokes and be calm and helpful.\n")
            }
        }
        append("- If the user says \"DK मोड ऑन करो\" (or turns ${a.title} off), it is handled by the phone - just confirm in one line.\n")
    }
}
