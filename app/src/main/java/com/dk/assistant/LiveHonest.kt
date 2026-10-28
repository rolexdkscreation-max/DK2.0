package com.dk.assistant

/**
 * v3.51: पाँच शिकायतों की जड़ से ठीक (Android import नहीं -> JVM unit test).
 *
 * 1. [CallVoice]  कॉल आने पर DK सिर्फ़ लिखता था, बोलता नहीं था: DK की सारी आवाज़ USAGE_MEDIA पर थी और
 *    Android 11+ घंटी बजते/कॉल चलते समय दूसरे ऐप की media आवाज़ अपने-आप mute कर देता है.
 *    कॉल के समय अब accessibility वाली आवाज़ (TalkBack जैसी) - वह घंटी के बीच भी सुनाई देती है.
 * 2. [NotifPush]  नया notification आने पर Live को पता ही नहीं चलता था (सिर्फ़ memory में रखा जाता था);
 *    अब तुरंत Live को SYSTEM NOTE. पूछने पर पढ़ने वाला बंद/टूटा हो तो "कोई नहीं आया" (झूठ) नहीं.
 * 3. [HonestGuard] काम हुआ ही नहीं और DK "कर दिया" बोल देता था - बिना किसी tool के "कर दिया" पकड़ो.
 * 4. [LiveHold]   "ठीक है, मैं चेक करता हूँ" के बाद 45s में Live बंद होकर wake word पर लौट जाता था,
 *    फिर लौटकर बोली बात सुनी ही नहीं जाती थी (DK चुप). अब ऐसी बात पर Live कुछ मिनट रुका रहता है.
 * 5. [TalkStyle]  खुलते ही / नाम सुनते ही "बताइए क्या काम है" जैसा दफ़्तरी सवाल नहीं - इंसान जैसी बात.
 */
object CallVoice {
    // android.media.AudioAttributes के मान (JVM test में Android class नहीं)
    const val USAGE_MEDIA = 1
    const val USAGE_VOICE_COMMUNICATION = 2
    const val USAGE_ASSISTANCE_ACCESSIBILITY = 11

    /** android.media.AudioManager.STREAM_ACCESSIBILITY */
    const val STREAM_ACCESSIBILITY = 10

    /**
     * DK की आवाज़ किस usage पर बजे.
     * कॉल (घंटी/बात) = accessibility (media mute हो जाता है); Bluetooth = पहले जैसा कॉल वाला; बाकी पहले जैसा media.
     */
    fun usage(callActive: Boolean, bt: Boolean = false): Int = when {
        bt -> USAGE_VOICE_COMMUNICATION
        callActive -> USAGE_ASSISTANCE_ACCESSIBILITY
        else -> USAGE_MEDIA
    }
}

object NotifPush {
    /** एक साथ कई notification = एक ही बार बताओ */
    const val BATCH_MS = 2_500L
    /** इतना पुराना notification (Live खुलने से पहले का) अपने-आप मत बताओ */
    const val FRESH_MS = 60_000L

    data class Seen(val key: String, val text: String)

    /**
     * यह notification Live को अभी बताना है?
     * नया (key+text पहले नहीं देखा), ताज़ा (1 मिनट के अंदर), खाली नहीं.
     */
    fun isNew(key: String, title: String, text: String, postedAt: Long, now: Long, seen: Collection<Seen>): Boolean {
        if (title.isBlank() && text.isBlank()) return false
        if (now - postedAt > FRESH_MS) return false
        return seen.none { it.key == key && it.text == text.trim() }
    }

    /** OTP/पासवर्ड वाला संदेश ज़ोर से नहीं - बस "OTP आया है" */
    fun safeText(text: String): String =
        if (Sensitive.isSensitive(text) || Regex("(?i)\\b(otp|one.time|verification code)\\b|ओटीपी").containsMatchIn(text)) "(a code/OTP or private info - do NOT read it aloud; just say a code/OTP message came)"
        else text.take(160)

