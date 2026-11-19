package com.dk.assistant

/**
 * 19 नवंबर 2026 (मालिक): "ऐप को background से पूरी तरह हटाकर खोला = नई चैट से खुले, पुरानी से नहीं. खुलते ही लाइव जुड़े तो
 * सिर्फ़ एक बार 'नमस्ते बॉस / सर / <नाम>' और फिर चुप - यूज़र पूछे तभी जवाब. पुरानी चैट खोलकर जोड़ा = उसी के हिसाब से बात,
 * लगातार बात चल रही हो तो पुरानी एक-दो बातें जो पूछने / बताने लायक लगें."
 *
 * - [freshOnLaunch]: ऐप हटाकर (recents से swipe / बंद) फिर launcher से खोला? तब नई चैट ([ChatFresh.start])
 * - [arm] / [take]: नई चैट शुरू / पुरानी चैट खोली = अगली बार Live जुड़ते ही एक ही बार अभिवादन (फिर कभी दोबारा नहीं, जब तक
 *   अगली नई / पुरानी चैट न खुले). यूज़र ने पहले ही कुछ बोल / लिख दिया = अभिवादन नहीं ([disarm]).
 * - [newNote] / [oldNote]: Gemini के लिए SYSTEM NOTE
 * - [switchDue]: चलते Live में चैट बदली = DK चुप होते ही नया सत्र (नई चैट में पुरानी बातचीत न चले)
 *
 * Pure logic - Android import नहीं -> JVM test ([ChatGreetTest]).
 */
object ChatGreet {
    /** इतनी देर में Live न जुड़ा = अभिवादन बासी (बाद में अचानक "नमस्ते" नहीं) */
    const val ARM_MS = 10 * 60_000L
    const val OLD_TURNS = 4
    const val OLD_TURN_CHARS = 140

    @Volatile var armedChat = 0
        private set
    @Volatile var armedAt = 0L
        private set
    /** पुरानी (सेव) चैट जो सूची से खोली गई - Live के नए सत्र में उसकी बातें (समय कितना भी पुराना हो) */
    @Volatile var reopenedChat = 0

    @Synchronized fun arm(chat: Int, now: Long) { armedChat = chat; armedAt = now }
    @Synchronized fun disarm() { armedChat = 0; armedAt = 0L }

    /** इसी चैट का अभिवादन अभी बाकी है? true = एक ही बार (उसी पल हट जाता है) */
    @Synchronized fun take(chat: Int, now: Long): Boolean {
        val ok = armedChat != 0 && armedChat == chat && now - armedAt in 0..ARM_MS
        if (armedChat != 0) disarm()      // ली गई / बासी / चैट बदल चुकी - तीनों में अब नहीं
        return ok
    }

    /**
     * ऐप हटाकर फिर खोला? = Activity नई बनी (system ने पुरानी हालत नहीं लौटाई), launcher के आइकन से,
     * recents की पुरानी हालत से नहीं. चैट खाली हो या DK अभी काम / जवाब में हो = पुरानी ही रहे.
     */
    fun freshOnLaunch(restored: Boolean, mainAction: Boolean, launcher: Boolean, fromHistory: Boolean, hasMsgs: Boolean, busy: Boolean): Boolean =
        !restored && mainAction && launcher && !fromHistory && hasMsgs && !busy

    /** चलते Live में चैट बदली (नई / पुरानी खोली) और DK चुप है = नया सत्र */
    fun switchDue(connChat: Int, nowChat: Int, turnActive: Boolean, playing: Boolean, toolsIdle: Boolean): Boolean =
        connChat > 0 && nowChat > 0 && connChat != nowChat && !turnActive && !playing && toolsIdle

    /** संबोधन: स्वभाव वाला (बाबू / सर / बॉस) > प्रोफ़ाइल का नाम ("जी धीरज") > "बॉस" */
    fun address(personaCall: String, userName: String): String =
        personaCall.trim().ifBlank { NameRule.call(userName) }.ifBlank { "बॉस" }

    /** नई (खाली) चैट: सिर्फ़ "नमस्ते <संबोधन>", फिर चुप */
    fun newNote(addr: String): String =
        "SYSTEM NOTE (this is not the user speaking): the voice connection just started in a NEW, empty chat. " +
            "Say ONLY this one short greeting in Hindi: \"नमस्ते $addr\" - nothing else: no question, no offer of help, " +
            "no time, weather, news, festival or introduction. Then stay completely silent and wait; answer only when the user speaks. " +
            "Do not mention this note."

    /** पुरानी चैट (बातें मौजूद): छोटा अभिवादन + ज़रूरत लगे तो उसी चैट की एक (ज़्यादा से ज़्यादा दो) बात, फिर चुप */
    fun oldNote(addr: String, turns: List<LiveCarry.Turn>): String = buildString {
        append("SYSTEM NOTE (this is not the user speaking): the user is back in an EARLIER chat and the voice connection just started. ")
        val lines = turns.filter { it.text.isNotBlank() && (it.role == "u" || it.role == "d") }.takeLast(OLD_TURNS).map { t ->
            val x = t.text.replace(Regex("\\s+"), " ").trim().let { if (it.length > OLD_TURN_CHARS) it.take(OLD_TURN_CHARS) + "…" else it }
            (if (t.role == "u") "User: " else "DK: ") + x
        }
        if (lines.isNotEmpty()) append("Last part of this chat:\n").append(lines.joinToString("\n")).append("\n")
        append("Greet briefly (\"नमस्ते $addr\"). Then, ONLY if something in this chat is genuinely worth it, ask or tell ONE short thing ")
        append("(at most two) from it - e.g. how an unfinished thing went, or a useful follow-up. If nothing fits, only greet. ")
        append("Max 2 short Hindi sentences in total, then stop and wait for the user. Do not repeat old answers, do not redo old tasks. ")
        append("Do not mention this note.")
    }
}
