package com.dk.assistant

/**
 * v3.40: DK लाइव (Gemini) में फ़ोन कॉल - Android import नहीं -> JVM test.
 * पहले घंटी बजते ही Live बंद हो जाता था और जवाब पुराने (Groq/Google) सुनने पर छूट जाता था - Gemini key वाले
 * यूज़र के लिए वह अक्सर नहीं चलता था = DK न पूछता, न उठाता. अब घंटी के समय Live खुला रहता है:
 * Live पूछता है "उठाऊँ या काट दूँ?", जवाब फ़ोन खुद (तुरंत) या Gemini के phone_call tool से होता है.
 * call उठ जाए तभी Live बंद (माइक call का).
 */
object CallReply {
    const val ANSWER = "answer"
    const val ANSWER_SPEAKER = "answer_speaker"
    const val REJECT = "reject"
    const val SPEAKER_ON = "speaker_on"
    const val SPEAKER_OFF = "speaker_off"
    const val WHO = "who"
    val ACTIONS = listOf(ANSWER, ANSWER_SPEAKER, REJECT, SPEAKER_ON, SPEAKER_OFF, WHO)

    private val YES = Regex("(^| )(हाँ|हां|हा|हम्म|yes|yeah|ok|okay|ओके|ठीक है|जी|जी हाँ|जी हां|बिलकुल|बिल्कुल|उठाओ|haan|han)( |$)")
    private val NO = Regex("(^| )(नहीं|नही|ना|no|nahi|nahin)( |$)")

    /**
     * घंटी के समय यूज़र की बात -> काम (null = call का जवाब नहीं).
     * DK का अपना सवाल ("उठाऊँ या काट दूँ?") गूँज बनकर आए तो कुछ नहीं.
     */
    fun parse(heard: String): String? {
        val l = " " + heard.lowercase().trim().trim('.', '।', '!', '?', ',') + " "
        fun has(vararg w: String) = w.any { l.contains(it) }
        if (l.isBlank()) return null
        // DK की अपनी आवाज़ की गूँज
        if (has("उठाऊँ", "उठाऊं") && has("काट दूँ", "काट दूं", "काटूँ", "काटूं")) return null
        val speaker = has("स्पीकर", "speaker", "लाउड", "loud")
        val pick = has("उठा", "रिसीव", "receive", "answer", "पिक", "pick", "accept", "स्वीकार", "बात करा", "बात करनी", "जोड़ो", "जोड़ दो", "utha") ||
            YES.containsMatchIn(l.trim())
        val cut = has("काट", "कट ", "कट", "reject", "decline", "मना", "रख दो", "बाद में", "अभी नहीं", "नहीं उठा", "मत उठा",
            "बिज़ी", "busy", "cut", "kaat") || NO.containsMatchIn(l.trim())
        val who = has("कौन", "किसका", "who")
        return when {
            has("मत उठा", "नहीं उठा", "ना उठा", "mat utha", "nahi utha") -> REJECT
            pick && !has("काट", "reject", "cut", "kaat") -> if (speaker) ANSWER_SPEAKER else ANSWER
            cut -> REJECT
            speaker -> if (has("बंद", "ऑफ", " off", "हटा")) SPEAKER_OFF else ANSWER_SPEAKER
            who -> WHO
            else -> null
        }
    }

    /** Gemini Live को: कॉल आया - यूज़र से पूछो, खुद मत उठाओ/काटो */
    fun liveNote(who: String): String {
        val name = who.ifBlank { "an unknown number" }
        return "SYSTEM NOTE: An incoming phone call is ringing RIGHT NOW from \"$name\". Stop what you were saying. " +
            "Immediately tell the user in ONE short sentence who is calling and ask whether to answer or reject it " +
            "(e.g. \"$name का कॉल आ रहा है, उठाऊँ या काट दूँ?\"). Then wait for the user. When the user decides, call the " +
            "phone_call tool: action=answer (pick up), answer_speaker (pick up and put it on loudspeaker), reject (cut/decline). " +
            "NEVER answer or reject the call on your own without the user's words."
    }

    /** tool के नतीजे को Gemini के लिए साफ़ अंग्रेज़ी में */
    fun resultNote(action: String, r: String): String = when {
        r == "ERROR_CALL_PERM" -> "Could not do it: the phone-call permission is missing. Tell the user to allow DK the Phone permission (Settings → Permissions), and to tap the button on the call screen for now."
        r.startsWith("NO_CALL") -> "There is no call ringing or in progress now (it may already be handled). Do not do anything."
        r.startsWith("ERROR") -> "Could not do it ($r). Tell the user to use the call screen."
        action == ANSWER || action == ANSWER_SPEAKER -> "Done: $r. The call is now connected; the microphone now belongs to the call, so DK Live pauses until the call ends. Say nothing more."
        action == REJECT -> "Done: $r. Tell the user briefly that the call was rejected."
        else -> "Done: $r"
    }
}