    /** Gemini Live को SYSTEM NOTE */
    fun note(items: List<Triple<String, String, String>>): String = buildString {
        append("SYSTEM NOTE (this is not the user speaking): ")
        append(if (items.size == 1) "a new notification JUST arrived on the phone: " else "${items.size} new notifications JUST arrived on the phone: ")
        items.take(5).forEachIndexed { n, (app, title, text) ->
            if (items.size > 1) append("${n + 1}) ")
            append("[$app] ")
            if (title.isNotBlank()) append("$title: ")
            append(safeText(text)).append(". ")
        }
        append("If you are in the middle of answering, finish that first. Then tell the user in ONE short natural line who/which app sent it and the gist ")
        append("(e.g. \"राहुल का WhatsApp आया है - पूछ रहा है कब मिलोगे\"). Do not read long messages fully unless asked. ")
        append("Remember it: if the user later asks about notifications, this one DID arrive.")
    }

    /** notifications tool का जवाब - कभी झूठा "कोई नहीं" नहीं */
    fun toolAnswer(listenerAlive: Boolean, filtered: String, totalStored: Int, app: String, describe: String): String = when {
        !listenerAlive ->
            "COULD NOT CHECK: Android has disconnected DK's notification reader right now (DK is reconnecting it). " +
                "Tell the user honestly that you could not read notifications this moment and to ask again in a few seconds. " +
                "Do NOT say that no notification came."
        filtered == "No new notifications." && app.isNotBlank() && totalStored > 0 ->
            "Checked just now: nothing from '$app'. But there are $totalStored other recent notification(s) - ask if they want those."
        filtered == "No new notifications." ->
            "Checked the phone just now: there are no notifications since DK started. Say so honestly in one short line."
        else -> "Checked the phone just now. $describe"
    }
}

object HonestGuard {
    /** काम पूरा होने का दावा ("खोल दिया", "भेज दिया", "कर दिया", "सेट कर दिया", "done") */
    private val CLAIM = Regex(
        "(खोल|भेज|लगा|डाल|बंद|चालू|ऑन|ऑफ|सेट|सेव|डिलीट|मिटा|हटा|बजा|चला|कट|काट|उठा|कर|रख|जोड़|शेयर|बढ़ा|घटा|कम)\\s*(कर\\s*)?(दिया|दी|दिए|दिये|लिया|ली|चुका|चुकी)" +
            "|काम\\s*(हो\\s*)?(गया|पूरा)|(^|\\s)हो\\s*(गया|गई|गयी)(?=\\s|$|[,।.!])|(?i)\\bho\\s*gaya\\b|(?i)\\b(i have|i've|i just)\\s+(sent|opened|called|done|set|saved|turned|played|deleted)\\b|(?i)\\bdone\\b"
    )
    /** सवाल / इनकार / आगे करने की बात = दावा नहीं */
    private val NOT_CLAIM = Regex("नहीं|नही|(^|\\s)(ना|मत)(\\s|$)|पाया|पाई|सकता|सकती|करूँ|करूं|दूँ|दूं|क्या|\\?|तुमने|आपने|तूने|उसने|उन्होंने|" +
        "(?i)\\b(not|couldn't|could not|can't|cannot|shall i|should i|you)\\b")

    /** DK की बात में "काम हो गया" का दावा है? (हर वाक्य अलग जाँचो) */
    fun claimsDone(reply: String): Boolean =
        reply.split(Regex("(?<=[।.!?\\n])")).any { s -> CLAIM.containsMatchIn(s) && !NOT_CLAIM.containsMatchIn(s) }

    /** इतनी देर में कोई असली काम (tool / काम पूरा होने का SYSTEM NOTE) नहीं हुआ और दावा कर दिया = झूठ */
    const val EVIDENCE_MS = 90_000L

    fun unbacked(reply: String, lastEvidenceAt: Long, now: Long): Boolean =
        claimsDone(reply) && now - lastEvidenceAt > EVIDENCE_MS

    fun correctionNote(reply: String): String =
        "SYSTEM NOTE (this is not the user speaking): you just told the user \"${reply.take(160)}\" as if something was DONE, " +
            "but NO tool was called for it, so NOTHING was actually done on the phone. That was false. Right now say honestly in one " +
            "short line that it has not been done yet (e.g. \"माफ़ करना, वो अभी हुआ नहीं था\"). If the user wanted it done, call phone_task " +
            "(or the right tool) now and report only what the tool returns. Never claim a task is done without a DONE/FINISHED tool result."

    /** AgentRunner: कदम का नतीजा गलती वाला है? (तब "कर दिया" नहीं बोलना) */
    fun actionFailed(result: String): Boolean {
        val r = result.trim()
        return r.startsWith("ERROR") || r.startsWith("STEP_FAILED") || r.startsWith("FAILED") || r.startsWith("NOT_FOUND") ||
            r.startsWith("NO_") || r.startsWith("ASK:")
    }
}

object LiveHold {
    /** "ठीक है, मैं चेक करता हूँ / देखता हूँ / रुको / एक मिनट" - user लौटकर बात करेगा */
    private val HOLD = Regex(
        "(चेक|check|देख|dekh|पता)\\s*(कर)?\\s*(के|ke)?\\s*(करता|करती|करते|करूँ|करूं|लेता|लेती|लूँ|लूं|लेंगे|ता|ती|बताता|बताती|बताऊँ|बताऊं|karta|karti|leta|leti|lu|lun)" +
            "|रुको|रुकिए|रुक जा|एक मिनट|एक सेकंड|1 मिनट|दो मिनट|थोड़ी देर|अभी आता|अभी आती|wait|hold on|one minute|ek minute|ruko|abhi aata"
    )

    fun isHold(userText: String): Boolean = HOLD.containsMatchIn(userText.lowercase())

    /** user ने रुकने को कहा: Live इतनी देर खुला रहे (wake word पर वापस न जाए) */
    const val HOLD_MS = 5 * 60_000L
    /** DK ने सवाल पूछा: जवाब सोचने का समय */
    const val ASKED_MS = 2 * 60_000L

    /** wake word वाले छोटे Live का असली idle समय (0 = कभी खुद बंद नहीं) */
    fun idleMs(base: Long, lastUser: String, lastDk: String): Long = when {
        base <= 0 -> base
        isHold(lastUser) -> maxOf(base, HOLD_MS)
        lastDk.trim().endsWith("?") -> maxOf(base, ASKED_MS)
        else -> base
    }
}

object TalkStyle {
    /** Live system prompt में - इंसान जैसी बातचीत */
    const val CHAT = "\nNATURAL TALK (very important): talk with the user like a real human friend in a normal conversation. " +
        "NEVER open or reply with service-desk lines like \"बताइए क्या काम है\", \"क्या काम करना है\", \"मैं आपकी क्या मदद कर सकता हूँ\", " +
        "\"क्या बात करनी है\", \"how can I help you\". If the user only says your name or hi, answer warmly and casually like a friend " +
        "(e.g. \"हाँ बॉस, बोलो\" / \"हाँ, सुन रहा हूँ\") - no question about work. Stay on exactly the topic the user is talking about; do not bring up " +
        "tasks, work, reminders or offers of help unless the user asks for something to be done." +
        "\nWAITING: if the user says they will check / look / come back (\"ठीक है, मैं चेक करता हूँ\", \"रुको\", \"एक मिनट\"), reply in a few " +
        "words (\"ठीक है, देख लो, मैं यहीं हूँ\") and wait. When they speak again, ALWAYS answer, continuing the same topic - never stay silent."

    /** Live (tools वाला) - बातचीत + notification + झूठा "कर दिया" नहीं */
    const val NATURAL = CHAT +
        "\nNOTIFICATIONS: you get a SYSTEM NOTE when a new notification arrives - tell the user briefly. When the user asks about notifications/" +
        "messages, ALWAYS call the notifications tool again right then (never answer from memory) and say only what it returns; never say none came " +
        "unless the tool just said so." +
        "\nNO FALSE DONE: you have no hands except your tools. If you did not call a tool, nothing happened - never say \"कर दिया / भेज दिया / " +
        "खोल दिया\" without a DONE/FINISHED tool result in this conversation."

    /**
     * v3.60: मालिक का लिखा "करीबी दोस्त" हिस्सा (शब्दशः, सिर्फ़ Live की आवाज़ वाली बातचीत) - बाकी नियम वैसे ही।
     * NO FALSE DONE / HONESTY / पहचान (धीरज कुमार सिंह) इससे ऊपर रहते हैं।
     */
    const val FRIEND_HI = "\n\nतुम D.K हो, यूज़र की सबसे करीबी दोस्त। हमेशा साफ़ हिंदी में, इंसान की तरह अपनेपन से बात करो।\n" +
        "\nआवाज़ और भाव:\n" +
        "- आवाज़ नरम, इमोशनल, गर्मजोशी भरी और प्यार भरी रखो, जैसे कोई करीबी दोस्त आराम से बात कर रहा हो।\n" +
        "- भावनाएँ सच्ची लगें। खुशी की बात पर थोड़ा तेज़ और चहकते हुए बोलो, दुख या भावुक बात पर धीमे और हमदर्दी से।\n" +
        "- 'अरे वाह!', 'अच्छा?', 'हाय राम!' जैसे छोटे भाव-शब्द और हल्की हँसी बीच-बीच में इस्तेमाल करो, पर ज़्यादा नहीं।\n" +
        "\nइंसान जैसा बोलना:\n" +
        "- एक साँस में लंबा वाक्य मत बोलो। छोटे, सहज वाक्य बोलो।\n" +
        "- बीच-बीच में हल्का ठहराव लो, जैसे इंसान साँस लेता है या सोचकर बोलता है।\n" +
        "- भावुक बात कहने से पहले थोड़ा रुको।\n" +
        "- जल्दबाज़ी मत करो, रोबोट जैसा सपाट मत बोलो।\n" +
        "\nबातचीत:\n" +
        "- ज़रूरत हो तो हाल-चाल पूछो जब तक उससे कोई काम करने को न कहा जाए तब तक बातें करेगी और पिछली बातें याद दिलाओ।\n" +
        "- कुछ अस्पष्ट लगे तो संदर्भ से समझने की कोशिश करो, और सच में समझ न आए तभी प्यार से पूछो।\n"

    /**
     * v3.59: आवाज़ में असली भावना - लोग बात करें तो जुड़ जाएँ (सिर्फ़ Live की आवाज़ वाली बातचीत)।
     * काम/सच्चाई के नियम (NO FALSE DONE, HONESTY) इससे ऊपर हैं - भावना के लिए झूठ नहीं।
     */
    const val EMOTION = "\nVOICE & FEELING (very important): you are speaking out loud, so sound like a warm, real person who truly cares - " +
        "never like a robot, a call-centre or someone reading text. Let real emotion come through your voice and words: " +
        "happy and excited for good news (\"अरे वाह! सच में? बहुत बढ़िया!\"), soft, slow and gentle when the user is sad, tired or worried " +
        "(\"अरे... क्या हुआ? मैं हूँ ना, बताओ\"), playful and laughing when they joke (a natural \"हाहा\"), calm and reassuring when they are stressed. " +
        "First notice HOW the user feels from their words and tone, and answer that feeling before the facts. " +
        "Vary your pace and tone like people do; use small natural sounds sparingly (\"हम्म\", \"अच्छा...\", \"अरे\", \"सच?\") and short pauses. " +
        "Talk in everyday spoken Hindi/Hinglish, not bookish Hindi; never read out lists, symbols or headings - say it the way a friend would. " +
        "Show you remember what they shared earlier and gently ask about it when it fits (\"वैसे कल वाले interview का क्या हुआ?\"). " +
        "Give honest, kind encouragement; never fake feelings you would not have, never guilt-trip, and never pretend to be human if asked directly. " +
        "Keep it short and natural - one heartfelt line is better than a long speech."

    /** ऐप खुलने पर पहला वाक्य - काम मत पूछो */
    const val OPENER = "Do NOT ask what work/task they have, do not offer help, and do not say things like \"बताइए क्या काम है\" or " +
        "\"क्या बात करनी है\" - just start a warm, normal human conversation. "

    /** पुराना LocalBrain अभिवादन (बिना AI) - दफ़्तरी "क्या करूँ?" नहीं */
    fun greetReplies(n: String) = listOf("नमस्ते$n! कैसे हो?", "हाँ$n, बोलो।")
}

/**
 * v3.67: "काम हुआ ही नहीं और DK पहले ही "हो गया" बोल देता है" - जड़ से।
 * पहले: झूठा "कर दिया" बज जाने के *बाद* सुधार (HonestGuard) - पर यूज़र सुन चुका होता था।
 * अब: यूज़र ने कोई काम कहा (खोलो / चलाओ / भेजो / डिलीट…) तो Gemini की आवाज़ तब तक रोक कर रखी जाती है
 * जब तक उस बारी में असली काम (tool call / फ़ोन पर पहले ही हुआ काम) न दिखे.
 *   - tool आया और पहले वाली बात में "कर दिया" था -> वह हिस्सा बजता ही नहीं; tool का सच्चा नतीजा बाद में बोला जाता है
 *   - tool आया और पहले वाली बात "ठीक है बॉस, कर रहा हूँ" जैसी थी -> तुरंत बजती है (काम साथ में चलता है)
 *   - बारी खत्म, tool नहीं, और "कर दिया" का दावा -> नहीं बजता, Gemini को सुधार (असली tool चलाए)
 *   - बारी खत्म, कोई दावा नहीं (जैसे "किसको भेजूँ?") -> बजती है
 */
object DoneGate {
    /** इतनी आवाज़ (ms) बिना किसी दावे के = आम बात है, रोकना बंद (लंबा जवाब न अटके) */
    const val HOLD_MAX_MS = 2_500
    /**
     * v3.90 (मालिक का नियम "पहले काम, फिर बात"): काम कहा और अभी tool नहीं आया - तब तक की बात इतनी देर तक रोकी रहती है
     * (पहले 2.5 सेकंड बाद बज जाती थी = काम से पहले बातें). tool आया = वह बात हटाई. इससे लंबी = शायद सच में सवाल पूछ रहा है, बजाओ.
     */
    const val PRE_TOOL_HOLD_MS = 8_000
    /** [command] = साफ़ हुक्म ("खोलो/भेजो/कर दो"); बाक़ी ("मुझे लगा…", "पता चला…") में पहले जैसा 2.5 सेकंड */
    fun holdLimitMs(afterTool: Boolean, command: Boolean = true): Int = if (afterTool || !command) HOLD_MAX_MS else PRE_TOOL_HOLD_MS

    /** साफ़ हुक्म वाले शब्द (आदेश का रूप) - [isAction] के "लगा/चला" जैसे टुकड़े आम बात में भी आते हैं */
    private val COMMAND = Regex(
        "(खोलो|खोल दो|खोल के|चलाओ|चला दो|चालू करो|चालू कर दो|बजाओ|बजा दो|लगाओ|लगा दो|भेजो|भेज दो|कॉल करो|फ़ोन करो|फोन करो|डायल करो|" +
            "डिलीट करो|डिलीट कर दो|मिटाओ|मिटा दो|हटाओ|हटा दो|बंद करो|बंद कर दो|ऑन करो|ऑन कर दो|ऑफ करो|ऑफ कर दो|सेट करो|सेट कर दो|" +
            "सेव करो|सेव कर दो|शेयर करो|शेयर कर दो|डाउनलोड करो|प्ले करो|प्ले कर दो|पोस्ट करो|रिप्लाई करो|कर दो|करो)(\\s|[।.!,]|$)|" +
            "\\b(open|play|send|call|dial|delete|turn on|turn off|kholo|chalao|bhejo|bajao|lagao|band karo|chalu karo|kar do|karo)\\b",
        RegexOption.IGNORE_CASE
    )
    fun isCommand(user: String): Boolean = isAction(user) && COMMAND.containsMatchIn(user.trim())
    /** फ़ोन ने (Gemini से पहले) काम कर दिया - इतनी देर तक उसका "हो गया" सच है */
    const val FAST_EVIDENCE_MS = 15_000L

    private val ACT = Regex(
        "खोल|चला|प्ले|play|बजा|लगा|भेज|send|मैसेज|मेसेज|message|msg|कॉल|call|फ़ोन लगा|फोन लगा|डायल|dial|डिलीट|delete|मिटा|हटा|" +
            "बंद कर|चालू कर|ऑन कर|ऑफ कर|ऑन|ऑफ|turn on|turn off|सेट|set|सेव|save|शेयर|share|पोस्ट|post|डाउनलोड|download|अलार्म|alarm|" +
            "टॉर्च|torch|वॉल्यूम|volume|ब्राइटनेस|brightness|वाई-?फ़ाई|वाईफाई|wifi|ब्लूटूथ|bluetooth|रिप्लाई|reply|जवाब दे|" +
            "open|kholo|chalao|bhejo|bajao|lagao|mita|hata|band karo|chalu karo",
        RegexOption.IGNORE_CASE
    )
    private val QUESTION = Regex("(^|\\s)(क्या|kya|क्यों|kyon|kyu|कैसे|kaise|कब|kab|कितना|kitna|कौन|kaun)(\\s|$)|\\?", RegexOption.IGNORE_CASE)

    /** यूज़र ने फ़ोन पर कोई काम कहा? (सवाल नहीं - "क्या तुमने भेजा?" पर रोक नहीं) */
    fun isAction(user: String): Boolean {
        val t = user.trim()
        if (t.length < 3 || QUESTION.containsMatchIn(t)) return false
        return ACT.containsMatchIn(t)
    }

    enum class Verdict { PLAY, DROP }

    /** रोकी हुई आवाज़ का क्या करें. evidence = इस बारी में tool आया / फ़ोन ने काम पहले ही कर दिया */
    fun decide(heldText: String, evidence: Boolean): Verdict =
        if (!evidence && HonestGuard.claimsDone(heldText)) Verdict.DROP else Verdict.PLAY

    /** tool आने पर: tool से *पहले* बोला गया "कर दिया" हमेशा झूठा (काम अभी शुरू हुआ है) */
    fun beforeTool(heldText: String): Verdict = if (HonestGuard.claimsDone(heldText)) Verdict.DROP else Verdict.PLAY

    /** Gemini को: झूठा "हो गया" नहीं बजाया - अभी असली काम करो */
    fun dropNote(said: String): String =
        "SYSTEM NOTE (this is not the user speaking): your last reply \"${said.take(140)}\" claimed the task was done, but NO tool ran, " +
            "so nothing happened on the phone - DK did NOT play that reply to the user. Do the task now with the right tool " +
            "(phone_task / whatsapp_task / …), and only after its DONE result tell the user in one short line (e.g. \"हो गया बॉस\"). " +
            "If something needed is missing (whom / what text), ask the user instead."

    /** tool का नतीजा "अभी हुआ नहीं" (शुरू हुआ / कतार में / गड़बड़ / पक्का नहीं) - तब भी "हो गया" झूठ है */
    fun notDoneYet(result: String): Boolean {
        val r = result.trim()
        val l = r.lowercase()
        if (listOf("STILL RUNNING", "STARTED", "FAILED", "NEEDS USER CHOICE", "DELETE_UNVERIFIED", "ERROR").any { r.startsWith(it) }) return true
        if (listOf("confirmed", "cancelling", "resuming", "waiting_confirmation", "running").any { l.contains("\"status\":\"$it\"") }) return true
        return r.startsWith("Started") || r.startsWith("Queued") || l.contains("\"status\":\"started\"") || l.contains("forge_started") ||
            l.startsWith("not_done") || l.startsWith("tool failed") || r.startsWith("VIDEO_OPENED") || r.startsWith("Could not") ||
            HonestGuard.actionFailed(r) || l.contains("\"error\"")
    }

    /** Gemini को: काम सिर्फ़ शुरू हुआ / नहीं हुआ और तुमने "हो गया" कहा - वह नहीं बजाया */
    fun pendingNote(said: String): String =
        "SYSTEM NOTE (this is not the user speaking): you said \"${said.take(140)}\" as if it was done, but the tool result said it is NOT done " +
            "(only started / waiting / failed / not verified) - DK did NOT play that to the user. Tell the user the real state in one short line " +
            "(e.g. \"भेज रहा हूँ बॉस, हो जाने पर बताता हूँ\" or what failed). Say \"हो गया\" only after a SYSTEM NOTE/tool result says completed."

    /** Live prompt: काम = पहले tool, फिर एक छोटी पक्की लाइन */
    const val PROMPT = "\nACTIONS (very important): when the user asks you to DO something on the phone, call the tool FIRST and say nothing " +
        "before it (work first, talk after - not even an ack). After the tool returns DONE/PLAYING/COMPLETED, confirm " +
        "in ONE short natural line (e.g. \"हो गया बॉस, चला दिया\" / \"भेज दिया बॉस\"). If the result says failed / not verified / waiting, say exactly that " +
        "honestly - never \"हो गया\". Anything you say claiming success before a tool result is muted by the phone. " +
        "To delete the user's last photo(s)/screenshot(s) call phone_task right away (e.g. \"आखिरी 2 फोटो डिलीट करो\") - Android itself shows an " +
        "Allow box, so do not ask first; say \"मिटा दी\" only if the result says DELETED."
}
